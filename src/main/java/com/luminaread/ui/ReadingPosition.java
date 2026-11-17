package com.luminaread.ui;

public record ReadingPosition(int chapterIndex, int pageIndex, int scrollOffset, int textOffset, String bookmarkId) {
    public ReadingPosition(int chapterIndex, int pageIndex, int scrollOffset) {
        this(chapterIndex, pageIndex, scrollOffset, -1, null);
    }
}
