package com.luminaread.parser;

import com.luminaread.model.Chapter;
import com.luminaread.util.WeightedCache;

import java.util.ArrayList;
import java.util.List;

public final class PaginationEngine {
    private static final WeightedCache<CacheKey, List<String>> CACHE = new WeightedCache<>(64, 16L * 1024 * 1024);

    private record CacheKey(String html, int limit, boolean spread) {}

    private PaginationEngine() {}

    public static List<String> paginateChapter(Chapter chapter, int targetCharsPerPage, boolean twoColumnSpread) {
        if (chapter == null || chapter.getHtmlContent().isBlank()) return List.of("<p></p>");

        String html = chapter.getHtmlContent();
        int limit = Math.max(600, targetCharsPerPage);
        var key = new CacheKey(html, limit, twoColumnSpread);
        var cached = CACHE.get(key);
        if (cached != null) return cached;

        List<String> pages = new HtmlPages(limit).split(html);
        List<String> result = List.copyOf(twoColumnSpread ? spreads(pages) : pages);
        long weight = 2L * html.length();
        for (String page : result) weight += 2L * page.length() + 64;
        CACHE.put(key, result, weight);
        return result;
    }

    private static List<String> spreads(List<String> pages) {
        List<String> spreads = new ArrayList<>((pages.size() + 1) / 2);
        for (int i = 0; i < pages.size(); i += 2) {
            String right = i + 1 < pages.size() ? pages.get(i + 1) : "";
            spreads.add("<table width='100%' cellspacing='0' cellpadding='0' border='0'><tr>"
                    + "<td width='48%' valign='top' style='padding-right:22px;'>" + pages.get(i) + "</td>"
                    + "<td width='4%' valign='top'></td>"
                    + "<td width='48%' valign='top' style='padding-left:22px;'>" + right + "</td>"
                    + "</tr></table>");
        }
        return spreads;
    }
}
