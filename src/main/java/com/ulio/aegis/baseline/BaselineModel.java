package com.ulio.aegis.baseline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ulio.aegis.telemetry.TelemetrySample;

import java.nio.file.Files;
import java.nio.file.Path;

public class BaselineModel implements AutoCloseable {
    private static final int SAVE_EVERY_SAMPLES = 30;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final int baselineMinSamples;
    private final Path baselinePath;

    private final RunningStat filesModifiedStat = new RunningStat();
    private final RunningStat filesDeletedStat = new RunningStat();
    private final RunningStat approxRenamesStat = new RunningStat();
    private final RunningStat diskWriteStat = new RunningStat();
    private final RunningStat topWriteStat = new RunningStat();

    private long updates;

    public BaselineModel(int baselineMinSamples, String baselinePath) {
        this.baselineMinSamples = baselineMinSamples > 0 ? baselineMinSamples : 300;
        Path resolvedBaselinePath;
        try {
            resolvedBaselinePath = (baselinePath == null || baselinePath.isBlank())
                    ? Path.of("baseline.json")
                    : Path.of(baselinePath);
        } catch (Throwable e) {
            resolvedBaselinePath = Path.of("baseline.json");
            System.err.println("Invalid baselinePath in config. Falling back to baseline.json");
        }
        this.baselinePath = resolvedBaselinePath;
        loadFromDisk();
    }

    public synchronized void update(TelemetrySample sample) {
        if (sample == null) {
            return;
        }

        filesModifiedStat.add(sample.getFilesModifiedPerSec());
        filesDeletedStat.add(sample.getFilesDeletedPerSec());
        approxRenamesStat.add(sample.getApproxRenamesPerSec());
        diskWriteStat.add(sample.getDiskWriteBytesPerSec());
        topWriteStat.add(sample.getTopWriteBytesPerSec());

        updates++;
        if (updates % SAVE_EVERY_SAMPLES == 0) {
            saveNow();
        }
    }

    public synchronized boolean baselineReady() {
        return filesModifiedStat.getCount() >= baselineMinSamples
                && filesDeletedStat.getCount() >= baselineMinSamples
                && approxRenamesStat.getCount() >= baselineMinSamples
                && diskWriteStat.getCount() >= baselineMinSamples
                && topWriteStat.getCount() >= baselineMinSamples;
    }

    public synchronized double getFilesModifiedMean() {
        return filesModifiedStat.getMean();
    }

    public synchronized double getFilesModifiedStdDev() {
        return filesModifiedStat.getStdDev();
    }

    public synchronized double getFilesDeletedMean() {
        return filesDeletedStat.getMean();
    }

    public synchronized double getFilesDeletedStdDev() {
        return filesDeletedStat.getStdDev();
    }

    public synchronized double getApproxRenamesMean() {
        return approxRenamesStat.getMean();
    }

    public synchronized double getApproxRenamesStdDev() {
        return approxRenamesStat.getStdDev();
    }

    public synchronized double getDiskWriteMean() {
        return diskWriteStat.getMean();
    }

    public synchronized double getDiskWriteStdDev() {
        return diskWriteStat.getStdDev();
    }

    public synchronized double getTopWriteMean() {
        return topWriteStat.getMean();
    }

    public synchronized double getTopWriteStdDev() {
        return topWriteStat.getStdDev();
    }

    public synchronized long getSampleCount() {
        long filesModifiedCount = filesModifiedStat.getCount();
        long filesDeletedCount = filesDeletedStat.getCount();
        long renamesCount = approxRenamesStat.getCount();
        long diskCount = diskWriteStat.getCount();
        long topWriteCount = topWriteStat.getCount();

        return Math.min(
                Math.min(filesModifiedCount, filesDeletedCount),
                Math.min(Math.min(renamesCount, diskCount), topWriteCount)
        );
    }

    public synchronized void saveNow() {
        try {
            if (baselinePath.getParent() != null) {
                Files.createDirectories(baselinePath.getParent());
            }

            PersistedBaseline persisted = new PersistedBaseline();
            persisted.baselineMinSamples = baselineMinSamples;
            persisted.filesModifiedPerSec = filesModifiedStat.toMetricState();
            persisted.filesDeletedPerSec = filesDeletedStat.toMetricState();
            persisted.approxRenamesPerSec = approxRenamesStat.toMetricState();
            persisted.diskWriteBytesPerSec = diskWriteStat.toMetricState();
            persisted.topWriteBytesPerSec = topWriteStat.toMetricState();

            objectMapper.writerWithDefaultPrettyPrinter().writeValue(baselinePath.toFile(), persisted);
        } catch (Throwable e) {
            System.err.println("Failed to save baseline model: " + e.getMessage());
        }
    }

    @Override
    public synchronized void close() {
        saveNow();
    }

    private void loadFromDisk() {
        try {
            if (!Files.exists(baselinePath)) {
                return;
            }

            PersistedBaseline persisted = objectMapper.readValue(baselinePath.toFile(), PersistedBaseline.class);
            if (persisted == null) {
                return;
            }

            filesModifiedStat.fromMetricState(persisted.filesModifiedPerSec);
            filesDeletedStat.fromMetricState(persisted.filesDeletedPerSec);
            approxRenamesStat.fromMetricState(persisted.approxRenamesPerSec);
            diskWriteStat.fromMetricState(persisted.diskWriteBytesPerSec);
            topWriteStat.fromMetricState(persisted.topWriteBytesPerSec);
            updates = getSampleCount();
        } catch (Throwable e) {
            System.err.println("Failed to load baseline model, starting fresh: " + e.getMessage());
        }
    }

    private static final class PersistedBaseline {
        public int baselineMinSamples;
        public MetricState filesModifiedPerSec;
        public MetricState filesDeletedPerSec;
        public MetricState approxRenamesPerSec;
        public MetricState diskWriteBytesPerSec;
        public MetricState topWriteBytesPerSec;
    }

    private static final class MetricState {
        public long count;
        public double mean;
        public double m2;
    }

    private static final class RunningStat {
        private long count;
        private double mean;
        private double m2;

        void add(double value) {
            if (!Double.isFinite(value)) {
                return;
            }

            count++;
            double delta = value - mean;
            mean += delta / count;
            double delta2 = value - mean;
            m2 += delta * delta2;
        }

        long getCount() {
            return count;
        }

        double getMean() {
            return count > 0 ? mean : 0.0;
        }

        double getStdDev() {
            if (count < 2) {
                return 0.0;
            }
            return Math.sqrt(m2 / (count - 1));
        }

        MetricState toMetricState() {
            MetricState state = new MetricState();
            state.count = count;
            state.mean = mean;
            state.m2 = m2;
            return state;
        }

        void fromMetricState(MetricState state) {
            if (state == null || state.count < 0 || !Double.isFinite(state.mean) || !Double.isFinite(state.m2) || state.m2 < 0.0) {
                return;
            }
            count = state.count;
            mean = state.mean;
            m2 = state.m2;
        }
    }
}
