package com.luminaread.ui;

import com.luminaread.model.Chapter;

import javax.swing.text.BadLocationException;
import javax.swing.text.Element;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

/** Resolves only links inside the loaded book. */
final class ReaderLinks {
    private ReaderLinks() {}

    record Target(int chapter, int page, String id) {}

    static Target find(List<Chapter> chapters, int current, ReaderTextSearch.Layout layout, String link)
            throws IOException, BadLocationException {
        if (link == null || !link.startsWith("#") || link.length() > 8192) return null;
        String fragment = link.substring(1);
        String source = null;
        try {
            if (fragment.startsWith("epub:")) {
                int separator = fragment.indexOf('.', 5);
                if (separator < 0) return null;
                var decoder = Base64.getUrlDecoder();
                source = new String(decoder.decode(fragment.substring(5, separator)), StandardCharsets.UTF_8);
                fragment = new String(decoder.decode(fragment.substring(separator + 1)), StandardCharsets.UTF_8);
            }
            fragment = URLDecoder.decode(fragment.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            return null;
        }
        if (fragment.isEmpty()) return null;

        ReaderHtmlKit kit = new ReaderHtmlKit();
        for (int i = 0; i < chapters.size(); i++) {
            int chapter = (current + i) % chapters.size();
            Chapter item = chapters.get(chapter);
            if (source != null && !source.equals(item.getSourcePath())) continue;
            // Avoid building Swing documents for chapters without the identifier.
            if (!item.getHtmlContent().contains(fragment)
                    && !item.getHtmlContent().contains(com.luminaread.util.HtmlSanitizer.escapeHtml(fragment))) continue;
            List<String> pages = layout.pages(item);
            for (int page = 0; page < pages.size(); page++) {
                Element target = ReaderTextSearch.readPage(kit, pages.get(page)).getElement(fragment);
                if (target != null) return new Target(chapter, page, fragment);
            }
        }
        return null;
    }
}
