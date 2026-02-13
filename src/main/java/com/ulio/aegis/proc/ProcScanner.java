package com.ulio.aegis.proc;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public class ProcScanner {
    private static final String WRITE_BYTES_KEY = "write_bytes:";

    private final Path procRoot;
    private final Map<Integer, Long> previousWriteBytesByPid = new HashMap<>();
    private long previousScanNs = -1L;

    public ProcScanner() {
        this(Path.of("/proc"));
    }

    public ProcScanner(Path procRoot) {
        this.procRoot = procRoot;
    }

    public synchronized TopWriterProcess scanTopWriter() {
        long nowNs = System.nanoTime();
        double elapsedSec = previousScanNs > 0 ? (nowNs - previousScanNs) / 1_000_000_000.0 : 0.0;

        Map<Integer, Long> currentWriteBytesByPid = new HashMap<>();
        int topPid = -1;
        long topDeltaBytes = 0L;

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(procRoot)) {
            for (Path entry : stream) {
                String name = entry.getFileName() == null ? "" : entry.getFileName().toString();
                if (!isNumeric(name)) {
                    continue;
                }

                int pid;
                try {
                    pid = Integer.parseInt(name);
                } catch (NumberFormatException ignored) {
                    continue;
                }

                Long currentWriteBytes = readWriteBytes(entry.resolve("io"));
                if (currentWriteBytes == null) {
                    continue;
                }

                currentWriteBytesByPid.put(pid, currentWriteBytes);

                if (elapsedSec <= 0.0) {
                    continue;
                }

                Long previousWriteBytes = previousWriteBytesByPid.get(pid);
                if (previousWriteBytes == null || currentWriteBytes < previousWriteBytes) {
                    continue;
                }

                long deltaBytes = currentWriteBytes - previousWriteBytes;
                if (deltaBytes > topDeltaBytes) {
                    topDeltaBytes = deltaBytes;
                    topPid = pid;
                }
            }
        } catch (Throwable e) {
            System.err.println("ProcScanner scan failed: " + e.getMessage());
        }

        previousWriteBytesByPid.clear();
        previousWriteBytesByPid.putAll(currentWriteBytesByPid);
        previousScanNs = nowNs;

        if (topPid < 0 || elapsedSec <= 0.0) {
            return TopWriterProcess.none();
        }

        String comm = readComm(topPid);
        double writeBytesPerSec = topDeltaBytes / elapsedSec;
        return new TopWriterProcess(topPid, comm, writeBytesPerSec);
    }

    private Long readWriteBytes(Path ioPath) {
        try (BufferedReader reader = Files.newBufferedReader(ioPath, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith(WRITE_BYTES_KEY)) {
                    continue;
                }
                String rawValue = line.substring(WRITE_BYTES_KEY.length()).trim();
                return Long.parseLong(rawValue);
            }
        } catch (NoSuchFileException | AccessDeniedException ignored) {
            // Process exited or permissions do not allow access to /proc/<pid>/io.
        } catch (IOException | NumberFormatException ignored) {
            // Ignore parse and read issues for this pid only.
        }
        return null;
    }

    private String readComm(int pid) {
        Path commPath = procRoot.resolve(String.valueOf(pid)).resolve("comm");
        try {
            String comm = Files.readString(commPath, StandardCharsets.UTF_8);
            return comm == null ? "" : comm.trim();
        } catch (NoSuchFileException | AccessDeniedException ignored) {
            return "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    private boolean isNumeric(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }

        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
