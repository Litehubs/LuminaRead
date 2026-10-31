package com.luminaread.service;

import com.luminaread.util.Json;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public class LibAccountService {

    public static class LibUser {
        public final String id;
        public final String username;
        public final String avatarUrl;
        public final String token;
        public final boolean isTokenAuth;

        public LibUser(String id, String username, String avatarUrl, String token, boolean isTokenAuth) {
            this.id = id != null ? id : "";
            this.username = username != null ? username : "";
            this.avatarUrl = avatarUrl != null ? avatarUrl : "";
            this.token = token != null ? token : "";
            this.isTokenAuth = isTokenAuth;
        }

        public boolean isAuthenticated() {
            return !id.isEmpty() || !username.isEmpty();
        }
    }

    public static class LibBookmarkItem {
        public final String id;
        public final String slugUrl;
        public final String title;
        public final String rusTitle;
        public final String coverUrl;
        public final String lastReadChapter;
        public final int status;
        public final int siteId;
        public final String typeLabel;

        public LibBookmarkItem(String id, String slugUrl, String title, String rusTitle,
                               String coverUrl, String lastReadChapter, int status, int siteId, String typeLabel) {
            this.id = id != null ? id : "";
            this.slugUrl = slugUrl != null ? slugUrl : "";
            this.title = title != null ? title : "";
            this.rusTitle = rusTitle != null ? rusTitle : "";
            this.coverUrl = coverUrl != null ? coverUrl : "";
            this.lastReadChapter = lastReadChapter != null ? lastReadChapter : "";
            this.status = status;
            this.siteId = siteId;
            this.typeLabel = typeLabel != null ? typeLabel : "";
        }

        public String getDisplayTitle() {
            return (!rusTitle.isEmpty()) ? rusTitle : title;
        }

        public String getStatusName() {
            switch (status) {
                case 1: return "Читаю";
                case 2: return "В планах";
                case 3: return "Брошено";
                case 4: return "Прочитано";
                case 5: return "Любимое";
                default: return "В закладках";
            }
        }
    }

    private static final String API_PRIMARY = "https://api.cdnlibs.org/api";
    private static final String API_SECONDARY = "https://api.imglib.info/api";
    private static final String BROWSER_UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";
    private static final Path ACCOUNT_FILE =
            Path.of(System.getProperty("user.home"), ".luminaread", "lib_account.json");

    private static LibAccountService instance;

    public static synchronized LibAccountService getInstance() {
        if (instance == null) {
            instance = new LibAccountService();
        }
        return instance;
    }

    private LibUser currentUser;
    private final List<Consumer<LibUser>> authListeners = new CopyOnWriteArrayList<>();

    private LibAccountService() {
        loadPersistedAccount();
    }

    public synchronized LibUser getCurrentUser() {
        return currentUser;
    }

    public synchronized boolean isLoggedIn() {
        return currentUser != null && currentUser.isAuthenticated();
    }

    public void addAuthListener(Consumer<LibUser> listener) {
        if (listener != null) {
            authListeners.add(listener);
            listener.accept(currentUser);
        }
    }

    private void notifyListeners() {
        for (Consumer<LibUser> l : authListeners) {
            try {
                l.accept(currentUser);
            } catch (Exception ignored) {}
        }
    }

    public LibUser loginWithToken(String rawToken) throws Exception {
        if (rawToken == null || rawToken.trim().isEmpty()) {
            throw new IllegalArgumentException("Токен не может быть пустым.");
        }
        String token = rawToken.trim().replaceFirst("^(?i)Bearer\\s+", "");

        String json = executeGetWithFailover("/auth/me", token, "1");
        Object root = Json.parse(json);
        if (!(root instanceof Map)) {
            throw new Exception("Некорректный ответ сервера при авторизации.");
        }
        Map<?, ?> map = (Map<?, ?>) root;
        Object dataObj = map.get("data");
        if (!(dataObj instanceof Map)) {
            throw new Exception("Не удалось получить данные аккаунта.");
        }
        Map<?, ?> data = (Map<?, ?>) dataObj;

        Object userObj = data.get("user");
        Map<?, ?> userMap = (userObj instanceof Map) ? (Map<?, ?>) userObj : data;

        String id = str(userMap.get("id"));
        String username = str(userMap.get("username"));
        if (username.isEmpty()) username = str(data.get("username"));
        if (id.isEmpty()) id = str(data.get("id"));

        String avatarUrl = extractAvatarUrl(userMap.get("avatar"));
        if (avatarUrl.isEmpty()) avatarUrl = extractAvatarUrl(data.get("avatar"));

        if (username.isEmpty() && id.isEmpty()) {
            throw new Exception("Токен недействителен или срок его действия истёк.");
        }

        LibUser user = new LibUser(id, username, avatarUrl, token, true);
        synchronized (this) {
            this.currentUser = user;
            savePersistedAccount();
        }
        notifyListeners();
        return user;
    }

    public LibUser loginWithProfile(String input) throws Exception {
        if (input == null || input.trim().isEmpty()) {
            throw new IllegalArgumentException("Введите ID, никнейм или ссылку на профиль.");
        }
        String clean = input.trim();

        String userId = "";
        java.util.regex.Matcher urlMatcher = java.util.regex.Pattern.compile("/user/(\\d+)").matcher(clean);
        if (urlMatcher.find()) {
            userId = urlMatcher.group(1);
        } else if (clean.matches("^\\d+$")) {
            userId = clean;
        }

        if (!userId.isEmpty()) {
            return fetchAndSetUserProfile(userId, null);
        }

        String cleanNick = clean.replaceFirst("^@", "");
        String searchJson = executeGetWithFailover("/user?q=" + URLEncoder.encode(cleanNick, StandardCharsets.UTF_8.name()), null, "1");
        Object root = Json.parse(searchJson);
        if (root instanceof Map) {
            Object dataObj = ((Map<?, ?>) root).get("data");
            if (dataObj instanceof List && !((List<?>) dataObj).isEmpty()) {

                for (Object o : (List<?>) dataObj) {
                    if (o instanceof Map) {
                        Map<?, ?> um = (Map<?, ?>) o;
                        String uName = str(um.get("username"));
                        if (uName.equalsIgnoreCase(cleanNick)) {
                            return fetchAndSetUserProfile(str(um.get("id")), null);
                        }
                    }
                }

                Object first = ((List<?>) dataObj).get(0);
                if (first instanceof Map) {
                    return fetchAndSetUserProfile(str(((Map<?, ?>) first).get("id")), null);
                }
            }
        }

        throw new Exception("Пользователь «" + clean + "» не найден в Lib.social.");
    }

    private LibUser fetchAndSetUserProfile(String id, String token) throws Exception {
        String json = executeGetWithFailover("/user/" + id, token, "1");
        Object root = Json.parse(json);
        if (!(root instanceof Map)) throw new Exception("Не удалось загрузить профиль пользователя.");
        Object dataObj = ((Map<?, ?>) root).get("data");
        if (!(dataObj instanceof Map)) throw new Exception("Пользователь с ID " + id + " не найден.");
        Map<?, ?> d = (Map<?, ?>) dataObj;

        String username = str(d.get("username"));
        String avatarUrl = extractAvatarUrl(d.get("avatar"));

        LibUser user = new LibUser(id, username, avatarUrl, token != null ? token : "", token != null && !token.isEmpty());
        synchronized (this) {
            this.currentUser = user;
            savePersistedAccount();
        }
        notifyListeners();
        return user;
    }

    public synchronized void logout() {
        this.currentUser = null;
        try {
            Files.deleteIfExists(ACCOUNT_FILE);
        } catch (Exception ignored) {}
        notifyListeners();
    }

    public List<LibBookmarkItem> fetchUserBookmarks(int siteId, int status) {
        List<LibBookmarkItem> list = new ArrayList<>();
        LibUser user = this.currentUser;
        if (user == null || user.id.isEmpty()) return list;

        int[] statusesToFetch = (status >= 1 && status <= 5) ? new int[]{status} : new int[]{1, 2, 5, 4, 3};

        for (int st : statusesToFetch) {
            try {
                String path = "/bookmarks?user_id=" + user.id + "&status=" + st;
                String json = executeGetWithFailover(path, user.token, String.valueOf(siteId));
                Object root = Json.parse(json);
                if (!(root instanceof Map)) continue;
                Object dataObj = ((Map<?, ?>) root).get("data");
                if (!(dataObj instanceof List)) continue;

                for (Object o : (List<?>) dataObj) {
                    if (!(o instanceof Map)) continue;
                    Map<?, ?> b = (Map<?, ?>) o;
                    String bId = str(b.get("id"));
                    String progress = str(b.get("progress"));
                    int bStatus = parseSafeInt(b.get("status"), st);

                    Object mediaObj = b.get("media");
                    if (!(mediaObj instanceof Map)) continue;
                    Map<?, ?> m = (Map<?, ?>) mediaObj;

                    int mSite = parseSafeInt(m.get("site"), siteId);
                    if (mSite != siteId) continue;

                    String slugUrl = str(m.get("slug_url"));
                    if (slugUrl.isEmpty()) slugUrl = str(m.get("id")) + "--" + str(m.get("slug"));
                    String name = str(m.get("name"));
                    String rusName = str(m.get("rus_name"));
                    String coverUrl = extractCoverUrl(m.get("cover"));
                    String typeLabel = extractLabel(m.get("type"));

                    list.add(new LibBookmarkItem(bId, slugUrl, name, rusName, coverUrl, progress, bStatus, mSite, typeLabel));
                }
            } catch (Exception ignored) {}
        }
        return list;
    }

    private static String extractAvatarUrl(Object avatarObj) {
        if (avatarObj instanceof Map) {
            Map<?, ?> am = (Map<?, ?>) avatarObj;
            String url = str(am.get("url"));
            if (!url.isEmpty()) return url;
            String fn = str(am.get("filename"));
            if (!fn.isEmpty()) return "https://cover.imglib.info/uploads/users/" + fn;
        }
        return "";
    }

    private static String extractCoverUrl(Object coverObj) {
        if (coverObj instanceof Map) {
            Map<?, ?> c = (Map<?, ?>) coverObj;
            for (String k : new String[]{"default", "md", "thumbnail"}) {
                String v = str(c.get(k));
                if (!v.isEmpty()) {
                    if (v.startsWith("//")) return "https:" + v;
                    if (v.startsWith("/")) return "https://cover.imglib.info" + v;
                    return v;
                }
            }
        }
        return "";
    }

    private static String extractLabel(Object obj) {
        if (obj instanceof Map) return str(((Map<?, ?>) obj).get("label"));
        return str(obj);
    }

    private String executeGetWithFailover(String path, String token, String siteId) throws Exception {
        try {
            return executeGet(API_PRIMARY + path, token, siteId);
        } catch (Exception e1) {
            return executeGet(API_SECONDARY + path, token, siteId);
        }
    }

    private String executeGet(String urlStr, String token, String siteId) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent", BROWSER_UA);
        conn.setRequestProperty("Accept", "application/json");

        String origin = "3".equals(siteId) ? "https://ranobelib.me" : "https://mangalib.me";
        conn.setRequestProperty("Referer", origin + "/");
        conn.setRequestProperty("Origin", origin);
        if (siteId != null && !siteId.isEmpty()) {
            conn.setRequestProperty("Site-Id", siteId);
        }

        if (token != null && !token.trim().isEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer " + token.trim());
        }

        conn.setConnectTimeout(8000);
        conn.setReadTimeout(18000);

        int code = conn.getResponseCode();
        InputStream is = (code >= 200 && code < 400) ? conn.getInputStream() : conn.getErrorStream();
        if (is == null) throw new Exception("HTTP error " + code);

        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            if (code >= 400) {
                throw new Exception("HTTP " + code + ": " + sb);
            }
            return sb.toString();
        }
    }

    private synchronized void savePersistedAccount() {
        if (currentUser == null) return;
        try {
            Files.createDirectories(ACCOUNT_FILE.getParent());
            String json = "{\n" +
                    "  \"id\": " + quote(currentUser.id) + ",\n" +
                    "  \"username\": " + quote(currentUser.username) + ",\n" +
                    "  \"avatarUrl\": " + quote(currentUser.avatarUrl) + ",\n" +
                    "  \"token\": " + quote(currentUser.token) + ",\n" +
                    "  \"isTokenAuth\": " + currentUser.isTokenAuth + "\n" +
                    "}";
            Files.writeString(ACCOUNT_FILE, json, StandardCharsets.UTF_8);
        } catch (Exception ignored) {}
    }

    private synchronized void loadPersistedAccount() {
        try {
            if (!Files.exists(ACCOUNT_FILE)) return;
            String json = Files.readString(ACCOUNT_FILE, StandardCharsets.UTF_8);
            Object root = Json.parse(json);
            if (root instanceof Map) {
                Map<?, ?> m = (Map<?, ?>) root;
                String id = str(m.get("id"));
                String username = str(m.get("username"));
                String avatarUrl = str(m.get("avatarUrl"));
                String token = str(m.get("token"));
                boolean isTokenAuth = Boolean.TRUE.equals(m.get("isTokenAuth"));
                if (!id.isEmpty() || !username.isEmpty()) {
                    this.currentUser = new LibUser(id, username, avatarUrl, token, isTokenAuth);
                }
            }
        } catch (Exception ignored) {}
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    private static int parseSafeInt(Object o, int def) {
        if (o == null) return def;
        try {
            return Integer.parseInt(o.toString());
        } catch (Exception e) {
            return def;
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
}
