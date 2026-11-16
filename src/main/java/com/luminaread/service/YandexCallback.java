package com.luminaread.service;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Short-lived local callback for one OAuth authorization attempt. */
public final class YandexCallback implements AutoCloseable {
    public static final int PORT = 49321;
    private static final String PATH = "/oauth/yandex/callback";
    private static final String CLEAR_CALLBACK_URL = "history.replaceState(null, \"\", location.pathname);";
    private static final String CONTENT_SECURITY_POLICY = "default-src 'none'; "
            + "script-src 'sha256-ixzo+owSEfJWq9z0HJaxCJAlk3mRYZRq9JaxhZ3hBmA='; base-uri 'none'; frame-ancestors 'none'";
    private final HttpServer server;
    private final String state;
    private final CompletableFuture<String> result = new CompletableFuture<>();
    private final java.util.concurrent.atomic.AtomicBoolean accepted = new java.util.concurrent.atomic.AtomicBoolean();

    private YandexCallback(HttpServer server, String state) {
        this.server = server;
        this.state = state;
        server.createContext(PATH, this::handle);
    }

    public static YandexCallback open(int port) throws IOException {
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 0);
        YandexCallback callback = new YandexCallback(server, Base64.getUrlEncoder().withoutPadding().encodeToString(random));
        server.start();
        return callback;
    }

    public URI redirectUri() { return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + PATH); }
    public String state() { return state; }

    public String awaitCode() throws Exception { return result.get(5, TimeUnit.MINUTES); }

    private void handle(HttpExchange exchange) throws IOException {
        int status = 400;
        String acceptedCode = null;
        boolean acceptedError = false;
        String message = "Не удалось подтвердить вход. Вернитесь в приложение.";
        try {
            String query = exchange.getRequestURI().getRawQuery();
            if (!"GET".equals(exchange.getRequestMethod()) || !PATH.equals(exchange.getRequestURI().getPath())
                    || query == null || query.length() > 8192) return;
            Map<String, String> values = new HashMap<>();
            for (String part : query.split("&", -1)) {
                String[] pair = part.split("=", 2);
                String key = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
                String value = URLDecoder.decode(pair.length == 2 ? pair[1] : "", StandardCharsets.UTF_8);
                if (values.putIfAbsent(key, value) != null) return;
            }
            String received = values.get("state");
            if (received == null || !MessageDigest.isEqual(state.getBytes(StandardCharsets.US_ASCII), received.getBytes(StandardCharsets.UTF_8))) return;
            String code = values.get("code");
            String error = values.get("error");
            if ((code == null || code.isBlank()) == (error == null || error.isBlank())) return;
            if (code != null && !code.isBlank()) {
                if (!accepted.compareAndSet(false, true)) return;
                acceptedCode = code;
                status = 200;
                message = "Код получен. Вернитесь в LuminaRead для завершения входа.";
            } else {
                if (!accepted.compareAndSet(false, true)) return;
                acceptedError = true;
                status = 200;
                message = "Вход отменён. Вернитесь в LuminaRead.";
            }
        } catch (IllegalArgumentException ignored) {
            // A malformed or unsolicited request must not consume the attempt.
        } finally {
            // Only fixed messages are included. The code and state are never reflected in HTML.
            byte[] body = ("<!doctype html><meta charset='utf-8'><script>" + CLEAR_CALLBACK_URL
                    + "</script><title>Яндекс ID · LuminaRead</title><p>" + message + "</p>")
                    .getBytes(StandardCharsets.UTF_8);
            try (exchange) {
                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                exchange.getResponseHeaders().set("Content-Security-Policy", CONTENT_SECURITY_POLICY);
                exchange.getResponseHeaders().set("Cache-Control", "no-store");
                exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
                exchange.sendResponseHeaders(status, body.length);
                try (var output = exchange.getResponseBody()) { output.write(body); }
            } finally {
                if (acceptedCode != null) result.complete(acceptedCode);
                else if (acceptedError) result.completeExceptionally(new IllegalStateException("Вход не подтверждён в Яндексе."));
            }
        }
    }

    @Override public void close() {
        result.completeExceptionally(new IllegalStateException("Авторизация отменена."));
        server.stop(0);
    }
}
