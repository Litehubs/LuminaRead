package com.luminaread.service;

import com.luminaread.config.OpdsCatalogConfig;
import com.luminaread.config.ReaderDisplayConfig;
import com.luminaread.config.SettingsJsonRepository;
import com.luminaread.service.manga.MangaStateRepository;
import com.luminaread.service.ranobe.RanobeStateRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StateRoundTripTest {
    @TempDir Path dir;
    private static final String TEXT = "Книга \"quote\" \\ " +
            java.util.stream.IntStream.range(0, 32).collect(StringBuilder::new,
                    (out, ch) -> out.append((char) ch), StringBuilder::append) + " 📚";

    @Test void oversizedSettingsDoNotReplaceThePreviousFile() throws Exception {
        Path file = dir.resolve("settings.json");
        var repository = new SettingsJsonRepository(file);
        var display = new ReaderDisplayConfig();
        repository.save(display, null);
        byte[] previous = Files.readAllBytes(file);

        display.setFontFamily("x".repeat(1024 * 1024));
        assertThrows(java.io.UncheckedIOException.class, () -> repository.save(display, null));
        assertArrayEquals(previous, Files.readAllBytes(file));
        assertTrue(repository.load(new ReaderDisplayConfig(), null));
    }

    @Test void oversizedComicsAndNovelProgressPreserveTheLastReadableState() throws Exception {
        Path mangaFile = dir.resolve("manga.json");
        Path ranobeFile = dir.resolve("ranobe.json");
        var manga = new MangaStateRepository(mangaFile);
        var ranobe = new RanobeStateRepository(ranobeFile);
        var previousProgress = Map.of("title", "chapter");
        manga.saveState(Map.of(), Map.of(), previousProgress, Map.of());
        ranobe.saveState(Map.of(), Map.of(), previousProgress, Map.of());

        // Quoting each character takes two UTF-8 bytes, crossing the serialized limit.
        var oversized = Map.of("title", "\"".repeat(16 * 1024 * 1024));
        assertThrows(java.io.UncheckedIOException.class, () -> manga.saveState(Map.of(), Map.of(), oversized, Map.of()));
        assertThrows(java.io.UncheckedIOException.class, () -> ranobe.saveState(Map.of(), Map.of(), oversized, Map.of()));

        var mangaProgress = new HashMap<String, String>();
        var ranobeProgress = new HashMap<String, String>();
        manga.loadState(new HashMap<>(), new HashMap<>(), mangaProgress, new HashMap<>());
        ranobe.loadState(new HashMap<>(), new HashMap<>(), ranobeProgress, new HashMap<>());
        assertEquals(previousProgress, mangaProgress);
        assertEquals(previousProgress, ranobeProgress);
    }

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
