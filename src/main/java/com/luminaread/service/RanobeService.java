package com.luminaread.service;

import com.luminaread.model.ranobe.RanobeChapter;
import com.luminaread.model.ranobe.RanobeChapterContent;
import com.luminaread.model.ranobe.RanobeItem;
import com.luminaread.service.ranobe.RanobeLibClient;
import com.luminaread.service.ranobe.RanobeStateRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Facade coordinating Ranobe operations: delegating HTTP API requests and HTML rendering
 * to {@link RanobeLibClient}, and state persistence to {@link RanobeStateRepository}.
 * Preserves full backward compatibility for existing callers.
 */
public class RanobeService implements IRanobeService {

    private final RanobeLibClient ranobeLibClient;
    private final RanobeStateRepository stateRepository;

    public RanobeService() {
        this(new RanobeLibClient(), new RanobeStateRepository());
    }

    public RanobeService(IMediaCacheService mediaCacheService) {
        this(new RanobeLibClient(mediaCacheService), new RanobeStateRepository());
    }

    public RanobeService(RanobeLibClient ranobeLibClient, RanobeStateRepository stateRepository) {
        this.ranobeLibClient = ranobeLibClient != null ? ranobeLibClient : new RanobeLibClient();
        this.stateRepository = stateRepository != null ? stateRepository : new RanobeStateRepository();
    }

    public RanobeLibClient getRanobeLibClient() {
        return ranobeLibClient;
    }

    public RanobeStateRepository getStateRepository() {
        return stateRepository;
    }

    // =========================================================================
    // Backward-Compatible Domain Model Subclasses
    // =========================================================================

    public static class RanobeItem extends com.luminaread.model.ranobe.RanobeItem {

        public RanobeItem(String id, String title, String rusTitle, String description,
                          String coverUrl, String status, String rating, String releaseDate,
                          List<String> genres, List<String> authors) {
            super(id, title, rusTitle, description, coverUrl, status, rating, releaseDate, genres, authors);
        }

        public RanobeItem(com.luminaread.model.ranobe.RanobeItem model) {
            super(model != null ? model.id : "",
                  model != null ? model.title : "",
                  model != null ? model.rusTitle : "",
                  model != null ? model.description : "",
                  model != null ? model.coverUrl : "",
                  model != null ? model.status : "",
                  model != null ? model.rating : "★ —",
                  model != null ? model.releaseDate : "",
                  model != null ? model.genres : null,
                  model != null ? model.authors : null);
        }
    }

    public static class RanobeChapter extends com.luminaread.model.ranobe.RanobeChapter {

        public RanobeChapter(String id, String volume, String number, String title, String branchId, String teamName) {
            super(id, volume, number, title, branchId, teamName);
        }

        public RanobeChapter(com.luminaread.model.ranobe.RanobeChapter model) {
            super(model != null ? model.id : "",
                  model != null ? model.volume : "1",
                  model != null ? model.number : "1",
                  model != null ? model.title : "",
                  model != null ? model.branchId : "",
                  model != null ? model.teamName : "");
        }
    }

    public static class RanobeChapterContent extends com.luminaread.model.ranobe.RanobeChapterContent {

        public RanobeChapterContent(String title, String volume, String number,
                                    String rawHtmlContent, String plainTextContent, List<String> illustrations) {
            super(title, volume, number, rawHtmlContent, plainTextContent, illustrations);
        }

        public RanobeChapterContent(com.luminaread.model.ranobe.RanobeChapterContent model) {
            super(model != null ? model.title : "",
                  model != null ? model.volume : "1",
                  model != null ? model.number : "1",
                  model != null ? model.rawHtmlContent : "",
                  model != null ? model.plainTextContent : "",
                  model != null ? model.illustrations : null);
        }
    }

    public static class CachedImageInfo extends IMediaCacheService.CachedImageInfo {

        public CachedImageInfo(String localUrl, int width, int height) {
            super(localUrl, width, height);
        }

        public CachedImageInfo(IMediaCacheService.CachedImageInfo info) {
            super(info != null ? info.localUrl : "", info != null ? info.width : 0, info != null ? info.height : 0);
        }
    }

