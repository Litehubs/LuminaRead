package com.luminaread.model.manga;

public final class MangaItem {
    public final String id;
    public final String title;
    public final String rusTitle;
    public final String description;
    public final String coverUrl;
    public final String source;
    public final String status;
    public final String rating;

    public MangaItem(String id, String title, String rusTitle, String description, String coverUrl, String source, String status, String rating) {
        this.id = id;
        this.title = title != null ? title : "";
        this.rusTitle = rusTitle != null ? rusTitle : "";
        this.description = description != null ? description : "";
        this.coverUrl = coverUrl != null ? coverUrl : "";
        this.source = source != null ? source : "";
        this.status = status != null ? status : "";
        this.rating = rating != null ? rating : "★ —";
    }

    public String getDisplayTitle() {
        return (!rusTitle.isEmpty()) ? rusTitle + " (" + title + ")" : title;
    }
}
