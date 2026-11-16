package com.luminaread.model.manga;

import java.util.List;

public record MangaSearchResult(List<MangaItem> items, List<String> failedSources) {
    public MangaSearchResult {
        items = List.copyOf(items);
        failedSources = List.copyOf(failedSources);
    }
}
