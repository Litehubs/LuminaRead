package com.luminaread.service;

import com.luminaread.model.manga.MangaChapter;
import com.luminaread.model.manga.MangaItem;
import com.luminaread.model.manga.MangaSearchResult;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Map;

public interface IMangaService {

    List<MangaItem> searchManga(String query, String sourceFilter);

    MangaSearchResult searchMangaDetailed(String query, String sourceFilter);

    String fetchDescription(MangaItem manga);

    List<MangaChapter> fetchChapters(MangaItem manga);

    List<String> fetchPageUrls(MangaChapter chapter);

    BufferedImage loadImageCached(String urlStr);

    void saveMangaState(Map<String, MangaItem> favorites,
                        Map<String, MangaItem> history,
                        Map<String, String> progress);

    void saveMangaState(Map<String, MangaItem> favorites,
                        Map<String, MangaItem> history,
                        Map<String, String> progress,
                        Map<String, String> preferences);

    void loadMangaState(Map<String, MangaItem> favorites,
                        Map<String, MangaItem> history,
                        Map<String, String> progress);

    void loadMangaState(Map<String, MangaItem> favorites,
                        Map<String, MangaItem> history,
                        Map<String, String> progress,
                        Map<String, String> preferences);
}
