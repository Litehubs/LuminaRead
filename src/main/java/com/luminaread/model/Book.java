package com.luminaread.model;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class Book implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String id;
    private String title;
    private String author;
    private String series;
    private String language;
    private String description;
    private String filePath;
    private String format;
    private byte[] coverImage;

    private int currentChapterIndex;
    private int currentPageInChapter;
    private int scrollOffset;
    private double progressPercent;

    private boolean favorite;
    private boolean finished;
    private final List<String> tags;

    private long addedTimestamp;
    private long lastOpenedTimestamp;
    private long totalReadingSeconds;
    private int totalWords;

    public Book(String title, String author, String filePath, String format) {
        this.id = UUID.randomUUID().toString();
        this.title = (title == null || title.isBlank()) ? "Без названия" : title.trim();
        this.author = (author == null || author.isBlank()) ? "Неизвестный автор" : author.trim();
        this.series = "";
        this.language = "ru";
        this.description = "";
        this.filePath = filePath;
        this.format = (format == null) ? "TXT" : format.toUpperCase();
        this.tags = new ArrayList<>();
        this.addedTimestamp = System.currentTimeMillis();
        this.lastOpenedTimestamp = 0L;
    }

    public String getId() { return id; }
    public String getTitle() { return title; }
    public void setTitle(String title) {
        if (title != null && !title.isBlank()) {
            this.title = title.trim();
        }
    }

    public String getAuthor() { return author; }
    public void setAuthor(String author) {
        if (author != null && !author.isBlank()) {
            this.author = author.trim();
        }
    }

    public String getSeries() { return series; }
    public void setSeries(String series) { this.series = series != null ? series.trim() : ""; }

    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language != null ? language.trim() : "ru"; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description != null ? description.trim() : ""; }

    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }

    public String getFormat() { return format; }
    public void setFormat(String format) { this.format = format != null ? format.toUpperCase() : "TXT"; }

    public byte[] getCoverImage() { return coverImage; }
    public void setCoverImage(byte[] coverImage) { this.coverImage = coverImage; }

    public int getCurrentChapterIndex() { return currentChapterIndex; }
    public void setCurrentChapterIndex(int currentChapterIndex) {
        this.currentChapterIndex = Math.max(0, currentChapterIndex);
    }

    public int getCurrentPageInChapter() { return currentPageInChapter; }
    public void setCurrentPageInChapter(int currentPageInChapter) {
        this.currentPageInChapter = Math.max(0, currentPageInChapter);
    }

    public int getScrollOffset() { return scrollOffset; }
    public void setScrollOffset(int scrollOffset) {
        this.scrollOffset = Math.max(0, scrollOffset);
    }

    public double getProgressPercent() { return progressPercent; }
    public void setProgressPercent(double progressPercent) {
        this.progressPercent = Math.max(0.0, Math.min(100.0, progressPercent));
        if (this.progressPercent >= 99.0) {
            this.finished = true;
        }
    }

    public boolean isFavorite() { return favorite; }
    public void setFavorite(boolean favorite) { this.favorite = favorite; }

    public boolean isFinished() { return finished; }
    public void setFinished(boolean finished) { this.finished = finished; }

    public List<String> getTags() { return tags; }
    public void addTag(String tag) {
        if (tag != null && !tag.isBlank() && !tags.contains(tag.trim())) {
            tags.add(tag.trim());
        }
    }

    public long getAddedTimestamp() { return addedTimestamp; }
    public void setAddedTimestamp(long addedTimestamp) { this.addedTimestamp = addedTimestamp; }

    public long getLastOpenedTimestamp() { return lastOpenedTimestamp; }
    public void setLastOpenedTimestamp(long lastOpenedTimestamp) { this.lastOpenedTimestamp = lastOpenedTimestamp; }

    public long getTotalReadingSeconds() { return totalReadingSeconds; }
    public void addReadingSeconds(long seconds) {
        if (seconds > 0) {
            this.totalReadingSeconds += seconds;
        }
    }

    public int getTotalWords() { return totalWords; }
    public void setTotalWords(int totalWords) { this.totalWords = Math.max(0, totalWords); }
}
