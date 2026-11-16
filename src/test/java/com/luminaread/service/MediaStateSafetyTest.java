package com.luminaread.service;

import com.luminaread.service.manga.MangaStateRepository;
import com.luminaread.service.ranobe.RanobeStateRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;

class MediaStateSafetyTest {
    @TempDir Path dir;

    @Test void corruptMangaStateIsReportedAndPreserved() throws Exception {
        Path file = dir.resolve("manga.json");
        Files.writeString(file, "bad json");
        MangaStateRepository repo = new MangaStateRepository(file);
        assertThrows(IllegalStateException.class,
                () -> repo.loadState(new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>()));
        assertEquals("bad json", Files.readString(file));
    }

    @Test void corruptRanobeStateIsReportedAndPreserved() throws Exception {
        Path file = dir.resolve("ranobe.json");
        Files.writeString(file, "[]");
        RanobeStateRepository repo = new RanobeStateRepository(file);
        assertThrows(IllegalStateException.class,
                () -> repo.loadState(new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>()));
        assertEquals("[]", Files.readString(file));
    }
}
