package com.ulio.aegis.telemetry;

import oshi.SystemInfo;
import oshi.hardware.HWDiskStore;
import oshi.hardware.HardwareAbstractionLayer;

import java.util.List;

public class TelemetryCollector {
    private final HardwareAbstractionLayer hardware;

    private long previousDiskWriteBytes = -1;
    private long previousDiskSampleNs = -1;

    public TelemetryCollector() {
        HardwareAbstractionLayer loadedHardware = null;

        try {
            SystemInfo systemInfo = new SystemInfo();
            loadedHardware = systemInfo.getHardware();
        } catch (Throwable e) {
            System.err.println("OSHI initialization failed, disk telemetry will be zero-filled: " + e.getMessage());
        }

        this.hardware = loadedHardware;
    }

    public double collectDiskWriteBytesPerSec() {
        if (hardware == null) {
            return 0.0;
        }

        long nowNs = System.nanoTime();
        long totalWriteBytes = 0;

        try {
            List<HWDiskStore> disks = hardware.getDiskStores();
            if (disks != null) {
                for (HWDiskStore disk : disks) {
                    if (disk == null) {
                        continue;
                    }
                    try {
                        disk.updateAttributes();
                        long writeBytes = disk.getWriteBytes();
                        if (writeBytes > 0) {
                            totalWriteBytes += writeBytes;
                        }
                    } catch (Throwable ignored) {
                        // Skip unreadable disk entries and continue.
                    }
                }
            }
        } catch (Throwable e) {
            return 0.0;
        }

        double bytesPerSec = 0.0;
        if (previousDiskWriteBytes >= 0
                && previousDiskSampleNs > 0
                && nowNs > previousDiskSampleNs
                && totalWriteBytes >= previousDiskWriteBytes) {
            long deltaBytes = totalWriteBytes - previousDiskWriteBytes;
            double deltaSec = (nowNs - previousDiskSampleNs) / 1_000_000_000.0;
            if (deltaSec > 0.0) {
                bytesPerSec = deltaBytes / deltaSec;
            }
        }

        previousDiskWriteBytes = totalWriteBytes;
        previousDiskSampleNs = nowNs;
        return sanitize(bytesPerSec);
    }

    private static double sanitize(double value) {
        if (!Double.isFinite(value) || value < 0.0) {
            return 0.0;
        }
        return value;
    }
}
