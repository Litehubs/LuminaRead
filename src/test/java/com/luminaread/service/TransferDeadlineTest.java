package com.luminaread.service;

import com.luminaread.model.DownloadTask;
import com.luminaread.service.catalog.BookDownloader;
import com.luminaread.util.BoundedHttpBody;
import com.luminaread.util.HttpRequests;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class TransferDeadlineTest {
    @TempDir Path dir;

    @Test void stalledDownloadFailsAndRemovesPartialFile() throws Exception {
        var release = new CountDownLatch(1);
        var headersSent = new CountDownLatch(1);
        var server = stalledServer(release, headersSent);
        try {
            var downloader = new BookDownloader(HttpClient.newHttpClient(), null, 1024, 1024, Duration.ofSeconds(1));
            var task = new DownloadTask("book", "TXT", url(server).toString());
            assertThrows(HttpTimeoutException.class, () -> downloader.downloadBookFileWithTask(task, dir, null));
            assertEquals(0, headersSent.getCount(), "Timeout must be exercised after the response headers");
            assertEquals(DownloadTask.Status.FAILED, task.getStatus());
            try (var files = Files.list(dir)) { assertEquals(0, files.count()); }
        } finally { release.countDown(); server.stop(0); }
    }

    @Test void bufferedResponseAlsoHasABodyDeadline() throws Exception {
        var release = new CountDownLatch(1);
        var headersSent = new CountDownLatch(1);
        var server = stalledServer(release, headersSent);
        try {
            var request = HttpRequest.newBuilder(url(server)).timeout(Duration.ofSeconds(2)).build();
            assertThrows(HttpTimeoutException.class, () -> HttpRequests.send(HttpClient.newHttpClient(), request,
                    BoundedHttpBody.bounded(1024, "too large")));
            assertEquals(0, headersSent.getCount());
        } finally { release.countDown(); server.stop(0); }
    }

    private HttpServer stalledServer(CountDownLatch release, CountDownLatch headersSent) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/book.txt", exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(200, 128);
                exchange.getResponseBody().write('a');
                exchange.getResponseBody().flush();
                headersSent.countDown();
                try { release.await(10, TimeUnit.SECONDS); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            }
        });
        server.start();
        return server;
    }

    private URI url(HttpServer server) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/book.txt");
    }
}
