package com.luminaread.service;

import com.luminaread.util.Json;

import java.awt.Desktop;
import java.io.IOException;
import java.io.InputStream;
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

        public YandexUser(String id, String login, String displayName, String realName,
                          String defaultEmail, String avatarUrl, String token) {
            this.id = id != null ? id : "";
            this.login = login != null ? login : "";
            this.displayName = displayName != null && !displayName.isEmpty() ? displayName : (login != null ? login : "Пользователь");
            this.realName = realName != null ? realName : "";
            this.defaultEmail = defaultEmail != null ? defaultEmail : "";
            this.avatarUrl = avatarUrl != null ? avatarUrl : "";
            this.token = token != null ? token : "";
        }

        public boolean isAuthenticated() {
            return !token.isEmpty() && (!login.isEmpty() || !id.isEmpty());
        }

        public String getDisplayTitle() {
            if (!displayName.isEmpty()) return displayName;
            if (!login.isEmpty()) return login;
            return "Яндекс Пользователь";
        }
    }

    private static final int MAX_OAUTH_RESPONSE_BYTES = 64 * 1024;
    private static final String YANDEX_USER_INFO_URL = "https://login.yandex.ru/info?format=json";
    private static final Path YANDEX_ACCOUNT_FILE =
            Path.of(System.getProperty("user.home"), ".luminaread", "yandex_account.json");

    private static final String DEFAULT_YANDEX_CLIENT_ID = "9b33ddacdc034232bb0338e4038ae21c";
    public static final String YANDEX_OAUTH_CLIENT_ID = resolveClientId();

    private static String resolveClientId() {
        String sysProp = System.getProperty("yandex.client.id");
        if (sysProp != null && !sysProp.isBlank()) return sysProp.trim();
        String env = System.getenv("YANDEX_CLIENT_ID");
        if (env != null && !env.isBlank()) return env.trim();
        java.nio.file.Path cfgPath = java.nio.file.Path.of(System.getProperty("user.home"), ".luminaread", "auth.properties");
        if (java.nio.file.Files.exists(cfgPath)) {
            try {
                java.util.Properties props = new java.util.Properties();
                try (java.io.InputStream is = java.nio.file.Files.newInputStream(cfgPath)) {
                    props.load(is);
                }
                String val = props.getProperty("yandex.client.id");
                if (val != null && !val.isBlank()) return val.trim();
            } catch (java.io.IOException | IllegalArgumentException ex) {
                java.util.logging.Logger.getLogger(YandexAuthService.class.getName()).log(
                        java.util.logging.Level.WARNING, "Не удалось прочитать настройки входа Яндекс", ex);
            }
        }
        return DEFAULT_YANDEX_CLIENT_ID;
    }

    private static final YandexAuthService INSTANCE = new YandexAuthService();
    public static YandexAuthService getInstance() { return INSTANCE; }

    private YandexUser currentUser;
    private YandexPkce pendingAuthorization;
    private YandexCallback callback;
    private long authorizationGeneration;
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

    public void removeAuthListener(Consumer<YandexUser> listener) {
        authListeners.remove(listener);
    }

    private void notifyListeners() {
        YandexUser u = currentUser;
        for (Consumer<YandexUser> l : authListeners) {
            try { l.accept(u); } catch (RuntimeException ex) {
                java.util.logging.Logger.getLogger(YandexAuthService.class.getName()).log(
                        java.util.logging.Level.WARNING, "Не удалось обновить состояние входа", ex);
            }
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
        } catch (Exception ex) {
            // A browser error may include the authorization URL and its state parameter.
            java.util.logging.Logger.getLogger(YandexAuthService.class.getName()).warning("Не удалось открыть браузер");
        }
        return false;
    }

    public synchronized long reserveBrowserAuthorization() {
        cancelBrowserAuthorization();
        return authorizationGeneration;
    }

    public YandexUser loginInBrowser(long generation) throws Exception {
        YandexCallback local;
        synchronized (this) {
            if (generation != authorizationGeneration) throw new IllegalStateException("Авторизация отменена.");
            try {
                local = YandexCallback.open(YandexCallback.PORT);
            } catch (java.net.BindException ex) {
                throw new IllegalStateException("Порт входа Яндекс ID занят. Закройте другую копию LuminaRead и повторите вход.", ex);
            }
            callback = local;
            pendingAuthorization = YandexPkce.create(YANDEX_OAUTH_CLIENT_ID);
        }
        try {
            final String url;
            synchronized (this) {
                if (generation != authorizationGeneration) throw new IllegalStateException("Авторизация отменена.");
                url = pendingAuthorization.authorizationUri(local.redirectUri(), local.state()).toString();
            }
            if (!safeBrowse(url)) throw new IllegalStateException("Не удалось открыть браузер.");
            String code;
            try {
                code = local.awaitCode();
            } catch (java.util.concurrent.TimeoutException ex) {
                throw new IllegalStateException("Яндекс не вернул код за пять минут. Начните вход заново.", ex);
            }
            return completeBrowserAuthorization(code, generation, local.redirectUri());
        } finally {
            synchronized (this) {
                if (callback == local) {
                    callback = null;
                    if (authorizationGeneration == generation) {
                        pendingAuthorization = null;
                        authorizationGeneration++;
                    }
                }
            }
            local.close();
        }
    }

    public synchronized boolean openYandexOAuthInBrowser() {
        YandexPkce attempt = YandexPkce.create(YANDEX_OAUTH_CLIENT_ID);
        authorizationGeneration++;
        pendingAuthorization = null;
        if (!safeBrowse(attempt.authorizationUri().toString())) return false;
        pendingAuthorization = attempt;
        return true;
    }

    public YandexUser completeBrowserAuthorization(String confirmationCode) throws Exception {
        final long generation;
        synchronized (this) { generation = authorizationGeneration; }
        return completeBrowserAuthorization(confirmationCode, generation, null);
    }

    private YandexUser completeBrowserAuthorization(String confirmationCode, long expectedGeneration, URI redirect) throws Exception {
        if (confirmationCode == null || confirmationCode.isBlank())
            throw new IllegalArgumentException("Введите код подтверждения из браузера.");
        final YandexPkce attempt;
        final long generation;
        synchronized (this) {
            generation = authorizationGeneration;
            if (generation != expectedGeneration) throw new IllegalStateException("Авторизация отменена.");
            attempt = pendingAuthorization;
            pendingAuthorization = null;
        }
        if (attempt == null) throw new IllegalStateException("Начните вход в браузере заново.");
        String body = "grant_type=authorization_code&code=" + form(confirmationCode.trim())
                + "&client_id=" + form(YANDEX_OAUTH_CLIENT_ID) + "&code_verifier=" + form(attempt.verifier())
                + (redirect == null ? "" : "&redirect_uri=" + form(redirect.toString()));
        HttpURLConnection conn = (HttpURLConnection) new URL("https://oauth.yandex.ru/token").openConnection();
        conn.setInstanceFollowRedirects(false);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        conn.setDoOutput(true);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        conn.setFixedLengthStreamingMode(bytes.length);
        Object response;
        try {
            try (var out = conn.getOutputStream()) { out.write(bytes); }
            int status = conn.getResponseCode();
            if (status != 200) throw tokenExchangeFailure(conn, status);
            response = readYandexJson(conn);
        } finally {
            conn.disconnect();
        }
        if (!(response instanceof Map<?, ?> result) || !(result.get("access_token") instanceof String token) || token.isBlank())
            throw new IllegalStateException("Яндекс не вернул токен. Начните вход заново.");
        return loginWithOAuthToken(token, generation);
    }

    public synchronized void cancelBrowserAuthorization() {
        authorizationGeneration++;
        pendingAuthorization = null;
        if (callback != null) { callback.close(); callback = null; }
    }

    private static String form(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    public YandexUser loginWithOAuthToken(String token) throws Exception {
        final long generation;
        synchronized (this) {
            generation = ++authorizationGeneration;
            pendingAuthorization = null;
        }
        return loginWithOAuthToken(token, generation);
    }

    private YandexUser loginWithOAuthToken(String token, long generation) throws Exception {
        if (token == null || token.trim().isEmpty()) {
            throw new IllegalArgumentException("OAuth токен не может быть пустым.");
        }
        String cleanToken = token.trim().replaceFirst("^(OAuth|Bearer)\\s+", "");

        HttpURLConnection conn = (HttpURLConnection) new URL(YANDEX_USER_INFO_URL).openConnection();
        conn.setInstanceFollowRedirects(false);
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Authorization", "OAuth " + cleanToken);
        conn.setRequestProperty("User-Agent", "LuminaRead/2.0");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);

        Object root;
        try {
            int status = conn.getResponseCode();
            if (status != 200) {
                throw new IOException("Яндекс ID не подтвердил профиль (HTTP " + status + ").");
            }
            root = readYandexJson(conn);
        } finally {
            conn.disconnect();
        }
        if (!(root instanceof Map)) {
            throw new Exception("Не удалось разобрать ответ от сервера Яндекс ID.");
        }
        Map<?, ?> m = (Map<?, ?>) root;

        String id = str(m.get("id"));
        String login = str(m.get("login"));
        if (id.isEmpty() && login.isEmpty()) throw new IllegalStateException("Яндекс не подтвердил личность пользователя.");
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

        YandexUser user = new YandexUser(id, login, displayName, realName, defaultEmail, avatarUrl, cleanToken);
        synchronized (this) {
            if (generation != authorizationGeneration) throw new IllegalStateException("Авторизация отменена.");
            this.currentUser = user;
            savePersistedAccount();
        }
        notifyListeners();
        return user;
    }

    private static Object readYandexJson(HttpURLConnection connection) throws IOException {
        try (InputStream input = connection.getInputStream()) {
            return readYandexJson(input);
        }
    }

    private static Object readYandexJson(InputStream input) throws IOException {
        byte[] data = input.readNBytes(MAX_OAUTH_RESPONSE_BYTES + 1);
        if (data.length > MAX_OAUTH_RESPONSE_BYTES) throw new IOException("Слишком большой ответ Яндекса.");
        try {
            return Json.parse(new String(data, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException ex) {
            throw new IOException("Яндекс вернул некорректный ответ.", ex);
        }
    }

    private static IOException tokenExchangeFailure(HttpURLConnection connection, int status) {
        try (InputStream input = connection.getErrorStream()) {
            if (input != null && readYandexJson(input) instanceof Map<?, ?> result) {
                Object error = result.get("error");
                if ("invalid_grant".equals(error)) return new IOException("Код входа недействителен. Начните вход заново.");
                if ("invalid_client".equals(error) || "unauthorized_client".equals(error)) {
                    return new IOException("Яндекс отклонил настройки OAuth приложения.");
                }
            }
        } catch (IOException ex) {
            return new IOException("Яндекс не подтвердил вход (HTTP " + status + ").", ex);
        }
        return new IOException("Яндекс не подтвердил вход (HTTP " + status + ").");
    }

    public YandexUser loginWithLoginOrEmail(String loginOrEmail) {
        throw new IllegalArgumentException("Логин или адрес почты не подтверждает владение аккаунтом. Используйте авторизацию Яндекс ID.");
    }

    public synchronized void logout() {
        cancelBrowserAuthorization();
        this.currentUser = null;
        try {
            Files.deleteIfExists(YANDEX_ACCOUNT_FILE);
        } catch (java.io.IOException ex) {
            throw new java.io.UncheckedIOException("Не удалось удалить сохранённые данные профиля Яндекс", ex);
        } finally {
            notifyListeners();
        }
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
            // Persisted profiles are not proof of a live authenticated session.
            String token = "";
            this.currentUser = new YandexUser(id, login, displayName, realName, email, avatarUrl, token);
            if (m.containsKey("token")) savePersistedAccount();
        } catch (Exception ex) {
            // The persisted data may contain an old token; do not log its contents or parse exception.
            java.util.logging.Logger.getLogger(YandexAuthService.class.getName()).warning(
                    "Не удалось прочитать сохранённый профиль Яндекс");
        }
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
                    + "  \"avatarUrl\": " + quote(currentUser.avatarUrl) + "\n"
                    + "}";
            Path temp = Files.createTempFile(YANDEX_ACCOUNT_FILE.getParent(), ".yandex-account-", ".tmp");
            try {
                Files.writeString(temp, json, StandardCharsets.UTF_8);
                Files.move(temp, YANDEX_ACCOUNT_FILE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (Exception ex) {
            java.util.logging.Logger.getLogger(YandexAuthService.class.getName()).log(
                    java.util.logging.Level.WARNING, "Не удалось безопасно сохранить профиль Яндекс", ex);
        }
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
