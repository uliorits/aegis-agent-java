package com.ulio.aegis.telemetry;

public class TelemetrySample {
    private final String timestamp;
    private final double filesModifiedPerSec;
    private final double filesCreatedPerSec;
    private final double filesDeletedPerSec;
    private final double approxRenamesPerSec;
    private final double diskWriteBytesPerSec;
    private final int topPid;
    private final String topComm;
    private final double topWriteBytesPerSec;

    public TelemetrySample(
            String timestamp,
            double filesModifiedPerSec,
            double filesCreatedPerSec,
            double filesDeletedPerSec,
            double approxRenamesPerSec,
            double diskWriteBytesPerSec,
            int topPid,
            String topComm,
            double topWriteBytesPerSec
    ) {
        this.timestamp = timestamp;
        this.filesModifiedPerSec = sanitize(filesModifiedPerSec);
        this.filesCreatedPerSec = sanitize(filesCreatedPerSec);
        this.filesDeletedPerSec = sanitize(filesDeletedPerSec);
        this.approxRenamesPerSec = sanitize(approxRenamesPerSec);
        this.diskWriteBytesPerSec = sanitize(diskWriteBytesPerSec);
        this.topPid = topPid;
        this.topComm = topComm == null ? "" : topComm;
        this.topWriteBytesPerSec = sanitize(topWriteBytesPerSec);
    }

    public String getTimestamp() {
        return timestamp;
    }

    public double getFilesModifiedPerSec() {
        return filesModifiedPerSec;
    }

    public double getFilesCreatedPerSec() {
        return filesCreatedPerSec;
    }

    public double getFilesDeletedPerSec() {
        return filesDeletedPerSec;
    }

    public double getApproxRenamesPerSec() {
        return approxRenamesPerSec;
    }

    public double getDiskWriteBytesPerSec() {
        return diskWriteBytesPerSec;
    }

    public int getTopPid() {
        return topPid;
    }

    public String getTopComm() {
        return topComm;
    }

    public double getTopWriteBytesPerSec() {
        return topWriteBytesPerSec;
    }

    private static double sanitize(double value) {
        if (!Double.isFinite(value) || value < 0.0) {
            return 0.0;
        }
        return value;
    }
}
