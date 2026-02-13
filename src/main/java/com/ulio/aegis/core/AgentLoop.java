package com.ulio.aegis.core;

import com.ulio.aegis.anomaly.AnomalyEngine;
import com.ulio.aegis.anomaly.AnomalyResult;
import com.ulio.aegis.baseline.BaselineModel;
import com.ulio.aegis.classifier.ClassifierResult;
import com.ulio.aegis.classifier.RansomwareClassifier;
import com.ulio.aegis.comms.Comms;
import com.ulio.aegis.fs.FsEventCounters;
import com.ulio.aegis.fs.FsWatcher;
import com.ulio.aegis.proc.ProcScanner;
import com.ulio.aegis.proc.TopWriterProcess;
import com.ulio.aegis.telemetry.TelemetryCollector;
import com.ulio.aegis.telemetry.TelemetrySample;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicBoolean;

public class AgentLoop {
    private final Config config;
    private final TelemetryCollector telemetryCollector;
    private final BaselineModel baselineModel;
    private final AnomalyEngine anomalyEngine;
    private final RansomwareClassifier ransomwareClassifier;
    private final Comms comms;
    private final ProcScanner procScanner;

    private final FsEventCounters fsEventCounters;
    private final FsWatcher fsWatcher;
    private final Thread fsWatcherThread;

    private final Deque<FsWindowBucket> fsWindowBuckets = new ArrayDeque<>();
    private double fsWindowElapsedSec;
    private long fsWindowModified;
    private long fsWindowCreated;
    private long fsWindowDeleted;
    private long fsWindowRenamed;

    private final AtomicBoolean shutdown = new AtomicBoolean(false);

