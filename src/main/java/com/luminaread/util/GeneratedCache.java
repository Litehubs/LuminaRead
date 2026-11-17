package com.luminaread.util;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

/** Enforces the disk budget when publishing assets that the reader may still be displaying. */
public final class GeneratedCache {
    public static final long MAX_BYTES = 512L * 1024 * 1024;
    private static final Map<Path, Long> usage = new HashMap<>();

    private GeneratedCache() {}

    public static synchronized void write(Path file, AtomicFile.Writer writer) throws IOException {
        Path root = Path.of(System.getProperty("user.home"), ".luminaread", "cache").toAbsolutePath().normalize();
        Path target = file.toAbsolutePath().normalize();
        if (!target.startsWith(root) || Files.isSymbolicLink(root) || Files.isSymbolicLink(target.getParent())) {
            throw new IOException("Некорректная папка кэша");
        }
        long bytes = usage.containsKey(root) ? usage.get(root) : measure(root);
        long oldSize = Files.isRegularFile(target, NOFOLLOW_LINKS) ? Files.size(target) : 0;
        long allowed = Math.max(0, MAX_BYTES - bytes);
        long[] written = {0};
        AtomicFile.write(target, output -> writer.write(new FilterOutputStream(output) {
            @Override public void write(int value) throws IOException {
                reserve(1);
                out.write(value);
            }

            @Override public void write(byte[] data, int offset, int length) throws IOException {
                reserve(length);
                out.write(data, offset, length);
            }

            private void reserve(int count) throws IOException {
                if (count > allowed - written[0]) throw new IOException("Недостаточно места в кэше LuminaRead");
                written[0] += count;
            }
        }));
        usage.put(root, Math.max(0, bytes - oldSize) + written[0]);
    }

    public static synchronized void invalidate(Path root) {
        usage.remove(root.toAbsolutePath().normalize());
    }

    public static long measure(Path root) throws IOException {
        if (!Files.isDirectory(root, NOFOLLOW_LINKS)) return 0;
        long bytes = 0;
        try (var paths = Files.walk(root, 2)) {
            var files = paths.iterator();
            while (files.hasNext()) {
                Path file = files.next();
                if (Files.isRegularFile(file, NOFOLLOW_LINKS)) bytes += Files.size(file);
            }
        }
        return bytes;
    }
}
