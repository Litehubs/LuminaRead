package com.luminaread.service;

import com.luminaread.service.catalog.BookDownloader;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class CatalogTransportTest {
    @TempDir Path home;

    @Test void secureRedirectChainStopsBeforeAPlainHttpHop() throws Exception {
        SSLContext tls = localTls();
        var bind = new InetSocketAddress("127.0.0.1", 0);
        var https = HttpsServer.create(bind, 0);
        var http = HttpServer.create(bind, 0);
        var secureHops = new AtomicInteger();
        var plainRequests = new AtomicInteger();
        https.setHttpsConfigurator(new HttpsConfigurator(tls));
        https.createContext("/start", exchange -> redirect(exchange, "/next"));
        https.createContext("/next", exchange -> {
            secureHops.incrementAndGet();
            redirect(exchange, "http://127.0.0.1:" + http.getAddress().getPort() + "/end");
        });
        http.createContext("/end", exchange -> {
            plainRequests.incrementAndGet();
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        // Only the two test clients capture this trust store at construction.
        SSLContext previous = SSLContext.getDefault();
        OnlineCatalogService catalog;
        BookDownloader downloads;
        try {
            SSLContext.setDefault(tls);
            catalog = new OnlineCatalogService();
            downloads = new BookDownloader();
        } finally {
            SSLContext.setDefault(previous);
        }
        https.start();
        http.start();
        try {
            String url = "https://127.0.0.1:" + https.getAddress().getPort() + "/start";
            var feed = assertThrows(IllegalStateException.class, () -> catalog.browseCustomOpds(url, ""));
            assertTrue(feed.getCause().getMessage().contains("302"));
            var book = assertThrows(IllegalStateException.class,
                    () -> downloads.downloadBookFile(url, "example", "TXT", home, null));
            assertTrue(book.getMessage().contains("302"));
            assertEquals(2, secureHops.get());
            assertEquals(0, plainRequests.get());
        } finally {
            https.stop(0);
            http.stop(0);
        }
    }

    private static void redirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().set("Location", location);
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }

    private SSLContext localTls() throws Exception {
        Path keys = home.resolve("loopback.p12");
        String password = java.util.UUID.randomUUID().toString();
        var keytool = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair", "-alias", "loopback", "-keyalg", "EC", "-dname", "CN=localhost",
                "-ext", "SAN=ip:127.0.0.1", "-keystore", keys.toString(), "-storetype", "PKCS12",
                "-storepass", password, "-validity", "2").redirectErrorStream(true).start();
        try {
            assertTrue(keytool.waitFor(30, TimeUnit.SECONDS));
            assertEquals(0, keytool.exitValue(), new String(keytool.getInputStream().readAllBytes()));
        } finally {
            keytool.destroyForcibly();
        }
        var store = KeyStore.getInstance("PKCS12");
        try (var in = Files.newInputStream(keys)) { store.load(in, password.toCharArray()); }
        var keyManager = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManager.init(store, password.toCharArray());
        var trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(store);
        SSLContext tls = SSLContext.getInstance("TLS");
        tls.init(keyManager.getKeyManagers(), trust.getTrustManagers(), null);
        return tls;
    }
}
