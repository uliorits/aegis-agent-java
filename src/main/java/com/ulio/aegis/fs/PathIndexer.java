package com.ulio.aegis.fs;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class PathIndexer {
    private final WatchService watchService;
    private final Map<WatchKey, Path> indexedDirectories = new ConcurrentHashMap<>();

    public PathIndexer(WatchService watchService) {
        this.watchService = watchService;
    }

    public void registerRecursively(Path root) throws IOException {
        if (root == null || !Files.isDirectory(root)) {
            return;
        }

        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                registerDirectoryQuietly(dir);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });
    }

    public void registerDirectory(Path dir) throws IOException {
        if (dir == null || !Files.isDirectory(dir)) {
            return;
        }

        WatchKey key = dir.register(
                watchService,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY,
                StandardWatchEventKinds.ENTRY_DELETE
        );
        indexedDirectories.put(key, dir.toAbsolutePath().normalize());
    }

    public Path resolveDirectory(WatchKey key) {
        return indexedDirectories.get(key);
    }

    public void removeKey(WatchKey key) {
        indexedDirectories.remove(key);
    }

    private void registerDirectoryQuietly(Path dir) {
        try {
            registerDirectory(dir);
        } catch (IOException ignored) {
            // Keep walking directories even when one registration fails.
        }
    }
}
