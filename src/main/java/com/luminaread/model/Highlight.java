package com.luminaread.model;

import java.io.Serializable;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

public class Highlight implements Serializable {
    private static final long serialVersionUID = 1L;
    private static final String DEFAULT_COLOR = "#FDE047";
    private static final java.util.regex.Pattern HEX_COLOR =
            java.util.regex.Pattern.compile("#(?:[0-9a-fA-F]{3}|[0-9a-fA-F]{6})");
    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault());

    private final String id;
    private final String bookId;
    private final String bookTitle;
    private final int chapterIndex;
    private final String chapterTitle;
    private final String selectedText;
    private int textStart;
    private int textEnd;
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
        this.colorHex = normalizeColor(colorHex);
        this.createdAt = System.currentTimeMillis();
    }

    public String getId() { return id; }
    public String getBookId() { return bookId; }
    public String getBookTitle() { return bookTitle; }
    public int getChapterIndex() { return chapterIndex; }
    public String getChapterTitle() { return chapterTitle; }
    public String getSelectedText() { return selectedText; }
    public boolean hasTextRange() { return textStart > 0 && textEnd > textStart; }
    public int getTextStart() { return textStart - 1; }
    public int getTextEnd() { return textEnd - 1; }

    public void setTextRange(int start, int end) {
        if (start < 0 || end <= start || end == Integer.MAX_VALUE) throw new IllegalArgumentException("Invalid text range");
        textStart = start + 1;
        textEnd = end + 1;
    }

    public String getNote() { return note; }
    public void setNote(String note) { this.note = note != null ? note.trim() : ""; }
    public String getColorHex() { return normalizeColor(colorHex); }
    public void setColorHex(String colorHex) { this.colorHex = normalizeColor(colorHex); }

    private static String normalizeColor(String color) {
        if (color == null || !HEX_COLOR.matcher(color).matches()) return DEFAULT_COLOR;
        if (color.length() == 7) return color;
        StringBuilder expanded = new StringBuilder("#");
        for (int i = 1; i < color.length(); i++) {
            expanded.append(color.charAt(i)).append(color.charAt(i));
        }
        return expanded.toString();
    }
    public long getCreatedAt() { return createdAt; }

    public String getFormattedDate() {
        return FORMATTER.format(Instant.ofEpochMilli(createdAt));
    }
}
