package com.luminaread.util;

import java.util.regex.Pattern;

public final class HtmlSanitizer {
    private static final Pattern SCRIPT_STYLE_PATTERN =
            Pattern.compile("<(script|style|head)[^>]*>.*?</\\1>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private HtmlSanitizer() {}

    public static String normalizeInlineWhitespace(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        int len = raw.length();
        StringBuilder sb = new StringBuilder(len);
        boolean lastWasSpace = false;

        for (int i = 0; i < len; i++) {
            char c = raw.charAt(i);

            if (c == '\u00AD' || c == '\u200B' || c == '\u200C' || c == '\u200D' || c == '\uFEFF') {
                continue;
            }
            if (Character.isWhitespace(c) || c == '\u00A0' || (c >= '\u2000' && c <= '\u200A')
                    || c == '\u202F' || c == '\u205F' || c == '\u3000') {
                if (!lastWasSpace) {
                    sb.append(' ');
                    lastWasSpace = true;
                }
            } else {
                sb.append(c);
                lastWasSpace = false;
            }
        }
        return sb.toString();
    }

    public static String escapeHtml(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        String normalized = normalizeInlineWhitespace(raw);
        int len = normalized.length();
        StringBuilder sb = new StringBuilder(len + 32);
        for (int i = 0; i < len; i++) {
            char c = normalized.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    public static String htmlToPlainText(String html) {
        if (html == null || html.isEmpty()) return "";
        String source = html;
        if (containsIgnoreCase(source, "<script") || containsIgnoreCase(source, "<style") || containsIgnoreCase(source, "<head")) {
            source = SCRIPT_STYLE_PATTERN.matcher(source).replaceAll(" ");
        }

        int len = source.length();
        StringBuilder out = new StringBuilder(len);
        boolean inTag = false;
        boolean lastWasSpace = false;
        int consecutiveNewlines = 0;
        StringBuilder tagNameBuf = new StringBuilder(12);
        boolean readingTagName = false;

        for (int i = 0; i < len; i++) {
            char c = source.charAt(i);
            if (c == '<') {
                inTag = true;
                readingTagName = true;
                tagNameBuf.setLength(0);
            } else if (c == '>' && inTag) {
                inTag = false;
                readingTagName = false;
                String tag = tagNameBuf.toString().toLowerCase();
                if ("p".equals(tag) || "/p".equals(tag) || "br".equals(tag) || "br/".equals(tag)
                        || "div".equals(tag) || "/div".equals(tag) || "h1".equals(tag) || "h2".equals(tag)
                        || "h3".equals(tag) || "li".equals(tag) || "tr".equals(tag) || "blockquote".equals(tag)) {
                    if (consecutiveNewlines < 2 && !out.isEmpty()) {
                        out.append('\n');
                        consecutiveNewlines++;
                        lastWasSpace = true;
                    }
                }
            } else if (inTag) {
                if (readingTagName) {
                    if (Character.isWhitespace(c)) {
                        if (!tagNameBuf.isEmpty()) readingTagName = false;
                    } else if (tagNameBuf.length() < 12) {
                        tagNameBuf.append(c);
                    }
                }
            } else {
                if (c == '\u00AD' || c == '\u200B' || c == '\uFEFF') {
                    continue;
                }
                if (Character.isWhitespace(c) || c == '\u00A0' || (c >= '\u2000' && c <= '\u200A')) {
                    if (!lastWasSpace && !out.isEmpty()) {
                        out.append(' ');
                        lastWasSpace = true;
                    }
                } else {
                    out.append(c);
                    lastWasSpace = false;
                    consecutiveNewlines = 0;
                }
            }
        }
        return decodeBasicEntities(out.toString()).trim();
    }

    public static String sanitizeBodyFragment(String rawHtml) {
        if (rawHtml == null || rawHtml.isEmpty()) return "";
        String cleaned = SCRIPT_STYLE_PATTERN.matcher(rawHtml).replaceAll("");
        String lower = cleaned.toLowerCase();
        int bodyStart = lower.indexOf("<body");
        if (bodyStart >= 0) {
            int tagClose = cleaned.indexOf('>', bodyStart);
            int bodyEnd = lower.lastIndexOf("</body>");
            if (tagClose >= 0 && bodyEnd > tagClose) {
                cleaned = cleaned.substring(tagClose + 1, bodyEnd);
            }
        }
        cleaned = cleaned.replace("&nbsp;", " ")
                .replace("&#160;", " ")
                .replace("&shy;", "")
                .replace("&#173;", "")
                .replace("\u00A0", " ")
                .replace("\u00AD", "");
        cleaned = cleaned.replaceAll("(?i)\\s+xmlns(:[a-z0-9]+)?=[\"'][^\"']*[\"']", "");
        cleaned = cleaned.replaceAll("(?i)\\s+epub:[a-z0-9\\-]+=[\"'][^\"']*[\"']", "");
        cleaned = cleaned.replaceAll("(?i)<(section|article|main|header|footer|aside)([^>]*)>", "<div$2>");
        cleaned = cleaned.replaceAll("(?i)</(section|article|main|header|footer|aside)>", "</div>");

        cleaned = cleaned.replaceAll("[ \\t\\x0B\\f\\r\\u00A0\\u2000-\\u200A]+", " ");
        return cleaned.trim();
    }

    public static String decodeBasicEntities(String text) {
        if (text == null || text.indexOf('&') < 0) return text;
        return text
                .replace("&nbsp;", " ")
                .replace("&#160;", " ")
                .replace("&shy;", "")
                .replace("&#173;", "")
                .replace("&mdash;", "—")
                .replace("&ndash;", "–")
                .replace("&laquo;", "«")
                .replace("&raquo;", "»")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&#39;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&");
    }

    private static boolean containsIgnoreCase(String src, String sub) {
        int n = sub.length();
        int max = src.length() - n;
        for (int i = 0; i <= max; i++) {
            if (src.regionMatches(true, i, sub, 0, n)) {
                return true;
            }
        }
        return false;
    }
}
