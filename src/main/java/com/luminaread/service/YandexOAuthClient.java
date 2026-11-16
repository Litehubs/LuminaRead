package com.luminaread.service;

import com.luminaread.util.BoundedHttpBody;
import com.luminaread.util.Json;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class YandexOAuthClient {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final HttpResponse.BodyHandler<byte[]> BODY =
            BoundedHttpBody.bounded(64 * 1024, "Слишком большой ответ Яндекса.");

    private final String clientId;
    private final URI tokenEndpoint;
    private final URI profileEndpoint;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER).build();

    YandexOAuthClient(String clientId) {
        this(clientId, URI.create("https://oauth.yandex.ru/token"), URI.create("https://login.yandex.ru/info?format=json"));
    }

    YandexOAuthClient(String clientId, URI tokenEndpoint, URI profileEndpoint) {
        this.clientId = clientId;
        this.tokenEndpoint = tokenEndpoint;
        this.profileEndpoint = profileEndpoint;
    }

    YandexAuthService.YandexUser verify(String code, String verifier, URI redirect) throws IOException, InterruptedException {
        String body = "grant_type=authorization_code&code=" + form(code)
                + "&client_id=" + form(clientId) + "&code_verifier=" + form(verifier)
                + "&redirect_uri=" + form(redirect.toString());
        HttpResponse<byte[]> exchange = send(HttpRequest.newBuilder(tokenEndpoint)
                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(body)));
        if (exchange.statusCode() != 200) throw exchangeFailure(exchange);
        String token = text(json(exchange).get("access_token"));
        if (token.isEmpty() || token.chars().anyMatch(ch -> ch <= 0x20 || ch > 0x7e)) {
            throw new IOException("Яндекс вернул недопустимый OAuth-токен.");
        }

        HttpResponse<byte[]> profile = send(HttpRequest.newBuilder(profileEndpoint)
                .header("Authorization", "OAuth " + token).GET());
        if (profile.statusCode() != 200) {
            throw new IOException("Яндекс ID не подтвердил профиль (HTTP " + profile.statusCode() + ").");
        }
        Map<?, ?> fields = json(profile);
        String id = text(fields.get("id"));
        if (id.isBlank() || !clientId.equals(fields.get("client_id"))) {
            throw new IOException("Яндекс ID не подтвердил профиль для LuminaRead.");
        }
        return new YandexAuthService.YandexUser(id, text(fields.get("login")), text(fields.get("display_name")),
                text(fields.get("default_email")));
    }

    private HttpResponse<byte[]> send(HttpRequest.Builder request) throws IOException, InterruptedException {
        var pending = http.sendAsync(request.timeout(TIMEOUT).header("User-Agent", "LuminaRead/2.0").build(), BODY);
        try {
            // Includes the body transfer, including a response that stalls after its headers.
            return pending.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            pending.cancel(true);
            throw new IOException("Яндекс не ответил вовремя.");
        } catch (InterruptedException ex) {
            pending.cancel(true);
            throw ex;
        } catch (ExecutionException ex) {
            if (ex.getCause() instanceof BoundedHttpBody.LimitExceededException error) throw error;
            throw new IOException("Не удалось получить ответ от Яндекса.");
        }
    }

    private static Map<?, ?> json(HttpResponse<byte[]> response) throws IOException {
        try {
            if (Json.parse(new String(response.body(), StandardCharsets.UTF_8)) instanceof Map<?, ?> fields) return fields;
        } catch (IllegalArgumentException ex) {
            // Do not expose OAuth response data to UI or logs.
        }
        throw new IOException("Яндекс вернул некорректный ответ.");
    }

    private static IOException exchangeFailure(HttpResponse<byte[]> response) {
        try {
            Object error = json(response).get("error");
            if ("invalid_grant".equals(error)) return new IOException("Код входа недействителен. Начните вход заново.");
            if ("invalid_client".equals(error) || "unauthorized_client".equals(error)) {
                return new IOException("Яндекс отклонил настройки OAuth приложения.");
            }
        } catch (IOException ex) {
            // Non-JSON errors are reported by status only.
        }
        return new IOException("Яндекс не подтвердил вход (HTTP " + response.statusCode() + ").");
    }

    private static String form(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String text(Object value) {
        return value instanceof String string ? string : "";
    }
}
