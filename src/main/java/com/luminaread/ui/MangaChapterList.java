package com.luminaread.ui;

import com.luminaread.model.manga.MangaChapter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Keeps chapter order and language selection separate from Swing controls. */
final class MangaChapterList {
    private List<MangaChapter> all = List.of();
    private List<MangaChapter> visible = List.of();
    private List<String> languages = List.of();
    private String language = "";
    private boolean ascending = true;

    void clear() {
        all = List.of(); visible = List.of(); languages = List.of(); language = "";
    }

    void setChapters(List<MangaChapter> chapters) {
        all = List.copyOf(chapters);
        boolean ru = false, en = false;
        for (MangaChapter chapter : all) {
            ru |= "ru".equalsIgnoreCase(chapter.language);
            en |= "en".equalsIgnoreCase(chapter.language);
        }
        List<String> found = new ArrayList<>();
        if (ru) found.add("ru");
        if (en) found.add("en");
        languages = List.copyOf(found);
        language = found.isEmpty() ? "" : found.get(0);
        refresh();
    }

    void selectLanguage(String selected) {
        if (!languages.contains(selected)) throw new IllegalArgumentException("Unknown chapter language");
        language = selected;
        refresh();
    }

    void toggleOrder() { ascending = !ascending; refresh(); }
    boolean isAscending() { return ascending; }
    List<String> languages() { return languages; }
    List<MangaChapter> visible() { return visible; }

    private void refresh() {
        List<MangaChapter> filtered = new ArrayList<>();
        for (MangaChapter chapter : all) {
            if (language.isEmpty() || language.equalsIgnoreCase(chapter.language)) filtered.add(chapter);
        }
        if (!ascending) Collections.reverse(filtered);
        visible = List.copyOf(filtered);
    }
}
