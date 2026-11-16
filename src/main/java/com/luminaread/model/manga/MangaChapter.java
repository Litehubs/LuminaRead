package com.luminaread.model.manga;

public final class MangaChapter {
    public final String id;
    public final String number;
    public final String title;
    public final String language;
    public final String source;

    public MangaChapter(String id, String number, String title, String language, String source) {
        this.id = id;
        this.number = number != null ? number : "";
        this.title = title != null ? title : "";
        this.language = language != null ? language : "ru";
        this.source = source != null ? source : "";
    }

    @Override
    public String toString() {
        String numStr = number.isEmpty() ? "" : "Глава " + number;
        String tStr = (title != null && !title.isEmpty()) ? " — " + title : "";
        String langBadge = " [" + language.toUpperCase() + "]";
        return (numStr.isEmpty() && tStr.isEmpty()) ? "Глава" + langBadge : (numStr + tStr + langBadge).trim();
    }
}
