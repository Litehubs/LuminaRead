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
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

public class MangaLibClient {
    private static final String MGL_API_PRIMARY = "https://api.cdnlibs.org/api";
    private static final String MGL_API_SECONDARY = "https://api.imglib.info/api";
    private static final String MGL_ORIGIN = "https://mangalib.me";
    public static final String MGL_SOURCE = "MangaLib (RU)";

    private static final String BROWSER_UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";

    private final List<String> knownImageServers = new CopyOnWriteArrayList<>(Arrays.asList(
            "https://img3.cdnrulib.link",
            "https://img3.cdnlibs.org",
            "https://img4.cdnrulib.link",
            "https://img2.imglib.info",
            "https://img3.hentaicdn.org",
            "https://img2.hentaicdn.org"
    ));
    private final AtomicBoolean serversDiscovered = new AtomicBoolean(false);

    public List<String> getKnownImageServers() {
        return Collections.unmodifiableList(new ArrayList<>(knownImageServers));
    }

    public List<MangaItem> search(String query) {
        List<MangaItem> list = new ArrayList<>();
        try {
            ensureServersDiscovered();
            String path = "/manga?page=1&site_id[]=1";
            if (query != null && !query.trim().isEmpty()) {
                path += "&q=" + URLEncoder.encode(query.trim(), StandardCharsets.UTF_8.name());
            }
            String json = httpGetWithFailover(path, false);
            Object root = Json.parse(json);
            if (!(root instanceof Map)) return list;
            Object data = ((Map<?, ?>) root).get("data");
            if (!(data instanceof List)) return list;
            for (Object o : (List<?>) data) {
                if (!(o instanceof Map)) continue;
                Map<?, ?> m = (Map<?, ?>) o;
                String slugUrl = str(m.get("slug_url"));
                if (slugUrl.isEmpty()) {
                    String slug = str(m.get("slug"));
                    String id = str(m.get("id"));
                    if (!id.isEmpty() && !slug.isEmpty()) slugUrl = id + "--" + slug;
                    else slugUrl = id.isEmpty() ? slug : id;
                }
                if (slugUrl.isEmpty()) continue;
                String name = str(m.get("name"));
                String rusName = str(m.get("rus_name"));
                String title = name.isEmpty() ? rusName : name;
                String coverUrl = extractCover(m.get("cover"));
                String status = extractLabel(m.get("status"));
                String typeLabel = extractLabel(m.get("type"));
                String rating = extractRating(m.get("rating"));
                String desc = typeLabel.isEmpty() ? "Описание на странице тайтла."
                        : typeLabel + (str(m.get("releaseDateString")).isEmpty() ? "" : " • " + str(m.get("releaseDateString")));
                list.add(new MangaItem("mgl:" + slugUrl, title, rusName, desc, coverUrl, MGL_SOURCE, status, rating));
            }
        } catch (Exception ignored) {}
        return list;
    }

    public String fetchDescription(MangaItem manga) {
        if (manga == null) return "Описание отсутствует.";
        String slugUrl = manga.id != null && manga.id.startsWith("mgl:") ? manga.id.substring(4) : (manga.id != null ? manga.id : "");
        String desc = fetchDescription(slugUrl);
        return (!desc.isEmpty()) ? desc : (manga.description != null && !manga.description.isEmpty() ? manga.description : "Описание отсутствует.");
    }

    public String fetchDescription(String slugUrl) {
        if (slugUrl == null || slugUrl.isBlank()) return "";
        try {
            String json = httpGetWithFailover("/manga/" + slashEncode(slugUrl) + "?fields[]=summary", true);
            Object root = Json.parse(json);
            if (root instanceof Map) {
                Object dataObj = ((Map<?, ?>) root).get("data");
                if (dataObj instanceof Map) {
                    String text = docText(((Map<?, ?>) dataObj).get("summary"));
                    if (!text.isEmpty()) return cleanDescription(text);
                }
            }
        } catch (Exception ignored) {}
        return "";
    }

    public List<MangaChapter> fetchChapters(MangaItem manga) {
        if (manga == null) return new ArrayList<>();
        String slugUrl = manga.id != null && manga.id.startsWith("mgl:") ? manga.id.substring(4) : (manga.id != null ? manga.id : "");
        return fetchChapters(slugUrl);
    }

