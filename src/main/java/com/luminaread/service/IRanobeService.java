package com.luminaread.service;

import java.util.List;
import java.util.Map;

public interface IRanobeService {

    List<RanobeService.RanobeItem> searchRanobe(String query);

    RanobeService.RanobeItem fetchRanobeDetails(RanobeService.RanobeItem basicItem);

    List<RanobeService.RanobeChapter> fetchChapters(String slugUrl);

    RanobeService.RanobeChapterContent fetchChapterContent(RanobeService.RanobeChapter chapter, String slugUrl) throws Exception;

    void saveRanobeState(Map<String, RanobeService.RanobeItem> favorites,
                         Map<String, RanobeService.RanobeItem> history,
                         Map<String, String> lastReadChapters,
                         Map<String, String> preferences);

    void loadRanobeState(Map<String, RanobeService.RanobeItem> favorites,
                         Map<String, RanobeService.RanobeItem> history,
                         Map<String, String> lastReadChapters,
                         Map<String, String> preferences);
}
