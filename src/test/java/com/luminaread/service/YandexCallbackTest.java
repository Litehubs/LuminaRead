package com.luminaread.service;

import org.junit.jupiter.api.Test;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import static org.junit.jupiter.api.Assertions.*;

final class YandexCallbackTest {
    @Test void acceptsOnlyMatchingStateAndSingleCode() throws Exception {
        try (YandexCallback callback = YandexCallback.open(0)) {
            HttpClient client = HttpClient.newHttpClient();
            String base = callback.redirectUri().toString();
            assertEquals(400, client.send(HttpRequest.newBuilder(URI.create(base + "?state=wrong&code=bad")).build(), HttpResponse.BodyHandlers.discarding()).statusCode());
            assertEquals(400, client.send(HttpRequest.newBuilder(URI.create(base + "?state=" + callback.state() + "&code=a&code=b")).build(), HttpResponse.BodyHandlers.discarding()).statusCode());
            var response = client.send(HttpRequest.newBuilder(
                    URI.create(base + "?state=" + callback.state() + "&code=example-one-time-code")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
            assertFalse(response.body().contains("example-one-time-code"));
            assertFalse(response.body().contains(callback.state()));
            String script = response.body().split("<script>", 2)[1].split("</script>", 2)[0];
            assertTrue(script.contains("history.replaceState"));
            String hash = java.util.Base64.getEncoder().encodeToString(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(script.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            assertTrue(response.headers().firstValue("Content-Security-Policy").orElseThrow().contains("'sha256-" + hash + "'"));
            assertEquals("example-one-time-code", callback.awaitCode());
        }
    }
}
