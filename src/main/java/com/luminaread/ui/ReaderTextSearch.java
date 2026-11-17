package com.luminaread.ui;

import com.luminaread.model.Chapter;
import com.luminaread.model.ReaderSettings;
import com.luminaread.parser.PaginationEngine;

import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import java.io.IOException;
import java.io.StringReader;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Searches the text the reader renders, using the active reading layout. */
final class ReaderTextSearch {
    private static final Pattern SPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

    enum Direction { FORWARD, BACKWARD }
    record Page(int chapter, int index) {}
    record Match(Page page, int occurrence, boolean wrapped) {}
    record Range(int start, int end) {}

    record Layout(ReaderSettings.ReadingMode mode, int charsPerPage, boolean twoColumnSpread) {
        static Layout from(ReaderSettings settings) {
            if (settings.getReadingMode() == ReaderSettings.ReadingMode.SCROLL) {
                return new Layout(ReaderSettings.ReadingMode.SCROLL, 0, false);
            }
            return new Layout(settings.getReadingMode(), settings.getCharsPerPage(), settings.isTwoColumnSpread());
        }

        List<String> pages(Chapter chapter) {
            if (mode == ReaderSettings.ReadingMode.SCROLL) return List.of(chapter.getHtmlContent());
            return PaginationEngine.paginateChapter(chapter, charsPerPage, twoColumnSpread);
        }
    }

    private ReaderTextSearch() {}

    static Pattern query(String input) {
        String normalized = SPACE.matcher(input).replaceAll(" ").strip();
        return Pattern.compile(normalized, Pattern.LITERAL | Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    static Match find(List<Chapter> chapters, Layout layout, Pattern query, Page start,
                      int previousOccurrence, Direction direction) throws IOException, BadLocationException {
        if (chapters.isEmpty() || query.pattern().isEmpty()) return null;
        ReaderHtmlKit kit = new ReaderHtmlKit();
        List<String> startPages = layout.pages(chapters.get(start.chapter()));
        List<String> pages = startPages;
        int chapter = start.chapter();
        int page = start.index();
        boolean wrapped = false;

        String startText = renderedText(kit, pages.get(page));
        int occurrence = findOccurrence(query, startText, previousOccurrence, direction);
        if (occurrence >= 0) return new Match(start, occurrence, false);

        while (true) {
            checkCancelled();
            page += direction == Direction.FORWARD ? 1 : -1;
            if (page < 0 || page >= pages.size()) {
                int nextChapter = chapter + (direction == Direction.FORWARD ? 1 : -1);
                wrapped |= nextChapter < 0 || nextChapter >= chapters.size();
                chapter = Math.floorMod(nextChapter, chapters.size());
                pages = chapter == start.chapter() ? startPages : layout.pages(chapters.get(chapter));
                page = direction == Direction.FORWARD ? 0 : pages.size() - 1;
            }

            if (chapter == start.chapter() && page == start.index()) {
                if (previousOccurrence < 0) return null;
                occurrence = findOccurrence(query, startText, -1, direction);
                return occurrence < 0 ? null : new Match(start, occurrence, wrapped);
            }

            occurrence = findOccurrence(query, renderedText(kit, pages.get(page)), -1, direction);
            if (occurrence >= 0) return new Match(new Page(chapter, page), occurrence, wrapped);
        }
    }

    private static String renderedText(ReaderHtmlKit kit, String fragment) throws IOException, BadLocationException {
        Document document = readPage(kit, fragment);
        return document.getText(0, document.getLength());
    }

    static javax.swing.text.html.HTMLDocument readPage(ReaderHtmlKit kit, String fragment)
            throws IOException, BadLocationException {
        checkCancelled();
        var document = (javax.swing.text.html.HTMLDocument) kit.createDefaultDocument();
        try (StringReader input = new StringReader(ReaderHtmlKit.pageHtml(ReaderHtmlKit.preparePage(fragment)))) {
            kit.read(input, document, 0);
        }
        checkCancelled();
        return document;
    }

    private static int findOccurrence(Pattern query, String text, int previous, Direction direction) {
        Matcher matcher = query.matcher(SPACE.matcher(text).replaceAll(" "));
        int last = -1;
        for (int i = 0; matcher.find(); i++) {
            checkCancelled();
            if (direction == Direction.FORWARD && i > previous) return i;
            if (direction == Direction.BACKWARD) {
                if (i == previous) break;
                last = i;
            }
        }
        return last;
    }

    static Range locate(Document document, Pattern query, int occurrence) throws BadLocationException {
        String original = document.getText(0, document.getLength());
        Matcher hits = query.matcher(SPACE.matcher(original).replaceAll(" "));
        for (int i = 0; hits.find(); i++) {
            if (i != occurrence) continue;
            int start = hits.start();
            int end = hits.end();
            Matcher spaces = SPACE.matcher(original);
            int skipped = 0;
            int originalStart = start;
            while (spaces.find() && spaces.start() - skipped < end) {
                int normalizedSpace = spaces.start() - skipped;
                int removed = spaces.end() - spaces.start() - 1;
                if (normalizedSpace < start) originalStart += removed;
                skipped += removed;
            }
            return new Range(originalStart, end + skipped);
        }
        return null;
    }

    private static void checkCancelled() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException();
    }
}
