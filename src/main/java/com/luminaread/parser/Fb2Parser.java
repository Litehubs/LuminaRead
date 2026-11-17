package com.luminaread.parser;

import com.luminaread.model.Book;
import com.luminaread.model.BookContent;
import com.luminaread.model.Chapter;
import com.luminaread.util.CharsetDetector;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class Fb2Parser implements BookParser {

    private static final int MAX_FB2_BYTES = 64 * 1024 * 1024;
    private static final int MAX_ZIP_ENTRIES = 10000;

    private static final Pattern BINARY_PATTERN = Pattern.compile(
            "<binary\\b([^>]*)>(.*?)</binary>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern ATTR_ID =
            Pattern.compile("\\bid\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern ATTR_TYPE =
            Pattern.compile("\\bcontent-type\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);

    private static final Pattern IMAGE_REF_PATTERN = Pattern.compile(
            "<image\\b[^>]*?(?:xlink:href|l:href|href)\\s*=\\s*[\"']#([^\"']+)[\"'][^>]*?/?>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Set<String> ALLOWED_TAGS = Set.of(
            "p", "h3", "h4", "em", "strong", "b", "i", "s", "br",
            "img", "div", "code", "sub", "sup", "blockquote", "a", "table", "tr", "th", "td");

    @Override
    public boolean supports(File file, String formatHint) {
        if (formatHint != null) {
            String upper = formatHint.toUpperCase(Locale.ROOT);
            if (upper.equals("FB2") || upper.equals("FB2.ZIP") || upper.equals("FBZ")) {
                return true;
            }
        }
        if (file != null) {
            String name = file.getName().toLowerCase(Locale.ROOT);
            if (name.endsWith(".fb2") || name.endsWith(".fb2.zip") || name.endsWith(".fbz")) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String getSupportedFormat() {
        return "FB2";
    }

    public BookContent parse(Book book) throws Exception {
        return parse(new File(book.getFilePath()));
    }

    @Override
    public BookContent parse(File file) throws Exception {
        if (file == null || !file.exists()) {
            throw new FileNotFoundException("Файл книги не найден: "
                    + (file != null ? file.getAbsolutePath() : "null"));
        }

        byte[] rawBytes = readActualFb2Bytes(file);
        String xml = CharsetDetector.decodeBytes(rawBytes);

        String bookTitle = file.getName().replaceFirst("[.][^.]+$", "");
        String bookAuthor = "Неизвестный автор";

        Matcher titleM = Pattern.compile("<book-title[^>]*>(.*?)</book-title>",
                Pattern.DOTALL | Pattern.CASE_INSENSITIVE).matcher(xml);
        if (titleM.find()) {
            String t = cleanTags(titleM.group(1)).trim();
            if (!t.isEmpty()) bookTitle = t;
        }

        String lowerSample = xml.substring(0, Math.min(xml.length(), 1500)).toLowerCase(Locale.ROOT);
        if ((lowerSample.contains("<!doctype html") || lowerSample.contains("<html"))
                && !lowerSample.contains("<fictionbook")) {
            String cleanHtml = stripScriptsAndStyles(xml);
            String msg = "Внимание! Онлайн-каталог вместо файла книги вернул веб-страницу "
                    + "с JavaScript-защитой (Cloudflare/DDoS-Guard).\n\n"
                    + "Пожалуйста, скачайте другое издание этой книги из списка "
                    + "(или выберите формат EPUB).\n\n"
                    + "--- Очищенный ответ сервера ---\n" + cleanTags(cleanHtml).trim();
            BookContent errContent = new BookContent();
            errContent.addChapter(new Chapter(0, "Ошибка источника (Защита от ботов)",
                    "<p>" + escapeSimple(msg).replace("\n", "<br/>") + "</p>", msg));
            errContent.setTitle(bookTitle);
            errContent.setAuthor(bookAuthor);
            return errContent;
        }

        BookContent content = new BookContent();

        Map<String, String> imageUriById = extractAndCacheImages(xml, content);

        xml = stripScriptsStylesAndBinaries(xml);

        List<Chapter> chapters = new ArrayList<>();

        Pattern bodyPattern = Pattern.compile("<body[^>]*>(.*?)</body>",
                Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
        Matcher bodyMatcher = bodyPattern.matcher(xml);
        List<String> bodies = new ArrayList<>();
        while (bodyMatcher.find()) bodies.add(bodyMatcher.group(1));
        String bodyContent = bodies.isEmpty() ? xml : String.join("\n", bodies);

        int index = 0;
        for (String sectionXml : topLevelSections(bodyContent)) {
            String title = extractTitle(sectionXml, "Глава " + (index + 1));
            String html = fb2SectionToHtml(sectionXml, imageUriById);
            String text = cleanFb2MarkupToText(sectionXml);
            boolean hasImg = html.toLowerCase(Locale.ROOT).contains("<img");
            if (!text.isBlank() || hasImg) {
                if (text.isBlank() && hasImg) {
                    text = "[" + title + " — Иллюстрация]";
                }
                chapters.add(new Chapter(index, title, html, text));
                index++;
            }
        }

        if (chapters.isEmpty()) {
            String fallbackHtml = fb2SectionToHtml(bodyContent, imageUriById);
            String fallbackText = cleanFb2MarkupToText(bodyContent);
            boolean fallbackHasImg = fallbackHtml.toLowerCase(Locale.ROOT).contains("<img");
            if (fallbackText.trim().isEmpty() && !fallbackHasImg) {
                fallbackText = "Книга пуста или имеет поврежденную разметку.";
                fallbackHtml = "<p>" + fallbackText + "</p>";
            } else if (fallbackText.trim().isEmpty()) {
                fallbackText = "Иллюстрация";
            }
            chapters.add(new Chapter(0, bookTitle, fallbackHtml, fallbackText));
        }

        for (Chapter ch : chapters) {
            content.addChapter(ch);
        }
        content.setTitle(bookTitle);
        content.setAuthor(bookAuthor);
        return content;
    }

    private static List<String> topLevelSections(String body) {
        List<String> sections = new ArrayList<>();
        Matcher tags = Pattern.compile("<!--.*?-->|<!\\[CDATA\\[.*?]]>|<(/?)section\\b(?:\"[^\"]*\"|'[^']*'|[^'\">])*>",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(body);
        int depth = 0;
        int start = -1;
        int consumed = 0;
        while (tags.find()) {
            if (tags.group(1) == null) continue;
            if (tags.group(1).isEmpty() && tags.group().matches("(?s).*/\\s*>")) continue;
            if (tags.group(1).isEmpty()) {
                if (depth++ == 0) {
                    if (tags.start() > consumed) sections.add(body.substring(consumed, tags.start()));
                    start = tags.start();
                }
            } else if (depth > 0 && --depth == 0) {
                sections.add(body.substring(start, tags.end()));
                consumed = tags.end();
            }
        }
        if (consumed < body.length()) sections.add(body.substring(depth > 0 ? start : consumed));
        return sections;
    }

    private Map<String, String> extractAndCacheImages(String xml, BookContent content) throws IOException {
        Map<String, String> uriById = new HashMap<>();
        Matcher m = BINARY_PATTERN.matcher(xml);
        boolean coverFound = content.getCoverImage() != null;
        byte[] firstImage = null;

        while (m.find()) {
            String attrs = m.group(1);
            String b64 = m.group(2);
            String id = attr(ATTR_ID, attrs);
            String type = attr(ATTR_TYPE, attrs);
            if (id.isEmpty() || b64 == null || b64.isBlank()) {
                continue;
            }
            byte[] imgBytes;
            try {
                imgBytes = Base64.getMimeDecoder().decode(b64.replaceAll("[\\r\\n\\t ]", ""));
            } catch (Exception e) {
                continue;
            }
            if (imgBytes.length == 0) {
                continue;
            }
            if (firstImage == null) {
                firstImage = imgBytes;
            }
            String lowerId = id.toLowerCase(Locale.ROOT);
            if (!coverFound && (lowerId.contains("cover") || lowerId.contains("облож"))) {
                content.setCoverImage(imgBytes);
                coverFound = true;
            }
            Path target = BookImages.save(imgBytes, extForType(type, id));
            uriById.put(id, target.toUri().toString());
        }
        if (!coverFound && firstImage != null) {
            content.setCoverImage(firstImage);
        }
        return uriById;
    }

    private static String attr(Pattern pattern, String attrs) {
        Matcher m = pattern.matcher(attrs);
        return m.find() ? m.group(1).trim() : "";
    }

    private static String extForType(String type, String id) {
        String t = type == null ? "" : type.toLowerCase(Locale.ROOT);
        if (t.contains("jpeg") || t.contains("jpg")) return ".jpg";
        if (t.contains("png")) return ".png";
        if (t.contains("gif")) return ".gif";
        if (t.contains("webp")) return ".webp";
        String low = id == null ? "" : id.toLowerCase(Locale.ROOT);
        for (String e : new String[]{".jpg", ".jpeg", ".png", ".gif", ".webp"}) {
            if (low.endsWith(e)) return e;
        }
        return ".jpg";
    }

    private String fb2SectionToHtml(String sectionXml, Map<String, String> imageUriById) {
        String s = stripScriptsAndStyles(sectionXml);

        Matcher im = IMAGE_REF_PATTERN.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (im.find()) {
            String id = im.group(1);
            String uri = imageUriById.get(id);
            String rep = "";
            if (uri != null && !uri.isEmpty()) {
                rep = "<div style='text-align:center; margin:10px 0;'><img src='" + uri + "'/></div>";
            }
            im.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        im.appendTail(sb);
        s = sb.toString();

        s = s.replaceAll("(?is)<title[^>]*>(.*?)</title>", "<h3>$1</h3>");
        s = s.replaceAll("(?is)<subtitle[^>]*>(.*?)</subtitle>", "<h4>$1</h4>");
        s = s.replaceAll("(?i)<emphasis[^>]*>", "<em>");
        s = s.replaceAll("(?i)</emphasis>", "</em>");
        s = s.replaceAll("(?i)<strikethrough[^>]*>", "<s>");
        s = s.replaceAll("(?i)</strikethrough>", "</s>");
        s = s.replaceAll("(?i)<empty-line\\s*/?>", "<p>&nbsp;</p>");
        s = s.replaceAll("(?i)(<a\\s[^>]*?\\s?)(?:xlink:|l:)href(\\s*=\\s*[\"']#)",
                "$1href$2");
        s = s.replaceAll("(?i)<(/?)section\\b([^>]*)>", "<$1div$2>");
        s = keepAllowedTags(s);
        s = s.replaceAll("[ \\t\\f\\r]+", " ");
        s = s.replaceAll(" *\\n *", "\n");
        s = s.replaceAll("\\n{3,}", "\n\n");
        if (s.isBlank()) {
            s = "<p></p>";
        }
        return s.trim();
    }

    private static String keepAllowedTags(String html) {
        Matcher tag = Pattern.compile("<(/?)([a-zA-Z][a-zA-Z0-9]*)([^>]*)>").matcher(html);
        StringBuffer sb = new StringBuffer();
        while (tag.find()) {
            String name = tag.group(2).toLowerCase(Locale.ROOT);
            if (ALLOWED_TAGS.contains(name)) {
                tag.appendReplacement(sb, Matcher.quoteReplacement(tag.group(0)));
            } else {
                tag.appendReplacement(sb, " ");
            }
        }
        tag.appendTail(sb);
        return sb.toString();
    }

    private static String escapeSimple(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private byte[] readActualFb2Bytes(File file) throws IOException {
        byte[] fileBytes;
        try (InputStream input = Files.newInputStream(file.toPath())) {
            fileBytes = readBounded(input);
        }

        if (fileBytes.length > 2 && (fileBytes[0] & 0xFF) == 0x1F && (fileBytes[1] & 0xFF) == 0x8B) {
            try (GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(fileBytes))) {
                fileBytes = readBounded(gis);
            }
        }

        if (fileBytes.length > 4 && fileBytes[0] == 'P' && fileBytes[1] == 'K') {
            try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(fileBytes))) {
                ZipEntry entry;
                int entries = 0;
                while ((entry = zis.getNextEntry()) != null) {
                    if (++entries > MAX_ZIP_ENTRIES) throw new IOException("Слишком много файлов в архиве FB2");
                    if (entry.isDirectory() && zis.read() != -1) {
                        throw new IOException("Папка в архиве FB2 содержит данные");
                    }
                    if (!entry.isDirectory()) {
                        return readBounded(zis);
                    }
                }
            }
            throw new IOException("В архиве FB2 не найдено файлов");
        }
        return fileBytes;
    }

    private static byte[] readBounded(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        int total = 0;
        while ((count = input.read(buffer)) != -1) {
            if (count > MAX_FB2_BYTES - total) {
                throw new IOException("Размер книги FB2 превышает допустимые 64 МиБ");
            }
            output.write(buffer, 0, count);
            total += count;
        }
        return output.toByteArray();
    }

    private String stripScriptsAndStyles(String html) {
        String res = html.replaceAll("(?is)<script[^>]*>.*?</script>", "");
        res = res.replaceAll("(?is)<style[^>]*>.*?</style>", "");
        res = res.replaceAll("(?is)<head[^>]*>.*?</head>", "");
        return res;
    }

    private String stripScriptsStylesAndBinaries(String xml) {
        String res = xml.replaceAll("(?is)<script[^>]*>.*?</script>", "");
        res = res.replaceAll("(?is)<style[^>]*>.*?</style>", "");
        res = res.replaceAll("(?is)<binary[^>]*>.*?</binary>", "");
        res = res.replaceAll("(?is)<description[^>]*>.*?</description>", "");
        res = res.replaceAll("(?is)<!--.*?-->", "");
        return res;
    }

    private String extractTitle(String sectionXml, String defaultTitle) {
        Pattern titlePattern = Pattern.compile("<title[^>]*>(.*?)</title>",
                Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
        Matcher m = titlePattern.matcher(sectionXml);
        if (m.find()) {
            String rawTitle = cleanTags(m.group(1)).replaceAll("\\s+", " ").trim();
            if (!rawTitle.isEmpty()) return rawTitle;
        }
        return defaultTitle;
    }

    private String cleanFb2MarkupToText(String xmlFragment) {
        String s = xmlFragment.replaceAll("(?is)<title[^>]*>.*?</title>", "");
        s = s.replaceAll("(?i)</p>", "\n\n");
        s = s.replaceAll("(?i)<empty-line\\s*/?>", "\n\n");
        s = s.replaceAll("(?i)<br\\s*/?>", "\n");
        s = cleanTags(s);
        s = s.replaceAll("[ \\t\\f\\r]+", " ");
        s = s.replaceAll(" *\\n *", "\n");
        s = s.replaceAll("\\n{3,}", "\n\n");
        return s.trim();
    }

    private String cleanTags(String input) {
        String noTags = input.replaceAll("<[^>]+>", " ");
        return noTags.replace("&nbsp;", " ")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&ndash;", "\u2013")
                .replace("&mdash;", "\u2014")
                .replace("&laquo;", "\u00AB")
                .replace("&raquo;", "\u00BB");
    }
}
