package com.ulio.aegis.proc;

public class TopWriterProcess {
    private final int pid;
    private final String comm;
    private final double writeBytesPerSec;

    public TopWriterProcess(int pid, String comm, double writeBytesPerSec) {
        this.pid = pid;
        this.comm = comm == null ? "" : comm;
        this.writeBytesPerSec = sanitize(writeBytesPerSec);
    }

    public static TopWriterProcess none() {
        return new TopWriterProcess(-1, "", 0.0);
    }

    public int getPid() {
        return pid;
    }

    public String getComm() {
        return comm;
    }

    public double getWriteBytesPerSec() {
        return writeBytesPerSec;
    }

    private static double sanitize(double value) {
        if (!Double.isFinite(value) || value < 0.0) {
            return 0.0;
        }
        return value;
    }
}
