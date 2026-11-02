package com.luminaread.parser;

import com.luminaread.model.BookContent;
import com.luminaread.model.Chapter;
import com.luminaread.util.CharsetDetector;
import com.luminaread.util.HtmlSanitizer;

import java.io.File;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.text.DefaultStyledDocument;
import javax.swing.text.rtf.RTFEditorKit;

public class TxtMarkdownParser implements BookParser {
    private static final Pattern CHAPTER_HEADING_PATTERN = Pattern.compile(
            "^(?:\\s*)(?:#{1,3}\\s+.+|(?:Глава|ГЛАВА|Часть|ЧАСТЬ|Книга|КНИГА|Раздел|Пролог|Эпилог|Chapter|CHAPTER|Part|PART|Book|BOOK)\\s+[0-9IVXLCDMА-Яа-яA-Za-z.\\-:\\s]{0,70})$"
    );

    @Override
    public boolean supports(File file, String formatHint) {
        if (formatHint != null) {
            String upper = formatHint.toUpperCase(Locale.ROOT);
            if (upper.equals("TXT") || upper.equals("MD") || upper.equals("MARKDOWN")
                    || upper.equals("HTML") || upper.equals("HTM") || upper.equals("RTF")) {
                return true;
            }
        }
        if (file != null) {
            String name = file.getName().toLowerCase(Locale.ROOT);
            if (name.endsWith(".txt") || name.endsWith(".md") || name.endsWith(".markdown")
                    || name.endsWith(".html") || name.endsWith(".htm") || name.endsWith(".rtf")) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String getSupportedFormat() {
        return "TXT";
    }

    @Override
    public BookContent parse(File file) throws Exception {
        byte[] bytes = Files.readAllBytes(file.toPath());
        String rawText = CharsetDetector.decodeBytes(bytes);
        String lowerName = file.getName().toLowerCase(Locale.ROOT);

        BookContent content = new BookContent();
        content.setTitle(stripExt(file.getName()));
        content.setAuthor("Неизвестный автор");

        if (lowerName.endsWith(".html") || lowerName.endsWith(".htm")) {
            parseHtmlFile(rawText, content);
        } else if (lowerName.endsWith(".rtf")) {
            String stripped = decodeRtf(bytes, rawText);
            parsePlainOrMarkdown(stripped, false, content);
        } else {
            boolean isMarkdown = lowerName.endsWith(".md") || lowerName.endsWith(".markdown");
            parsePlainOrMarkdown(rawText, isMarkdown, content);
        }

        return content;
    }

    private void parseHtmlFile(String rawHtml, BookContent content) {
        String title = extractTag(rawHtml, "title");
        if (!title.isBlank()) {
            content.setTitle(title);
        }
        String sanitized = HtmlSanitizer.sanitizeBodyFragment(rawHtml);
        String plain = HtmlSanitizer.htmlToPlainText(sanitized);
        content.addChapter(new Chapter(0, content.getTitle(), sanitized, plain));
    }

    private void parsePlainOrMarkdown(String rawText, boolean isMarkdown, BookContent content) {
        String[] lines = rawText.replace("\r\n", "\n").replace('\r', '\n').split("\n");

        for (int i = 0; i < Math.min(10, lines.length); i++) {
            String line = lines[i].trim();
            if (line.toLowerCase(Locale.ROOT).startsWith("автор:") || line.toLowerCase(Locale.ROOT).startsWith("author:")) {
                content.setAuthor(line.substring(line.indexOf(':') + 1).trim());
            } else if (line.toLowerCase(Locale.ROOT).startsWith("название:") || line.toLowerCase(Locale.ROOT).startsWith("title:")) {
                content.setTitle(line.substring(line.indexOf(':') + 1).trim());
            }
        }

        List<String> chapterTitles = new ArrayList<>();
        List<List<String>> chapterLines = new ArrayList<>();

        String currentTitle = "Глава 1";
        List<String> currentBuffer = new ArrayList<>();

        for (String line : lines) {
            String trimmed = line.trim();
            Matcher m = CHAPTER_HEADING_PATTERN.matcher(trimmed);
            boolean isHeading = m.matches() && trimmed.length() <= 90;

            if (isHeading && currentBuffer.size() > 15) {
                chapterTitles.add(cleanHeadingTitle(currentTitle));
                chapterLines.add(currentBuffer);
                currentTitle = cleanHeadingTitle(trimmed);
                currentBuffer = new ArrayList<>();
            } else {
                if (currentBuffer.isEmpty() && isHeading) {
                    currentTitle = cleanHeadingTitle(trimmed);
                }
                currentBuffer.add(line);
            }
        }

        if (!currentBuffer.isEmpty()) {
            chapterTitles.add(cleanHeadingTitle(currentTitle));
            chapterLines.add(currentBuffer);
        }

        if (chapterLines.size() == 1 && chapterLines.get(0).size() > 700) {
            List<String> allLines = chapterLines.get(0);
            chapterTitles.clear();
            chapterLines.clear();
            int chunkSize = 400;
            int part = 1;
            for (int i = 0; i < allLines.size(); i += chunkSize) {
                int end = Math.min(allLines.size(), i + chunkSize);
                chapterTitles.add("Часть " + part++);
                chapterLines.add(allLines.subList(i, end));
            }
        }

        for (int idx = 0; idx < chapterLines.size(); idx++) {
            String chTitle = chapterTitles.get(idx);
            List<String> chLines = chapterLines.get(idx);
            String html = convertLinesToHtml(chTitle, chLines, isMarkdown);
            String plain = String.join("\n", chLines).trim();
            if (!plain.isBlank()) {
                content.addChapter(new Chapter(idx, chTitle, html, plain));
            }
        }

        if (content.getChapters().isEmpty()) {
            content.addChapter(new Chapter(0, content.getTitle(), "<p>(Пустой документ)</p>", ""));
        }
    }

    private String convertLinesToHtml(String chTitle, List<String> lines, boolean isMarkdown) {
        StringBuilder sb = new StringBuilder();
        sb.append("<h2>").append(HtmlSanitizer.escapeHtml(chTitle)).append("</h2>\n");

        StringBuilder paragraph = new StringBuilder();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                flushParagraph(sb, paragraph, isMarkdown);
            } else if (isMarkdown && trimmed.startsWith("#")) {
                flushParagraph(sb, paragraph, true);
                int level = 0;
                while (level < trimmed.length() && trimmed.charAt(level) == '#') level++;
                int hTag = Math.min(4, Math.max(2, level));
                String hText = trimmed.substring(level).trim();
                sb.append("<h").append(hTag).append(">")
                  .append(formatInlineMarkdown(HtmlSanitizer.escapeHtml(hText)))
                  .append("</h").append(hTag).append(">\n");
            } else if (isMarkdown && trimmed.startsWith(">")) {
                flushParagraph(sb, paragraph, true);
                String quoteText = trimmed.substring(1).trim();
                sb.append("<blockquote><p>")
                  .append(formatInlineMarkdown(HtmlSanitizer.escapeHtml(quoteText)))
                  .append("</p></blockquote>\n");
            } else if ("***".equals(trimmed) || "* * *".equals(trimmed) || "---".equals(trimmed)) {
                flushParagraph(sb, paragraph, isMarkdown);
                sb.append("<p style='text-align:center;'>* &nbsp; * &nbsp; *</p>\n");
            } else {
                if (!paragraph.isEmpty()) paragraph.append(" ");
                paragraph.append(trimmed);
            }
        }
        flushParagraph(sb, paragraph, isMarkdown);
        return sb.toString();
    }

