package com.luminaread.service.ranobe;

import com.luminaread.model.ranobe.RanobeItem;
import com.luminaread.util.Json;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Repository responsible for thread-safe persistence and retrieval of Ranobe state
 * (favorites, reading history, last read chapters, and reader preferences) on disk.
 */
public class RanobeStateRepository {

    @FunctionalInterface
    public interface ItemFactory<T extends RanobeItem> {
        T create(String id, String title, String rusTitle, String description,
                 String coverUrl, String status, String rating, String releaseDate,
                 List<String> genres, List<String> authors);
    }

    public static final ItemFactory<RanobeItem> DEFAULT_FACTORY = RanobeItem::new;

    private final Path explicitPath;

    public RanobeStateRepository() {
        this.explicitPath = null;
    }

    public RanobeStateRepository(Path explicitPath) {
        this.explicitPath = explicitPath;
    }

    public Path getStateFilePath() {
        if (explicitPath != null) {
            return explicitPath;
        }
        return Path.of(System.getProperty("user.home"), ".luminaread", "ranobe_state.json");
    }

    public synchronized void saveState(Map<String, ? extends RanobeItem> favorites,
                                       Map<String, ? extends RanobeItem> history,
                                       Map<String, String> lastReadChapterMap,
                                       Map<String, String> readerPrefs) {
        try {
            Path stateFile = getStateFilePath();
            if (stateFile.getParent() != null) {
                Files.createDirectories(stateFile.getParent());
            }

            StringBuilder sb = new StringBuilder();
            sb.append("{\n");

            sb.append("  \"favorites\": [\n");
            int i = 0;
            if (favorites != null) {
                for (RanobeItem item : favorites.values()) {
                    if (item == null) continue;
                    if (i++ > 0) sb.append(",\n");
                    sb.append("    ").append(itemToJson(item));
                }
            }
            sb.append("\n  ],\n");

            sb.append("  \"history\": [\n");
            i = 0;
            if (history != null) {
                for (RanobeItem item : history.values()) {
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
                    if (e.getKey() == null) continue;
                    if (i++ > 0) sb.append(",\n");
                    sb.append("    ").append(quote(e.getKey())).append(": ").append(quote(e.getValue()));
                }
            }
            sb.append("\n  }");

            if (readerPrefs != null && !readerPrefs.isEmpty()) {
                sb.append(",\n  \"readerPrefs\": {\n");
                i = 0;
                for (Map.Entry<String, String> e : readerPrefs.entrySet()) {
                    if (e.getKey() == null) continue;
                    if (i++ > 0) sb.append(",\n");
                    sb.append("    ").append(quote(e.getKey())).append(": ").append(quote(e.getValue()));
                }
                sb.append("\n  }\n");
            } else {
                sb.append("\n");
            }

            sb.append("}");
            Files.writeString(stateFile, sb.toString(), StandardCharsets.UTF_8);
        } catch (Exception ignored) {}
    }

    public synchronized void loadState(Map<String, RanobeItem> favorites,
                                       Map<String, RanobeItem> history,
                                       Map<String, String> lastReadChapterMap,
                                       Map<String, String> readerPrefs) {
        loadState(favorites, history, lastReadChapterMap, readerPrefs, DEFAULT_FACTORY);
    }

    public synchronized <T extends RanobeItem> void loadState(Map<String, T> favorites,
                                                             Map<String, T> history,
                                                             Map<String, String> lastReadChapterMap,
                                                             Map<String, String> readerPrefs,
                                                             ItemFactory<T> factory) {
        try {
            Path stateFile = getStateFilePath();
            if (!Files.exists(stateFile)) return;
            String text = Files.readString(stateFile, StandardCharsets.UTF_8);
            Object root = Json.parse(text);
            if (!(root instanceof Map)) return;
            Map<?, ?> map = (Map<?, ?>) root;

            ItemFactory<T> effectiveFactory = factory != null ? factory : (id, t, rt, d, c, s, r, rd, g, a) -> (T) new RanobeItem(id, t, rt, d, c, s, r, rd, g, a);

            Object favObj = map.get("favorites");
            if (favObj instanceof List && favorites != null) {
                favorites.clear();
                for (Object o : (List<?>) favObj) {
                    T it = itemFromJson(o, effectiveFactory);
                    if (it != null && !it.id.isEmpty()) favorites.put(it.id, it);
                }
            }

            Object histObj = map.get("history");
            if (histObj instanceof List && history != null) {
                history.clear();
                for (Object o : (List<?>) histObj) {
                    T it = itemFromJson(o, effectiveFactory);
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

    private static String itemToJson(RanobeItem item) {
        return "{\"id\":" + quote(item.id) +
                ",\"title\":" + quote(item.title) +
                ",\"rusTitle\":" + quote(item.rusTitle) +
                ",\"description\":" + quote(item.description) +
                ",\"coverUrl\":" + quote(item.coverUrl) +
                ",\"status\":" + quote(item.status) +
                ",\"rating\":" + quote(item.rating) +
                ",\"releaseDate\":" + quote(item.releaseDate) + "}";
    }

    private static <T extends RanobeItem> T itemFromJson(Object obj, ItemFactory<T> factory) {
        if (!(obj instanceof Map)) return null;
        Map<?, ?> m = (Map<?, ?>) obj;
        return factory.create(
                str(m.get("id")),
                str(m.get("title")),
                str(m.get("rusTitle")),
                str(m.get("description")),
                str(m.get("coverUrl")),
                str(m.get("status")),
                str(m.get("rating")),
                str(m.get("releaseDate")),
                Collections.emptyList(),
                Collections.emptyList()
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
