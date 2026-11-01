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
 * Dedicated API client for Remanga (RU).
 */
public class RemangaClient {

    private static final String REM_API = "https://api.remanga.org/api";
    public static final String REM_SOURCE = "Remanga (RU)";

    private static final String BROWSER_UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";

    public List<MangaItem> search(String query) {
        List<MangaItem> list = new ArrayList<>();
        try {
            String apiUrl = REM_API + "/search/?count=24";
            if (query != null && !query.trim().isEmpty()) {
                apiUrl += "&query=" + URLEncoder.encode(query.trim(), StandardCharsets.UTF_8.name());
            } else {
                apiUrl += "&ordering=-rating";
            }
            String json = httpGet(apiUrl);
            Object root = Json.parse(json);
            if (!(root instanceof Map)) return list;
            Object data = ((Map<?, ?>) root).get("content");
            if (!(data instanceof List)) return list;
            for (Object o : (List<?>) data) {
                if (!(o instanceof Map)) continue;
                Map<?, ?> m = (Map<?, ?>) o;
                String dir = str(m.get("dir"));
                if (dir.isEmpty()) continue;
                String mainName = str(m.get("main_name"));
                String rusName = str(m.get("rus_name"));
                String enName = str(m.get("en_name"));
                String title = mainName.isEmpty() ? (enName.isEmpty() ? rusName : enName) : mainName;
                String coverUrl = remangaCover(m.get("cover"));
                if (coverUrl.isEmpty()) coverUrl = remangaCover(m.get("img"));
                String status = extractStatusLabel(m.get("status"));
                String typeLabel = str(m.get("type"));
                String rating = str(m.get("avg_rating"));
                StringBuilder desc = new StringBuilder();
                if (!typeLabel.isEmpty()) desc.append(typeLabel);
                String year = str(m.get("issue_year"));
                if (!year.isEmpty() && !"0".equals(year)) desc.append(desc.length() > 0 ? " • " : "").append(year);
                String chapters = str(m.get("count_chapters"));
                if (!chapters.isEmpty() && !"0".equals(chapters)) desc.append(desc.length() > 0 ? " • " : "").append("глав: ").append(chapters);
                list.add(new MangaItem("rem:" + dir, title, rusName, desc.length() == 0 ? "Описание на странице тайтла." : desc.toString(),
                        coverUrl, REM_SOURCE, status, rating.isEmpty() ? "★ —" : "★ " + rating));
            }
        } catch (Exception ignored) {}
        return list;
    }

    public String fetchDescription(MangaItem manga) {
        if (manga == null) return "Описание отсутствует.";
        try {
            String dir = manga.id.startsWith("rem:") ? manga.id.substring(4) : manga.id;
            String json = httpGet(REM_API + "/titles/" + slashEncode(dir) + "/");
            Object root = Json.parse(json);
            if (root instanceof Map) {
                Object contentObj = ((Map<?, ?>) root).get("content");
                if (contentObj instanceof Map) {
                    String text = str(((Map<?, ?>) contentObj).get("description"));
                    if (!text.isEmpty()) return cleanDescription(text);
                }
            }
        } catch (Exception ignored) {}
        return manga.description != null && !manga.description.isEmpty() ? manga.description : "Описание отсутствует.";
    }

