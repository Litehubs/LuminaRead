package com.luminaread.service;

import com.luminaread.util.Json;

import java.awt.Desktop;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public class YandexAuthService {

    public static class YandexUser {
        public final String id;
        public final String login;
        public final String displayName;
        public final String realName;
        public final String defaultEmail;
        public final String avatarUrl;
        public final String token;
        public final String linkedLibUserId;
        public final String linkedLibUsername;

        public YandexUser(String id, String login, String displayName, String realName,
                          String defaultEmail, String avatarUrl, String token,
                          String linkedLibUserId, String linkedLibUsername) {
            this.id = id != null ? id : "";
            this.login = login != null ? login : "";
            this.displayName = displayName != null && !displayName.isEmpty() ? displayName : (login != null ? login : "Пользователь");
            this.realName = realName != null ? realName : "";
            this.defaultEmail = defaultEmail != null ? defaultEmail : "";
            this.avatarUrl = avatarUrl != null ? avatarUrl : "";
            this.token = token != null ? token : "";
            this.linkedLibUserId = linkedLibUserId != null ? linkedLibUserId : "";
            this.linkedLibUsername = linkedLibUsername != null ? linkedLibUsername : "";
        }

        public boolean isAuthenticated() {
            return !login.isEmpty() || !id.isEmpty();
        }

        public String getDisplayTitle() {
            if (!displayName.isEmpty()) return displayName;
            if (!login.isEmpty()) return login;
            return "Яндекс Пользователь";
        }
    }

    private static final String YANDEX_USER_INFO_URL = "https://login.yandex.ru/info?format=json";
    private static final Path YANDEX_ACCOUNT_FILE =
            Path.of(System.getProperty("user.home"), ".luminaread", "yandex_account.json");

    public static final String YANDEX_OAUTH_CLIENT_ID = "23cabb9fd67047f39b6dcdc467bb4b44";

    private static final YandexAuthService INSTANCE = new YandexAuthService();
    public static YandexAuthService getInstance() { return INSTANCE; }

    private YandexUser currentUser;
    private final List<Consumer<YandexUser>> authListeners = new CopyOnWriteArrayList<>();

    private YandexAuthService() {
        loadPersistedAccount();
    }

    public synchronized YandexUser getCurrentUser() {
        return currentUser;
    }

    public synchronized boolean isLoggedIn() {
        return currentUser != null && currentUser.isAuthenticated();
    }

    public void addAuthListener(Consumer<YandexUser> listener) {
        if (listener != null) {
            authListeners.add(listener);
            listener.accept(currentUser);
        }
    }

    private void notifyListeners() {
        YandexUser u = currentUser;
        for (Consumer<YandexUser> l : authListeners) {
            try { l.accept(u); } catch (Exception ignored) {}
        }
    }

    public static boolean safeBrowse(String url) {
        try {
            if (java.awt.GraphicsEnvironment.isHeadless()) {
                return false;
            }
            if (Desktop.isDesktopSupported()) {
                Desktop desktop = Desktop.getDesktop();
                if (desktop != null && desktop.isSupported(Desktop.Action.BROWSE)) {
                    desktop.browse(new URI(url));
                    return true;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    public boolean openYandexOAuthInBrowser() {
        String url = "https://oauth.yandex.ru/authorize?response_type=token&client_id="
                + YANDEX_OAUTH_CLIENT_ID
                + "&redirect_uri=https://oauth.yandex.ru/verification_code";
        return safeBrowse(url);
    }

    public YandexUser loginWithOAuthToken(String token) throws Exception {
        if (token == null || token.trim().isEmpty()) {
            throw new IllegalArgumentException("OAuth токен не может быть пустым.");
        }
        String cleanToken = token.trim().replaceFirst("^(OAuth|Bearer)\\s+", "");

        HttpURLConnection conn = (HttpURLConnection) new URL(YANDEX_USER_INFO_URL).openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Authorization", "OAuth " + cleanToken);
        conn.setRequestProperty("User-Agent", "LuminaRead/2.0");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);

        int code = conn.getResponseCode();
        if (code != 200) {
            throw new Exception("Неверный токен Яндекс ID (Код ответа: " + code + "). Проверьте правильность токена.");
        }

        String json;
        try (InputStream in = conn.getInputStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line).append('\n');
            json = sb.toString();
        }

        Object root = Json.parse(json);
        if (!(root instanceof Map)) {
            throw new Exception("Не удалось разобрать ответ от сервера Яндекс ID.");
        }
        Map<?, ?> m = (Map<?, ?>) root;

        String id = str(m.get("id"));
        String login = str(m.get("login"));
        String displayName = str(m.get("display_name"));
        String realName = str(m.get("real_name"));
        String defaultEmail = str(m.get("default_email"));
        if (defaultEmail.isEmpty() && !login.isEmpty()) {
            defaultEmail = login + "@yandex.ru";
        }

        String avatarId = str(m.get("default_avatar_id"));
        String avatarUrl = "";
        if (!avatarId.isEmpty() && !"0/0-0".equals(avatarId)) {
            avatarUrl = "https://avatars.yandex.net/get-yapic/" + avatarId + "/islands-200";
        }

        String linkedLibId = (currentUser != null) ? currentUser.linkedLibUserId : "";
        String linkedLibUser = (currentUser != null) ? currentUser.linkedLibUsername : "";

        YandexUser user = new YandexUser(id, login, displayName, realName, defaultEmail, avatarUrl, cleanToken, linkedLibId, linkedLibUser);
        synchronized (this) {
            this.currentUser = user;
            savePersistedAccount();
        }
        notifyListeners();
        return user;
    }

    public YandexUser loginWithLoginOrEmail(String loginOrEmail) throws Exception {
        if (loginOrEmail == null || loginOrEmail.trim().isEmpty()) {
            throw new IllegalArgumentException("Введите логин или почту Яндекс.");
        }
        String clean = loginOrEmail.trim();
        String login = clean.contains("@") ? clean.substring(0, clean.indexOf('@')) : clean;
        String email = clean.contains("@") ? clean : clean + "@yandex.ru";

        String linkedLibId = (currentUser != null) ? currentUser.linkedLibUserId : "";
        String linkedLibUser = (currentUser != null) ? currentUser.linkedLibUsername : "";

        YandexUser user = new YandexUser("ya_" + login.toLowerCase(Locale.ROOT), login, login, login, email, "", "", linkedLibId, linkedLibUser);
        synchronized (this) {
            this.currentUser = user;
            savePersistedAccount();
        }
        notifyListeners();
        return user;
    }

    public synchronized void linkLibSocial(String libUserId, String libUsername) {
        if (currentUser == null) return;
        this.currentUser = new YandexUser(
                currentUser.id, currentUser.login, currentUser.displayName, currentUser.realName,
                currentUser.defaultEmail, currentUser.avatarUrl, currentUser.token,
                libUserId, libUsername
        );
        savePersistedAccount();
        notifyListeners();
    }

    public synchronized void logout() {
        this.currentUser = null;
        try {
            Files.deleteIfExists(YANDEX_ACCOUNT_FILE);
        } catch (Exception ignored) {}
        notifyListeners();
    }

    private void loadPersistedAccount() {
        try {
            if (!Files.exists(YANDEX_ACCOUNT_FILE)) return;
            String json = Files.readString(YANDEX_ACCOUNT_FILE, StandardCharsets.UTF_8);
            Object root = Json.parse(json);
            if (!(root instanceof Map)) return;
            Map<?, ?> m = (Map<?, ?>) root;

            String id = str(m.get("id"));
            String login = str(m.get("login"));
            String displayName = str(m.get("displayName"));
            String realName = str(m.get("realName"));
            String email = str(m.get("defaultEmail"));
            String avatarUrl = str(m.get("avatarUrl"));
            String token = str(m.get("token"));
            String linkedLibId = str(m.get("linkedLibUserId"));
            String linkedLibUser = str(m.get("linkedLibUsername"));

            this.currentUser = new YandexUser(id, login, displayName, realName, email, avatarUrl, token, linkedLibId, linkedLibUser);
        } catch (Exception ignored) {}
    }

    private void savePersistedAccount() {
        try {
            if (currentUser == null) return;
            Files.createDirectories(YANDEX_ACCOUNT_FILE.getParent());
            String json = "{\n"
                    + "  \"id\": " + quote(currentUser.id) + ",\n"
                    + "  \"login\": " + quote(currentUser.login) + ",\n"
                    + "  \"displayName\": " + quote(currentUser.displayName) + ",\n"
                    + "  \"realName\": " + quote(currentUser.realName) + ",\n"
                    + "  \"defaultEmail\": " + quote(currentUser.defaultEmail) + ",\n"
                    + "  \"avatarUrl\": " + quote(currentUser.avatarUrl) + ",\n"
                    + "  \"token\": " + quote(currentUser.token) + ",\n"
                    + "  \"linkedLibUserId\": " + quote(currentUser.linkedLibUserId) + ",\n"
                    + "  \"linkedLibUsername\": " + quote(currentUser.linkedLibUsername) + "\n"
                    + "}";
            Files.writeString(YANDEX_ACCOUNT_FILE, json, StandardCharsets.UTF_8);
        } catch (Exception ignored) {}
    }

    private static String quote(String s) {
        if (s == null) return "\"\"";
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c == '"') sb.append("\\\"");
            else if (c == '\\') sb.append("\\\\");
            else if (c == '\n') sb.append("\\n");
            else if (c == '\r') sb.append("\\r");
            else if (c == '\t') sb.append("\\t");
            else sb.append(c);
        }
        sb.append("\"");
        return sb.toString();
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }
}
