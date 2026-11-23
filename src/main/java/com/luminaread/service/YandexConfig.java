package com.luminaread.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.NoSuchFileException;
import java.util.Properties;
import java.util.logging.Logger;
import java.util.logging.Level;

record YandexConfig(YandexOAuth oauth, YandexProfileStore profiles) {
    private static final Logger LOG = Logger.getLogger(YandexConfig.class.getName());

    static YandexConfig load() {
        Path dir = Path.of(System.getProperty("user.home"), ".luminaread");
        return new YandexConfig(new YandexOAuth(clientId(dir)), new YandexProfileStore(dir.resolve("yandex_account.json")));
    }

    private static String clientId(Path dir) {
        String id = System.getProperty("yandex.client.id");
        if (id != null && !id.isBlank()) return id.strip();
        id = System.getenv("YANDEX_CLIENT_ID");
        if (id != null && !id.isBlank()) return id.strip();

        try (InputStream input = Files.newInputStream(dir.resolve("auth.properties"))) {
            id = readClientId(input);
            if (!id.isBlank()) return id;
        } catch (NoSuchFileException expected) {
            // Fall back to the bundled public client ID.
        } catch (IOException | IllegalArgumentException | SecurityException ex) {
            LOG.log(Level.WARNING, "Не удалось прочитать настройки Яндекс ID.", ex);
        }

        try (InputStream input = YandexConfig.class.getResourceAsStream("/luminaread-yandex.properties")) {
            return input == null ? "" : readClientId(input);
        } catch (IOException | IllegalArgumentException ex) {
            LOG.log(Level.WARNING, "Не удалось прочитать настройки Яндекс ID из приложения.", ex);
            return "";
        }
    }

    private static String readClientId(InputStream input) throws IOException {
        var properties = new Properties();
        properties.load(new InputStreamReader(input, StandardCharsets.UTF_8));
        return properties.getProperty("yandex.client.id", "").strip();
    }
}
