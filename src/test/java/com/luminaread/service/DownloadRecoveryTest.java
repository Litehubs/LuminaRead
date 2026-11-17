package com.luminaread.service;

import com.luminaread.model.DownloadTask;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class DownloadRecoveryTest {
    @TempDir Path home;

    @Test void processExitAfterTransferRecoversTheActualDownload() throws Exception {
        var requests = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/book.txt", exchange -> {
            requests.incrementAndGet();
            byte[] body = "A real download awaiting import. ".repeat(8).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/book.txt";
            Path log = home.resolve("transfer.log");
            var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp", System.getProperty("java.class.path"), CrashAfterTransfer.class.getName(), home.toString(), url)
                    .redirectErrorStream(true).redirectOutput(log.toFile()).start();
            try {
                assertTrue(process.waitFor(20, TimeUnit.SECONDS), "Transfer process timed out");
                assertEquals(73, process.exitValue(), Files.readString(log));
            } finally {
                process.destroyForcibly();
            }

            var library = new LibraryStorageService(home);
            assertTrue(library.getAllBooks().isEmpty());
            var catalog = new OnlineCatalogService();
            catalog.recoverDownloads(library);
            assertEquals(1, requests.get());
            assertEquals(1, library.getAllBooks().size());
            assertEquals(DownloadTask.Status.COMPLETED, catalog.getDownloadTasks().get(0).getStatus());
        } finally {
            server.stop(0);
        }
    }

    public static class CrashAfterTransfer {
        public static void main(String[] args) throws Exception {
            var library = new LibraryStorageService(Path.of(args[0]));
            var task = new DownloadTask("Book", "TXT", args[1], true);
            var queue = new DownloadQueue(library.getBooksDir());
            new OnlineCatalogService().downloadBookFileWithTask(task, queue.begin(task), null);
            Runtime.getRuntime().halt(73);
        }
    }

    @Test void interruptedTransferIsVisibleAndOnlyItsPartialFileIsRemoved() throws Exception {
        var library = new LibraryStorageService(home);
        var queue = new DownloadQueue(library.getBooksDir());
        var task = new DownloadTask("Книга", "TXT", "https://example.test/book?token=secret", true);
        Path directory = queue.begin(task);
        Path part = Files.writeString(directory.resolve(".luminaread-download-1.tmp"), "partial");
        Path finderInfo = Files.writeString(directory.resolve(".DS_Store"), "Finder metadata");
        assertFalse(Files.readString(directory.resolve(".job.json")).contains("secret"));

        var catalog = new OnlineCatalogService();
        catalog.recoverDownloads(library);
        assertEquals(DownloadTask.Status.FAILED, catalog.getDownloadTasks().get(0).getStatus());
        assertEquals(task.getId(), catalog.getDownloadTasks().get(0).getId());
        assertFalse(Files.exists(part));
        assertTrue(Files.exists(finderInfo));
        assertTrue(library.getAllBooks().isEmpty());
    }

    @Test void restartBetweenLibraryCommitAndQueueUpdateKeepsOneBook() throws Exception {
        var library = new LibraryStorageService(home);
        var queue = new DownloadQueue(library.getBooksDir());
        var task = new DownloadTask("Книга", "TXT", "https://example.test/book", true);
        Path file = Files.writeString(queue.begin(task).resolve("book.txt"), "The completed book.");
        var original = library.importBookFile(file.toFile(), false);

        var catalog = new OnlineCatalogService();
        var reopened = new LibraryStorageService(home);
        catalog.recoverDownloads(reopened);
        var tasks = catalog.getDownloadTasks();
        assertEquals(1, reopened.getAllBooks().size());
        assertEquals(1, tasks.size());
        assertEquals(original.getId(), tasks.get(0).getImportedBook().getId());
        assertEquals(DownloadTask.Status.COMPLETED, tasks.get(0).getStatus());

        // A finished entry restores history without bringing back a deliberately removed book.
        reopened.removeBook(reopened.getAllBooks().get(0), false);
        var restarted = new OnlineCatalogService();
        restarted.recoverDownloads(new LibraryStorageService(home));
        assertEquals(DownloadTask.Status.COMPLETED, restarted.getDownloadTasks().get(0).getStatus());
        assertNull(restarted.getDownloadTasks().get(0).getImportedBook());
    }
}
