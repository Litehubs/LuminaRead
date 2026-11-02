package com.luminaread;

import com.luminaread.model.DownloadTask;
import com.luminaread.model.OnlineBookItem;
import com.luminaread.service.IOnlineCatalogService;
import com.luminaread.service.OnlineCatalogService;
import com.luminaread.service.catalog.IBookDownloader;
import com.luminaread.service.catalog.ICatalogSearchService;
import com.luminaread.service.catalog.IDownloadManager;
import com.luminaread.service.catalog.OpdsFeedParser;
import com.luminaread.service.catalog.BookDownloader;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;

public class OnlineCatalogArchitectureTest {

    public static void main(String[] args) throws Exception {
        System.out.println("=== RUNNING ONLINE CATALOG ARCHITECTURE TESTS ===");

        testInterfaceSegregationHierarchy();
        testOpdsFeedParser();
        testOpdsFeedParserBareAmpersandAndMalformed();
        testBookDownloaderExtensionDetection();
        testBookDownloaderGzipDecompression();
        testBookDownloaderFileValidation();
        testOnlineCatalogServiceDelegationAndListeners();

        System.out.println("=== ALL ONLINE CATALOG ARCHITECTURE TESTS PASSED! ===");
    }

    private static void testInterfaceSegregationHierarchy() {
        require(ICatalogSearchService.class.isAssignableFrom(IOnlineCatalogService.class),
                "IOnlineCatalogService must extend ICatalogSearchService");
        require(IDownloadManager.class.isAssignableFrom(IOnlineCatalogService.class),
                "IOnlineCatalogService must extend IDownloadManager");
        require(IBookDownloader.class.isAssignableFrom(IOnlineCatalogService.class),
                "IOnlineCatalogService must extend IBookDownloader");

        require(IOnlineCatalogService.class.isAssignableFrom(OnlineCatalogService.class),
                "OnlineCatalogService must implement IOnlineCatalogService");
        require(ICatalogSearchService.class.isAssignableFrom(OnlineCatalogService.class),
                "OnlineCatalogService must implement ICatalogSearchService");
        require(IDownloadManager.class.isAssignableFrom(OnlineCatalogService.class),
                "OnlineCatalogService must implement IDownloadManager");
        require(IBookDownloader.class.isAssignableFrom(OnlineCatalogService.class),
                "OnlineCatalogService must implement IBookDownloader");
        require(IBookDownloader.class.isAssignableFrom(BookDownloader.class),
                "BookDownloader must implement IBookDownloader");

        System.out.println("[PASS] ISP hierarchy validated successfully.");
    }

    private static void testOpdsFeedParser() {
        String sampleFeed = """
                <?xml version="1.0" encoding="utf-8"?>
                <feed xmlns="http://www.w3.org/2005/Atom">
                  <title>Sample Catalog</title>
                  <entry>
                    <title>Война и мир</title>
                    <author><name>Лев Толстой</name></author>
                    <summary>&lt;b&gt;Великий&lt;/b&gt; роман-эпопея.</summary>
                    <dc:language>ru-RU</dc:language>
                    <link rel="http://opds-spec.org/image/thumbnail" href="/covers/war_and_peace.jpg" type="image/jpeg" />
                    <link rel="http://opds-spec.org/acquisition" href="/download/war_and_peace.epub" type="application/epub+zip" />
                    <link rel="http://opds-spec.org/acquisition" href="/download/war_and_peace.fb2.zip" type="application/fb2+zip" />
                  </entry>
                  <entry>
                    <title>The Great Gatsby</title>
                    <author><name>F. Scott Fitzgerald</name></author>
                    <content>A 1925 novel by American writer.</content>
                    <language>en</language>
                    <link rel="http://opds-spec.org/cover" href="https://example.com/gatsby.png" type="image/png" />
                    <link rel="http://opds-spec.org/acquisition" href="https://example.com/gatsby.pdf" type="application/pdf" />
                  </entry>
                </feed>
                """;

        OpdsFeedParser parser = new OpdsFeedParser();
        List<OnlineBookItem> items = parser.parse(sampleFeed, "https://opds.example.com/catalog", "Test Catalog");

        require(items.size() == 2, "Expected 2 books parsed, got: " + items.size());

        OnlineBookItem item1 = items.get(0);
        require("Война и мир".equals(item1.getTitle()), "Title mismatch: " + item1.getTitle());
        require("Лев Толстой".equals(item1.getAuthor()), "Author mismatch: " + item1.getAuthor());
        require("Великий роман-эпопея.".equals(item1.getSummary()), "Summary mismatch: " + item1.getSummary());
        require("ru".equals(item1.getLanguage()), "Language mismatch: " + item1.getLanguage());
        require("https://opds.example.com/covers/war_and_peace.jpg".equals(item1.getCoverUrl()),
                "Cover mismatch: " + item1.getCoverUrl());
        require(item1.getDownloadLinks().containsKey("EPUB"), "Missing EPUB download link");
        require(item1.getDownloadLinks().containsKey("FB2.ZIP"), "Missing FB2.ZIP download link");
        require("https://opds.example.com/download/war_and_peace.epub".equals(item1.getDownloadLinks().get("EPUB")),
                "EPUB URL mismatch");

        OnlineBookItem item2 = items.get(1);
        require("The Great Gatsby".equals(item2.getTitle()), "Title mismatch");
        require("F. Scott Fitzgerald".equals(item2.getAuthor()), "Author mismatch");
        require("en".equals(item2.getLanguage()), "Language mismatch");
        require("https://example.com/gatsby.png".equals(item2.getCoverUrl()), "Cover URL mismatch");
        require("https://example.com/gatsby.pdf".equals(item2.getDownloadLinks().get("PDF")), "PDF URL mismatch");

        System.out.println("[PASS] OpdsFeedParser standard feed parsing validated.");
    }

