package com.ulio.aegis.fs;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class FsWatcher implements Runnable, AutoCloseable {
    private static final long RENAME_WINDOW_MS = 500L;
    private static final int PER_DIRECTORY_DELETE_BUFFER_LIMIT = 64;

    private final Path watchRoot;
    private final FsEventCounters counters;
    private final AtomicBoolean running = new AtomicBoolean(true);

    private final Map<Path, Deque<Long>> recentDeletesByDirectory = new HashMap<>();

    private volatile WatchService watchService;
    private volatile PathIndexer pathIndexer;

    public FsWatcher(Path watchRoot, FsEventCounters counters) {
        this.watchRoot = watchRoot == null
                ? Path.of(".").toAbsolutePath().normalize()
                : watchRoot.toAbsolutePath().normalize();
        this.counters = counters;
    }

    @Override
    public void run() {
        try {
            initializeWatcher();
            loop();
        } catch (Throwable e) {
            System.err.println("FsWatcher stopped: " + e.getMessage());
        } finally {
            closeQuietly();
        }
    }

    @Override
    public void close() {
        running.set(false);
        closeQuietly();
    }

    private void initializeWatcher() throws IOException {
        if (!Files.exists(watchRoot)) {
            Files.createDirectories(watchRoot);
        }
        if (!Files.isDirectory(watchRoot)) {
            throw new IOException("watchRoot is not a directory: " + watchRoot);
        }

        WatchService localWatchService = FileSystems.getDefault().newWatchService();
        PathIndexer localPathIndexer = new PathIndexer(localWatchService);
        localPathIndexer.registerRecursively(watchRoot);

        this.watchService = localWatchService;
        this.pathIndexer = localPathIndexer;
    }

    private void loop() {
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            WatchService ws = watchService;
            PathIndexer indexer = pathIndexer;
            if (ws == null || indexer == null) {
                return;
            }

            try {
                WatchKey key = ws.poll(250, TimeUnit.MILLISECONDS);
                long nowMs = System.currentTimeMillis();
                if (key == null) {
                    evictExpiredDeletes(nowMs);
                    continue;
                }

                Path directory = indexer.resolveDirectory(key);
                if (directory == null) {
                    key.reset();
                    continue;
                }

                for (WatchEvent<?> rawEvent : key.pollEvents()) {
                    WatchEvent.Kind<?> kind = rawEvent.kind();
                    if (kind == StandardWatchEventKinds.OVERFLOW) {
                        continue;
                    }

                    Object context = rawEvent.context();
                    if (!(context instanceof Path relativePath)) {
                        continue;
                    }

                    Path absolutePath = directory.resolve(relativePath);
                    handleEvent(directory, absolutePath, kind, nowMs, indexer);
                }

                boolean valid = key.reset();
                if (!valid) {
                    indexer.removeKey(key);
                }

                evictExpiredDeletes(nowMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable e) {
                System.err.println("FsWatcher tick failed: " + e.getMessage());
            }
        }
    }

    private void handleEvent(
            Path directory,
            Path absolutePath,
            WatchEvent.Kind<?> kind,
            long nowMs,
            PathIndexer indexer
    ) {
        if (kind == StandardWatchEventKinds.ENTRY_MODIFY) {
            counters.incrementModified();
            return;
        }

        if (kind == StandardWatchEventKinds.ENTRY_DELETE) {
            counters.incrementDeleted();
            rememberDelete(directory, nowMs);
            return;
        }

        if (kind == StandardWatchEventKinds.ENTRY_CREATE) {
            counters.incrementCreated();

            // ENTRY_CREATE for directories must be registered so recursive watching continues.
            if (Files.isDirectory(absolutePath)) {
                try {
                    indexer.registerRecursively(absolutePath);
                } catch (IOException ignored) {
                    // Ignore this directory if it cannot be registered.
                }
            }

            if (consumeRecentDelete(directory, nowMs)) {
                counters.incrementApproxRenamed();
            }
        }
    }

    private void rememberDelete(Path directory, long nowMs) {
        Deque<Long> recentDeletes = recentDeletesByDirectory.computeIfAbsent(directory, ignored -> new ArrayDeque<>());
        recentDeletes.addLast(nowMs);
        trimDeque(recentDeletes, nowMs);
        while (recentDeletes.size() > PER_DIRECTORY_DELETE_BUFFER_LIMIT) {
            recentDeletes.removeFirst();
        }
    }

    private boolean consumeRecentDelete(Path directory, long nowMs) {
        Deque<Long> recentDeletes = recentDeletesByDirectory.get(directory);
        if (recentDeletes == null) {
            return false;
        }

        trimDeque(recentDeletes, nowMs);
        if (recentDeletes.isEmpty()) {
            recentDeletesByDirectory.remove(directory);
            return false;
        }

        recentDeletes.removeFirst();
        if (recentDeletes.isEmpty()) {
            recentDeletesByDirectory.remove(directory);
        }
        return true;
    }

    private void evictExpiredDeletes(long nowMs) {
        Iterator<Map.Entry<Path, Deque<Long>>> iterator = recentDeletesByDirectory.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Path, Deque<Long>> entry = iterator.next();
            Deque<Long> deque = entry.getValue();
            trimDeque(deque, nowMs);
            if (deque.isEmpty()) {
                iterator.remove();
            }
        }
    }

    private void trimDeque(Deque<Long> deque, long nowMs) {
        while (!deque.isEmpty()) {
            Long oldest = deque.peekFirst();
            if (oldest == null || nowMs - oldest > RENAME_WINDOW_MS) {
                deque.removeFirst();
            } else {
                break;
            }
        }
    }

    private void closeQuietly() {
        WatchService ws = watchService;
        watchService = null;
        if (ws == null) {
            return;
        }

        try {
            ws.close();
        } catch (Throwable ignored) {
            // Ignore close errors.
        }
    }
}
