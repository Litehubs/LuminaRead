package com.luminaread.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class OnlineCatalogFailureTest {
    @Test void httpFailureIsDistinctFromAValidEmptyFeed() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/empty", exchange -> {
            byte[] body = "<?xml version='1.0'?><feed xmlns='http://www.w3.org/2005/Atom'></feed>".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.createContext("/failure", exchange -> { exchange.sendResponseHeaders(503, -1); exchange.close(); });
        server.createContext("/html", exchange -> {
            byte[] body = "<html><body>Not an OPDS feed</body></html>".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.createContext("/huge", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            byte[] block = new byte[64 * 1024];
            try (var output = exchange.getResponseBody()) {
                for (int i = 0; i < 70; i++) output.write(block);
            } catch (java.io.IOException expected) {
                // The client can close the stream before the server finishes writing.
            }
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            OnlineCatalogService service = new OnlineCatalogService();
            assertTrue(service.browseCustomOpds(base + "/empty", "").isEmpty());
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> service.browseCustomOpds(base + "/failure", ""));
            assertTrue(failure.getMessage().contains("Не удалось загрузить"));
            assertTrue(failure.getCause().getMessage().contains("503"));
            assertThrows(IllegalStateException.class, () -> service.browseCustomOpds(base + "/html", ""));
            var oversized = assertThrows(IllegalStateException.class,
                    () -> service.browseCustomOpds(base + "/huge", ""));
            assertTrue(oversized.getCause().getMessage().contains("Ответ каталога превышает"));
        } finally { server.stop(0); }
    }
    @Test void relativeBookLinksFollowTheFinalCatalogAddress(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/start", exchange -> {
            exchange.getResponseHeaders().set("Location", "/books/feed");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/books/feed", exchange -> {
            byte[] xml = """
                    <feed><entry><title>Example</title><link rel="acquisition" href="book.txt"
                      type="text/plain"/></entry></feed>
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, xml.length);
            try (var out = exchange.getResponseBody()) { out.write(xml); }
        });
        server.createContext("/books/book.txt", exchange -> {
            byte[] body = "A complete example book. ".repeat(4).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            var catalog = new OnlineCatalogService();
            var item = catalog.browseCustomOpds(base + "/start", "").get(0);
            String link = item.getDownloadLinks().get("TXT");
            assertEquals(base + "/books/book.txt", link);
            var file = catalog.downloadBookFile(link, "Example", "TXT", dir, null);
            assertEquals("A complete example book. ".repeat(4), java.nio.file.Files.readString(file.toPath()));
        } finally {
            server.stop(0);
        }
    }

    @Test void partialFailureKeepsBooksAndReportsUnavailableSource() {
        var book = new com.luminaread.model.OnlineBookItem("Book", "Author", "", "en", "Working");
        var tasks = new java.util.LinkedHashMap<String, java.util.concurrent.CompletableFuture<
                java.util.List<com.luminaread.model.OnlineBookItem>>>();
        var broken = new java.io.IOException("offline");
        tasks.put("Missing", java.util.concurrent.CompletableFuture.failedFuture(broken));
        tasks.put("Working", java.util.concurrent.CompletableFuture.completedFuture(java.util.List.of(book)));

        var result = OnlineCatalogService.collectSearchResults(tasks);
        assertEquals(java.util.List.of(book), result.books());
        assertEquals(java.util.List.of("Missing"), result.unavailableSources());

        tasks.remove("Working");
        var failure = assertThrows(IllegalStateException.class, () -> OnlineCatalogService.collectSearchResults(tasks));
        assertArrayEquals(new Throwable[]{broken}, failure.getSuppressed());
    }

    @Test void gutenbergMetadataDoesNotDependOnJsonFieldOrder() {
        String body = """
                [{"languages":["ru"],"download_count":2,"authors":[{"name":"Автор"}],
                  "title":"Книга", "id":42}]
                """;
        var items = OnlineCatalogService.parseGutendexBooks(
                assertInstanceOf(java.util.List.class, com.luminaread.util.Json.parse(body)));
        assertEquals(1, items.size());
        var item = items.get(0);
        assertEquals("Книга", item.getTitle());
        assertEquals("Автор", item.getAuthor());
        assertEquals("ru", item.getLanguage());
        assertEquals("https://www.gutenberg.org/ebooks/42.txt.utf-8", item.getDownloadLinks().get("TXT"));
    }

    @Test void archiveParsersKeepBookBoundariesAndRejectUnsafeIdentifiers() {
        String body = """
                [
                  {"title":"A title with { braces }", "extra":{"title":"Wrong nested title"},
                   "creator":["Archive author"], "identifier":"book_123",
                   "author_name":["Open Library author"], "ia":["book_123"], "first_publish_year":1900},
                  {"title":"Unsafe record", "identifier":"../details", "ia":["bad?id"]},
                  {"extra":{"title":"Not a book", "identifier":"fake", "ia":["fake"]}}
                ]
                """;
        var docs = assertInstanceOf(java.util.List.class, com.luminaread.util.Json.parse(body));
        var archive = OnlineCatalogService.parseArchiveBooks(docs, true);
        var openLibrary = OnlineCatalogService.parseArchiveBooks(docs, false);
        assertEquals(1, archive.size());
        assertEquals(1, openLibrary.size());
        assertEquals("A title with { braces }", archive.get(0).getTitle());
        assertEquals("Archive author", archive.get(0).getAuthor());
        assertEquals("Open Library author", openLibrary.get(0).getAuthor());
        assertEquals("https://archive.org/download/book_123/book_123.epub",
                openLibrary.get(0).getDownloadLinks().get("EPUB"));
    }

    @Test void failedImportLeavesTheDownloadedFileAndReportsTheRightStage(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path home) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var requests = new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/book.txt", exchange -> {
            requests.incrementAndGet();
            byte[] body = "A normal line in a book. ".repeat(10).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        var catalog = new OnlineCatalogService();
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/book.txt";
        var task = catalog.createLibraryDownload("book", "TXT", url);
        var library = new LibraryStorageService(home) {
            @Override public com.luminaread.model.Book importBookFile(java.io.File file, boolean copy) throws Exception {
                assertEquals(com.luminaread.model.DownloadTask.Status.IMPORTING, task.getStatus());
                catalog.clearCompletedTasks();
                assertTrue(catalog.getDownloadTasks().contains(task));
                throw new java.io.IOException("simulated import failure");
            }
        };
        try {
            var error = assertThrows(IllegalStateException.class,
                    () -> catalog.downloadAndImportBook(task, library, null));
            assertEquals("simulated import failure", error.getCause().getMessage());
            assertEquals(com.luminaread.model.DownloadTask.Status.FAILED, task.getStatus());
            assertTrue(task.getErrorMessage().contains("Файл скачан"));
            assertTrue(task.getSavedFile().isFile());
            assertNull(task.getImportedBook());

            var reopened = new LibraryStorageService(home);
            var restarted = new OnlineCatalogService();
            restarted.recoverDownloads(reopened);
            assertEquals(1, requests.get(), "Recovery must reuse the downloaded file");
            assertEquals(1, reopened.getAllBooks().size());
            var recovered = restarted.getDownloadTasks().get(0);
            assertEquals(task.getId(), recovered.getId());
            assertEquals(com.luminaread.model.DownloadTask.Status.COMPLETED, recovered.getStatus());
            assertEquals(task.getSavedFile().getAbsolutePath(), recovered.getImportedBook().getFilePath());

            restarted.clearLibraryDownloads(reopened);
            var afterClear = new OnlineCatalogService();
            afterClear.recoverDownloads(new LibraryStorageService(home));
            assertTrue(afterClear.getDownloadTasks().isEmpty());
            assertTrue(task.getSavedFile().isFile(), "Clearing history must keep the downloaded book");

            var success = catalog.createLibraryDownload("other book", "TXT", url);
            assertNotNull(catalog.downloadAndImportBook(success, new LibraryStorageService(home), null));
            assertEquals(com.luminaread.model.DownloadTask.Status.COMPLETED, success.getStatus());
            assertNotNull(success.getImportedBook());
        } finally {
            server.stop(0);
        }
    }

}
