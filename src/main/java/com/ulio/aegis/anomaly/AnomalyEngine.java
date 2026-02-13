package com.ulio.aegis.anomaly;

import com.ulio.aegis.baseline.BaselineModel;
import com.ulio.aegis.core.Config;
import com.ulio.aegis.telemetry.TelemetrySample;

import java.util.LinkedHashSet;
import java.util.Set;

public class AnomalyEngine {
    private final double zScoreThreshold;
    private final Config.Thresholds thresholds;

    public AnomalyEngine(Config config) {
        this.zScoreThreshold = config == null ? 3.0 : config.getZScoreThreshold();
        this.thresholds = config == null ? new Config.Thresholds() : config.getThresholds();
    }

    public AnomalyResult analyze(TelemetrySample sample, BaselineModel baselineModel) {
        if (sample == null || baselineModel == null) {
            return new AnomalyResult(0.0, 0.0, Set.of());
        }

        double filesModifiedZ = zScore(
                sample.getFilesModifiedPerSec(),
                baselineModel.getFilesModifiedMean(),
                baselineModel.getFilesModifiedStdDev()
        );
        double filesDeletedZ = zScore(
                sample.getFilesDeletedPerSec(),
                baselineModel.getFilesDeletedMean(),
                baselineModel.getFilesDeletedStdDev()
        );
        double renamesZ = zScore(
                sample.getApproxRenamesPerSec(),
                baselineModel.getApproxRenamesMean(),
                baselineModel.getApproxRenamesStdDev()
        );
        double diskWriteZ = zScore(
                sample.getDiskWriteBytesPerSec(),
                baselineModel.getDiskWriteMean(),
                baselineModel.getDiskWriteStdDev()
        );
        double topWriterZ = zScore(
                sample.getTopWriteBytesPerSec(),
                baselineModel.getTopWriteMean(),
                baselineModel.getTopWriteStdDev()
        );

        double maxAbsZ = Math.max(
                Math.max(Math.abs(filesModifiedZ), Math.abs(filesDeletedZ)),
                Math.max(Math.max(Math.abs(renamesZ), Math.abs(diskWriteZ)), Math.abs(topWriterZ))
        );
        double anomalyScore = clamp01(1.0 - Math.exp(-1.0 * maxAbsZ));

        Set<String> flags = new LinkedHashSet<>();

        if (filesModifiedZ >= zScoreThreshold
                || sample.getFilesModifiedPerSec() >= thresholds.getFilesModifiedPerSec()
                || sample.getDiskWriteBytesPerSec() > thresholds.getDiskWriteBytesPerSec()) {
            flags.add("WRITE_STORM");
        }

        if (filesDeletedZ >= zScoreThreshold
                || sample.getFilesDeletedPerSec() >= thresholds.getFilesDeletedPerSec()) {
            flags.add("DELETE_STORM");
        }

        if (renamesZ >= zScoreThreshold
                || sample.getApproxRenamesPerSec() >= thresholds.getApproxRenamesPerSec()) {
            flags.add("RENAME_STORM");
        }

        if (topWriterZ >= zScoreThreshold) {
            flags.add("TOP_WRITER_SPIKE");
        }

        return new AnomalyResult(anomalyScore, maxAbsZ, flags);
    }

    private double zScore(double value, double mean, double stdDev) {
        if (!Double.isFinite(value) || !Double.isFinite(mean) || !Double.isFinite(stdDev) || stdDev <= 0.0) {
            return 0.0;
        }

        double z = (value - mean) / stdDev;
        if (!Double.isFinite(z)) {
            return 0.0;
        }
        return z;
    }

    private double clamp01(double value) {
        if (!Double.isFinite(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }
}
