package com.luminaread.service;

import com.luminaread.service.manga.MangaDexClient;
import com.luminaread.service.manga.MangaLibClient;
import com.luminaread.service.manga.MangaStateRepository;
import com.luminaread.service.manga.RemangaClient;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Facade coordinating manga source clients (MangaLib, Remanga, MangaDex),
 * disk state persistence, and media cache decoding.
 */
public class MangaService implements IMangaService {

    private final MangaLibClient mangaLibClient;
    private final RemangaClient remangaClient;
    private final MangaDexClient mangaDexClient;
    private final MangaStateRepository stateRepository;
    private final IMediaCacheService mediaCacheService;

    public MangaService() {
        this(new MangaLibClient(), new RemangaClient(), new MangaDexClient(),
                new MangaStateRepository(), MediaCacheService.getInstance());
    }

    public MangaService(IMediaCacheService mediaCacheService) {
        this(new MangaLibClient(), new RemangaClient(), new MangaDexClient(),
                new MangaStateRepository(), mediaCacheService);
    }

    public MangaService(MangaLibClient mangaLibClient,
                        RemangaClient remangaClient,
                        MangaDexClient mangaDexClient,
                        MangaStateRepository stateRepository,
                        IMediaCacheService mediaCacheService) {
        this.mangaLibClient = mangaLibClient != null ? mangaLibClient : new MangaLibClient();
        this.remangaClient = remangaClient != null ? remangaClient : new RemangaClient();
        this.mangaDexClient = mangaDexClient != null ? mangaDexClient : new MangaDexClient();
        this.stateRepository = stateRepository != null ? stateRepository : new MangaStateRepository();
        this.mediaCacheService = mediaCacheService != null ? mediaCacheService : MediaCacheService.getInstance();
    }

    public static class MangaItem {
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

    public static class MangaChapter {
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

    @Override
    public List<MangaItem> searchManga(String query, String sourceFilter) {
        List<MangaItem> results = new ArrayList<>();
        String q = (query == null || query.trim().isEmpty()) ? "" : query.trim();
        boolean all = sourceFilter == null || "Все источники".equals(sourceFilter);

        if (all || sourceFilter.contains("MangaLib")) {
            results.addAll(mangaLibClient.search(q));
        }
        if (all || sourceFilter.contains("Remanga")) {
            results.addAll(remangaClient.search(q));
        }
        if (all || sourceFilter.contains("MangaDex")) {
            results.addAll(mangaDexClient.search(q));
        }
        return results;
    }

    @Override
    public String fetchDescription(MangaItem manga) {
        if (manga == null) return "Описание отсутствует.";
        if (manga.source.startsWith("MangaLib")) {
            return mangaLibClient.fetchDescription(manga);
        } else if (manga.source.startsWith("Remanga")) {
            return remangaClient.fetchDescription(manga);
        } else if ("MangaDex".equals(manga.source)) {
            return mangaDexClient.fetchDescription(manga);
        }
        return manga.description != null && !manga.description.isEmpty() ? manga.description : "Описание отсутствует.";
    }

    @Override
    public List<MangaChapter> fetchChapters(MangaItem manga) {
        if (manga == null) return new ArrayList<>();
        if ("MangaDex".equals(manga.source)) {
            return mangaDexClient.fetchChapters(manga);
        } else if (manga.source.startsWith("MangaLib")) {
            return mangaLibClient.fetchChapters(manga);
        } else if (manga.source.startsWith("Remanga")) {
            return remangaClient.fetchChapters(manga);
        }
        return new ArrayList<>();
    }

    @Override
    public List<String> fetchPageUrls(MangaChapter chapter) {
        if (chapter == null) return new ArrayList<>();
        if ("MangaDex".equals(chapter.source)) {
            return mangaDexClient.fetchPageUrls(chapter);
        } else if (chapter.source.startsWith("MangaLib")) {
            return mangaLibClient.fetchPageUrls(chapter);
        } else if (chapter.source.startsWith("Remanga")) {
            return remangaClient.fetchPageUrls(chapter);
        }
        return new ArrayList<>();
    }

    @Override
    public BufferedImage loadImageCached(String urlStr) {
        return mediaCacheService.loadImageCached(urlStr, mangaLibClient.getKnownImageServers());
    }

    @Override
    public synchronized void saveMangaState(Map<String, MangaItem> favorites,
                                           Map<String, MangaItem> history,
                                           Map<String, String> lastReadChapterMap) {
        stateRepository.saveState(favorites, history, lastReadChapterMap, null);
    }

    @Override
    public synchronized void saveMangaState(Map<String, MangaItem> favorites,
                                           Map<String, MangaItem> history,
                                           Map<String, String> lastReadChapterMap,
                                           Map<String, String> readerPrefs) {
        stateRepository.saveState(favorites, history, lastReadChapterMap, readerPrefs);
    }

    @Override
    public synchronized void loadMangaState(Map<String, MangaItem> favorites,
                                           Map<String, MangaItem> history,
                                           Map<String, String> lastReadChapterMap) {
        stateRepository.loadState(favorites, history, lastReadChapterMap, null);
    }

    @Override
    public synchronized void loadMangaState(Map<String, MangaItem> favorites,
                                           Map<String, MangaItem> history,
                                           Map<String, String> lastReadChapterMap,
                                           Map<String, String> readerPrefs) {
        stateRepository.loadState(favorites, history, lastReadChapterMap, readerPrefs);
    }

    public MangaLibClient getMangaLibClient() {
        return mangaLibClient;
    }

    public RemangaClient getRemangaClient() {
        return remangaClient;
    }

    public MangaDexClient getMangaDexClient() {
        return mangaDexClient;
    }

    public MangaStateRepository getStateRepository() {
        return stateRepository;
    }

    public IMediaCacheService getMediaCacheService() {
        return mediaCacheService;
    }
}
