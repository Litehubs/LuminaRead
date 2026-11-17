package com.luminaread.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Reads untrusted input without buffering beyond its caller's size limit. */
public final class BoundedInput {
    private BoundedInput() {}

    public static byte[] read(Path path, int maxBytes) throws IOException {
        if (Files.size(path) > maxBytes) throw new IOException("Файл превышает допустимый размер.");
        try (InputStream input = Files.newInputStream(path)) {
            return read(input, maxBytes);
        }
    }

    public static byte[] read(InputStream input, int maxBytes) throws IOException {
        if (maxBytes <= 0) throw new IllegalArgumentException("Size limit must be positive");
        var output = new ByteArrayOutputStream();
        byte[] buffer = new byte[Math.min(8192, maxBytes)];
        while (true) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Чтение отменено.");
            int remaining = maxBytes - output.size();
            int count = input.read(buffer, 0, remaining >= buffer.length ? buffer.length : remaining + 1);
            if (count < 0) return output.toByteArray();
            if (count > maxBytes - output.size()) throw new IOException("Данные превышают допустимый размер.");
            output.write(buffer, 0, count);
        }
    }
}
