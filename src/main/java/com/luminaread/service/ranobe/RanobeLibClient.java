package com.luminaread.service.ranobe;

import com.luminaread.model.ranobe.RanobeChapter;
import com.luminaread.model.ranobe.RanobeChapterContent;
import com.luminaread.model.ranobe.RanobeItem;
import com.luminaread.service.IMediaCacheService;
import com.luminaread.service.LibAccountService;
import com.luminaread.service.MediaCacheService;
import com.luminaread.util.HtmlSanitizer;
import com.luminaread.util.Json;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Client responsible for communicating with RanobeLib APIs (with failover support),
 * executing requests, parsing JSON responses, and rendering TipTap/HTML chapter content.
 */
public class RanobeLibClient {

    public static final String API_PRIMARY = "https://api.cdnlibs.org/api";
    public static final String API_SECONDARY = "https://api.imglib.info/api";
    public static final String RANOBE_ORIGIN = "https://ranobelib.me";
    public static final String BROWSER_UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";

    private final String primaryApiUrl;
    private final String secondaryApiUrl;
    private final IMediaCacheService mediaCacheService;
    private final LibAccountService libAccountService;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;

    public RanobeLibClient() {
        this(API_PRIMARY, API_SECONDARY, MediaCacheService.getInstance(), LibAccountService.getInstance(), 8000, 20000);
    }

    public RanobeLibClient(IMediaCacheService mediaCacheService) {
        this(API_PRIMARY, API_SECONDARY, mediaCacheService, LibAccountService.getInstance(), 8000, 20000);
    }

    public RanobeLibClient(String primaryApiUrl, String secondaryApiUrl,
                           IMediaCacheService mediaCacheService, LibAccountService libAccountService,
                           int connectTimeoutMs, int readTimeoutMs) {
        this.primaryApiUrl = (primaryApiUrl != null && !primaryApiUrl.isEmpty()) ? primaryApiUrl : API_PRIMARY;
        this.secondaryApiUrl = (secondaryApiUrl != null && !secondaryApiUrl.isEmpty()) ? secondaryApiUrl : API_SECONDARY;
        this.mediaCacheService = mediaCacheService != null ? mediaCacheService : MediaCacheService.getInstance();
        this.libAccountService = libAccountService != null ? libAccountService : LibAccountService.getInstance();
        this.connectTimeoutMs = connectTimeoutMs > 0 ? connectTimeoutMs : 8000;
        this.readTimeoutMs = readTimeoutMs > 0 ? readTimeoutMs : 20000;
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
                IMediaCacheService.CachedImageInfo cached = cacheIllustration(imgUrl);
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

    public String executeGetWithFailover(String path) throws Exception {
        try {
            return executeGet(primaryApiUrl + path);
        } catch (Exception e1) {
            return executeGet(secondaryApiUrl + path);
        }
    }

    public String executeGet(String urlStr) throws Exception {
        URL url = URI.create(urlStr).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent", BROWSER_UA);
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("Referer", RANOBE_ORIGIN + "/");
        conn.setRequestProperty("Origin", RANOBE_ORIGIN);
        conn.setRequestProperty("Site-Id", "3");

        if (libAccountService != null && libAccountService.isLoggedIn() && libAccountService.getCurrentUser().isTokenAuth) {
            conn.setRequestProperty("Authorization", "Bearer " + libAccountService.getCurrentUser().token);
        }

        conn.setConnectTimeout(connectTimeoutMs);
        conn.setReadTimeout(readTimeoutMs);

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

    public static IMediaCacheService.CachedImageInfo cacheIllustration(String urlStr) {
        if (urlStr == null || urlStr.isEmpty()) {
            return new IMediaCacheService.CachedImageInfo("", 0, 0);
        }
        return MediaCacheService.getInstance().cacheImageToDisk(urlStr, "ranobe", RANOBE_ORIGIN + "/", 680);
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

        IMediaCacheService.CachedImageInfo info = cacheIllustration(fullUrl);
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
            IMediaCacheService.CachedImageInfo info = cacheIllustration(fullUrl);
            int w = info.width > 0 ? Math.min(680, info.width) : 680;
            String replacement = "<img src='" + info.localUrl + "' width='" + w + "' style='border-radius:6px;' alt='Иллюстрация главы'/>";
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
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
}
