package com.luminaread.service;

import com.luminaread.util.HtmlSanitizer;
import com.luminaread.util.Json;

import javax.imageio.ImageIO;
import java.awt.Image;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MangaService implements IMangaService {

    private final IMediaCacheService mediaCacheService;

    public MangaService() {
        this(MediaCacheService.getInstance());
    }

    public MangaService(IMediaCacheService mediaCacheService) {
        this.mediaCacheService = mediaCacheService != null ? mediaCacheService : MediaCacheService.getInstance();
    }

    public static class MangaItem {
        public final String id;
        public final String title;
        public final String rusTitle;
        public final String description;
        public final String coverUrl;
        public final String source;
        public final String status;
        public final String rating;

        public MangaItem(String id, String title, String rusTitle, String description, String coverUrl, String source, String status, String rating) {
            this.id = id;
            this.title = title != null ? title : "";
            this.rusTitle = rusTitle != null ? rusTitle : "";
            this.description = description != null ? description : "";
            this.coverUrl = coverUrl != null ? coverUrl : "";
            this.source = source != null ? source : "";
            this.status = status != null ? status : "";
            this.rating = rating != null ? rating : "★ —";
        }

        public String getDisplayTitle() {
            return (!rusTitle.isEmpty()) ? rusTitle + " (" + title + ")" : title;
        }
    }

    public static class MangaChapter {
        public final String id;
        public final String number;
        public final String title;
        public final String language;
        public final String source;

        public MangaChapter(String id, String number, String title, String language, String source) {
            this.id = id;
            this.number = number != null ? number : "";
            this.title = title != null ? title : "";
            this.language = language != null ? language : "ru";
            this.source = source != null ? source : "";
        }

        @Override
        public String toString() {
            String numStr = number.isEmpty() ? "" : "Глава " + number;
            String tStr = (title != null && !title.isEmpty()) ? " — " + title : "";
            String langBadge = " [" + language.toUpperCase() + "]";
            return (numStr.isEmpty() && tStr.isEmpty()) ? "Глава" + langBadge : (numStr + tStr + langBadge).trim();
        }
    }

    public List<MangaItem> searchManga(String query, String sourceFilter) {
        List<MangaItem> results = new ArrayList<>();
        String q = (query == null || query.trim().isEmpty()) ? "" : query.trim();
        boolean all = sourceFilter == null || "Все источники".equals(sourceFilter);

        if (all || sourceFilter.contains("MangaLib")) {
            results.addAll(searchMangaLib(q));
        }
        if (all || sourceFilter.contains("Remanga")) {
            results.addAll(searchRemanga(q));
        }
        if (all || sourceFilter.contains("MangaDex")) {
            results.addAll(searchMangaDex(q));
        }
        return results;
    }

    private static final String MGL_API_PRIMARY = "https://api.cdnlibs.org/api";
    private static final String MGL_API_SECONDARY = "https://api.imglib.info/api";
    private static final String MGL_ORIGIN = "https://mangalib.me";
    private static final String MGL_SOURCE = "MangaLib (RU)";

    private static final List<String> KNOWN_MGL_IMAGE_SERVERS = new CopyOnWriteArrayList<>(Arrays.asList(
            "https://img3.cdnrulib.link",
            "https://img3.cdnlibs.org",
            "https://img4.cdnrulib.link",
            "https://img2.imglib.info",
            "https://img3.hentaicdn.org",
            "https://img2.hentaicdn.org"
    ));
    private static final AtomicBoolean SERVERS_DISCOVERED = new AtomicBoolean(false);

    private List<MangaItem> searchMangaLib(String query) {
        List<MangaItem> list = new ArrayList<>();
        try {
            ensureMangaLibServersDiscovered();
            String path = "/manga?page=1&site_id[]=1";
            if (!query.isEmpty()) {
                path += "&q=" + URLEncoder.encode(query, StandardCharsets.UTF_8.name());
            }
            String json = httpGetMangaLibWithFailover(path, false);
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
                String coverUrl = mangaLibCover(m.get("cover"));
                String status = mangaLibLabel(m.get("status"));
                String typeLabel = mangaLibLabel(m.get("type"));
                String rating = mangaLibRating(m.get("rating"));
                String desc = typeLabel.isEmpty() ? "Описание на странице тайтла."
                        : typeLabel + (str(m.get("releaseDateString")).isEmpty() ? "" : " • " + str(m.get("releaseDateString")));
                list.add(new MangaItem("mgl:" + slugUrl, title, rusName, desc, coverUrl, MGL_SOURCE, status, rating));
            }
        } catch (Exception ignored) {}
        return list;
    }

    private static String mangaLibCover(Object coverObj) {
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

    private static String mangaLibLabel(Object obj) {
        if (obj instanceof Map) return str(((Map<?, ?>) obj).get("label"));
        if (obj instanceof List) {
            StringBuilder sb = new StringBuilder();
            for (Object o : (List<?>) obj) {
                String l = mangaLibLabel(o);
                if (!l.isEmpty()) {
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(l);
                }
            }
            return sb.toString();
        }
        return str(obj);
    }

    private static String mangaLibRating(Object ratingObj) {
        if (ratingObj instanceof Map) {
            String avg = str(((Map<?, ?>) ratingObj).get("average"));
            if (avg.isEmpty()) avg = str(((Map<?, ?>) ratingObj).get("averageFormated"));
            if (!avg.isEmpty()) return "★ " + avg;
        }
        return "★ —";
    }

    private static final String REM_API = "https://api.remanga.org/api";
    private static final String REM_SOURCE = "Remanga (RU)";

    private List<MangaItem> searchRemanga(String query) {
        List<MangaItem> list = new ArrayList<>();
        try {
            String apiUrl = REM_API + "/search/?count=24";
            if (!query.isEmpty()) {
                apiUrl += "&query=" + URLEncoder.encode(query, StandardCharsets.UTF_8.name());
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
                String status = mangaLibLabel(m.get("status"));
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

    private List<MangaItem> searchMangaDex(String query) {
        List<MangaItem> list = new ArrayList<>();
        try {
            String apiUrl = "https://api.mangadex.org/manga?limit=24&includes[]=cover_art&order[followedCount]=desc";
            if (!query.isEmpty()) {
                apiUrl += "&title=" + URLEncoder.encode(query, StandardCharsets.UTF_8.name());
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
                list.add(new MangaItem(id, title, rusTitle, desc, coverUrl, "MangaDex", status, "★ MD"));
            }
        } catch (Exception ignored) {}
        return list;
    }

    public String fetchDescription(MangaItem manga) {
        if (manga == null) return "Описание отсутствует.";
        try {
            if (manga.source.startsWith("MangaLib")) {
                String slugUrl = manga.id.startsWith("mgl:") ? manga.id.substring(4) : manga.id;
                String json = httpGetMangaLibWithFailover("/manga/" + slashEncode(slugUrl) + "?fields[]=summary", true);
                Object root = Json.parse(json);
                if (root instanceof Map) {
                    Object dataObj = ((Map<?, ?>) root).get("data");
                    if (dataObj instanceof Map) {
                        String text = docText(((Map<?, ?>) dataObj).get("summary"));
                        if (!text.isEmpty()) return cleanDescription(text);
                    }
                }
            } else if (manga.source.startsWith("Remanga")) {
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
            }
        } catch (Exception ignored) {}
        return manga.description != null && !manga.description.isEmpty() ? manga.description : "Описание отсутствует.";
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
            if (content != null) {
                collectDocText(content, sb);
            }
            if ("paragraph".equals(str(m.get("type"))) || "hardBreak".equals(str(m.get("type")))) {
                sb.append(' ');
            }
        } else if (node instanceof List) {
            for (Object o : (List<?>) node) collectDocText(o, sb);
        }
    }

    public List<MangaChapter> fetchChapters(MangaItem manga) {
        List<MangaChapter> chapters = new ArrayList<>();
        if (manga == null) return chapters;
        try {
            if ("MangaDex".equals(manga.source)) {

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
                            chapters.add(new MangaChapter(chId, chNum, chTitle, lang, "MangaDex"));
                        }
                    }
                }
            } else if (manga.source.startsWith("MangaLib")) {
                ensureMangaLibServersDiscovered();
                String slugUrl = manga.id.startsWith("mgl:") ? manga.id.substring(4) : manga.id;
                String json = httpGetMangaLibWithFailover("/manga/" + slashEncode(slugUrl) + "/chapters", true);
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
            } else if (manga.source.startsWith("Remanga")) {
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
            }
        } catch (Exception ignored) {}
        return chapters;
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

    public List<String> fetchPageUrls(MangaChapter chapter) {
        List<String> pages = new ArrayList<>();
        if (chapter == null) return pages;
        try {
            if ("MangaDex".equals(chapter.source)) {
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
            } else if (chapter.source.startsWith("MangaLib")) {
                ensureMangaLibServersDiscovered();
                String[] parts = chapter.id.split("\\|", -1);
                String slugUrl = parts.length > 0 ? parts[0] : "";
                String vol = parts.length > 1 ? parts[1] : "";
                String num = parts.length > 2 ? parts[2] : "";
                String branch = parts.length > 3 ? parts[3] : "";

                List<String> relPaths = fetchMangaLibRelPaths(slugUrl, num, vol, branch);

                if (relPaths.isEmpty() && !branch.isEmpty()) {
                    relPaths = fetchMangaLibRelPaths(slugUrl, num, vol, "");
                }

                if (!relPaths.isEmpty()) {
                    String primaryServer = KNOWN_MGL_IMAGE_SERVERS.get(0);
                    for (String rel : relPaths) {
                        String clean = rel.replaceFirst("^//", "").replaceFirst("^/", "");
                        pages.add(primaryServer + "/" + clean);
                    }
                }
            } else if (chapter.source.startsWith("Remanga")) {
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
            }
        } catch (Exception ignored) {}
        return pages;
    }

    private static String normalizeRemangaUrl(String link) {
        if (link.startsWith("http://") || link.startsWith("https://")) return link;
        if (link.startsWith("//")) return "https:" + link;
        return "https://remanga.org" + (link.startsWith("/") ? "" : "/") + link;
    }

    private List<String> fetchMangaLibRelPaths(String slugUrl, String num, String vol, String branch) {
        List<String> rel = new ArrayList<>();
        try {
            String path = "/manga/" + slashEncode(slugUrl) + "/chapter?number=" + urlEnc(num) + "&volume=" + urlEnc(vol);
            if (branch != null && !branch.isEmpty()) path += "&branch_id=" + urlEnc(branch);
            String json = httpGetMangaLibWithFailover(path, true);
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

    public BufferedImage loadImageCached(String urlStr) {
        return mediaCacheService.loadImageCached(urlStr, KNOWN_MGL_IMAGE_SERVERS);
    }

    private static boolean isMangaLibUrl(String url) {
        return MediaCacheService.isMangaLibUrl(url);
    }

    private static String replaceHost(String urlStr, String newBase) {
        return MediaCacheService.replaceHost(urlStr, newBase);
    }

    private BufferedImage downloadAndDecode(String urlStr) {
        return mediaCacheService.downloadAndDecode(urlStr, null);
    }

    private byte[] downloadBytes(String urlStr) {
        return MediaCacheService.getInstance().downloadImageBytes(urlStr, null);
    }

    private static BufferedImage toBufferedImage(Image img) {
        return MediaCacheService.toBufferedImage(img);
    }

    private static BufferedImage tryDecodeWithSips(byte[] bytes) {
        return MediaCacheService.tryDecodeWithSips(bytes);
    }

    private String httpGet(String urlStr) throws IOException {
        return executeGet(urlStr, null, null);
    }

    private String httpGetMangaLibWithFailover(String path, boolean siteScoped) throws IOException {

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
        } else if (urlStr.contains("remanga.org")) {
            conn.setRequestProperty("Referer", "https://remanga.org/");
            conn.setRequestProperty("Origin", "https://remanga.org");
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

    private void ensureMangaLibServersDiscovered() {
        if (SERVERS_DISCOVERED.get()) return;
        SERVERS_DISCOVERED.set(true);
        new Thread(() -> {
            try {
                String json = httpGetMangaLibWithFailover("/constants?fields[]=imageServers", true);
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
                                    KNOWN_MGL_IMAGE_SERVERS.remove(s);
                                    KNOWN_MGL_IMAGE_SERVERS.add(0, s);
                                }
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}
        }, "MangaLibServerDiscovery").start();
    }

    private static final String BROWSER_UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";

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

    private static String str(Object o) {
        return o == null ? "" : o.toString();
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

    private static Path getMangaStateFile() {
        return Path.of(System.getProperty("user.home"), ".luminaread", "manga_state.json");
    }

    public synchronized void saveMangaState(Map<String, MangaItem> favorites,
                                           Map<String, MangaItem> history,
                                           Map<String, String> lastReadChapterMap) {
        saveMangaState(favorites, history, lastReadChapterMap, null);
    }

    public synchronized void saveMangaState(Map<String, MangaItem> favorites,
                                           Map<String, MangaItem> history,
                                           Map<String, String> lastReadChapterMap,
                                           Map<String, String> readerPrefs) {
        try {
            Path stateFile = getMangaStateFile();
            Files.createDirectories(stateFile.getParent());
            StringBuilder sb = new StringBuilder();
            sb.append("{\n");

            sb.append("  \"favorites\": [\n");
            int i = 0;
            for (MangaItem item : favorites.values()) {
                if (i++ > 0) sb.append(",\n");
                sb.append("    ").append(itemToJson(item));
            }
            sb.append("\n  ],\n");

            sb.append("  \"history\": [\n");
            i = 0;
            for (MangaItem item : history.values()) {
                if (i++ > 0) sb.append(",\n");
                sb.append("    ").append(itemToJson(item));
            }
            sb.append("\n  ],\n");

            sb.append("  \"progress\": {\n");
            i = 0;
            for (Map.Entry<String, String> e : lastReadChapterMap.entrySet()) {
                if (i++ > 0) sb.append(",\n");
                sb.append("    ").append(quote(e.getKey())).append(": ").append(quote(e.getValue()));
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
            Files.writeString(stateFile, sb.toString(), StandardCharsets.UTF_8);
        } catch (Exception ignored) {}
    }

    public synchronized void loadMangaState(Map<String, MangaItem> favorites,
                                           Map<String, MangaItem> history,
                                           Map<String, String> lastReadChapterMap) {
        loadMangaState(favorites, history, lastReadChapterMap, null);
    }

    public synchronized void loadMangaState(Map<String, MangaItem> favorites,
                                           Map<String, MangaItem> history,
                                           Map<String, String> lastReadChapterMap,
                                           Map<String, String> readerPrefs) {
        try {
            Path stateFile = getMangaStateFile();
            if (!Files.exists(stateFile)) return;
            String text = Files.readString(stateFile, StandardCharsets.UTF_8);
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
}
