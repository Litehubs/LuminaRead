package com.luminaread.parser;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Splits book fragments while preserving inline styles and indivisible blocks such as tables. */
final class HtmlPages {
    private static final Set<String> VOID = Set.of("area", "base", "br", "col", "embed", "hr", "img", "input",
            "link", "meta", "param", "source", "track", "wbr");
    private static final Set<String> ATOMIC = Set.of("table", "pre", "ol", "ul", "svg", "math", "ruby");
    private static final Set<String> BLOCK = Set.of("p", "div", "blockquote", "h1", "h2", "h3", "h4", "h5", "h6");
    private static final Pattern TOKEN = Pattern.compile(
            "<!--|<(/?)([a-zA-Z][a-zA-Z0-9:-]*)(?=[\\s/>])(?:\"[^\"]*\"|'[^']*'|[^\"'<>])*+>");
    private static final Pattern ATTRIBUTE = Pattern.compile(
            "\\s+([\\w:-]+)(?:\\s*=\\s*(?:\"[^\"]*\"|'[^']*'|[^\\s>]+))?");

    private final int limit;
    private final List<String> pages = new ArrayList<>();
    private final List<Tag> open = new ArrayList<>();
    private final StringBuilder page = new StringBuilder();
    private long chars;
    private int atomic;
    private int reopenLength;

    HtmlPages(int limit) {
        this.limit = limit;
    }

    static void checkDepth(String html) throws java.io.IOException {
        var stack = new ArrayList<String>();
        Matcher tokens = TOKEN.matcher(html);
        int cursor = 0;
        while (tokens.find(cursor)) {
            cursor = tokens.end();
            if (tokens.group(2) == null) {
                int end = html.indexOf("-->", cursor);
                if (end < 0) return;
                cursor = end + 3;
                continue;
            }
            String name = tokens.group(2).toLowerCase(Locale.ROOT);
            if (!tokens.group(1).isEmpty()) {
                int last = stack.lastIndexOf(name);
                if (last >= 0) stack.subList(last, stack.size()).clear();
            } else if (!VOID.contains(name) && !tokens.group().endsWith("/>")) {
                stack.add(name);
                if (stack.size() > 64) throw new java.io.IOException("Слишком много вложенных тегов в EPUB");
            }
        }
    }

    List<String> split(String html) {
        Matcher tokens = TOKEN.matcher(html);
        int cursor = 0;
        while (tokens.find(cursor)) {
            text(html, cursor, tokens.start());
            cursor = tokens.end();
            if (tokens.group(2) == null) {
                int end = html.indexOf("-->", cursor);
                cursor = end < 0 ? html.length() : end + 3;
                continue;
            }
            String name = tokens.group(2).toLowerCase(Locale.ROOT);
            String markup = tokens.group();
            if (tokens.group(1).isEmpty()) start(name, markup);
            else end(name);
            // Do not amplify hostile nesting by duplicating an unbounded stack at each break.
            if (open.size() > 64 || reopenLength > 4096) return List.of(html);
        }
        text(html, cursor, html.length());
        if (chars > 0 || pages.isEmpty()) {
            closeTags();
            pages.add(page.toString());
        }
        return pages;
    }

    private void start(String name, String markup) {
        int extra = switch (name) {
            case "img", "svg" -> 550;
            case "br", "hr" -> 40;
            case "table", "pre", "ol", "ul" -> 20;
            default -> 0;
        };
        if (atomic == 0 && chars > 0 && (chars >= limit
                || extra > 0 && chars + extra > limit
                || BLOCK.contains(name) && chars >= limit * (2.0 / 3))) {
            nextPage();
        }
        page.append(markup);
        chars += extra;
        if (VOID.contains(name) || markup.endsWith("/>")) return;
        Tag tag = new Tag(name, continuation(markup, name));
        open.add(tag);
        reopenLength += tag.markup().length();
        if (ATOMIC.contains(name)) atomic++;
    }

    private void end(String name) {
        int index = open.size() - 1;
        while (index >= 0 && !open.get(index).name().equals(name)) index--;
        if (index < 0) return;
        while (open.size() > index) {
            Tag tag = open.remove(open.size() - 1);
            appendClose(tag);
            reopenLength -= tag.markup().length();
            if (ATOMIC.contains(tag.name())) atomic--;
        }
    }

    private void text(String html, int from, int to) {
        if (atomic > 0) {
            page.append(html, from, to);
            chars += to - from;
            return;
        }
        while (from < to) {
            if (chars >= limit) nextPage();
            int end = from;
            int space = from;
            int count = 0;
            int beforeSpace = 0;
            while (end < to && chars + count < limit) {
                char c = html.charAt(end);
                if (c == '&') {
                    int tail = end + 1;
                    while (tail < to && tail - end <= 32 && (Character.isLetterOrDigit(html.charAt(tail))
                            || html.charAt(tail) == '#')) tail++;
                    end = tail < to && html.charAt(tail) == ';' ? tail + 1 : end + 1;
                } else {
                    end += Character.charCount(html.codePointAt(end));
                }
                count++;
                if (Character.isWhitespace(c)) {
                    space = end;
                    beforeSpace = count;
                }
            }
            if (end < to && space > from && count - beforeSpace <= 80) {
                end = space;
                count = beforeSpace;
            }
            page.append(html, from, end);
            chars += count;
            from = end;
            if (from < to) nextPage();
        }
    }

    private void nextPage() {
        closeTags();
        pages.add(page.toString());
        page.setLength(0);
        chars = 0;
        for (Tag tag : open) page.append(tag.markup());
    }

    private void closeTags() {
        for (int i = open.size() - 1; i >= 0; i--) appendClose(open.get(i));
    }

    private void appendClose(Tag tag) {
        page.append("</").append(tag.name()).append('>');
    }

    private static String continuation(String markup, String name) {
        var attrs = ATTRIBUTE.matcher(markup);
        StringBuilder result = new StringBuilder();
        while (attrs.find()) {
            String attr = attrs.group(1);
            boolean anchor = "a".equals(name) && "name".equalsIgnoreCase(attr);
            attrs.appendReplacement(result, "id".equalsIgnoreCase(attr) || anchor
                    ? "" : Matcher.quoteReplacement(attrs.group()));
        }
        return attrs.appendTail(result).toString();
    }

    private record Tag(String name, String markup) {}
}
