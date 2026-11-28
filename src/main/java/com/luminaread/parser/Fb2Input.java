package com.luminaread.parser;

import com.luminaread.util.BoundedInput;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Reads an FB2 document from a plain file, gzip stream or ZIP archive. */
final class Fb2Input {
    private static final int MAX_BYTES = 64 * 1024 * 1024;
    private static final int MAX_ENTRIES = 10_000;

    private Fb2Input() {}

    static byte[] read(Path path) throws IOException {
        byte[] bytes = BoundedInput.read(path, MAX_BYTES);
        if (bytes.length > 2 && (bytes[0] & 0xff) == 0x1f && (bytes[1] & 0xff) == 0x8b) {
            try (var gzip = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
                bytes = BoundedInput.read(gzip, MAX_BYTES);
            }
        }
        if (!BookFormat.zipHeader(bytes)) return bytes;
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            return readArchive(zip);
        }
    }

    private static byte[] readArchive(ZipInputStream zip) throws IOException {
        byte[] firstFile = null;
        int remaining = MAX_BYTES;
        byte[] buffer = new byte[8192];
        int entries = 0;
        ZipEntry entry;
        while ((entry = zip.getNextEntry()) != null) {
            if (++entries > MAX_ENTRIES) throw new IOException("Слишком много файлов в архиве FB2");
            if (entry.isDirectory()) {
                if (zip.read() != -1) throw new IOException("Папка в архиве FB2 содержит данные");
                continue;
            }
            boolean isBook = entry.getName().toLowerCase(Locale.ROOT).endsWith(".fb2");
            // Some older archives contain a single book with a nonstandard filename.
            var output = isBook || firstFile == null ? new ByteArrayOutputStream() : null;
            int count;
            while ((count = zip.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Чтение отменено");
                if (count > remaining) throw new IOException("Размер распакованного FB2 превышает 64 МиБ");
                remaining -= count;
                if (output != null) output.write(buffer, 0, count);
            }
            if (isBook) return output.toByteArray();
            if (firstFile == null) firstFile = output.toByteArray();
        }
        if (firstFile != null) return firstFile;
        throw new IOException("В архиве FB2 не найдено файлов");
    }
}
