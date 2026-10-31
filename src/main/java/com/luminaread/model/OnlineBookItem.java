package com.luminaread.model;

import java.util.LinkedHashMap;
import java.util.Map;

public class OnlineBookItem {
    private final String title;
    private final String author;
    private final String summary;
    private final String language;
    private final String sourceName;
    private String coverUrl;
    private final Map<String, String> downloadLinks = new LinkedHashMap<>();

    public OnlineBookItem(String title, String author, String summary, String language, String sourceName) {
        this.title = (title == null || title.isBlank()) ? "Без названия" : title.trim();
        this.author = (author == null || author.isBlank()) ? "Неизвестный автор" : author.trim();
        this.summary = summary != null ? summary.trim() : "";
        this.language = (language == null || language.isBlank()) ? "ru/en" : language.trim();
        this.sourceName = sourceName != null ? sourceName : "Онлайн-каталог";
    }

    public String getTitle() { return title; }
    public String getAuthor() { return author; }
    public String getSummary() { return summary; }
    public String getLanguage() { return language; }
    public String getSourceName() { return sourceName; }
    public String getCoverUrl() { return coverUrl; }
    public void setCoverUrl(String coverUrl) { this.coverUrl = coverUrl; }

    public void addDownloadLink(String formatLabel, String url) {
        if (formatLabel != null && url != null && !url.isBlank()) {
            downloadLinks.put(formatLabel.toUpperCase(), url.trim());
        }
    }

    public Map<String, String> getDownloadLinks() {
        return downloadLinks;
    }

    public boolean hasDownloadLinks() {
        return !downloadLinks.isEmpty();
    }
}
