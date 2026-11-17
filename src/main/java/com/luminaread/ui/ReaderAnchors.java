package com.luminaread.ui;

import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import java.io.IOException;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Maps positions by counting visible characters across the chapter, independent of page breaks. */
final class ReaderAnchors {
    private static final Logger LOG = Logger.getLogger(ReaderAnchors.class.getName());
    private final List<String> pages;
    private final int[] starts;
    private int indexedPages;

    ReaderAnchors(List<String> pages) {
        this.pages = pages;
        starts = new int[pages.size() + 1];
    }

    record Target(int page, int offset) {}

    Target locate(int offset) {
        if (offset < 0) return null;
        for (int page = 0; page < pages.size(); page++) {
            if (!indexThrough(page + 1)) return null;
            if (offset < starts[page + 1]) return new Target(page, offset - starts[page]);
        }
        return null;
    }

    int capture(int page, Document doc, int position) {
        try {
            if (!indexThrough(page)) return -1;
            String text = doc.getText(0, doc.getLength());
            int length = weight(text, text.length());
            if (length == 0) return -1;
            int before = weight(text, Math.min(text.length(), Math.max(0, position)));
            return starts[page] + Math.min(before, length - 1);
        } catch (BadLocationException ex) {
            LOG.log(Level.FINE, "Не удалось получить позицию в тексте", ex);
            return -1;
        }
    }

    private boolean indexThrough(int end) {
        if (indexedPages >= end) return true;
        ReaderHtmlKit kit = new ReaderHtmlKit();
        try {
            while (indexedPages < end) {
                var doc = ReaderTextSearch.readPage(kit, pages.get(indexedPages));
                String text = doc.getText(0, doc.getLength());
                starts[indexedPages + 1] = starts[indexedPages] + weight(text, text.length());
                indexedPages++;
            }
            return true;
        } catch (IOException | BadLocationException ex) {
            LOG.log(Level.FINE, "Не удалось сопоставить страницы книги", ex);
            return false;
        }
    }

    static int modelOffset(Document doc, int offset) throws BadLocationException {
        String text = doc.getText(0, doc.getLength());
        for (int i = 0; i < text.length(); i++) {
            if (visible(text.charAt(i)) && offset-- == 0) return i;
        }
        return -1;
    }

    private static int weight(String text, int end) {
        int count = 0;
        for (int i = 0; i < end; i++) if (visible(text.charAt(i))) count++;
        return count;
    }

    private static boolean visible(char ch) {
        return !Character.isWhitespace(ch) && !Character.isSpaceChar(ch) && ch != '\ufffc';
    }
}
