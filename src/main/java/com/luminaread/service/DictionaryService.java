package com.luminaread.service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;
import com.luminaread.util.Json;

public class DictionaryService implements IDictionaryService {
    private static final Logger LOG = Logger.getLogger(DictionaryService.class.getName());

    record Response(int status, String body) {}
    @FunctionalInterface interface Transport {
        Response send(HttpRequest request) throws IOException, InterruptedException;
    }
    private final Transport transport;

    public DictionaryService() {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(8)).build();
        this.transport = request -> {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Response(response.statusCode(), response.body());
        };
    }

    DictionaryService(Transport transport) {
        this.transport = java.util.Objects.requireNonNull(transport);
    }

    @Override
    public String lookupAndTranslate(String selectedText) {
        if (selectedText == null || selectedText.isBlank()) {
            return "Выделите слово или фразу в тексте книги для перевода и поиска значения.";
        }
        String clean = selectedText.trim();
        if (clean.length() > 350) {
            clean = clean.substring(0, 350);
        }

        boolean isCyrillic = containsCyrillic(clean);
        String langPair = isCyrillic ? "ru|en" : "en|ru";
        String directionLabel = isCyrillic ? "Русский → Английский" : "Английский → Русский";

        StringBuilder result = new StringBuilder();
        result.append("Исходный текст: «").append(clean).append("»\n");
        result.append("Направление: ").append(directionLabel).append("\n\n");

        try {
            String url = "https://api.mymemory.translated.net/get?q="
                    + URLEncoder.encode(clean, StandardCharsets.UTF_8)
                    + "&langpair=" + URLEncoder.encode(langPair, StandardCharsets.UTF_8);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(8))
                    .GET()
                    .build();

            Response resp = transport.send(req);
            String translated = translation(resp);
            result.append("Перевод: ").append(translated).append("\n");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return result.append("Поиск перевода отменён.\n").toString();
        } catch (IOException | IllegalArgumentException ex) {
            LOG.log(Level.FINE, "Не удалось получить перевод", ex);
            result.append("Онлайн-перевод недоступен (проверьте подключение к сети).\n");
        }

        if (!isCyrillic && !clean.contains(" ")) {
            try {
                String dictUrl = "https://api.dictionaryapi.dev/api/v2/entries/en/"
                        + URLEncoder.encode(clean.toLowerCase(Locale.ROOT), StandardCharsets.UTF_8);
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(dictUrl))
                        .timeout(Duration.ofSeconds(6))
                        .GET()
                        .build();
                Response resp = transport.send(req);
                String definition = definition(resp);
                if (definition != null) result.append("\nТолкование:\n• ").append(definition).append("\n");
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } catch (IOException | IllegalArgumentException ex) {
                LOG.log(Level.FINE, "Не удалось получить дополнительное толкование", ex);
            }
        }
        return result.toString();
    }

    private static String translation(Response response) throws IOException {
        if (response.status() != 200) throw new IOException("Translation service returned HTTP " + response.status());
        Object data = Json.parse(response.body());
        if (data instanceof Map<?, ?> root && root.get("responseStatus") instanceof Number status
                && status.intValue() != 200) throw new IOException("Translation service reported an error");
        if (data instanceof Map<?, ?> root && root.get("responseData") instanceof Map<?, ?> responseData
                && responseData.get("translatedText") instanceof String text && !text.isBlank()) return text;
        throw new IOException("Translation service returned no translation");
    }

    private static String definition(Response response) throws IOException {
        if (response.status() == 404) return null;
        if (response.status() != 200) throw new IOException("Dictionary service returned HTTP " + response.status());
        Object parsed = Json.parse(response.body());
        if (parsed instanceof List<?> entries) for (Object entry : entries) {
            if (!(entry instanceof Map<?, ?> item) || !(item.get("meanings") instanceof List<?> meanings)) continue;
            for (Object meaning : meanings) {
                if (!(meaning instanceof Map<?, ?> value) || !(value.get("definitions") instanceof List<?> definitions)) continue;
                for (Object definition : definitions) {
                    if (definition instanceof Map<?, ?> def && def.get("definition") instanceof String text && !text.isBlank()) return text;
                }
            }
        }
        return null;
    }

    private boolean containsCyrillic(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '\u0400' && c <= '\u04FF') return true;
        }
        return false;
    }
}
