package com.ulio.aegis.classifier;

public class ClassifierResult {
    private final double ransomwareScore;
    private final Verdict verdict;
    private final double confidence;

    public ClassifierResult(double ransomwareScore, Verdict verdict, double confidence) {
        this.ransomwareScore = ransomwareScore;
        this.verdict = verdict;
        this.confidence = confidence;
    }

    public double getRansomwareScore() {
        return ransomwareScore;
    }

    public Verdict getVerdict() {
        return verdict;
    }

    public double getConfidence() {
        return confidence;
    }
}
