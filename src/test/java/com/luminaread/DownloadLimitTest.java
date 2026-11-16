package com.luminaread;

import com.luminaread.service.catalog.BookDownloader;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

final class DownloadLimitTest {
    @Test void oversizedResponseDoesNotReplaceExistingBook(@TempDir Path dir) throws Exception {
        Path existing = dir.resolve("book.txt");
        Files.writeString(existing, "existing");
        var downloader = new BookDownloader(new DownloadSafetyTest.FakeClient("x".repeat(200)), null, 100, 1000);
        assertThrows(Exception.class, () -> downloader.downloadBookFile("http://example.com/book.txt", "book", "TXT", dir, null));
        assertEquals("existing", Files.readString(existing));
        try (var entries = Files.list(dir)) { assertEquals(1, entries.count()); }
    }
    @Test void oversizedGzipDoesNotLeaveTemporaryFiles(@TempDir Path dir) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(buffer)) { gzip.write(new byte[500]); }
        var downloader = new BookDownloader(new DownloadSafetyTest.FakeClient(buffer.toByteArray()), null, 1000, 100);
        assertThrows(Exception.class, () -> downloader.downloadBookFile("http://example.com/book.txt", "book", "TXT", dir, null));
        try (var entries = Files.list(dir)) { assertEquals(0, entries.count()); }
    }
    @Test void oversizedArchiveMetadataIsClosedAndIgnored() {
        var client = new DownloadSafetyTest.FakeClient(new byte[2 * 1024 * 1024 + 1]);
        var downloader = new BookDownloader(client, null, 100, 100);
        String original = "https://archive.org/download/item/book.epub";
        assertEquals(original, downloader.resolveBestArchiveOrgUrl(original, "EPUB"));
        assertTrue(client.bodyClosed, "Metadata response stream must be closed");
    }
    @Test void normalArchiveMetadataStillSelectsTheRequestedFormat() {
        var client = new DownloadSafetyTest.FakeClient("{\"files\":[{\"name\":\"book.epub\"}]}");
        var downloader = new BookDownloader(client, null, 100, 100);
        assertEquals("https://archive.org/download/item/book.epub", downloader.resolveBestArchiveOrgUrl(
                "https://archive.org/download/item/other.pdf", "EPUB"));
        assertTrue(client.bodyClosed);
    }
}
