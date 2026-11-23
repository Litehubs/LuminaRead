package com.luminaread.service;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CancellationException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class YandexBrowserFlowTest {
    @Test void browserCallbackCompletesPkceAndPersistsOnlyTheProfile(@TempDir Path home) throws Exception {
        try (var provider = new Provider()) {
            Path file = home.resolve("yandex_account.json");
            var service = provider.service(file);
            var login = service.beginLogin(true);
            YandexAuthService.Profile user = login.run();
            assertEquals("verified-id", user.id());
            assertEquals("verified", user.title());
            assertEquals("", user.defaultEmail());
            assertEquals(YandexAuthService.Status.VERIFIED, service.account().status());
            assertEquals("one-time-code", provider.exchange.get().get("code"));
            assertEquals("authorization_code", provider.exchange.get().get("grant_type"));
            assertEquals("client", provider.exchange.get().get("client_id"));
            assertEquals(provider.authorization.get().get("redirect_uri"), provider.exchange.get().get("redirect_uri"));
            assertEquals(provider.authorization.get().get("code_challenge"), challenge(provider.exchange.get().get("code_verifier")));
            assertEquals("OAuth issued-token", provider.profileAuthorization.get());
            assertFalse(Files.readString(file).contains("issued-token"));
            assertFalse(Files.readString(file).contains("refresh-secret"));
            assertThrows(CancellationException.class, login::run);
        }
    }

    @Test void sessionOnlySignInForgetsAnEarlierRememberedProfile(@TempDir Path home) throws Exception {
        try (var provider = new Provider()) {
            Path file = home.resolve("account.json");
            var service = provider.service(file);
            service.beginLogin(true).run();
            assertTrue(Files.exists(file));
            service.beginLogin(false).run();
            assertEquals(YandexAuthService.Status.VERIFIED, service.account().status());
            assertFalse(Files.exists(file));
            assertEquals(YandexAuthService.Status.SIGNED_OUT, provider.service(file).account().status());
        }
    }

    @Test void tokenAndProfileRedirectsAreNeverFollowed(@TempDir Path home) throws Exception {
        try (var provider = new Provider()) {
            var service = provider.service(home.resolve("yandex_account.json"));
            provider.tokenStatus = 302;
            assertThrows(IOException.class, () -> service.beginLogin().run());
            assertEquals(0, provider.profileRequests.get());
            assertEquals(0, provider.redirectRequests.get());
            provider.tokenStatus = 200;
            provider.profileStatus = 302;
            assertThrows(IOException.class, () -> service.beginLogin().run());
            assertEquals(0, provider.redirectRequests.get());
            assertEquals(YandexAuthService.Status.SIGNED_OUT, service.account().status());
        }
    }

    @Test void rejectsInvalidTokensOversizedBodiesAndProfilesForOtherClients(@TempDir Path home) throws Exception {
        try (var provider = new Provider()) {
            var service = provider.service(home.resolve("yandex_account.json"));
            provider.tokenBody = "{\"access_token\":\"secret-marker\\r\\ninvalid\"}";
            IOException invalid = assertThrows(IOException.class, () -> service.beginLogin().run());
            assertFalse(invalid.getMessage().contains("secret-marker"));
            assertEquals(0, provider.profileRequests.get());

            provider.tokenBody = "{\"access_token\":\"issued-token\"}";
            provider.profileBody = " ".repeat(65_537);
            assertTrue(assertThrows(IOException.class, () -> service.beginLogin().run())
                    .getMessage().contains("Слишком большой ответ"));

            provider.profileBody = "{\"id\":\"verified-id\",\"client_id\":\"another-app\"}";
            assertThrows(IOException.class, () -> service.beginLogin().run());
            assertEquals(YandexAuthService.Status.SIGNED_OUT, service.account().status());
        }
    }

    @Test void closingDuringTokenExchangeTerminatesTheWorker(@TempDir Path home) throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var provider = new Provider()) {
            provider.server.removeContext("/token");
            provider.server.createContext("/token", request -> {
                try (request) {
                    entered.countDown();
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            });
            var service = provider.service(home.resolve("profile.json"));
            var login = service.beginLogin();
            var worker = Executors.newSingleThreadExecutor();
            try {
                var future = worker.submit(login::run);
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                login.cancel();
                login.cancel();
                var failure = assertThrows(ExecutionException.class,
                        () -> future.get(2, TimeUnit.SECONDS));
                assertInstanceOf(CancellationException.class, failure.getCause());
                assertEquals(0, provider.profileRequests.get());
                assertFalse(Files.exists(home.resolve("profile.json")));
            } finally {
                release.countDown();
                worker.shutdownNow();
            }
        }
    }

    private static String challenge(String verifier) throws NoSuchAlgorithmException {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
    }

    private static Map<String, String> decode(String raw) {
        Map<String, String> values = new HashMap<>();
        for (String part : raw.split("&")) {
            String[] pair = part.split("=", 2);
            values.put(pair[0], URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
        }
        return values;
    }

    private static final class Provider implements AutoCloseable {
        final HttpServer server;
        final URI base;
        final AtomicReference<Map<String, String>> authorization = new AtomicReference<>();
        final AtomicReference<Map<String, String>> exchange = new AtomicReference<>();
        final AtomicReference<String> profileAuthorization = new AtomicReference<>();
        final AtomicInteger profileRequests = new AtomicInteger();
        final AtomicInteger redirectRequests = new AtomicInteger();
        volatile int tokenStatus = 200;
        volatile int profileStatus = 200;
        volatile String tokenBody = "{\"access_token\":\"issued-token\",\"refresh_token\":\"refresh-secret\"}";
        volatile String profileBody = "{\"id\":\"verified-id\",\"client_id\":\"client\",\"login\":\"verified\"}";

        Provider() throws IOException {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
            base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            server.createContext("/token", request -> {
                exchange.set(decode(new String(request.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                respond(request, tokenStatus, tokenBody);
            });
            server.createContext("/info", request -> {
                profileRequests.incrementAndGet();
                profileAuthorization.set(request.getRequestHeaders().getFirst("Authorization"));
                respond(request, profileStatus, profileBody);
            });
            server.createContext("/redirected", request -> {
                redirectRequests.incrementAndGet();
                respond(request, 200, "{}");
            });
            server.start();
        }

        YandexAuthService service(Path profileFile) {
            return new YandexAuthService(new YandexOAuth("client", base.resolve("/token"), base.resolve("/info")),
                    new YandexProfileStore(profileFile), this::openBrowser, 0);
        }

        private void openBrowser(URI url) throws IOException {
            Map<String, String> parameters = decode(url.getRawQuery());
            authorization.set(parameters);
            URI callback = URI.create(parameters.get("redirect_uri") + "?state=" + parameters.get("state") + "&code=one-time-code");
            try {
                var request = HttpRequest.newBuilder(callback).GET().build();
                assertEquals(200, HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding()).statusCode());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IOException(ex);
            }
        }

        private void respond(HttpExchange request, int status, String text) throws IOException {
            try (request) {
                if (status == 302) request.getResponseHeaders().set("Location", base.resolve("/redirected").toString());
                byte[] data = text.getBytes(StandardCharsets.UTF_8);
                request.sendResponseHeaders(status, data.length);
                request.getResponseBody().write(data);
            }
        }

        @Override public void close() { server.stop(0); }
    }
}
