package com.luminaread.ui;

import com.luminaread.model.Book;

/** Converts the settled reader viewport into a checkpoint. */
final class ReaderProgress {
    private ReaderProgress() {}

    static void update(Book book, int chapter, int chapters, int page, int pages,
                       boolean scrolling, boolean settled, int offset, int range) {
        book.setCurrentChapterIndex(chapter);
        book.setCurrentPageInChapter(page);
        if (scrolling && !settled) return;
        double fraction = scrolling && range > 0
                ? (double) offset / range
                : (double) (page + 1) / Math.max(1, pages);
        book.setProgressPercent((chapter + fraction) * 100.0 / chapters);
    }
}
