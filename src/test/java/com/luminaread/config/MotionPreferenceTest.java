package com.luminaread.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class MotionPreferenceTest {
    @Test
    void olderSettingsKeepDefaultAndExplicitOptOutRoundTrips(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("settings.json");
        Files.writeString(file, "{\"display\":{\"fontSize\":21}}");
        SettingsJsonRepository repository = new SettingsJsonRepository(file);
        ReaderDisplayConfig settings = new ReaderDisplayConfig();
        assertTrue(repository.load(settings, null));
        assertFalse(settings.isReduceMotion());
        assertEquals(21, settings.getFontSize());

        settings.setReduceMotion(true);
        repository.save(settings, null);
        ReaderDisplayConfig reloaded = new ReaderDisplayConfig();
        assertTrue(repository.load(reloaded, null));
        assertTrue(reloaded.isReduceMotion());
        assertEquals(21, reloaded.getFontSize());
    }
}
