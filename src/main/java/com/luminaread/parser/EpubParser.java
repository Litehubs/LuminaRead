package com.luminaread.parser;

import com.luminaread.model.BookContent;
import com.luminaread.model.Chapter;
import com.luminaread.util.CharsetDetector;
import com.luminaread.util.HtmlSanitizer;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

public class EpubParser implements BookParser {
    private static final Pattern IMG_SRC_PATTERN =
            Pattern.compile("(<img[^>]+src\\s*=\\s*[\"'])([^\"']+)([\"'][^>]*>)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SVG_IMAGE_PATTERN =
            Pattern.compile("<image[^>]+(?:xlink:href|href)\\s*=\\s*[\"']([^\"']+)[\"'][^>]*/?>", Pattern.CASE_INSENSITIVE);
    private static final Pattern H1_H3_PATTERN =
            Pattern.compile("<h[1-4][^>]*>(.*?)</h[1-4]>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    @Override
    public boolean supports(File file, String formatHint) {
        if (formatHint != null && "EPUB".equalsIgnoreCase(formatHint)) {
            return true;
        }
        if (file != null && file.getName().toLowerCase(Locale.ROOT).endsWith(".epub")) {
            return true;
        }
        return false;
    }

    @Override
    public String getSupportedFormat() {
        return "EPUB";
    }

    @Override
    public BookContent parse(File file) throws Exception {
        Map<String, byte[]> zipEntries = readZipEntriesRobust(file);

        if (zipEntries.isEmpty()) {
            return new TxtMarkdownParser().parse(file);
        }

        BookContent content = new BookContent();
        content.setTitle(stripExt(file.getName()));
        content.setAuthor("Неизвестный автор");

        Path imgCacheDir = getImageCacheDir(file.getName());
        Map<String, String> imageUriByEntryPath = new HashMap<>();
        for (Map.Entry<String, byte[]> e : zipEntries.entrySet()) {
            String path = e.getKey();
            String lower = path.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")
                    || lower.endsWith(".gif") || lower.endsWith(".svg") || lower.endsWith(".webp")) {
                if (!lower.endsWith(".svg")) {
                    content.putEmbeddedImage(path, e.getValue());
                    if (content.getCoverImage() == null && lower.contains("cover")) {
                        content.setCoverImage(e.getValue());
                    }
                }
                if (imgCacheDir != null && !lower.endsWith(".svg")) {
                    try {
                        String flatName = path.replace('/', '_').replace('\\', '_');
                        if (lower.endsWith(".webp")) {
                            flatName = flatName.replaceAll("(?i)\\.webp$", ".png");
                            var bi = com.luminaread.service.MediaCacheService.getInstance().decodeBytes(e.getValue());
                            Path target = imgCacheDir.resolve(flatName);
                            if (bi != null) {
                                javax.imageio.ImageIO.write(bi, "png", target.toFile());
                                imageUriByEntryPath.put(path, target.toUri().toString());
                            } else {
                                Files.write(target, e.getValue());
                                imageUriByEntryPath.put(path, target.toUri().toString());
                            }
                        } else {
                            Path target = imgCacheDir.resolve(flatName);
                            Files.write(target, e.getValue());
                            imageUriByEntryPath.put(path, target.toUri().toString());
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        }

        String opfPath = findOpfPath(zipEntries);
        List<String> spineFiles = new ArrayList<>();
        Map<String, String> tocTitlesByHref = new HashMap<>();

        if (opfPath != null && zipEntries.containsKey(opfPath)) {
            String opfDir = getParentDir(opfPath);
            String opfXml = cleanXmlProlog(CharsetDetector.decodeBytes(zipEntries.get(opfPath)));
            parseOpf(opfXml, opfDir, zipEntries, content, spineFiles, tocTitlesByHref);
        }

        if (spineFiles.isEmpty()) {
            for (String path : zipEntries.keySet()) {
                String lower = path.toLowerCase(Locale.ROOT);
                if (lower.endsWith(".xhtml") || lower.endsWith(".html") || lower.endsWith(".htm")
                        || (lower.endsWith(".xml") && !lower.endsWith("container.xml") && !lower.contains(".opf"))) {
                    if (!lower.contains("toc") && !lower.contains("nav")) {
                        spineFiles.add(path);
                    }
                }
            }
        }

        int chapterIndex = 0;
        for (String chapterPath : spineFiles) {
            byte[] chBytes = findEntryBytesFlexible(zipEntries, chapterPath);
            if (chBytes == null) continue;

            String rawXhtml = CharsetDetector.decodeBytes(chBytes);
            String chapterDir = getParentDir(chapterPath);

            rawXhtml = convertSvgImagesToImgTags(rawXhtml);
            String bodyHtml = HtmlSanitizer.sanitizeBodyFragment(rawXhtml);
            bodyHtml = rewriteImageSources(bodyHtml, chapterDir, imageUriByEntryPath);

            String plainText = HtmlSanitizer.htmlToPlainText(bodyHtml);
            boolean hasImg = bodyHtml.toLowerCase(Locale.ROOT).contains("<img");
            if (plainText.isBlank() && !hasImg) {
                continue;
            }

            String chTitle = tocTitlesByHref.get(chapterPath);
            if (chTitle == null || chTitle.isBlank()) {
                chTitle = extractHeadingFromHtml(rawXhtml);
            }
            if (chTitle == null || chTitle.isBlank()) {
                chTitle = "Глава " + (chapterIndex + 1);
            }

            if (plainText.isBlank() && hasImg) {
                plainText = "[" + chTitle + " — Иллюстрация]";
            }

            content.addChapter(new Chapter(chapterIndex++, chTitle, bodyHtml, plainText));
        }

        if (content.getChapters().isEmpty()) {
            for (Map.Entry<String, byte[]> entry : zipEntries.entrySet()) {
                String lower = entry.getKey().toLowerCase(Locale.ROOT);
                if (lower.endsWith(".xhtml") || lower.endsWith(".html") || lower.endsWith(".htm") || lower.endsWith(".xml")) {
                    if (lower.endsWith("container.xml") || lower.endsWith(".opf") || lower.endsWith(".ncx")) continue;
                    String raw = convertSvgImagesToImgTags(CharsetDetector.decodeBytes(entry.getValue()));
                    String body = rewriteImageSources(HtmlSanitizer.sanitizeBodyFragment(raw), getParentDir(entry.getKey()), imageUriByEntryPath);
                    String plain = HtmlSanitizer.htmlToPlainText(body);
                    if (!plain.isBlank() || body.toLowerCase(Locale.ROOT).contains("<img")) {
                        content.addChapter(new Chapter(chapterIndex++, "Раздел " + (chapterIndex), body,
                                plain.isBlank() ? "Иллюстрация" : plain));
                    }
                }
            }
        }

        if (content.getChapters().isEmpty() && !imageUriByEntryPath.isEmpty()) {
            List<String> sortedImgPaths = new ArrayList<>(imageUriByEntryPath.keySet());
            sortedImgPaths.sort(String.CASE_INSENSITIVE_ORDER);
            for (String imgKey : sortedImgPaths) {
                String uri = imageUriByEntryPath.get(imgKey);
                String pageTitle = "Страница " + (chapterIndex + 1);
                String html = "<div style='text-align:center; margin:10px 0;'><img src='" + uri + "'/></div>";
                content.addChapter(new Chapter(chapterIndex++, pageTitle, html, pageTitle));
            }
        }

        if (content.getCoverImage() == null && !content.getEmbeddedImages().isEmpty()) {
            content.setCoverImage(content.getEmbeddedImages().values().iterator().next());
        }

        return content;
    }

    private Map<String, byte[]> readZipEntriesRobust(File file) {
        Map<String, byte[]> zipEntries = new LinkedHashMap<>();

        Charset[] charsetsToTry = {
                StandardCharsets.UTF_8,
                Charset.isSupported("CP866") ? Charset.forName("CP866") : StandardCharsets.ISO_8859_1,
                Charset.isSupported("windows-1251") ? Charset.forName("windows-1251") : StandardCharsets.ISO_8859_1
        };

        for (Charset cs : charsetsToTry) {
            try (ZipFile zf = new ZipFile(file, cs)) {
                Enumeration<? extends ZipEntry> entries = zf.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (!entry.isDirectory()) {
                        try (InputStream is = zf.getInputStream(entry)) {
                            zipEntries.put(normalizePath(entry.getName()), is.readAllBytes());
                        }
                    }
                }
                if (!zipEntries.isEmpty()) {
                    return zipEntries;
                }
            } catch (Exception ignored) {
            }
        }

        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(file))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    zipEntries.put(normalizePath(entry.getName()), readEntryBytes(zis));
                }
            }
        } catch (Exception ignored) {
        }
        return zipEntries;
    }

