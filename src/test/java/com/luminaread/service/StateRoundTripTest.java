package com.luminaread.service;

import com.luminaread.config.OpdsCatalogConfig;
import com.luminaread.config.ReaderDisplayConfig;
import com.luminaread.config.SettingsJsonRepository;
import com.luminaread.service.manga.MangaStateRepository;
import com.luminaread.service.ranobe.RanobeStateRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StateRoundTripTest {
    @TempDir Path dir;
    private static final String TEXT = "Книга \"quote\" \\ " +
            java.util.stream.IntStream.range(0, 32).collect(StringBuilder::new,
                    (out, ch) -> out.append((char) ch), StringBuilder::append) + " 📚";

    @Test void mangaPreservesControlCharacters() {
        var repository = new MangaStateRepository(dir.resolve("manga.json"));
        repository.saveState(Map.of(), Map.of(), Map.of(TEXT, TEXT), Map.of());
        var restored = new HashMap<String, String>();
        repository.loadState(new HashMap<>(), new HashMap<>(), restored, new HashMap<>());
        assertEquals(Map.of(TEXT, TEXT), restored);
    }

    @Test void ranobePreservesControlCharacters() {
        var repository = new RanobeStateRepository(dir.resolve("ranobe.json"));
        repository.saveState(Map.of(), Map.of(), Map.of(TEXT, TEXT), Map.of());
        var restored = new HashMap<String, String>();
        repository.loadState(new HashMap<>(), new HashMap<>(), restored, new HashMap<>());
        assertEquals(Map.of(TEXT, TEXT), restored);
    }

    @Test void settingsPreserveControlCharacters() {
        var repository = new SettingsJsonRepository(dir.resolve("settings.json"));
        var display = new ReaderDisplayConfig();
        display.setFontFamily(TEXT);
        repository.save(display, new OpdsCatalogConfig());
        var restored = new ReaderDisplayConfig();
        assertTrue(repository.load(restored, new OpdsCatalogConfig()));
        assertEquals(display.getFontFamily(), restored.getFontFamily());
    }
}
