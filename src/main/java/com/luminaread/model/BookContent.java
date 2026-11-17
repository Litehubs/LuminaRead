package com.luminaread.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class BookContent {
    private static final long MAX_BYTES = 64L * 1024 * 1024;
    private static final int MAX_CHAPTER_CHARS = 2_000_000;
    private static final int MAX_CHAPTERS = 10_000;
    private long chapterBytes;
    private String title;
    private String author;
    private String description;
    private String series;
    private String language;
    private byte[] coverImage;
    private final List<Chapter> chapters = new ArrayList<>();


    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getAuthor() { return author; }
    public void setAuthor(String author) { this.author = author; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getSeries() { return series; }
    public void setSeries(String series) { this.series = series; }

    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }

    public byte[] getCoverImage() { return coverImage; }
    public void setCoverImage(byte[] coverImage) {
        if (chapterBytes + size(coverImage) > MAX_BYTES) throw tooLarge();
        this.coverImage = coverImage;
    }

    public long estimatedBytes() { return chapterBytes + size(coverImage); }

    private static int size(byte[] bytes) { return bytes == null ? 0 : bytes.length; }

    private static IllegalArgumentException tooLarge() {
        return new IllegalArgumentException("Документ превышает допустимый объём текста и изображений");
    }

    public List<Chapter> getChapters() { return Collections.unmodifiableList(chapters); }
    public void addChapter(Chapter chapter) {
        if (chapter == null) return;
        int htmlChars = chapter.getHtmlContent().length();
        long added = 128 + 2L * (htmlChars + chapter.getPlainText().length() + chapter.getTitle().length());
        if (htmlChars > MAX_CHAPTER_CHARS || chapters.size() >= MAX_CHAPTERS
                || estimatedBytes() + added > MAX_BYTES) throw tooLarge();
        chapters.add(chapter);
        chapterBytes += added;
    }

    public int getTotalWords() {
        int sum = 0;
        for (Chapter ch : chapters) {
            sum += ch.getWordCount();
        }
        return sum;
    }
}