    private byte[] findEntryBytesFlexible(Map<String, byte[]> zipEntries, String path) {
        byte[] exact = zipEntries.get(path);
        if (exact != null) return exact;
        for (Map.Entry<String, byte[]> e : zipEntries.entrySet()) {
            if (e.getKey().equalsIgnoreCase(path) || e.getKey().endsWith("/" + path)) {
                return e.getValue();
            }
        }
        return null;
    }

    private String convertSvgImagesToImgTags(String rawHtml) {
        if (rawHtml == null || !rawHtml.toLowerCase(Locale.ROOT).contains("<image")) {
            return rawHtml;
        }
        return SVG_IMAGE_PATTERN.matcher(rawHtml).replaceAll("<img src=\"$1\" />");
    }

    private String findOpfPath(Map<String, byte[]> zipEntries) {
        byte[] containerBytes = findEntryBytesFlexible(zipEntries, "META-INF/container.xml");
        if (containerBytes != null) {
            try {
                String xml = cleanXmlProlog(CharsetDetector.decodeBytes(containerBytes));
                Document doc = parseXml(xml);
                List<Element> rootfiles = findElementsByLocalName(doc.getDocumentElement(), "rootfile");
                if (!rootfiles.isEmpty()) {
                    String fullPath = rootfiles.get(0).getAttribute("full-path");
                    if (fullPath != null && !fullPath.isBlank()) {
                        return normalizePath(fullPath);
                    }
                }
            } catch (Exception ignored) {
            }
        }
        for (String key : zipEntries.keySet()) {
            if (key.toLowerCase(Locale.ROOT).endsWith(".opf")) {
                return key;
            }
        }
        return null;
    }

