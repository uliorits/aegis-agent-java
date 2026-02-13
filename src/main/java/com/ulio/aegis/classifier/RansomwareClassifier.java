package com.ulio.aegis.classifier;

import com.ulio.aegis.anomaly.AnomalyResult;
import com.ulio.aegis.core.Config;

public class RansomwareClassifier {
    private final double suspiciousThreshold;
    private final double ransomwareThreshold;

    public RansomwareClassifier(Config.ScoreThresholds scoreThresholds) {
        if (scoreThresholds == null) {
            this.suspiciousThreshold = 0.55;
            this.ransomwareThreshold = 0.85;
            return;
        }

        this.suspiciousThreshold = scoreThresholds.getSuspicious();
        this.ransomwareThreshold = scoreThresholds.getRansomware();
    }

    public ClassifierResult classify(AnomalyResult anomalyResult) {
        if (anomalyResult == null) {
            return new ClassifierResult(0.0, Verdict.SAFE, 0.0);
        }

        double ransomwareScore = 0.5 * anomalyResult.getAnomalyScore();

        if (anomalyResult.hasFlag("WRITE_STORM")) {
            ransomwareScore += 0.25;
        }
        if (anomalyResult.hasFlag("RENAME_STORM")) {
            ransomwareScore += 0.15;
        }
        if (anomalyResult.hasFlag("DELETE_STORM")) {
            ransomwareScore += 0.10;
        }
        if (anomalyResult.hasFlag("TOP_WRITER_SPIKE")) {
            ransomwareScore += 0.20;
        }

        ransomwareScore = clamp01(ransomwareScore);

        Verdict verdict;
        if (ransomwareScore >= ransomwareThreshold) {
            verdict = Verdict.RANSOMWARE;
        } else if (ransomwareScore >= suspiciousThreshold) {
            verdict = Verdict.SUSPICIOUS;
        } else {
            verdict = Verdict.SAFE;
        }

        double confidence = clamp01(2.0 * Math.abs(ransomwareScore - 0.5));
        return new ClassifierResult(ransomwareScore, verdict, confidence);
    }

    private double clamp01(double value) {
        if (!Double.isFinite(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }
}
