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
    @Test void connectsToTheValidatedAddressAndBlocksRedirectToPrivateNetwork() throws Exception {
        var loopback = InetAddress.getByName("127.0.0.1");
        HttpServer server = HttpServer.create(new InetSocketAddress(loopback, 0), 0);
        var blocked = new java.util.concurrent.atomic.AtomicInteger();
        var lookups = new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/redirect", exchange -> {
            String location = "http://private.fixture.invalid:" + server.getAddress().getPort() + "/blocked";
            exchange.getResponseHeaders().set("Location", location);
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/valid", exchange -> {
            assertTrue(exchange.getRequestHeaders().getFirst("Host").startsWith("images.fixture.invalid:"));
            exchange.sendResponseHeaders(200, 1);
            try (var out = exchange.getResponseBody()) { out.write(9); }
        });
        server.createContext("/blocked", exchange -> {
            blocked.incrementAndGet();
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.start();
        try {
            var policy = new ImageUrlPolicy(host -> {
                lookups.incrementAndGet();
                return switch (host) {
                    case "images.fixture.invalid" -> new InetAddress[]{loopback};
                    case "private.fixture.invalid" -> new InetAddress[]{InetAddress.getByName("192.168.0.1")};
                    default -> throw new java.net.UnknownHostException(host);
                };
            }, true);
            var media = new MediaCacheService(100, policy);
            String base = "http://images.fixture.invalid:" + server.getAddress().getPort();
            assertArrayEquals(new byte[]{9}, media.downloadImageBytes(base + "/valid", null));
            assertEquals(1, lookups.get(), "The connection must use the result of validation");
            assertNull(media.downloadImageBytes(base + "/redirect", null));
            assertEquals(3, lookups.get());
            assertEquals(0, blocked.get());
        } finally {
            server.stop(0);
        }
    }

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
            MediaCacheService media = new MediaCacheService(100, new ImageUrlPolicy(InetAddress::getAllByName, true));
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
