package com.ulio.aegis.anomaly;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

public class AnomalyResult {
    private final double anomalyScore;
    private final double maxAbsZ;
    private final Set<String> flags;

    public AnomalyResult(double anomalyScore, double maxAbsZ, Set<String> flags) {
        this.anomalyScore = sanitize(anomalyScore);
        this.maxAbsZ = sanitize(maxAbsZ);
        this.flags = flags == null
                ? Collections.emptySet()
                : Collections.unmodifiableSet(new LinkedHashSet<>(flags));
    }

    public double getAnomalyScore() {
        return anomalyScore;
    }

    public double getMaxAbsZ() {
        return maxAbsZ;
    }

    public Set<String> getFlags() {
        return flags;
    }

    public boolean hasFlag(String flag) {
        return flags.contains(flag);
    }

    private static double sanitize(double value) {
        if (!Double.isFinite(value) || value < 0.0) {
            return 0.0;
        }
        return value;
    }
}