    public List<MangaChapter> fetchChapters(MangaItem manga) {
        List<MangaChapter> chapters = new ArrayList<>();
        if (manga == null) return chapters;
        try {
            String dir = manga.id.startsWith("rem:") ? manga.id.substring(4) : manga.id;
            String titleJson = httpGet(REM_API + "/titles/" + slashEncode(dir) + "/");
            Object tr = Json.parse(titleJson);
            if (tr instanceof Map) {
                Object contentObj = ((Map<?, ?>) tr).get("content");
                if (contentObj instanceof Map) {
                    Object branchesObj = ((Map<?, ?>) contentObj).get("branches");
                    if (branchesObj instanceof List && !((List<?>) branchesObj).isEmpty()) {
                        List<String> bIds = new ArrayList<>();
                        for (Object b : (List<?>) branchesObj) {
                            if (b instanceof Map) {
                                String bid = str(((Map<?, ?>) b).get("id"));
                                if (!bid.isEmpty()) bIds.add(bid);
                            }
                        }
                        if (!bIds.isEmpty()) {
                            String branchParam = String.join(",", bIds);
                            int page = 1;
                            boolean hasMore = true;
                            while (hasMore && page <= 5) {
                                String chJson = httpGet(REM_API + "/titles/chapters/?branch_id=" + branchParam
                                        + "&count=100&ordering=index&page=" + page);
                                Object cr = Json.parse(chJson);
                                if (!(cr instanceof Map)) break;
                                Object chData = ((Map<?, ?>) cr).get("content");
                                if (!(chData instanceof List) || ((List<?>) chData).isEmpty()) break;
                                for (Object o : (List<?>) chData) {
                                    if (!(o instanceof Map)) continue;
                                    Map<?, ?> c = (Map<?, ?>) o;
                                    String chId = str(c.get("id"));
                                    String num = str(c.get("chapter"));
                                    if (num.isEmpty()) num = str(c.get("index"));
                                    if (num.isEmpty()) continue;
                                    String chTitle = str(c.get("name"));
                                    chapters.add(new MangaChapter("remc:" + chId, num, chTitle, "ru", REM_SOURCE));
                                }
                                List<?> list = (List<?>) chData;
                                if (list.size() < 100) hasMore = false;
                                else page++;
                            }
                        }
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
            String chId = chapter.id.startsWith("remc:") ? chapter.id.substring(5) : chapter.id;
            String json = httpGet(REM_API + "/titles/chapters/" + slashEncode(chId) + "/");
            Object root = Json.parse(json);
            if (root instanceof Map) {
                Object contentObj = ((Map<?, ?>) root).get("content");
                if (contentObj instanceof Map) {
                    Object pagesObj = ((Map<?, ?>) contentObj).get("pages");
                    if (pagesObj instanceof List) {
                        for (Object group : (List<?>) pagesObj) {
                            if (group instanceof List) {
                                for (Object pg : (List<?>) group) {
                                    if (pg instanceof Map) {
                                        String link = str(((Map<?, ?>) pg).get("link"));
                                        if (!link.isEmpty()) pages.add(normalizeRemangaUrl(link));
                                    }
                                }
                            } else if (group instanceof Map) {
                                String link = str(((Map<?, ?>) group).get("link"));
                                if (!link.isEmpty()) pages.add(normalizeRemangaUrl(link));
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return pages;
    }

    public static String normalizeRemangaUrl(String link) {
        if (link == null) return "";
        if (link.startsWith("http://") || link.startsWith("https://")) return link;
        if (link.startsWith("//")) return "https:" + link;
        return "https://remanga.org" + (link.startsWith("/") ? "" : "/") + link;
    }

    private static String remangaCover(Object coverObj) {
        if (coverObj instanceof Map) {
            Map<?, ?> c = (Map<?, ?>) coverObj;
            for (String k : new String[]{"mid", "high", "low"}) {
                String v = str(c.get(k));
                if (!v.isEmpty()) {
                    if (v.startsWith("http://") || v.startsWith("https://")) return v;
                    if (v.startsWith("//")) return "https:" + v;
                    return "https://remanga.org" + (v.startsWith("/") ? "" : "/") + v;
                }
            }
        }
        return "";
    }

    private static String extractStatusLabel(Object obj) {
        if (obj instanceof Map) return str(((Map<?, ?>) obj).get("label"));
        if (obj instanceof List) {
            StringBuilder sb = new StringBuilder();
            for (Object o : (List<?>) obj) {
                String l = extractStatusLabel(o);
                if (!l.isEmpty()) {
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(l);
                }
            }
            return sb.toString();
        }
        return str(obj);
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

    private static String slashEncode(String s) {
        if (s == null) return "";
        try {
            return URLEncoder.encode(s, "UTF-8").replace("%2F", "/").replace("+", "%20");
        } catch (Exception e) {
            return s;
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
        conn.setRequestProperty("Referer", "https://remanga.org/");
        conn.setRequestProperty("Origin", "https://remanga.org");
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
