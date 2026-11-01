package com.luminaread.service.manga;

import com.luminaread.service.MangaService.MangaItem;
import com.luminaread.util.Json;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Repository responsible for persisting and restoring manga state (favorites, history, progress, reader prefs).
 */
public class MangaStateRepository {

    private final Path stateFilePath;

    public MangaStateRepository() {
        this(Path.of(System.getProperty("user.home"), ".luminaread", "manga_state.json"));
    }

    public MangaStateRepository(Path stateFilePath) {
        this.stateFilePath = stateFilePath != null ? stateFilePath
                : Path.of(System.getProperty("user.home"), ".luminaread", "manga_state.json");
    }

    public Path getStateFilePath() {
        return stateFilePath;
    }

    public synchronized void saveState(Map<String, MangaItem> favorites,
                                      Map<String, MangaItem> history,
                                      Map<String, String> lastReadChapterMap,
                                      Map<String, String> readerPrefs) {
        try {
            if (stateFilePath.getParent() != null) {
                Files.createDirectories(stateFilePath.getParent());
            }
            StringBuilder sb = new StringBuilder();
            sb.append("{\n");

            sb.append("  \"favorites\": [\n");
            int i = 0;
            if (favorites != null) {
                for (MangaItem item : favorites.values()) {
                    if (item == null) continue;
                    if (i++ > 0) sb.append(",\n");
                    sb.append("    ").append(itemToJson(item));
                }
            }
            sb.append("\n  ],\n");

            sb.append("  \"history\": [\n");
            i = 0;
            if (history != null) {
                for (MangaItem item : history.values()) {
                    if (item == null) continue;
                    if (i++ > 0) sb.append(",\n");
                    sb.append("    ").append(itemToJson(item));
                }
            }
            sb.append("\n  ],\n");

            sb.append("  \"progress\": {\n");
            i = 0;
            if (lastReadChapterMap != null) {
                for (Map.Entry<String, String> e : lastReadChapterMap.entrySet()) {
                    if (i++ > 0) sb.append(",\n");
                    sb.append("    ").append(quote(e.getKey())).append(": ").append(quote(e.getValue()));
                }
            }
            sb.append("\n  }");

            if (readerPrefs != null && !readerPrefs.isEmpty()) {
                sb.append(",\n  \"readerPrefs\": {\n");
                i = 0;
                for (Map.Entry<String, String> e : readerPrefs.entrySet()) {
                    if (i++ > 0) sb.append(",\n");
                    sb.append("    ").append(quote(e.getKey())).append(": ").append(quote(e.getValue()));
                }
                sb.append("\n  }\n");
            } else {
                sb.append("\n");
            }

            sb.append("}");
            Files.writeString(stateFilePath, sb.toString(), StandardCharsets.UTF_8);
        } catch (Exception ignored) {}
    }

    public synchronized void loadState(Map<String, MangaItem> favorites,
                                      Map<String, MangaItem> history,
                                      Map<String, String> lastReadChapterMap,
                                      Map<String, String> readerPrefs) {
        try {
            if (!Files.exists(stateFilePath)) return;
            String text = Files.readString(stateFilePath, StandardCharsets.UTF_8);
            Object root = Json.parse(text);
            if (!(root instanceof Map)) return;
            Map<?, ?> map = (Map<?, ?>) root;

            Object favObj = map.get("favorites");
            if (favObj instanceof List && favorites != null) {
                favorites.clear();
                for (Object o : (List<?>) favObj) {
                    MangaItem it = itemFromJson(o);
                    if (it != null && !it.id.isEmpty()) favorites.put(it.id, it);
                }
            }

            Object histObj = map.get("history");
            if (histObj instanceof List && history != null) {
                history.clear();
                for (Object o : (List<?>) histObj) {
                    MangaItem it = itemFromJson(o);
                    if (it != null && !it.id.isEmpty()) history.put(it.id, it);
                }
            }

            Object progObj = map.get("progress");
            if (progObj instanceof Map && lastReadChapterMap != null) {
                lastReadChapterMap.clear();
                for (Map.Entry<?, ?> e : ((Map<?, ?>) progObj).entrySet()) {
                    lastReadChapterMap.put(str(e.getKey()), str(e.getValue()));
                }
            }

            Object prefObj = map.get("readerPrefs");
            if (prefObj instanceof Map && readerPrefs != null) {
                readerPrefs.clear();
                for (Map.Entry<?, ?> e : ((Map<?, ?>) prefObj).entrySet()) {
                    readerPrefs.put(str(e.getKey()), str(e.getValue()));
                }
            }
        } catch (Exception ignored) {}
    }

    private static String itemToJson(MangaItem item) {
        return "{\"id\":" + quote(item.id) +
                ",\"title\":" + quote(item.title) +
                ",\"rusTitle\":" + quote(item.rusTitle) +
                ",\"description\":" + quote(item.description) +
                ",\"coverUrl\":" + quote(item.coverUrl) +
                ",\"source\":" + quote(item.source) +
                ",\"status\":" + quote(item.status) +
                ",\"rating\":" + quote(item.rating) + "}";
    }

    private static MangaItem itemFromJson(Object obj) {
        if (!(obj instanceof Map)) return null;
        Map<?, ?> m = (Map<?, ?>) obj;
        return new MangaItem(
                str(m.get("id")),
                str(m.get("title")),
                str(m.get("rusTitle")),
                str(m.get("description")),
                str(m.get("coverUrl")),
                str(m.get("source")),
                str(m.get("status")),
                str(m.get("rating"))
        );
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
        return o == null ? "" : o.toString();
    }
}
