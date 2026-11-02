package com.luminaread.service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DictionaryService implements IDictionaryService {
    private static final Pattern TRANSLATED_TEXT_PATTERN =
            Pattern.compile("\"translatedText\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern DEFINITION_PATTERN =
            Pattern.compile("\"definition\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    private final HttpClient httpClient;

    public DictionaryService() {
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .connectTimeout(Duration.ofSeconds(8))
                .build();
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

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() == 200 && resp.body() != null) {
                Matcher m = TRANSLATED_TEXT_PATTERN.matcher(resp.body());
                if (m.find()) {
                    String translated = unescapeUnicode(m.group(1));
                    result.append("Перевод: ").append(translated).append("\n");
                }
            }
        } catch (Exception e) {
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
                HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (resp.statusCode() == 200 && resp.body() != null) {
                    Matcher defMatcher = DEFINITION_PATTERN.matcher(resp.body());
                    if (defMatcher.find()) {
                        result.append("\nТолкование (Oxford/Wiktionary):\n• ")
                              .append(unescapeUnicode(defMatcher.group(1))).append("\n");
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return result.toString();
    }

    private boolean containsCyrillic(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '\u0400' && c <= '\u04FF') {
                return true;
            }
        }
        return false;
    }

    private String unescapeUnicode(String raw) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\\' && i + 1 < raw.length()) {
                char next = raw.charAt(++i);
                if (next == 'u' && i + 4 < raw.length()) {
                    String hex = raw.substring(i + 1, i + 5);
                    try {
                        sb.append((char) Integer.parseInt(hex, 16));
                        i += 4;
                    } catch (Exception e) {
                        sb.append("\\u").append(hex);
                    }
                } else {
                    sb.append(next);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
