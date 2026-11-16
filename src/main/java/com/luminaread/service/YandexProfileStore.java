package com.luminaread.service;

import com.luminaread.util.AtomicFile;
import com.luminaread.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Map;

record YandexProfileStore(Path path) {
    private static final int MAX_PROFILE_BYTES = 64 * 1024;

    YandexAuthService.YandexUser load() throws IOException {
        byte[] bytes;
        try (var input = Files.newInputStream(path)) {
            bytes = input.readNBytes(MAX_PROFILE_BYTES + 1);
        } catch (NoSuchFileException ex) {
            return null;
        }
        if (bytes.length > MAX_PROFILE_BYTES) throw new IOException("Сохранённый профиль Яндекс слишком большой.");
        if (!(Json.parse(new String(bytes, StandardCharsets.UTF_8)) instanceof Map<?, ?> fields)) {
            throw new IOException("Некорректный сохранённый профиль Яндекс.");
        }

        String id = field(fields, "id");
        if (id.isBlank()) {
            delete();
            return null;
        }
        String login = field(fields, "login");
        String email = field(fields, "defaultEmail");
        // Older builds synthesized this address. Its origin is unknown until the next verified login.
        if (!fields.containsKey("version") && email.equals(login + "@yandex.ru")) email = "";
        var profile = new YandexAuthService.YandexUser(id, login, field(fields, "displayName"), email);
        if (!fields.equals(data(profile))) save(profile);
        return profile;
    }

    void save(YandexAuthService.YandexUser profile) throws IOException {
        byte[] bytes = Json.writeStringObject(data(profile)).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_PROFILE_BYTES) throw new IOException("Профиль Яндекс слишком большой.");
        AtomicFile.write(path, out -> out.write(bytes));
    }

    void delete() throws IOException {
        Files.deleteIfExists(path);
    }

    private static Map<String, String> data(YandexAuthService.YandexUser profile) {
        return Map.of("version", "1", "id", profile.id(), "login", profile.login(),
                "displayName", profile.displayName(), "defaultEmail", profile.defaultEmail());
    }

    private static String field(Map<?, ?> fields, String key) {
        return fields.get(key) instanceof String text ? text : "";
    }
}
