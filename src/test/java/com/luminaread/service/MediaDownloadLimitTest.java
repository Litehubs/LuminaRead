package com.luminaread.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class MediaDownloadLimitTest {
    @Test void acceptsLimitAndRejectsDeclaredAndChunkedOversize(@TempDir Path home) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/exact", exchange -> {
            byte[] body = new byte[100]; Arrays.fill(body, (byte) 7);
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        server.createContext("/declared", exchange -> {
            exchange.sendResponseHeaders(200, 101);
            try (var out = exchange.getResponseBody()) { out.write(new byte[101]); }
            catch (java.io.IOException expected) { /* Client may reject the header before reading. */ }
        });
        server.createContext("/chunked", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (var out = exchange.getResponseBody()) { out.write(new byte[101]); }
            catch (java.io.IOException expected) { /* Client may close after reaching the limit. */ }
        });
        server.start();
        String originalHome = System.getProperty("user.home");
        try {
            System.setProperty("user.home", home.toString());
            String base = "http://localhost:" + server.getAddress().getPort();
            MediaCacheService media = new MediaCacheService(100);
            byte[] exact = media.downloadImageBytes(base + "/exact", null);
            assertNotNull(exact);
            assertEquals(100, exact.length);
            assertEquals(7, exact[0]);
            assertNull(media.downloadImageBytes(base + "/declared", null));
            assertNull(media.downloadImageBytes(base + "/chunked", null));
            assertEquals("", media.cacheImageToDisk(base + "/chunked", "test", null, 680).localUrl,
                    "The renderer must not retry an oversized remote image outside the limit");
        } finally {
            if (originalHome == null) System.clearProperty("user.home");
            else System.setProperty("user.home", originalHome);
            server.stop(0);
        }
    }
}
