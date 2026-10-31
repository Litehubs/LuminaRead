package com.luminaread.service;

import com.luminaread.util.HtmlSanitizer;
import com.luminaread.util.Json;

import java.awt.Image;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RanobeService implements IRanobeService {

    private final IMediaCacheService mediaCacheService;

    public RanobeService() {
        this(MediaCacheService.getInstance());
    }

    public RanobeService(IMediaCacheService mediaCacheService) {
        this.mediaCacheService = mediaCacheService != null ? mediaCacheService : MediaCacheService.getInstance();
    }

    public static class RanobeItem {
        public final String id;
        public final String title;
        public final String rusTitle;
        public final String description;
        public final String coverUrl;
        public final String status;
        public final String rating;
        public final String releaseDate;
        public final List<String> genres;
        public final List<String> authors;

        public RanobeItem(String id, String title, String rusTitle, String description,
                          String coverUrl, String status, String rating, String releaseDate,
                          List<String> genres, List<String> authors) {
            this.id = id != null ? id : "";
            this.title = title != null ? title : "";
            this.rusTitle = rusTitle != null ? rusTitle : "";
            this.description = description != null ? description : "";
            this.coverUrl = coverUrl != null ? coverUrl : "";
            this.status = status != null ? status : "";
            this.rating = rating != null ? rating : "★ —";
            this.releaseDate = releaseDate != null ? releaseDate : "";
            this.genres = genres != null ? genres : Collections.emptyList();
            this.authors = authors != null ? authors : Collections.emptyList();
        }

        public String getDisplayTitle() {
            return (!rusTitle.isEmpty()) ? rusTitle + " (" + title + ")" : title;
        }

        public String getGenresString() {
            return String.join(", ", genres);
        }

        public String getAuthorsString() {
            return String.join(", ", authors);
        }
    }

    public static class RanobeChapter {
        public final String id;
        public final String volume;
        public final String number;
        public final String title;
        public final String branchId;
        public final String teamName;

        public RanobeChapter(String id, String volume, String number, String title, String branchId, String teamName) {
            this.id = id != null ? id : "";
            this.volume = volume != null ? volume : "1";
            this.number = number != null ? number : "1";
            this.title = title != null ? title : "";
            this.branchId = branchId != null ? branchId : "";
            this.teamName = teamName != null ? teamName : "";
        }

        @Override
        public String toString() {
            String numStr = "Том " + volume + ", Глава " + number;
            String tStr = (!title.isEmpty()) ? " — " + title : "";
            String teamStr = (!teamName.isEmpty()) ? " [" + teamName + "]" : "";
            return numStr + tStr + teamStr;
        }
    }

    public static class RanobeChapterContent {
        public final String title;
        public final String volume;
        public final String number;
        public final String rawHtmlContent;
        public final String plainTextContent;
        public final List<String> illustrations;

        public RanobeChapterContent(String title, String volume, String number,
                                    String rawHtmlContent, String plainTextContent, List<String> illustrations) {
            this.title = title != null ? title : "";
            this.volume = volume != null ? volume : "1";
            this.number = number != null ? number : "1";
            this.rawHtmlContent = rawHtmlContent != null ? rawHtmlContent : "";
            this.plainTextContent = plainTextContent != null ? plainTextContent : "";
            this.illustrations = illustrations != null ? illustrations : Collections.emptyList();
        }
    }

    private static final String API_PRIMARY = "https://api.cdnlibs.org/api";
    private static final String API_SECONDARY = "https://api.imglib.info/api";
    private static final String RANOBE_ORIGIN = "https://ranobelib.me";
    private static final String BROWSER_UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";

    private static Path getRanobeStateFile() {
        return Path.of(System.getProperty("user.home"), ".luminaread", "ranobe_state.json");
    }

    public List<RanobeItem> searchRanobe(String query) {
        List<RanobeItem> list = new ArrayList<>();
        try {
            String path = "/manga?page=1&site_id[]=3";
            if (query != null && !query.trim().isEmpty()) {
                path += "&q=" + URLEncoder.encode(query.trim(), StandardCharsets.UTF_8.name());
            }
            String json = executeGetWithFailover(path);
            Object root = Json.parse(json);
            if (!(root instanceof Map)) return list;
            Object dataObj = ((Map<?, ?>) root).get("data");
            if (!(dataObj instanceof List)) return list;

            for (Object o : (List<?>) dataObj) {
                if (!(o instanceof Map)) continue;
                Map<?, ?> m = (Map<?, ?>) o;
                String slugUrl = str(m.get("slug_url"));
                if (slugUrl.isEmpty()) {
                    String id = str(m.get("id"));
                    String slug = str(m.get("slug"));
                    slugUrl = !id.isEmpty() && !slug.isEmpty() ? id + "--" + slug : id;
                }
                if (slugUrl.isEmpty()) continue;

                String name = str(m.get("name"));
                String rusName = str(m.get("rus_name"));
                String title = name.isEmpty() ? rusName : name;
                String coverUrl = extractCoverUrl(m.get("cover"));
                String status = extractLabel(m.get("status"));
                String rating = extractRating(m.get("rating"));
                String releaseDate = str(m.get("releaseDateString"));

                String typeLabel = extractLabel(m.get("type"));
                String desc = typeLabel.isEmpty() ? "Описание на странице тайтла."
                        : typeLabel + (releaseDate.isEmpty() ? "" : " • " + releaseDate);

                list.add(new RanobeItem(slugUrl, title, rusName, desc, coverUrl, status, rating, releaseDate, null, null));
            }
        } catch (Exception ignored) {}
        return list;
    }

    public RanobeItem fetchRanobeDetails(RanobeItem basicItem) {
        if (basicItem == null || basicItem.id.isEmpty()) return basicItem;
        try {
            String path = "/manga/" + slashEncode(basicItem.id) + "?fields[]=summary&fields[]=genres&fields[]=tags&fields[]=authors";
            String json = executeGetWithFailover(path);
            Object root = Json.parse(json);
            if (!(root instanceof Map)) return basicItem;
            Object dataObj = ((Map<?, ?>) root).get("data");
            if (!(dataObj instanceof Map)) return basicItem;
            Map<?, ?> d = (Map<?, ?>) dataObj;

            String fullDesc = docText(d.get("summary"));
            if (fullDesc.isEmpty()) fullDesc = basicItem.description;

            List<String> genres = new ArrayList<>();
            Object gObj = d.get("genres");
            if (gObj instanceof List) {
                for (Object g : (List<?>) gObj) {
                    if (g instanceof Map) {
                        String gn = str(((Map<?, ?>) g).get("name"));
                        if (!gn.isEmpty()) genres.add(gn);
                    }
                }
            }

            List<String> authors = new ArrayList<>();
            Object aObj = d.get("authors");
            if (aObj instanceof List) {
                for (Object a : (List<?>) aObj) {
                    if (a instanceof Map) {
                        String an = str(((Map<?, ?>) a).get("name"));
                        if (!an.isEmpty()) authors.add(an);
                    }
                }
            }

            String releaseDate = str(d.get("releaseDateString"));
            if (releaseDate.isEmpty()) releaseDate = basicItem.releaseDate;

            return new RanobeItem(
                    basicItem.id, basicItem.title, basicItem.rusTitle, fullDesc,
                    basicItem.coverUrl, basicItem.status, basicItem.rating, releaseDate,
                    genres, authors
            );
        } catch (Exception ignored) {}
        return basicItem;
    }

    public List<RanobeChapter> fetchChapters(String slugUrl) {
        List<RanobeChapter> chapters = new ArrayList<>();
        if (slugUrl == null || slugUrl.isEmpty()) return chapters;
        try {
            String path = "/manga/" + slashEncode(slugUrl) + "/chapters";
            String json = executeGetWithFailover(path);
            Object root = Json.parse(json);
            if (!(root instanceof Map)) return chapters;
            Object dataObj = ((Map<?, ?>) root).get("data");
            if (!(dataObj instanceof List)) return chapters;

            for (Object o : (List<?>) dataObj) {
                if (!(o instanceof Map)) continue;
                Map<?, ?> c = (Map<?, ?>) o;
                String vol = str(c.get("volume"));
                if (vol.isEmpty()) vol = "1";
                String num = str(c.get("number"));
                if (num.isEmpty()) num = str(c.get("item_number"));
                if (num.isEmpty()) continue;

                String chTitle = str(c.get("name"));
                String branchId = extractFirstOpenBranchId(c.get("branches"));
                String teamName = extractFirstTeamName(c.get("branches"));
                String chId = slugUrl + "|" + vol + "|" + num + "|" + branchId;

                chapters.add(new RanobeChapter(chId, vol, num, chTitle, branchId, teamName));
            }
        } catch (Exception ignored) {}
        return chapters;
    }

    private static String extractFirstOpenBranchId(Object branchesObj) {
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

    private static String extractFirstTeamName(Object branchesObj) {
        if (branchesObj instanceof List) {
            for (Object b : (List<?>) branchesObj) {
                if (b instanceof Map) {
                    Object teams = ((Map<?, ?>) b).get("teams");
                    if (teams instanceof List && !((List<?>) teams).isEmpty()) {
                        Object firstTeam = ((List<?>) teams).get(0);
                        if (firstTeam instanceof Map) {
                            String name = str(((Map<?, ?>) firstTeam).get("name"));
                            if (!name.isEmpty()) return name;
                        }
                    }
                }
            }
        }
        return "";
    }

    public RanobeChapterContent fetchChapterContent(RanobeChapter chapter, String slugUrl) throws Exception {
        if (chapter == null) throw new IllegalArgumentException("Глава не выбрана.");

        String cleanSlug = slugUrl;
        if (cleanSlug == null || cleanSlug.isEmpty()) {
            String[] p = chapter.id.split("\\|", -1);
            if (p.length > 0) cleanSlug = p[0];
        }

        String path = "/manga/" + slashEncode(cleanSlug) + "/chapter?number="
                + urlEnc(chapter.number) + "&volume=" + urlEnc(chapter.volume);
        if (chapter.branchId != null && !chapter.branchId.isEmpty()) {
            path += "&branch_id=" + urlEnc(chapter.branchId);
        }

        String json = executeGetWithFailover(path);
        Object root = Json.parse(json);
        if (!(root instanceof Map)) throw new Exception("Некорректный ответ сервера при загрузке главы.");
        Object dataObj = ((Map<?, ?>) root).get("data");
        if (!(dataObj instanceof Map)) throw new Exception("Текст главы не найден на сервере.");
        Map<?, ?> d = (Map<?, ?>) dataObj;

        String chTitle = str(d.get("name"));
        if (chTitle.isEmpty()) chTitle = chapter.title;

        List<String> illustrations = new ArrayList<>();
        Set<String> renderedImages = new HashSet<>();

        Object contentObj = d.get("content");
        String docHtml = "";

        if (contentObj instanceof Map || contentObj instanceof List) {
            docHtml = docToHtml(contentObj, illustrations, renderedImages);
        } else if (contentObj instanceof String) {
            String strContent = ((String) contentObj).trim();
            if (strContent.startsWith("{") || strContent.startsWith("[")) {
                try {
                    Object parsed = Json.parse(strContent);
                    if (parsed instanceof Map || parsed instanceof List) {
                        docHtml = docToHtml(parsed, illustrations, renderedImages);
                    }
                } catch (Exception ignored) {}
            }
            if (docHtml.isEmpty()) {
                if (strContent.contains("<p") || strContent.contains("<div") || strContent.contains("<br")) {
                    docHtml = processHtmlImages(strContent, illustrations, renderedImages);
                } else if (!strContent.isEmpty()) {
                    StringBuilder sb = new StringBuilder();
                    String[] lines = strContent.split("\r?\n");
                    for (String line : lines) {
                        String trimmed = line.trim();
                        if (!trimmed.isEmpty()) {
                            sb.append("<p>").append(escapeHtml(trimmed)).append("</p>\n");
                        }
                    }
                    docHtml = sb.toString().trim();
                }
            }
        }

        if (docHtml.isEmpty()) {
            String altText = docText(contentObj);
            if (!altText.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (String line : altText.split("\r?\n")) {
                    String t = line.trim();
                    if (!t.isEmpty()) sb.append("<p>").append(escapeHtml(t)).append("</p>\n");
                }
                docHtml = sb.toString().trim();
            }
        }

        Object attObj = d.get("attachments");
        if (attObj instanceof List) {
            for (Object a : (List<?>) attObj) {
                if (a instanceof Map) {
                    Map<?, ?> am = (Map<?, ?>) a;
                    String u = str(am.get("url"));
                    if (u.isEmpty()) u = str(am.get("path"));
                    if (u.isEmpty()) u = str(am.get("src"));
                    if (!u.isEmpty()) {
                        String fullImg = u.startsWith("http") ? u : "https://cover.imglib.info" + (u.startsWith("/") ? "" : "/") + u;
                        if (!illustrations.contains(fullImg)) {
                            illustrations.add(fullImg);
                        }
                    }
                }
            }
        }

        StringBuilder formattedHtml = new StringBuilder();

        for (String imgUrl : illustrations) {
            if (!renderedImages.contains(imgUrl)) {
                CachedImageInfo cached = cacheIllustration(imgUrl);
                int w = cached.width > 0 ? Math.min(680, cached.width) : 680;
                formattedHtml.append("<p style='text-align:center; margin:18px 0;'>")
                        .append("<img src='").append(cached.localUrl).append("' width='").append(w)
                        .append("' style='border-radius:6px;' alt='Иллюстрация главы'/></p>\n");
            }
        }

        formattedHtml.append(docHtml);

        String finalHtml = formattedHtml.toString().trim();
        if (finalHtml.isEmpty()) {
            finalHtml = "<p><em>Текст данной главы отсутствует или находится в обработке.</em></p>";
        }

        finalHtml = finalHtml.replaceAll("<p>\\s*</p>", "");

        String plainText = HtmlSanitizer.htmlToPlainText(finalHtml);

        return new RanobeChapterContent(chTitle, chapter.volume, chapter.number,
                finalHtml, plainText, illustrations);
    }

    private String executeGetWithFailover(String path) throws Exception {
        try {
            return executeGet(API_PRIMARY + path);
        } catch (Exception e1) {
            return executeGet(API_SECONDARY + path);
        }
    }

    private String executeGet(String urlStr) throws Exception {
        URL url = URI.create(urlStr).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent", BROWSER_UA);
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("Referer", RANOBE_ORIGIN + "/");
        conn.setRequestProperty("Origin", RANOBE_ORIGIN);
        conn.setRequestProperty("Site-Id", "3");

        LibAccountService acc = LibAccountService.getInstance();
        if (acc.isLoggedIn() && acc.getCurrentUser().isTokenAuth) {
            conn.setRequestProperty("Authorization", "Bearer " + acc.getCurrentUser().token);
        }

        conn.setConnectTimeout(8000);
        conn.setReadTimeout(20000);

        int code = conn.getResponseCode();
        InputStream is = (code >= 200 && code < 400) ? conn.getInputStream() : conn.getErrorStream();
        if (is == null) throw new Exception("HTTP error " + code);

        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            if (code >= 400) throw new Exception("HTTP " + code + ": " + sb);
            return sb.toString();
        }
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

    private static String docText(Object node) {
        StringBuilder sb = new StringBuilder();
        collectDocText(node, sb);
        return sb.toString().trim();
    }

    private static void collectDocText(Object node, StringBuilder sb) {
        if (node == null) return;
        if (node instanceof String) {
            String s = ((String) node).trim();
            if (s.startsWith("{") || s.startsWith("[")) {
                try {
                    Object parsed = Json.parse(s);
                    if (parsed instanceof Map || parsed instanceof List) {
                        collectDocText(parsed, sb);
                        return;
                    }
                } catch (Exception ignored) {}
            }
            if (sb.length() > 0 && !sb.toString().endsWith(" ") && !sb.toString().endsWith("\n")) sb.append(' ');
            sb.append(HtmlSanitizer.htmlToPlainText(s));
            return;
        }
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
                sb.append('\n');
            }
        } else if (node instanceof List) {
            for (Object o : (List<?>) node) collectDocText(o, sb);
        }
    }

    public static class CachedImageInfo extends IMediaCacheService.CachedImageInfo {
        public CachedImageInfo(String localUrl, int width, int height) {
            super(localUrl, width, height);
        }

        public CachedImageInfo(IMediaCacheService.CachedImageInfo info) {
            super(info != null ? info.localUrl : "", info != null ? info.width : 0, info != null ? info.height : 0);
        }
    }

    public static String docToHtml(Object node, List<String> illustrations, Set<String> renderedImages) {
        if (node == null) return "";
        StringBuilder sb = new StringBuilder();
        renderDocNode(node, sb, illustrations, renderedImages);
        return sb.toString().trim();
    }

    private static void renderDocNode(Object node, StringBuilder out, List<String> illustrations, Set<String> renderedImages) {
        if (node == null) return;
        if (node instanceof List) {
            for (Object child : (List<?>) node) {
                renderDocNode(child, out, illustrations, renderedImages);
            }
            return;
        }
        if (!(node instanceof Map)) {
            String s = node.toString().trim();
            if (!s.isEmpty()) {
                out.append("<p>").append(escapeHtml(s)).append("</p>\n");
            }
            return;
        }

        Map<?, ?> map = (Map<?, ?>) node;
        String type = str(map.get("type")).toLowerCase();
        Object contentObj = map.get("content");
        Map<?, ?> attrs = (map.get("attrs") instanceof Map) ? (Map<?, ?>) map.get("attrs") : null;

        if ("doc".equals(type)) {
            if (contentObj != null) {
                renderDocNode(contentObj, out, illustrations, renderedImages);
            }
        } else if ("paragraph".equals(type)) {
            StringBuilder pBuf = new StringBuilder();
            if (contentObj != null) {
                renderInlineNodes(contentObj, pBuf, illustrations, renderedImages);
            }
            String pContent = pBuf.toString().trim();
            if (!pContent.isEmpty()) {
                String align = attrs != null ? str(attrs.get("textAlign")) : "";
                if (align.isEmpty() && attrs != null) align = str(attrs.get("align"));
                if (!align.isEmpty()) {
                    out.append("<p style='text-align:").append(align).append(";'>").append(pContent).append("</p>\n");
                } else {
                    out.append("<p>").append(pContent).append("</p>\n");
                }
            }
        } else if ("heading".equals(type)) {
            int level = 2;
            if (attrs != null && attrs.get("level") != null) {
                try { level = Integer.parseInt(str(attrs.get("level"))); } catch (Exception ignored) {}
            }
            if (level < 1) level = 1;
            if (level > 4) level = 4;
            StringBuilder hBuf = new StringBuilder();
            if (contentObj != null) {
                renderInlineNodes(contentObj, hBuf, illustrations, renderedImages);
            }
            String hText = hBuf.toString().trim();
            if (!hText.isEmpty()) {
                out.append("<h").append(level).append(">").append(hText).append("</h").append(level).append(">\n");
            }
        } else if ("blockquote".equals(type)) {
            out.append("<blockquote>\n");
            if (contentObj != null) {
                renderDocNode(contentObj, out, illustrations, renderedImages);
            }
            out.append("</blockquote>\n");
        } else if ("bulletlist".equals(type) || "bullet_list".equals(type)) {
            out.append("<ul>\n");
            if (contentObj != null) renderDocNode(contentObj, out, illustrations, renderedImages);
            out.append("</ul>\n");
        } else if ("orderedlist".equals(type) || "ordered_list".equals(type)) {
            out.append("<ol>\n");
            if (contentObj != null) renderDocNode(contentObj, out, illustrations, renderedImages);
            out.append("</ol>\n");
        } else if ("listitem".equals(type) || "list_item".equals(type)) {
            out.append("<li>");
            if (contentObj != null) renderDocNode(contentObj, out, illustrations, renderedImages);
            out.append("</li>\n");
        } else if ("codeblock".equals(type) || "code_block".equals(type)) {
            StringBuilder cBuf = new StringBuilder();
            if (contentObj != null) renderInlineNodes(contentObj, cBuf, illustrations, renderedImages);
            out.append("<pre><code>").append(cBuf).append("</code></pre>\n");
        } else if ("horizontalrule".equals(type) || "rule".equals(type)) {
            out.append("<hr/>\n");
        } else if ("hardbreak".equals(type)) {
            out.append("<br/>");
        } else if ("image".equals(type)) {
            renderImageBlock(attrs, out, illustrations, renderedImages);
        } else if ("text".equals(type)) {
            renderTextNode(map, out);
        } else {
            if (contentObj != null) {
                renderDocNode(contentObj, out, illustrations, renderedImages);
            }
        }
    }

    private static void renderInlineNodes(Object node, StringBuilder out, List<String> illustrations, Set<String> renderedImages) {
        if (node == null) return;
        if (node instanceof List) {
            for (Object item : (List<?>) node) {
                renderInlineNodes(item, out, illustrations, renderedImages);
            }
            return;
        }
        if (!(node instanceof Map)) {
            out.append(escapeHtml(node.toString()));
            return;
        }

        Map<?, ?> map = (Map<?, ?>) node;
        String type = str(map.get("type")).toLowerCase();

        if ("text".equals(type)) {
            renderTextNode(map, out);
        } else if ("hardbreak".equals(type)) {
            out.append("<br/>");
        } else if ("image".equals(type)) {
            out.append("</p>");
            Map<?, ?> attrs = (map.get("attrs") instanceof Map) ? (Map<?, ?>) map.get("attrs") : null;
            renderImageBlock(attrs, out, illustrations, renderedImages);
            out.append("<p>");
        } else {
            Object content = map.get("content");
            if (content != null) {
                renderInlineNodes(content, out, illustrations, renderedImages);
            }
        }
    }

    private static void renderTextNode(Map<?, ?> map, StringBuilder out) {
        String text = str(map.get("text"));
        if (text.isEmpty()) return;

        String escaped = escapeHtml(text);
        Object marksObj = map.get("marks");
        if (marksObj instanceof List) {
            for (Object m : (List<?>) marksObj) {
                if (m instanceof Map) {
                    String mType = str(((Map<?, ?>) m).get("type")).toLowerCase();
                    if ("bold".equals(mType) || "strong".equals(mType)) {
                        escaped = "<b>" + escaped + "</b>";
                    } else if ("italic".equals(mType) || "em".equals(mType)) {
                        escaped = "<i>" + escaped + "</i>";
                    } else if ("underline".equals(mType)) {
                        escaped = "<u>" + escaped + "</u>";
                    } else if ("strike".equals(mType) || "strikethrough".equals(mType) || "s".equals(mType)) {
                        escaped = "<s>" + escaped + "</s>";
                    } else if ("code".equals(mType)) {
                        escaped = "<code>" + escaped + "</code>";
                    } else if ("link".equals(mType)) {
                        Map<?, ?> mAttrs = (((Map<?, ?>) m).get("attrs") instanceof Map) ? (Map<?, ?>) ((Map<?, ?>) m).get("attrs") : null;
                        String href = mAttrs != null ? str(mAttrs.get("href")) : "";
                        if (!href.isEmpty()) {
                            escaped = "<a href='" + escapeHtml(href) + "'>" + escaped + "</a>";
                        }
                    }
                }
            }
        }
        out.append(escaped);
    }

    private static void renderImageBlock(Map<?, ?> attrs, StringBuilder out, List<String> illustrations, Set<String> renderedImages) {
        if (attrs == null) return;
        String src = str(attrs.get("src"));
        if (src.isEmpty()) src = str(attrs.get("url"));
        if (src.isEmpty()) return;

        String fullUrl = src.startsWith("http") ? src : "https://cover.imglib.info" + (src.startsWith("/") ? "" : "/") + src;
        if (illustrations != null && !illustrations.contains(fullUrl)) {
            illustrations.add(fullUrl);
        }
        if (renderedImages != null) {
            renderedImages.add(fullUrl);
            renderedImages.add(src);
        }

        CachedImageInfo info = cacheIllustration(fullUrl);
        int w = info.width > 0 ? Math.min(680, info.width) : 680;
        out.append("<p style='text-align:center; margin:18px 0;'>")
                .append("<img src='").append(info.localUrl).append("' width='").append(w)
                .append("' style='border-radius:6px;' alt='Иллюстрация главы'/></p>\n");
    }

    private static String processHtmlImages(String html, List<String> illustrations, Set<String> renderedImages) {
        if (html == null || !html.contains("<img")) return html;
        Pattern imgPat = Pattern.compile("<img[^>]+src=['\"]([^'\"]+)['\"][^>]*>", Pattern.CASE_INSENSITIVE);
        Matcher m = imgPat.matcher(html);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String src = m.group(1);
            String fullUrl = src.startsWith("http") ? src : "https://cover.imglib.info" + (src.startsWith("/") ? "" : "/") + src;
            if (illustrations != null && !illustrations.contains(fullUrl)) {
                illustrations.add(fullUrl);
            }
            if (renderedImages != null) {
                renderedImages.add(fullUrl);
                renderedImages.add(src);
            }
            CachedImageInfo info = cacheIllustration(fullUrl);
            int w = info.width > 0 ? Math.min(680, info.width) : 680;
            String replacement = "<img src='" + info.localUrl + "' width='" + w + "' style='border-radius:6px;' alt='Иллюстрация главы'/>";
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    public static CachedImageInfo cacheIllustration(String urlStr) {
        if (urlStr == null || urlStr.isEmpty()) {
            return new CachedImageInfo("", 0, 0);
        }
        IMediaCacheService.CachedImageInfo info = MediaCacheService.getInstance()
                .cacheImageToDisk(urlStr, "ranobe", RANOBE_ORIGIN + "/", 680);
        return new CachedImageInfo(info.localUrl, info.width, info.height);
    }

    private static byte[] downloadImageBytes(String urlStr) {
        return MediaCacheService.getInstance().downloadImageBytes(urlStr, RANOBE_ORIGIN + "/");
    }

    private static BufferedImage tryDecodeWithSips(byte[] bytes) {
        return MediaCacheService.tryDecodeWithSips(bytes);
    }

    private static BufferedImage toBufferedImage(Image img) {
        return MediaCacheService.toBufferedImage(img);
    }

    private static String hashUrl(String url) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(url.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(url.hashCode());
        }
    }

    private static String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
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

    public synchronized void saveRanobeState(Map<String, RanobeItem> favorites,
                                            Map<String, RanobeItem> history,
                                            Map<String, String> lastReadChapterMap,
                                            Map<String, String> readerPrefs) {
        try {
            Path stateFile = getRanobeStateFile();
            Files.createDirectories(stateFile.getParent());
            StringBuilder sb = new StringBuilder();
            sb.append("{\n");

            sb.append("  \"favorites\": [\n");
            int i = 0;
            for (RanobeItem item : favorites.values()) {
                if (i++ > 0) sb.append(",\n");
                sb.append("    ").append(itemToJson(item));
            }
            sb.append("\n  ],\n");

            sb.append("  \"history\": [\n");
            i = 0;
            for (RanobeItem item : history.values()) {
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

    public synchronized void loadRanobeState(Map<String, RanobeItem> favorites,
                                            Map<String, RanobeItem> history,
                                            Map<String, String> lastReadChapterMap,
                                            Map<String, String> readerPrefs) {
        try {
            Path stateFile = getRanobeStateFile();
            if (!Files.exists(stateFile)) return;
            String text = Files.readString(stateFile, StandardCharsets.UTF_8);
            Object root = Json.parse(text);
            if (!(root instanceof Map)) return;
            Map<?, ?> map = (Map<?, ?>) root;

            Object favObj = map.get("favorites");
            if (favObj instanceof List && favorites != null) {
                favorites.clear();
                for (Object o : (List<?>) favObj) {
                    RanobeItem it = itemFromJson(o);
                    if (it != null && !it.id.isEmpty()) favorites.put(it.id, it);
                }
            }

            Object histObj = map.get("history");
            if (histObj instanceof List && history != null) {
                history.clear();
                for (Object o : (List<?>) histObj) {
                    RanobeItem it = itemFromJson(o);
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

    private static RanobeItem itemFromJson(Object obj) {
        if (!(obj instanceof Map)) return null;
        Map<?, ?> m = (Map<?, ?>) obj;
        return new RanobeItem(
                str(m.get("id")),
                str(m.get("title")),
                str(m.get("rusTitle")),
                str(m.get("description")),
                str(m.get("coverUrl")),
                str(m.get("status")),
                str(m.get("rating")),
                str(m.get("releaseDate")),
                null, null
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
