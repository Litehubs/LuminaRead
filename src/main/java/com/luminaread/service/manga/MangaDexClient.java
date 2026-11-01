package com.luminaread.service.manga;

import com.luminaread.service.MangaService.MangaChapter;
import com.luminaread.service.MangaService.MangaItem;
import com.luminaread.util.HtmlSanitizer;
import com.luminaread.util.Json;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Dedicated API client for MangaDex.
 */
public class MangaDexClient {

    public static final String MD_SOURCE = "MangaDex";

    private static final String BROWSER_UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";

    public List<MangaItem> search(String query) {
        List<MangaItem> list = new ArrayList<>();
        try {
            String apiUrl = "https://api.mangadex.org/manga?limit=24&includes[]=cover_art&order[followedCount]=desc";
            if (query != null && !query.trim().isEmpty()) {
                apiUrl += "&title=" + URLEncoder.encode(query.trim(), StandardCharsets.UTF_8.name());
            }
            String json = httpGet(apiUrl);
            Object root = Json.parse(json);
            if (!(root instanceof Map)) return list;
            Object data = ((Map<?, ?>) root).get("data");
            if (!(data instanceof List)) return list;
            for (Object o : (List<?>) data) {
                if (!(o instanceof Map)) continue;
                Map<?, ?> m = (Map<?, ?>) o;
                Object attrs = m.get("attributes");
                if (!(attrs instanceof Map)) continue;
                Map<?, ?> a = (Map<?, ?>) attrs;

                String id = str(m.get("id"));
                String title = pickTitle((Map<?, ?>) a.get("title"));
                String rusTitle = pickAltTitleRu(a.get("altTitles"));
                String desc = pickDescription(a.get("description"));
                String fileName = coverFileName(m.get("relationships"));
                String coverUrl = fileName.isEmpty() ? "" : "https://uploads.mangadex.org/covers/" + id + "/" + fileName + ".256.jpg";
                String status = statusLabel(str(a.get("status")));
                list.add(new MangaItem(id, title, rusTitle, desc, coverUrl, MD_SOURCE, status, "★ MD"));
            }
        } catch (Exception ignored) {}
        return list;
    }

    public String fetchDescription(MangaItem manga) {
        if (manga == null) return "Описание отсутствует.";
        return (manga.description != null && !manga.description.isEmpty()) ? manga.description : "Описание отсутствует.";
    }

    public List<MangaChapter> fetchChapters(MangaItem manga) {
        List<MangaChapter> chapters = new ArrayList<>();
        if (manga == null) return chapters;
        try {
            String url = "https://api.mangadex.org/manga/" + manga.id
                    + "/feed?limit=500&translatedLanguage[]=ru&translatedLanguage[]=en&order[chapter]=asc&includes[]=scanlation_group";
            String json = httpGet(url);
            Object root = Json.parse(json);
            if (root instanceof Map) {
                Object data = ((Map<?, ?>) root).get("data");
                if (data instanceof List) {
                    for (Object o : (List<?>) data) {
                        if (!(o instanceof Map)) continue;
                        Map<?, ?> m = (Map<?, ?>) o;
                        Object attrs = m.get("attributes");
                        if (!(attrs instanceof Map)) continue;
                        Map<?, ?> a = (Map<?, ?>) attrs;

                        String externalUrl = str(a.get("externalUrl"));
                        if (!externalUrl.isEmpty()) continue;

                        String chId = str(m.get("id"));
                        String chNum = str(a.get("chapter"));
                        if (chNum.isEmpty()) chNum = str(a.get("volume"));
                        String chTitle = str(a.get("title"));
                        String lang = str(a.get("translatedLanguage"));
                        if (lang.isEmpty()) lang = "ru";

                        String groupName = extractScanlationGroupName(m.get("relationships"));
                        if (!groupName.isEmpty()) {
                            chTitle = chTitle.isEmpty() ? "[" + groupName + "]" : chTitle + " [" + groupName + "]";
                        }
                        chapters.add(new MangaChapter(chId, chNum, chTitle, lang, MD_SOURCE));
                    }
                }
            }
        } catch (Exception ignored) {}
        return chapters;
    }

