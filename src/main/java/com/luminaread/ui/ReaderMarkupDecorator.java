package com.luminaread.ui;

import com.luminaread.model.Highlight;
import com.luminaread.util.HtmlSanitizer;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Decorates visible text while keeping HTML tags and entity encodings intact. */
final class ReaderMarkupDecorator {
    private ReaderMarkupDecorator() {}

    static String decorate(String html, List<Highlight> highlights, int chapterIndex) {
        String result = html;
        for (Highlight highlight : highlights) {
            if (highlight.hasTextRange() || highlight.getChapterIndex() != chapterIndex || highlight.getSelectedText().length() < 4) continue;
            String color = highlight.getColorHex();
            result = decorateText(result, HtmlSanitizer.escapeHtml(highlight.getSelectedText()),
                    "<span style='background-color:" + color + "; color:#111111;'>", "</span>");
        }
        return result;
    }

    private static String decorateText(String html, String target, String open, String close) {
        if (target.isEmpty()) return html;
        Pattern pattern = Pattern.compile(Pattern.quote(target));
        StringBuilder out = new StringBuilder(html.length());
        int pos = 0;
        while (pos < html.length()) {
            int tag = html.indexOf('<', pos);
            if (tag < 0) { appendText(out, html.substring(pos), pattern, open, close); break; }
            appendText(out, html.substring(pos, tag), pattern, open, close);
            int end = tagEnd(html, tag);
            if (end < 0) { out.append(html, tag, html.length()); break; }
            out.append(html, tag, end + 1);
            pos = end + 1;
        }
        return out.toString();
    }

    private static int tagEnd(String html, int start) {
        if (html.startsWith("<!--", start)) {
            int end = html.indexOf("-->", start + 4);
            return end < 0 ? -1 : end + 2;
        }
        char quote = 0;
        for (int i = start + 1; i < html.length(); i++) {
            char c = html.charAt(i);
            if (quote != 0) { if (c == quote) quote = 0; }
            else if (c == '\'' || c == '"') quote = c;
            else if (c == '>') return i;
        }
        return -1;
    }

    private static void appendText(StringBuilder out, String text, Pattern pattern, String open, String close) {
        Matcher matcher = pattern.matcher(text);
        int cursor = 0;
        while (matcher.find()) {
            if (insideEntity(text, matcher.start()) || insideEntity(text, matcher.end())) continue;
            out.append(text, cursor, matcher.start()).append(open).append(matcher.group()).append(close);
            cursor = matcher.end();
        }
        out.append(text, cursor, text.length());
    }

    private static boolean insideEntity(String text, int offset) {
        if (offset <= 0) return false;
        int amp = text.lastIndexOf('&', offset - 1);
        int semi = text.lastIndexOf(';', offset - 1);
        if (amp <= semi) return false;
        int end = text.indexOf(';', amp + 1);
        return end >= offset && end - amp <= 32;
    }
}
