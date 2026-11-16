package com.luminaread.model.ranobe;

import java.util.List;

@FunctionalInterface
public interface RanobeItemFactory<T extends RanobeItem> {
    T create(String id, String title, String rusTitle, String description,
             String coverUrl, String status, String rating, String releaseDate,
             List<String> genres, List<String> authors);
}
