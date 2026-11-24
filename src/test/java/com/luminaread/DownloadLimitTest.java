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
    @Test void shortTextDownloadsButEmptyResponseFails(@TempDir Path dir) throws Exception {
        var shortText = new BookDownloader(new DownloadSafetyTest.FakeClient("Hello world!"), null);
        var file = shortText.downloadBookFile("http://example.com/book.txt", "short", "TXT", dir, null);
        assertEquals("Hello world!", Files.readString(file.toPath()));
        var empty = new BookDownloader(new DownloadSafetyTest.FakeClient(""), null);
        assertThrows(IllegalStateException.class,
                () -> empty.downloadBookFile("http://example.com/book.txt", "empty", "TXT", dir, null));
    }

    @Test void archiveMetadataNeverSubstitutesAnUnavailableFormat() {
        var downloader = new BookDownloader(new DownloadSafetyTest.FakeClient("""
                {"result":[{"name":"book.pdf"},{"name":"encrypted.epub"},{"name":"book.fb2.zip"}]}
                """), null);
        String original = "https://archive.org/download/item/book.pdf";
        assertThrows(IllegalStateException.class, () -> downloader.resolveBestArchiveOrgUrl(original, "EPUB"));
        assertEquals("https://archive.org/download/item/book.fb2.zip",
                downloader.resolveBestArchiveOrgUrl(original, "FB2.ZIP"));
        assertEquals(original, downloader.resolveBestArchiveOrgUrl(original, "PDF"));
    }

    @Test void unexpectedHtmlIsRejectedEvenWithoutKnownErrorKeywords(@TempDir Path dir) throws Exception {
        String html = "<!doctype html><html><body>Service temporarily unavailable. Please try again later.</body></html>";
        var downloader = new BookDownloader(new DownloadSafetyTest.FakeClient(html), null);
        assertThrows(IllegalStateException.class,
                () -> downloader.downloadBookFile("http://example.com/book.epub", "book", "EPUB", dir, null));
        try (var entries = Files.list(dir)) { assertEquals(0, entries.count()); }
        assertNotNull(downloader.downloadBookFile("http://example.com/book.html", "book", "HTML", dir, null));
    }
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