    public List<String> fetchPageUrls(MangaChapter chapter) {
        List<String> pages = new ArrayList<>();
        if (chapter == null) return pages;
        try {
            String json = httpGet("https://api.mangadex.org/at-home/server/" + chapter.id);
            Object root = Json.parse(json);
            if (root instanceof Map) {
                Map<?, ?> rootMap = (Map<?, ?>) root;
                String baseUrl = str(rootMap.get("baseUrl"));
                Object chObj = rootMap.get("chapter");
                if (chObj instanceof Map) {
                    Map<?, ?> chMap = (Map<?, ?>) chObj;
                    String hash = str(chMap.get("hash"));
                    Object saverObj = chMap.get("dataSaver");
                    Object fullObj = chMap.get("data");
                    List<?> saver = saverObj instanceof List ? (List<?>) saverObj : null;
                    List<?> full = fullObj instanceof List ? (List<?>) fullObj : null;

                    String mode = "/data-saver/";
                    List<?> files = (saver != null && !saver.isEmpty()) ? saver : full;
                    if (files == null || files.isEmpty()) {
                        files = full;
                        mode = "/data/";
                    }
                    if (files != null) {
                        for (Object f : files) {
                            pages.add(baseUrl + mode + hash + "/" + str(f));
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return pages;
    }

    private static String pickTitle(Map<?, ?> titleMap) {
        if (titleMap == null || titleMap.isEmpty()) return "Без названия";
        for (String p : new String[]{"en", "ru", "ja", "ko"}) {
            Object v = titleMap.get(p);
            if (v != null && !v.toString().trim().isEmpty()) return v.toString();
        }
        for (Object v : titleMap.values()) {
            if (v != null && !v.toString().trim().isEmpty()) return v.toString();
        }
        return "Без названия";
    }

    private static String pickAltTitleRu(Object altTitles) {
        if (altTitles instanceof List) {
            for (Object at : (List<?>) altTitles) {
                if (at instanceof Map) {
                    Object v = ((Map<?, ?>) at).get("ru");
                    if (v != null && !v.toString().trim().isEmpty()) return v.toString();
                }
            }
        }
        return "";
    }

    private static String pickDescription(Object descObj) {
        if (descObj instanceof Map) {
            Map<?, ?> descMap = (Map<?, ?>) descObj;
            for (String p : new String[]{"ru", "en"}) {
                Object v = descMap.get(p);
                if (v != null && !v.toString().trim().isEmpty()) {
                    return cleanDescription(v.toString());
                }
            }
            for (Object v : descMap.values()) {
                if (v != null && !v.toString().trim().isEmpty()) {
                    return cleanDescription(v.toString());
                }
            }
        }
        return "Описание отсутствует.";
    }

    private static String cleanDescription(String raw) {
        if (raw == null) return "Описание отсутствует.";
        String text = HtmlSanitizer.htmlToPlainText(raw);
        int hr = text.indexOf("---");
        if (hr >= 0) text = text.substring(0, hr);
        text = text.replaceAll("\\[[^\\]]*\\]\\([^)]*\\)", " ");
        text = text.replaceAll("https?://\\S+", " ");
        text = text.replaceAll("\\s+", " ").trim();
        if (text.length() > 450) {
            text = text.substring(0, 450).trim() + "…";
        }
        return text.isEmpty() ? "Описание отсутствует." : text;
    }

    private static String coverFileName(Object relationships) {
        if (relationships instanceof List) {
            for (Object r : (List<?>) relationships) {
                if (r instanceof Map) {
                    Map<?, ?> rm = (Map<?, ?>) r;
                    if ("cover_art".equals(str(rm.get("type")))) {
                        Object attrs = rm.get("attributes");
                        if (attrs instanceof Map) {
                            String fn = str(((Map<?, ?>) attrs).get("fileName"));
                            if (!fn.isEmpty()) return fn;
                        }
                    }
                }
            }
        }
        return "";
    }

    private static String extractScanlationGroupName(Object relationships) {
        if (relationships instanceof List) {
            for (Object r : (List<?>) relationships) {
                if (r instanceof Map) {
                    Map<?, ?> rm = (Map<?, ?>) r;
                    if ("scanlation_group".equals(str(rm.get("type")))) {
                        Object attrs = rm.get("attributes");
                        if (attrs instanceof Map) {
                            String name = str(((Map<?, ?>) attrs).get("name"));
                            if (!name.isEmpty()) return name;
                        }
                    }
                }
            }
        }
        return "";
    }

    private static String statusLabel(String s) {
        if (s == null) return "";
        switch (s) {
            case "ongoing": return "Онгоинг";
            case "completed": return "Завершён";
            case "hiatus": return "Хиатус";
            case "cancelled": return "Отменён";
            default: return s;
        }
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    private String httpGet(String urlStr) throws IOException {
        URL url = URI.create(urlStr).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent", BROWSER_UA);
        conn.setRequestProperty("Accept", "application/json");
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(20000);

        int code = conn.getResponseCode();
        InputStream is = (code >= 200 && code < 400) ? conn.getInputStream() : conn.getErrorStream();
        if (is == null) throw new IOException("HTTP error: " + code);

        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            if (code >= 400) throw new IOException("HTTP " + code + ": " + sb);
            return sb.toString();
        }
    }
}
