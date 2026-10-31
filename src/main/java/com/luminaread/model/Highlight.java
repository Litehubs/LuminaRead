package com.luminaread.model;

import java.io.Serializable;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

public class Highlight implements Serializable {
    private static final long serialVersionUID = 1L;
    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault());

    private final String id;
    private final String bookId;
    private final String bookTitle;
    private final int chapterIndex;
    private final String chapterTitle;
    private final String selectedText;
    private String note;
    private String colorHex;
    private final long createdAt;

    public Highlight(String bookId, String bookTitle, int chapterIndex, String chapterTitle,
                     String selectedText, String note, String colorHex) {
        this.id = UUID.randomUUID().toString();
        this.bookId = bookId;
        this.bookTitle = bookTitle != null ? bookTitle : "";
        this.chapterIndex = chapterIndex;
        this.chapterTitle = chapterTitle != null ? chapterTitle : ("Глава " + (chapterIndex + 1));
        this.selectedText = selectedText != null ? selectedText.trim() : "";
        this.note = note != null ? note.trim() : "";
        this.colorHex = (colorHex == null || colorHex.isBlank()) ? "#FDE047" : colorHex;
        this.createdAt = System.currentTimeMillis();
    }

    public String getId() { return id; }
    public String getBookId() { return bookId; }
    public String getBookTitle() { return bookTitle; }
    public int getChapterIndex() { return chapterIndex; }
    public String getChapterTitle() { return chapterTitle; }
    public String getSelectedText() { return selectedText; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note != null ? note.trim() : ""; }
    public String getColorHex() { return colorHex; }
    public void setColorHex(String colorHex) { this.colorHex = colorHex; }
    public long getCreatedAt() { return createdAt; }

    public String getFormattedDate() {
        return FORMATTER.format(Instant.ofEpochMilli(createdAt));
    }
}