    public AgentLoop(Config config) {
        this.config = config;
        this.telemetryCollector = new TelemetryCollector();
        this.baselineModel = new BaselineModel(config.getBaselineMinSamples(), config.getBaselinePath());
        this.anomalyEngine = new AnomalyEngine(config);
        this.ransomwareClassifier = new RansomwareClassifier(config.getScoreThresholds());
        this.comms = new Comms();
        this.procScanner = new ProcScanner();

        this.fsEventCounters = new FsEventCounters();
        Path watchRootPath;
        try {
            watchRootPath = Path.of(config.getWatchRoot());
        } catch (Throwable e) {
            watchRootPath = Path.of("/tmp/aegis-watch");
            System.err.println("Invalid watchRoot in config. Falling back to /tmp/aegis-watch");
        }
        this.fsWatcher = new FsWatcher(watchRootPath, fsEventCounters);
        this.fsWatcherThread = new Thread(fsWatcher, "aegis-fs-watcher");
        this.fsWatcherThread.setDaemon(true);

        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "aegis-shutdown"));
    }

    public void run() {
        fsWatcherThread.start();

        long previousTickNs = System.nanoTime();
        try {
            while (!Thread.currentThread().isInterrupted()) {
                long tickStartMs = System.currentTimeMillis();

                try {
                    long nowNs = System.nanoTime();
                    double elapsedSec = (nowNs - previousTickNs) / 1_000_000_000.0;
                    previousTickNs = nowNs;
                    if (!Double.isFinite(elapsedSec) || elapsedSec <= 0.0) {
                        elapsedSec = Math.max(0.001, config.getSamplingIntervalMs() / 1000.0);
                    }

                    FsEventCounters.Snapshot fsSnapshot = fsEventCounters.snapshotAndReset();
                    FsRates fsRates = updateFsRates(fsSnapshot, elapsedSec);

                    double diskWriteBytesPerSec = telemetryCollector.collectDiskWriteBytesPerSec();
                    TopWriterProcess topWriterProcess = procScanner.scanTopWriter();

                    TelemetrySample sample = new TelemetrySample(
                            Instant.now().toString(),
                            fsRates.modifiedPerSec,
                            fsRates.createdPerSec,
                            fsRates.deletedPerSec,
                            fsRates.approxRenamesPerSec,
                            diskWriteBytesPerSec,
                            topWriterProcess.getPid(),
                            topWriterProcess.getComm(),
                            topWriterProcess.getWriteBytesPerSec()
                    );

                    baselineModel.update(sample);

                    boolean baselineReady = baselineModel.baselineReady();
                    boolean detectNow = config.getMode() == Config.Mode.DETECT && baselineReady;

                    AnomalyResult anomalyResult = null;
                    ClassifierResult classifierResult = null;
                    if (detectNow) {
                        anomalyResult = anomalyEngine.analyze(sample, baselineModel);
                        classifierResult = ransomwareClassifier.classify(anomalyResult);
                    }

                    comms.emitTelemetry(sample, baselineReady, anomalyResult, classifierResult);
                } catch (Throwable e) {
                    // A broken sample tick must not kill the process.
                    System.err.println("Agent tick failed: " + e.getMessage());
                }

                sleepUntilNextTick(tickStartMs);
            }
        } finally {
            shutdown();
        }
    }

    private FsRates updateFsRates(FsEventCounters.Snapshot snapshot, double elapsedSec) {
        FsWindowBucket bucket = new FsWindowBucket(
                snapshot.getModified(),
                snapshot.getCreated(),
                snapshot.getDeleted(),
                snapshot.getApproxRenamed(),
                elapsedSec
        );
        fsWindowBuckets.addLast(bucket);

        fsWindowElapsedSec += bucket.elapsedSec;
        fsWindowModified += bucket.modified;
        fsWindowCreated += bucket.created;
        fsWindowDeleted += bucket.deleted;
        fsWindowRenamed += bucket.approxRenamed;

        int stormWindowSeconds = Math.max(1, config.getStormWindowSeconds());
        while (fsWindowElapsedSec > stormWindowSeconds && fsWindowBuckets.size() > 1) {
            FsWindowBucket oldest = fsWindowBuckets.removeFirst();
            fsWindowElapsedSec -= oldest.elapsedSec;
            fsWindowModified -= oldest.modified;
            fsWindowCreated -= oldest.created;
            fsWindowDeleted -= oldest.deleted;
            fsWindowRenamed -= oldest.approxRenamed;
        }

        double divisor = fsWindowElapsedSec > 0.0 ? fsWindowElapsedSec : elapsedSec;
        if (!Double.isFinite(divisor) || divisor <= 0.0) {
            divisor = 1.0;
        }

        return new FsRates(
                fsWindowModified / divisor,
                fsWindowCreated / divisor,
                fsWindowDeleted / divisor,
                fsWindowRenamed / divisor
        );
    }

    private void sleepUntilNextTick(long tickStartMs) {
        long elapsedMs = System.currentTimeMillis() - tickStartMs;
        long sleepMs = config.getSamplingIntervalMs() - elapsedMs;
        if (sleepMs <= 0) {
            return;
        }

        try {
            Thread.sleep(sleepMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void shutdown() {
        if (!shutdown.compareAndSet(false, true)) {
            return;
        }

        try {
            fsWatcher.close();
        } catch (Throwable ignored) {
            // Ignore watcher close errors during shutdown.
        }

        if (fsWatcherThread.isAlive() && fsWatcherThread != Thread.currentThread()) {
            fsWatcherThread.interrupt();
            try {
                fsWatcherThread.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        try {
            baselineModel.close();
        } catch (Throwable ignored) {
            // Ignore baseline save failures at shutdown.
        }
    }

    private static final class FsWindowBucket {
        private final long modified;
        private final long created;
        private final long deleted;
        private final long approxRenamed;
        private final double elapsedSec;

        private FsWindowBucket(long modified, long created, long deleted, long approxRenamed, double elapsedSec) {
            this.modified = Math.max(0L, modified);
            this.created = Math.max(0L, created);
            this.deleted = Math.max(0L, deleted);
            this.approxRenamed = Math.max(0L, approxRenamed);
            this.elapsedSec = Math.max(0.001, elapsedSec);
        }
    }

    private static final class FsRates {
        private final double modifiedPerSec;
        private final double createdPerSec;
        private final double deletedPerSec;
        private final double approxRenamesPerSec;

        private FsRates(double modifiedPerSec, double createdPerSec, double deletedPerSec, double approxRenamesPerSec) {
            this.modifiedPerSec = sanitize(modifiedPerSec);
            this.createdPerSec = sanitize(createdPerSec);
            this.deletedPerSec = sanitize(deletedPerSec);
            this.approxRenamesPerSec = sanitize(approxRenamesPerSec);
        }

        private static double sanitize(double value) {
            if (!Double.isFinite(value) || value < 0.0) {
                return 0.0;
            }
            return value;
        }
    }
}
