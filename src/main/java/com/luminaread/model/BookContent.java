package com.luminaread.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class BookContent {
    private String title;
    private String author;
    private String description;
    private String series;
    private String language;
    private byte[] coverImage;
    private final List<Chapter> chapters = new ArrayList<>();
    private final Map<String, byte[]> embeddedImages = new HashMap<>();

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
    public void setCoverImage(byte[] coverImage) { this.coverImage = coverImage; }

    public List<Chapter> getChapters() { return Collections.unmodifiableList(chapters); }
    public void addChapter(Chapter chapter) {
        if (chapter != null) {
            chapters.add(chapter);
        }
    }

    public Map<String, byte[]> getEmbeddedImages() { return embeddedImages; }
    public void putEmbeddedImage(String key, byte[] bytes) {
        if (key != null && bytes != null) {
            embeddedImages.put(key, bytes);
        }
    }

    public int getTotalWords() {
        int sum = 0;
        for (Chapter ch : chapters) {
            sum += ch.getWordCount();
        }
        return sum;
    }
}
