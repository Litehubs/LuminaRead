package com.luminaread.util;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

/** Writes a complete replacement beside the original before publishing it. */
public final class AtomicFile {
    private AtomicFile() {}

    @FunctionalInterface
    public interface Writer { void write(OutputStream output) throws IOException; }

    /** Rejects oversized output before it can replace the last committed file. */
    public static void write(Path target, long maxBytes, Writer writer) throws IOException {
        if (maxBytes <= 0) throw new IllegalArgumentException("Size limit must be positive");
        write(target, output -> writer.write(new FilterOutputStream(output) {
            private long remaining = maxBytes;

            @Override public void write(int value) throws IOException {
                reserve(1);
                out.write(value);
            }

            @Override public void write(byte[] data, int offset, int length) throws IOException {
                Objects.checkFromIndexSize(offset, length, data.length);
                reserve(length);
                out.write(data, offset, length);
            }

            private void reserve(int bytes) throws IOException {
                if (bytes > remaining) throw new IOException("Данные превышают допустимый размер файла.");
                remaining -= bytes;
            }
        }));
    }

    public static void write(Path target, Writer writer) throws IOException {
        Path absolute = target.toAbsolutePath();
        Path parent = absolute.getParent();
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, ".luminaread-", ".tmp");
        try {
            try (OutputStream output = Files.newOutputStream(temporary)) {
                writer.write(output);
            }
            if (Thread.currentThread().isInterrupted()) {
                throw new java.io.InterruptedIOException("Запись отменена до публикации файла");
            }
            try {
                Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
