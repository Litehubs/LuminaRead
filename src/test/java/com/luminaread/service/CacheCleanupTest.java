package com.luminaread.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class CacheCleanupTest {
    @TempDir Path temp;

    @Test void prunesOnlyOldGeneratedFiles() throws Exception {
        Path cache = temp.resolve("cache");
        Path dir = Files.createDirectories(cache.resolve("books"));
        Instant cutoff = Instant.now().minusSeconds(3600);
        Path old = oldFile(dir.resolve("a".repeat(64) + ".png"));
        Path fresh = Files.writeString(dir.resolve("b".repeat(64) + ".png"), "fresh");
        Path unknown = oldFile(dir.resolve("Keep.txt"));
        Path outside = oldFile(temp.resolve("c".repeat(64) + ".png"));
        Path link = dir.resolve("d".repeat(64) + ".png");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (IOException | UnsupportedOperationException ignored) {
            // Windows may restrict symlink creation on the test account.
        }
        Path pdf = Files.createDirectory(cache.resolve("pdf_11111111-2222-3333-4444-555555555555"));
        oldFile(pdf.resolve("page_1.png"));
        Files.setLastModifiedTime(pdf, FileTime.from(Instant.EPOCH));

        CacheCleanup.prune(cache, cutoff);

        assertFalse(Files.exists(old));
        assertFalse(Files.exists(pdf));
        assertTrue(Files.exists(fresh));
        assertTrue(Files.exists(unknown));
        assertTrue(Files.exists(outside));
    }

    @Test void runtimeDiskBudgetKeepsAnExistingAssetAfterRejectedWrite() throws Exception {
        var root = Files.createDirectories(temp.resolve(".luminaread/cache/books"));
        Path previous = Files.writeString(root.resolve("a".repeat(64) + ".png"), "ok");
        try (var filler = new java.io.RandomAccessFile(root.resolve("b".repeat(64) + ".png").toFile(), "rw")) {
            filler.setLength(com.luminaread.util.GeneratedCache.MAX_BYTES - 4);
        }
        String home = System.getProperty("user.home");
        try {
            System.setProperty("user.home", temp.toString());
            assertThrows(IOException.class, () -> com.luminaread.util.GeneratedCache.write(previous,
                    output -> output.write(new byte[3])));
            assertEquals("ok", Files.readString(previous));
            try (var files = Files.list(root)) {
                assertEquals(2, files.count(), "Partial writes must be removed");
            }
        } finally {
            com.luminaread.util.GeneratedCache.invalidate(root.getParent());
            System.setProperty("user.home", home);
        }
    }

    private static Path oldFile(Path file) throws IOException {
        Files.writeString(file, "old");
        Files.setLastModifiedTime(file, FileTime.from(Instant.EPOCH));
        return file;
    }
}
