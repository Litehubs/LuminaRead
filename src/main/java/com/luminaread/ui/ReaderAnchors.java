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
    private boolean indexed;

    ReaderAnchors(List<String> pages) {
        this.pages = pages;
        starts = new int[pages.size() + 1];
    }

    record Target(int page, int offset) {}

    ReaderAnchors index() throws IOException, BadLocationException {
        ReaderHtmlKit kit = new ReaderHtmlKit();
        for (int page = 0; page < pages.size(); page++) {
            var doc = ReaderTextSearch.readPage(kit, pages.get(page));
            String text = doc.getText(0, doc.getLength());
            starts[page + 1] = starts[page] + weight(text, text.length());
        }
        indexed = true;
        return this;
    }

    boolean isReady() {
        return pages.size() <= 1 || indexed;
    }

    Target locate(int offset) {
        if (offset < 0 || pages.isEmpty()) return null;
        if (pages.size() == 1 && !indexed) return new Target(0, offset);
        if (!indexed || offset >= starts[pages.size()]) return null;
        int low = 0;
        int high = pages.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (starts[middle + 1] <= offset) low = middle + 1;
            else high = middle;
        }
        return new Target(low, offset - starts[low]);
    }

    int capture(int page, Document doc, int position) {
        try {
            if (page > 0 && !indexed) return -1;
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

    ReaderTextSearch.Range selection(int page, Document doc, int first, int last) throws BadLocationException {
        if (page > 0 && !indexed) return null;
        String text = doc.getText(0, last);
        int start = starts[page] + weight(text, first);
        int end = starts[page] + weight(text, last);
        return start < end ? new ReaderTextSearch.Range(start, end) : null;
    }

    ReaderTextSearch.Range onPage(int page, String text, int start, int end) {
        if (page > 0 && !indexed) return null;
        if (end <= starts[page] || indexed && start >= starts[page + 1]) return null;
        int first = Math.max(0, start - starts[page]);
        int length = indexed ? starts[page + 1] - starts[page] : weight(text, text.length());
        int last = Math.min(length, end - starts[page]);
        if (first >= last) return null;
        int from = modelOffset(text, first);
        int to = modelOffset(text, last - 1);
        return from >= 0 && to >= from ? new ReaderTextSearch.Range(from, to + 1) : null;
    }

    static int modelOffset(Document doc, int offset) throws BadLocationException {
        return modelOffset(doc.getText(0, doc.getLength()), offset);
    }

    private static int modelOffset(String text, int offset) {
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
