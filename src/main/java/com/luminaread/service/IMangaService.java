package com.luminaread.service;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Map;

public interface IMangaService {

    List<MangaService.MangaItem> searchManga(String query, String sourceFilter);

    String fetchDescription(MangaService.MangaItem manga);

    List<MangaService.MangaChapter> fetchChapters(MangaService.MangaItem manga);

    List<String> fetchPageUrls(MangaService.MangaChapter chapter);

    BufferedImage loadImageCached(String urlStr);

    void saveMangaState(Map<String, MangaService.MangaItem> favorites,
                        Map<String, MangaService.MangaItem> history,
                        Map<String, String> progress);

    void saveMangaState(Map<String, MangaService.MangaItem> favorites,
                        Map<String, MangaService.MangaItem> history,
                        Map<String, String> progress,
                        Map<String, String> preferences);

    void loadMangaState(Map<String, MangaService.MangaItem> favorites,
                        Map<String, MangaService.MangaItem> history,
                        Map<String, String> progress);

    void loadMangaState(Map<String, MangaService.MangaItem> favorites,
                        Map<String, MangaService.MangaItem> history,
                        Map<String, String> progress,
                        Map<String, String> preferences);
}
