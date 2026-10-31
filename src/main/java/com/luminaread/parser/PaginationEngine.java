package com.luminaread.parser;

import com.luminaread.model.Chapter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PaginationEngine {
    private static final Pattern BLOCK_SPLIT_PATTERN =
            Pattern.compile("(<(?:h[1-6]|p|blockquote|div)[^>]*>[\\s\\S]*?</(?:h[1-6]|p|blockquote|div)>|<br\\s*/?>)",
                    Pattern.CASE_INSENSITIVE);

    private static final Map<String, List<String>> PAGINATION_CACHE =
            Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, List<String>> eldest) {
                    return size() > 64;
                }
            });

    private PaginationEngine() {}

    public static List<String> paginateChapter(Chapter chapter, int targetCharsPerPage, boolean twoColumnSpread) {
        if (chapter == null || chapter.getHtmlContent() == null || chapter.getHtmlContent().isBlank()) {
            return List.of("<p></p>");
        }

        int effectiveLimit = Math.max(600, targetCharsPerPage);
        String html = chapter.getHtmlContent();
        String cacheKey = chapter.getIndex() + "_" + html.hashCode() + "_" + effectiveLimit + "_" + twoColumnSpread;

        List<String> cached = PAGINATION_CACHE.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        List<String> blocks = extractCompleteHtmlBlocks(html);
        if (blocks.isEmpty()) {

            blocks = splitUntaggedIntoBlocks(html, effectiveLimit);
        }

        List<String> normalized = new ArrayList<>(blocks.size() + 8);
        for (String block : blocks) {
            if (estimateVisibleLength(block) > effectiveLimit) {
                normalized.addAll(splitOversizedBlock(block, effectiveLimit));
            } else {
                normalized.add(block);
            }
        }
        blocks = normalized;

        List<String> singlePages = new ArrayList<>();
        StringBuilder currentPageHtml = new StringBuilder(effectiveLimit + 256);
        int currentVisibleChars = 0;

        for (String block : blocks) {
            int visibleLen = estimateVisibleLength(block);
            if (currentVisibleChars > 0 && currentVisibleChars + visibleLen > effectiveLimit) {
                singlePages.add(currentPageHtml.toString());
                currentPageHtml.setLength(0);
                currentVisibleChars = 0;
            }
            currentPageHtml.append(block).append('\n');
            currentVisibleChars += visibleLen;
        }
        if (!currentPageHtml.isEmpty()) {
            singlePages.add(currentPageHtml.toString());
        }
        if (singlePages.isEmpty()) {
            singlePages.add(html);
        }

        List<String> result;
        if (!twoColumnSpread) {
            result = Collections.unmodifiableList(singlePages);
        } else {
            List<String> spreads = new ArrayList<>((singlePages.size() + 1) / 2);
            for (int i = 0; i < singlePages.size(); i += 2) {
                String leftCol = singlePages.get(i);
                String rightCol = (i + 1 < singlePages.size()) ? singlePages.get(i + 1) : "";
                String spreadHtml = "<table width='100%' cellspacing='0' cellpadding='0' border='0'><tr>"
                        + "<td width='48%' valign='top' style='padding-right:22px;'>" + leftCol + "</td>"
                        + "<td width='4%' valign='top'></td>"
                        + "<td width='48%' valign='top' style='padding-left:22px;'>" + rightCol + "</td>"
                        + "</tr></table>";
                spreads.add(spreadHtml);
            }
            result = Collections.unmodifiableList(spreads);
        }

        PAGINATION_CACHE.put(cacheKey, result);
        return result;
    }

    private static final Pattern IMG_TAG_PATTERN =
            Pattern.compile("<img[^>]*>", Pattern.CASE_INSENSITIVE);

    private static List<String> splitUntaggedIntoBlocks(String html, int limit) {
        List<String> blocks = new ArrayList<>();

        List<String> images = new ArrayList<>();
        Matcher imgM = IMG_TAG_PATTERN.matcher(html);
        while (imgM.find()) {
            images.add(imgM.group(0));
        }

        String text = IMG_TAG_PATTERN.matcher(html).replaceAll(" ");
        text = text.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();

        if (!text.isEmpty()) {
            StringBuilder cur = new StringBuilder();
            int curLen = 0;
            for (String sentence : text.split("(?<=[.!?\u2026;:])\\s+")) {
                if (sentence.isBlank()) continue;
                if (curLen > 0 && curLen + sentence.length() > limit) {
                    blocks.add("<p>" + escapeAngle(cur.toString().trim()) + "</p>");
                    cur.setLength(0);
                    curLen = 0;
                }
                cur.append(sentence).append(' ');
                curLen += sentence.length() + 1;
            }
            if (!cur.toString().isBlank()) {
                blocks.add("<p>" + escapeAngle(cur.toString().trim()) + "</p>");
            }
        }

        for (String image : images) {
            blocks.add(image);
        }
        if (blocks.isEmpty()) {
            blocks.add("<p></p>");
        }
        return blocks;
    }

    private static List<String> splitOversizedBlock(String block, int limit) {
        String plain = block.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
        if (plain.length() <= limit || plain.isEmpty()) {
            return List.of(block);
        }
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int curLen = 0;
        for (String sentence : plain.split("(?<=[.!?\u2026;:])\\s+")) {
            if (sentence.isBlank()) continue;
            if (curLen > 0 && curLen + sentence.length() > limit) {
                out.add("<p>" + escapeAngle(cur.toString().trim()) + "</p>");
                cur.setLength(0);
                curLen = 0;
            }
            cur.append(sentence).append(' ');
            curLen += sentence.length() + 1;
        }
        if (!cur.toString().isBlank()) {
            out.add("<p>" + escapeAngle(cur.toString().trim()) + "</p>");
        }
        return out.isEmpty() ? List.of(block) : out;
    }

    private static String escapeAngle(String s) {
        return s.replace("<", "&lt;").replace(">", "&gt;");
    }

    private static List<String> extractCompleteHtmlBlocks(String html) {
        List<String> blocks = new ArrayList<>();
        Matcher m = BLOCK_SPLIT_PATTERN.matcher(html);
        int lastEnd = 0;
        while (m.find()) {
            if (m.start() > lastEnd) {
                String between = html.substring(lastEnd, m.start()).trim();
                if (!between.isEmpty()) {
                    blocks.add("<p>" + between + "</p>");
                }
            }
            blocks.add(m.group(1));
            lastEnd = m.end();
        }
        if (lastEnd < html.length()) {
            String tail = html.substring(lastEnd).trim();
            if (!tail.isEmpty()) {
                blocks.add(tail);
            }
        }
        return blocks;
    }

    private static int estimateVisibleLength(String htmlBlock) {
        if (htmlBlock == null || htmlBlock.isEmpty()) return 0;
        boolean hasImg = htmlBlock.indexOf("<img") >= 0 || htmlBlock.indexOf("<IMG") >= 0;
        int count = hasImg ? 550 : 0;
        boolean inTag = false;
        int len = htmlBlock.length();
        for (int i = 0; i < len; i++) {
            char c = htmlBlock.charAt(i);
            if (c == '<') {
                inTag = true;
            } else if (c == '>') {
                inTag = false;
            } else if (!inTag) {
                count++;
            }
        }
        return Math.max(20, count);
    }
}