    private void flushParagraph(StringBuilder sb, StringBuilder paragraph, boolean isMarkdown) {
        if (paragraph.isEmpty()) return;
        String escaped = HtmlSanitizer.escapeHtml(paragraph.toString());
        if (isMarkdown) {
            escaped = formatInlineMarkdown(escaped);
        }
        sb.append("<p>").append(escaped).append("</p>\n");
        paragraph.setLength(0);
    }

    private String formatInlineMarkdown(String text) {
        String res = text.replaceAll("\\*\\*(.+?)\\*\\*", "<b>$1</b>");
        res = res.replaceAll("\\*(.+?)\\*", "<i>$1</i>");
        res = res.replaceAll("`(.+?)`", "<code>$1</code>");
        return res;
    }

    private String cleanHeadingTitle(String raw) {
        if (raw == null) return "Глава";
        return raw.replaceFirst("^#{1,6}\\s*", "").trim();
    }

    private String extractTag(String html, String tag) {
        Pattern p = Pattern.compile("<" + tag + "[^>]*>(.*?)</" + tag + ">", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Matcher m = p.matcher(html);
        if (m.find()) {
            return HtmlSanitizer.htmlToPlainText(m.group(1));
        }
        return "";
    }

    private String stripBasicRtf(String rtf) {
        String text = rtf.replaceAll("\\\\par\\b", "\n")
                .replaceAll("\\\\line\\b", "\n")
                .replaceAll("\\\\'[0-9a-fA-F]{2}", "")
                .replaceAll("\\\\[a-zA-Z]+\\d*\\s?", "")
                .replaceAll("[{}]", "");
        return text.trim();
    }

    private String decodeRtf(byte[] bytes, String fallbackText) {
        try {
            RTFEditorKit kit = new RTFEditorKit();
            DefaultStyledDocument document = new DefaultStyledDocument();
            kit.read(new ByteArrayInputStream(bytes), document, 0);
            String plain = document.getText(0, document.getLength());
            Matcher codePageMatcher = Pattern.compile("\\\\ansicpg(\\d+)", Pattern.CASE_INSENSITIVE).matcher(fallbackText);
            if (codePageMatcher.find()) {
                try {
                    Charset rtfCharset = Charset.forName("windows-" + codePageMatcher.group(1));
                    return reinterpretAnsiCharacters(plain, rtfCharset);
                } catch (Exception ignored) {
                }
            }
            return plain;
        } catch (Exception ignored) {
            return stripBasicRtf(fallbackText);
        }
    }

    private String reinterpretAnsiCharacters(String text, Charset charset) {
        StringBuilder result = new StringBuilder(text.length());
        ByteArrayOutputStream ansiBytes = new ByteArrayOutputStream();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch >= 0x80 && ch <= 0xFF) {
                ansiBytes.write(ch);
            } else {
                if (ansiBytes.size() > 0) {
                    result.append(new String(ansiBytes.toByteArray(), charset));
                    ansiBytes.reset();
                }
                result.append(ch);
            }
        }
        if (ansiBytes.size() > 0) result.append(new String(ansiBytes.toByteArray(), charset));
        return result.toString();
    }

    private String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
