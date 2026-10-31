package com.luminaread.model;

import java.io.Serializable;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

public class Bookmark implements Serializable {
    private static final long serialVersionUID = 1L;
    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault());

    private final String id;
    private final String bookId;
    private final String bookTitle;
    private final int chapterIndex;
    private final String chapterTitle;
    private final int pageIndex;
    private final int scrollOffset;
    private String label;
    private final String snippet;
    private final long createdAt;

    public Bookmark(String bookId, String bookTitle, int chapterIndex, String chapterTitle,
                    int pageIndex, int scrollOffset, String label, String snippet) {
        this.id = UUID.randomUUID().toString();
        this.bookId = bookId;
        this.bookTitle = bookTitle != null ? bookTitle : "";
        this.chapterIndex = chapterIndex;
        this.chapterTitle = chapterTitle != null ? chapterTitle : ("Глава " + (chapterIndex + 1));
        this.pageIndex = pageIndex;
        this.scrollOffset = scrollOffset;
        this.label = (label == null || label.isBlank()) ? this.chapterTitle : label.trim();
        this.snippet = snippet != null ? snippet.trim() : "";
        this.createdAt = System.currentTimeMillis();
    }

    public String getId() { return id; }
    public String getBookId() { return bookId; }
    public String getBookTitle() { return bookTitle; }
    public int getChapterIndex() { return chapterIndex; }
    public String getChapterTitle() { return chapterTitle; }
    public int getPageIndex() { return pageIndex; }
    public int getScrollOffset() { return scrollOffset; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public String getSnippet() { return snippet; }
    public long getCreatedAt() { return createdAt; }

    public String getFormattedDate() {
        return FORMATTER.format(Instant.ofEpochMilli(createdAt));
    }
}