    // =========================================================================
    // Core Service API Delegation
    // =========================================================================

    @Override
    public List<RanobeItem> searchRanobe(String query) {
        List<com.luminaread.model.ranobe.RanobeItem> raw = ranobeLibClient.searchRanobe(query);
        List<RanobeItem> items = new ArrayList<>(raw.size());
        for (com.luminaread.model.ranobe.RanobeItem it : raw) {
            if (it instanceof RanobeItem) {
                items.add((RanobeItem) it);
            } else {
                items.add(new RanobeItem(it));
            }
        }
        return items;
    }

    @Override
    public RanobeItem fetchRanobeDetails(com.luminaread.model.ranobe.RanobeItem basicItem) {
        com.luminaread.model.ranobe.RanobeItem full = ranobeLibClient.fetchRanobeDetails(basicItem);
        if (full instanceof RanobeItem) {
            return (RanobeItem) full;
        }
        return full != null ? new RanobeItem(full) : null;
    }

    public RanobeItem fetchRanobeDetails(RanobeItem basicItem) {
        return fetchRanobeDetails((com.luminaread.model.ranobe.RanobeItem) basicItem);
    }

    @Override
    public List<RanobeChapter> fetchChapters(String slugUrl) {
        List<com.luminaread.model.ranobe.RanobeChapter> raw = ranobeLibClient.fetchChapters(slugUrl);
        List<RanobeChapter> chapters = new ArrayList<>(raw.size());
        for (com.luminaread.model.ranobe.RanobeChapter ch : raw) {
            if (ch instanceof RanobeChapter) {
                chapters.add((RanobeChapter) ch);
            } else {
                chapters.add(new RanobeChapter(ch));
            }
        }
        return chapters;
    }

    @Override
    public RanobeChapterContent fetchChapterContent(com.luminaread.model.ranobe.RanobeChapter chapter, String slugUrl) throws Exception {
        com.luminaread.model.ranobe.RanobeChapterContent content = ranobeLibClient.fetchChapterContent(chapter, slugUrl);
        if (content instanceof RanobeChapterContent) {
            return (RanobeChapterContent) content;
        }
        return content != null ? new RanobeChapterContent(content) : null;
    }

    public RanobeChapterContent fetchChapterContent(RanobeChapter chapter, String slugUrl) throws Exception {
        return fetchChapterContent((com.luminaread.model.ranobe.RanobeChapter) chapter, slugUrl);
    }

    // =========================================================================
    // State Persistence Delegation
    // =========================================================================

    @Override
    public synchronized void saveRanobeState(Map<String, ? extends com.luminaread.model.ranobe.RanobeItem> favorites,
                                            Map<String, ? extends com.luminaread.model.ranobe.RanobeItem> history,
                                            Map<String, String> lastReadChapters,
                                            Map<String, String> preferences) {
        stateRepository.saveState(favorites, history, lastReadChapters, preferences);
    }

    @Override
    @SuppressWarnings("unchecked")
    public synchronized <T extends com.luminaread.model.ranobe.RanobeItem> void loadRanobeState(
            Map<String, T> favorites,
            Map<String, T> history,
            Map<String, String> lastReadChapters,
            Map<String, String> preferences) {
        stateRepository.loadState(favorites, history, lastReadChapters, preferences,
                (id, title, rusTitle, description, coverUrl, status, rating, releaseDate, genres, authors) ->
                        (T) new RanobeItem(id, title, rusTitle, description, coverUrl, status, rating, releaseDate, genres, authors));
    }

    // =========================================================================
    // Static Utility Delegation (Backward Compatibility)
    // =========================================================================

    public static CachedImageInfo cacheIllustration(String urlStr) {
        IMediaCacheService.CachedImageInfo info = RanobeLibClient.cacheIllustration(urlStr);
        return new CachedImageInfo(info.localUrl, info.width, info.height);
    }

    public static String docToHtml(Object node, List<String> illustrations, java.util.Set<String> renderedImages) {
        return RanobeLibClient.docToHtml(node, illustrations, renderedImages);
    }
}