    private void parseOpf(String opfXml, String opfDir, Map<String, byte[]> zipEntries,
                          BookContent content, List<String> spineFiles, Map<String, String> tocTitlesByHref) {
        try {
            Document doc = parseXml(opfXml);
            Element root = doc.getDocumentElement();

            String title = findTagTextByLocalName(root, "title");
            if (!title.isBlank()) content.setTitle(title);

            String creator = findTagTextByLocalName(root, "creator");
            if (!creator.isBlank()) content.setAuthor(creator);

            String desc = findTagTextByLocalName(root, "description");
            if (!desc.isBlank()) content.setDescription(HtmlSanitizer.htmlToPlainText(desc));

            String lang = findTagTextByLocalName(root, "language");
            if (!lang.isBlank()) content.setLanguage(lang);

            Map<String, String> manifestById = new HashMap<>();
            String ncxPath = null;
            List<Element> items = findElementsByLocalName(root, "item");
            for (Element itemEl : items) {
                String id = itemEl.getAttribute("id");
                String href = itemEl.getAttribute("href");
                String mediaType = itemEl.getAttribute("media-type");
                String props = itemEl.getAttribute("properties");
                if (id != null && href != null && !href.isBlank()) {
                    int hashIdx = href.indexOf('#');
                    String cleanHref = hashIdx >= 0 ? href.substring(0, hashIdx) : href;
                    String resolved = resolveRelativePath(opfDir, decodeUrlPath(cleanHref));
                    manifestById.put(id, resolved);

                    if ("application/x-dtbncx+xml".equalsIgnoreCase(mediaType) || resolved.toLowerCase(Locale.ROOT).endsWith(".ncx")) {
                        ncxPath = resolved;
                    }
                    if (props != null && props.contains("cover-image")) {
                        byte[] cov = findEntryBytesFlexible(zipEntries, resolved);
                        if (cov != null) content.setCoverImage(cov);
                    }
                }
            }

            List<Element> itemrefs = findElementsByLocalName(root, "itemref");
            for (Element refEl : itemrefs) {
                String idref = refEl.getAttribute("idref");
                String targetPath = manifestById.get(idref);
                if (targetPath != null && !spineFiles.contains(targetPath)) {
                    spineFiles.add(targetPath);
                }
            }

            if (ncxPath != null) {
                byte[] ncxBytes = findEntryBytesFlexible(zipEntries, ncxPath);
                if (ncxBytes != null) {
                    parseNcxToc(cleanXmlProlog(CharsetDetector.decodeBytes(ncxBytes)), getParentDir(ncxPath), tocTitlesByHref);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void parseNcxToc(String ncxXml, String ncxDir, Map<String, String> tocTitlesByHref) {
        try {
            Document doc = parseXml(ncxXml);
            List<Element> navPoints = findElementsByLocalName(doc.getDocumentElement(), "navPoint");
            for (Element np : navPoints) {
                String label = findTagTextByLocalName(np, "text");
                List<Element> contents = findElementsByLocalName(np, "content");
                if (!contents.isEmpty()) {
                    String src = contents.get(0).getAttribute("src");
                    if (src != null && !src.isBlank()) {
                        int hash = src.indexOf('#');
                        String cleanSrc = hash >= 0 ? src.substring(0, hash) : src;
                        String fullPath = resolveRelativePath(ncxDir, decodeUrlPath(cleanSrc));
                        if (!label.isBlank() && !tocTitlesByHref.containsKey(fullPath)) {
                            tocTitlesByHref.put(fullPath, label);
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    private List<Element> findElementsByLocalName(Element root, String localName) {
        List<Element> matched = new ArrayList<>();
        if (root == null) return matched;
        NodeList all = root.getElementsByTagName("*");
        String suffix = ":" + localName.toLowerCase(Locale.ROOT);
        for (int i = 0; i < all.getLength(); i++) {
            Node n = all.item(i);
            if (n instanceof Element el) {
                String tag = el.getTagName().toLowerCase(Locale.ROOT);
                if (tag.equals(localName.toLowerCase(Locale.ROOT)) || tag.endsWith(suffix)) {
                    matched.add(el);
                }
            }
        }
        return matched;
    }

    private String findTagTextByLocalName(Element root, String localName) {
        List<Element> els = findElementsByLocalName(root, localName);
        for (Element el : els) {
            String txt = HtmlSanitizer.normalizeInlineWhitespace(el.getTextContent()).trim();
            if (!txt.isEmpty()) {
                return txt;
            }
        }
        return "";
    }

    private String rewriteImageSources(String html, String chapterDir, Map<String, String> imageUriByEntryPath) {
        Matcher m = IMG_SRC_PATTERN.matcher(html);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String rawSrc = m.group(2);
            String resolved = resolveRelativePath(chapterDir, decodeUrlPath(rawSrc));
            String fileUri = imageUriByEntryPath.get(resolved);
            if (fileUri == null) {
                String fileNameOnly = resolved.contains("/") ? resolved.substring(resolved.lastIndexOf('/') + 1) : resolved;
                for (Map.Entry<String, String> entry : imageUriByEntryPath.entrySet()) {
                    if (entry.getKey().endsWith(fileNameOnly)) {
                        fileUri = entry.getValue();
                        break;
                    }
                }
            }
            if (fileUri != null) {
                String replacement = "<div style='text-align:center; margin:10px 0;'><img src='" + fileUri + "'/></div>";
                m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
            } else {
                m.appendReplacement(sb, "");
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String extractHeadingFromHtml(String html) {
        Matcher m = H1_H3_PATTERN.matcher(html);
        if (m.find()) {
            String h = HtmlSanitizer.htmlToPlainText(m.group(1));
            if (!h.isBlank() && h.length() <= 120) {
                return h;
            }
        }
        return "";
    }

    private String cleanXmlProlog(String xml) {
        if (xml == null) return "";
        int firstLt = xml.indexOf('<');
        return firstLt > 0 ? xml.substring(firstLt) : xml;
    }

    private Document parseXml(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        factory.setValidating(false);
        try {
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        } catch (Exception ignored) {
        }
        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(new InputSource(new StringReader(xml)));
    }

    private byte[] readEntryBytes(ZipInputStream zis) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(16384);
        byte[] buf = new byte[8192];
        int r;
        while ((r = zis.read(buf)) != -1) {
            bos.write(buf, 0, r);
        }
        return bos.toByteArray();
    }

    private String normalizePath(String path) {
        if (path == null) return "";
        String p = path.replace('\\', '/');
        while (p.startsWith("/")) p = p.substring(1);
        List<String> parts = new ArrayList<>();
        for (String seg : p.split("/")) {
            if (seg.isEmpty() || ".".equals(seg)) continue;
            if ("..".equals(seg)) {
                if (!parts.isEmpty()) parts.remove(parts.size() - 1);
            } else {
                parts.add(seg);
            }
        }
        return String.join("/", parts);
    }

    private String getParentDir(String path) {
        int slash = path.lastIndexOf('/');
        return slash > 0 ? path.substring(0, slash) : "";
    }

    private String resolveRelativePath(String baseDir, String relativeHref) {
        if (baseDir == null || baseDir.isEmpty()) {
            return normalizePath(relativeHref);
        }
        return normalizePath(baseDir + "/" + relativeHref);
    }

    private String decodeUrlPath(String raw) {
        if (raw == null) return "";
        try {
            return java.net.URLDecoder.decode(raw, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return raw;
        }
    }

    private Path getImageCacheDir(String fileName) {
        try {
            String safeBook = fileName.replaceAll("[^a-zA-Z0-9._\\-]", "_");
            Path dir = Path.of(System.getProperty("user.home"), ".luminaread", "cache", "epub_" + safeBook);
            Files.createDirectories(dir);
            return dir;
        } catch (Exception e) {
            return null;
        }
    }

    private String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