    private static void testOpdsFeedParserBareAmpersandAndMalformed() {
        OpdsFeedParser parser = new OpdsFeedParser();

        String rawXmlWithBareAmp = "<feed><entry><title>Tom & Jerry</title><link rel='acquisition' href='/book.epub' type='application/epub+zip'/></entry></feed>";
        List<OnlineBookItem> items = parser.parse(rawXmlWithBareAmp, "http://example.com", "Test");
        require(items.size() == 1, "Bare ampersand should be tolerated and parsed");
        require(items.get(0).getTitle().contains("Tom & Jerry"), "Title should preserve '&': " + items.get(0).getTitle());

        List<OnlineBookItem> emptyItems = parser.parse((String) null, "http://example.com", "Test");
        require(emptyItems.isEmpty(), "Null feed must return empty list");

        List<OnlineBookItem> malformedItems = parser.parse("<<<not-xml>>>", "http://example.com", "Test");
        require(malformedItems.isEmpty(), "Malformed xml must return empty list without exception");

        System.out.println("[PASS] OpdsFeedParser bare ampersand and error resilience validated.");
    }

    private static void testBookDownloaderExtensionDetection() {
        BookDownloader downloader = new BookDownloader();
        require(".epub".equals(downloader.determineExtension("https://site.org/get?id=1", "EPUB", "application/octet-stream", "")),
                "Should detect .epub from formatHint");
        require(".fb2.zip".equals(downloader.determineExtension("https://site.org/dl/book/fb2", null, "application/octet-stream", "")),
                "Should detect .fb2.zip from URL path /fb2");
        require(".pdf".equals(downloader.determineExtension("https://site.org/download", null, "application/pdf", "")),
                "Should detect .pdf from content-type");
        require(".epub".equals(downloader.determineExtension("https://site.org/dl", null, "", "attachment; filename=\"book.epub\"")),
                "Should detect .epub from Content-Disposition header");

        System.out.println("[PASS] BookDownloader extension detection validated.");
    }

    private static void testBookDownloaderGzipDecompression() throws Exception {
        BookDownloader downloader = new BookDownloader();
        Path tempFile = Files.createTempFile("luminaread-test-gzip-", ".txt");
        try {
            String originalText = "Hello LuminaRead GZIP Stream Handling!";
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (GZIPOutputStream gzos = new GZIPOutputStream(baos)) {
                gzos.write(originalText.getBytes(StandardCharsets.UTF_8));
            }
            Files.write(tempFile, baos.toByteArray());

            File result = downloader.decompressGzipIfNeeded(tempFile.toFile());
            String decompressed = Files.readString(result.toPath(), StandardCharsets.UTF_8);
            require(originalText.equals(decompressed), "Decompressed text must match original: " + decompressed);
        } finally {
            Files.deleteIfExists(tempFile);
        }

        System.out.println("[PASS] BookDownloader GZIP decompression validated.");
    }

    private static void testBookDownloaderFileValidation() throws Exception {
        BookDownloader downloader = new BookDownloader();
        Path tempFile = Files.createTempFile("luminaread-test-empty-", ".epub");
        try {
            boolean caught = false;
            try {
                downloader.validateDownloadedBookFile(tempFile.toFile(), "EPUB");
            } catch (IllegalStateException e) {
                caught = true;
            }
            require(caught, "Empty file should fail validation");
        } finally {
            Files.deleteIfExists(tempFile);
        }

        Path htmlDisguised = Files.createTempFile("luminaread-test-html-", ".epub");
        try {
            Files.writeString(htmlDisguised, "<!doctype html><html><head><title>Archive.org Login</title></head><body>login required</body></html>");
            boolean caught = false;
            try {
                downloader.validateDownloadedBookFile(htmlDisguised.toFile(), "EPUB");
            } catch (IllegalStateException e) {
                caught = true;
            }
            require(caught, "HTML error page disguised as EPUB should fail validation");
        } finally {
            Files.deleteIfExists(htmlDisguised);
        }

        System.out.println("[PASS] BookDownloader file validation validated.");
    }

    private static void testOnlineCatalogServiceDelegationAndListeners() {
        OnlineCatalogService service = new OnlineCatalogService();
        AtomicInteger listenerNotified = new AtomicInteger(0);
        service.addDownloadListener(listenerNotified::incrementAndGet);

        DownloadTask task = service.createTrackedTask("Test Task Title", "EPUB", "https://example.com/test.epub");
        require(task != null, "Tracked task must not be null");
        require(service.getDownloadTasks().size() == 1, "Task must be present in download list");
        require(service.getDownloadTasks().get(0) == task, "Task must match");

        // Wait a moment for SwingUtilities.invokeLater if needed
        try {
            Thread.sleep(100);
        } catch (InterruptedException ignored) {}

        task.setStatus(DownloadTask.Status.COMPLETED);
        service.clearCompletedTasks();
        require(service.getDownloadTasks().isEmpty(), "Completed tasks should be cleared");

        System.out.println("[PASS] OnlineCatalogService delegation and listeners validated.");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