    public List<MangaChapter> fetchChapters(String slugUrl) {
        List<MangaChapter> chapters = new ArrayList<>();
        if (slugUrl == null || slugUrl.isBlank()) return chapters;
        try {
            ensureServersDiscovered();
            String json = httpGetWithFailover("/manga/" + slashEncode(slugUrl) + "/chapters", true);
            Object root = Json.parse(json);
            if (root instanceof Map) {
                Object data = ((Map<?, ?>) root).get("data");
                if (data instanceof List) {
                    for (Object o : (List<?>) data) {
                        if (!(o instanceof Map)) continue;
                        Map<?, ?> c = (Map<?, ?>) o;
                        String vol = str(c.get("volume"));
                        String num = str(c.get("number"));
                        if (num.isEmpty()) num = str(c.get("item_number"));
                        if (num.isEmpty()) continue;
                        String chTitle = str(c.get("name"));
                        String branchId = firstOpenBranchId(c.get("branches"));
                        String chId = slugUrl + "|" + vol + "|" + num + "|" + branchId;
                        chapters.add(new MangaChapter(chId, num, chTitle, "ru", MGL_SOURCE));
                    }
                }
            }
        } catch (Exception ignored) {}
        return chapters;
    }

    public List<String> fetchPageUrls(MangaChapter chapter) {
        if (chapter == null) return new ArrayList<>();
        return fetchPageUrls(chapter.id);
    }

    public List<String> fetchPageUrls(String chapterId) {
        List<String> pages = new ArrayList<>();
        if (chapterId == null || chapterId.isBlank()) return pages;
        try {
            ensureServersDiscovered();
            String[] parts = chapterId.split("\\|", -1);
            String slugUrl = parts.length > 0 ? parts[0] : "";
            String vol = parts.length > 1 ? parts[1] : "";
            String num = parts.length > 2 ? parts[2] : "";
            String branch = parts.length > 3 ? parts[3] : "";

            List<String> relPaths = fetchRelPaths(slugUrl, num, vol, branch);
            if (relPaths.isEmpty() && !branch.isEmpty()) {
                relPaths = fetchRelPaths(slugUrl, num, vol, "");
            }

            if (!relPaths.isEmpty()) {
                String primaryServer = knownImageServers.get(0);
                for (String rel : relPaths) {
                    String clean = rel.replaceFirst("^//", "").replaceFirst("^/", "");
                    pages.add(primaryServer + "/" + clean);
                }
            }
        } catch (Exception ignored) {}
        return pages;
    }

    private List<String> fetchRelPaths(String slugUrl, String num, String vol, String branch) {
        List<String> rel = new ArrayList<>();
        try {
            String path = "/manga/" + slashEncode(slugUrl) + "/chapter?number=" + urlEnc(num) + "&volume=" + urlEnc(vol);
            if (branch != null && !branch.isEmpty()) path += "&branch_id=" + urlEnc(branch);
            String json = httpGetWithFailover(path, true);
            Object root = Json.parse(json);
            if (!(root instanceof Map)) return rel;
            Object dataObj = ((Map<?, ?>) root).get("data");
            if (!(dataObj instanceof Map)) return rel;
            Object pagesObj = ((Map<?, ?>) dataObj).get("pages");
            if (!(pagesObj instanceof List)) return rel;
            for (Object p : (List<?>) pagesObj) {
                if (p instanceof Map) {
                    String u = str(((Map<?, ?>) p).get("url"));
                    if (!u.isEmpty()) rel.add(u);
                }
            }
        } catch (Exception ignored) {}
        return rel;
    }

