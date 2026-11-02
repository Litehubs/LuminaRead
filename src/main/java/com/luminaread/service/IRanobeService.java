package com.luminaread.service;

import com.luminaread.model.ranobe.RanobeChapter;
import com.luminaread.model.ranobe.RanobeChapterContent;
import com.luminaread.model.ranobe.RanobeItem;

import java.util.List;
import java.util.Map;

/**
 * High-level abstraction defining operations for searching, fetching,
 * and persisting Ranobe (light novels).
 */
public interface IRanobeService {

    List<? extends RanobeItem> searchRanobe(String query);

    RanobeItem fetchRanobeDetails(RanobeItem basicItem);

    List<? extends RanobeChapter> fetchChapters(String slugUrl);

    RanobeChapterContent fetchChapterContent(RanobeChapter chapter, String slugUrl) throws Exception;

    void saveRanobeState(Map<String, ? extends RanobeItem> favorites,
                         Map<String, ? extends RanobeItem> history,
                         Map<String, String> lastReadChapters,
                         Map<String, String> preferences);

    <T extends RanobeItem> void loadRanobeState(Map<String, T> favorites,
                                                Map<String, T> history,
                                                Map<String, String> lastReadChapters,
                                                Map<String, String> preferences);
}
