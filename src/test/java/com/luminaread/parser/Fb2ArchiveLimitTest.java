package com.luminaread.parser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.OutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import static org.junit.jupiter.api.Assertions.*;

class Fb2ArchiveLimitTest {
    @TempDir Path dir;
    private static final byte[] VALID = ("<?xml version=\"1.0\"?><FictionBook><description><title-info>"
            + "<book-title>Test</book-title></title-info></description><body><section><p>Hello</p>"
            + "</section></body></FictionBook>").getBytes(StandardCharsets.UTF_8);

    @Test void validZipStillOpens() throws Exception {
        Path file = dir.resolve("book.fb2.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(file))) {
            out.putNextEntry(new ZipEntry("book.fb2")); out.write(VALID); out.closeEntry();
        }
        assertEquals("Test", new Fb2Parser().parse(file.toFile()).getTitle());
    }

    @Test void oversizedZipIsRejected() throws Exception {
        Path file = dir.resolve("large.fb2.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(file))) {
            out.putNextEntry(new ZipEntry("book.fb2")); writeOversized(out); out.closeEntry();
        }
        assertThrows(IOException.class, () -> new Fb2Parser().parse(file.toFile()));
    }

    @Test void oversizedGzipIsRejected() throws Exception {
        Path file = dir.resolve("large.fb2");
        try (GZIPOutputStream out = new GZIPOutputStream(Files.newOutputStream(file))) { writeOversized(out); }
        assertThrows(IOException.class, () -> new Fb2Parser().parse(file.toFile()));
    }

    private static void writeOversized(OutputStream out) throws IOException {
        byte[] chunk = new byte[8192];
        for (int i = 0; i < 8192; i++) out.write(chunk);
        out.write(0);
    }
}
