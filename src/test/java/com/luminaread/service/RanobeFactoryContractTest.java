package com.luminaread.service;

import com.luminaread.model.ranobe.RanobeItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RanobeFactoryContractTest {
    static class CustomItem extends RanobeItem {
        CustomItem(String id, String title, String rusTitle, String description, String coverUrl,
                   String status, String rating, String releaseDate, List<String> genres, List<String> authors) {
            super(id, title, rusTitle, description, coverUrl, status, rating, releaseDate, genres, authors);
        }
    }

    @TempDir Path home;

    @Test void loadUsesTheCallerSpecifiedSubtype() {
        var repo = new com.luminaread.service.ranobe.RanobeStateRepository(home.resolve("ranobe.json"));
        Map<String, CustomItem> original = new LinkedHashMap<>();
        original.put("id", new CustomItem("id", "title", "", "", "", "", "", "", List.of(), List.of()));
        repo.saveState(original, Map.of(), Map.of(), Map.of());
        Map<String, CustomItem> loaded = new LinkedHashMap<>();
        IRanobeService service = new RanobeService(null, repo);
        service.loadRanobeState(loaded, new LinkedHashMap<>(), new LinkedHashMap<>(), new LinkedHashMap<>(), CustomItem::new);
        assertInstanceOf(CustomItem.class, loaded.get("id"));
    }
}