    private void ensureServersDiscovered() {
        if (serversDiscovered.get()) return;
        serversDiscovered.set(true);
        new Thread(() -> {
            try {
                String json = httpGetWithFailover("/constants?fields[]=imageServers", true);
                Object root = Json.parse(json);
                if (root instanceof Map) {
                    Object data = ((Map<?, ?>) root).get("data");
                    if (data instanceof Map) {
                        Object serversList = ((Map<?, ?>) data).get("imageServers");
                        if (serversList instanceof List) {
                            List<String> discovered = new ArrayList<>();
                            for (Object o : (List<?>) serversList) {
                                if (o instanceof Map) {
                                    Map<?, ?> sm = (Map<?, ?>) o;
                                    String u = str(sm.get("url"));
                                    Object siteIds = sm.get("site_ids");
                                    boolean matchesSite = false;
                                    if (siteIds instanceof List) {
                                        for (Object id : (List<?>) siteIds) {
                                            if ("1".equals(str(id))) { matchesSite = true; break; }
                                        }
                                    }
                                    if (matchesSite && !u.isEmpty() && !discovered.contains(u)) {
                                        discovered.add(u);
                                    }
                                }
                            }
                            if (!discovered.isEmpty()) {
                                for (int i = discovered.size() - 1; i >= 0; i--) {
                                    String s = discovered.get(i);
                                    knownImageServers.remove(s);
                                    knownImageServers.add(0, s);
                                }
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}
        }, "MangaLibServerDiscovery").start();
    }

    private String httpGetWithFailover(String path, boolean siteScoped) throws IOException {
        String primaryUrl = MGL_API_PRIMARY + path;
        try {
            return executeGet(primaryUrl, MGL_ORIGIN, siteScoped ? "1" : null);
        } catch (Exception exPrimary) {
            String secondaryUrl = MGL_API_SECONDARY + path;
            return executeGet(secondaryUrl, MGL_ORIGIN, siteScoped ? "1" : null);
        }
    }

    private String executeGet(String urlStr, String originReferer, String siteId) throws IOException {
        URL url = URI.create(urlStr).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent", BROWSER_UA);
        conn.setRequestProperty("Accept", "application/json");

        if (originReferer != null && !originReferer.isEmpty()) {
            conn.setRequestProperty("Referer", originReferer + "/");
            conn.setRequestProperty("Origin", originReferer);
        }
        if (siteId != null && !siteId.isEmpty()) {
            conn.setRequestProperty("Site-Id", siteId);
        }

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

    private static String extractCover(Object coverObj) {
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
        if (obj instanceof List) {
            StringBuilder sb = new StringBuilder();
            for (Object o : (List<?>) obj) {
                String l = extractLabel(o);
                if (!l.isEmpty()) {
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(l);
                }
            }
            return sb.toString();
        }
        return str(obj);
    }

    private static String extractRating(Object ratingObj) {
        if (ratingObj instanceof Map) {
            String avg = str(((Map<?, ?>) ratingObj).get("average"));
            if (avg.isEmpty()) avg = str(((Map<?, ?>) ratingObj).get("averageFormated"));
            if (!avg.isEmpty()) return "★ " + avg;
        }
        return "★ —";
    }

    private static String firstOpenBranchId(Object branchesObj) {
        if (branchesObj instanceof List) {
            String firstAny = null;
            for (Object b : (List<?>) branchesObj) {
                if (b instanceof Map) {
                    Map<?, ?> bm = (Map<?, ?>) b;
                    String bid = str(bm.get("branch_id"));
                    if (bid.isEmpty()) bid = str(bm.get("id"));
                    if (bid.isEmpty()) continue;
                    Object open = bm.get("is_open");
                    if (open == null || Boolean.TRUE.equals(open)) return bid;
                    if (firstAny == null) firstAny = bid;
                }
            }
            if (firstAny != null) return firstAny;
        }
        return "";
    }

    private static String docText(Object node) {
        StringBuilder sb = new StringBuilder();
        collectDocText(node, sb);
        return sb.toString().trim();
    }

    private static void collectDocText(Object node, StringBuilder sb) {
        if (node instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) node;
            if ("text".equals(str(m.get("type")))) {
                String t = str(m.get("text"));
                if (!t.isEmpty()) {
                    if (sb.length() > 0 && !sb.toString().endsWith(" ")) sb.append(' ');
                    sb.append(t);
                }
            }
            Object content = m.get("content");
            if (content != null) collectDocText(content, sb);
            if ("paragraph".equals(str(m.get("type"))) || "hardBreak".equals(str(m.get("type")))) {
                sb.append(' ');
            }
        } else if (node instanceof List) {
            for (Object o : (List<?>) node) collectDocText(o, sb);
        }
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

    private static String urlEnc(String s) {
        if (s == null) return "";
        try {
            return URLEncoder.encode(s, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return s;
        }
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }
}
