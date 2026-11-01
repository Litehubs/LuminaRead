package com.luminaread;

import com.luminaread.config.OpdsCatalogConfig;
import com.luminaread.config.ReaderDisplayConfig;
import com.luminaread.config.SettingsJsonRepository;
import com.luminaread.model.ReaderSettings;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class ReaderConfigTest {
    public static void main(String[] args) throws Exception {
        System.out.println("=== RUNNING READER CONFIG ARCHITECTURE TESTS ===");

        testClampingBoundaries();
        testNullSafety();
        testOpdsCatalogEncapsulation();
        testReaderSettingsAdapter();
        testJsonPersistence();

        System.out.println("=== ALL READER CONFIG TESTS PASSED! ===");
    }

    private static void testClampingBoundaries() {
        ReaderDisplayConfig config = new ReaderDisplayConfig();

        config.setFontSize(5);
        if (config.getFontSize() != ReaderDisplayConfig.MIN_FONT_SIZE) {
            throw new AssertionError("Font size underflow not clamped: " + config.getFontSize());
        }

        config.setFontSize(100);
        if (config.getFontSize() != ReaderDisplayConfig.MAX_FONT_SIZE) {
            throw new AssertionError("Font size overflow not clamped: " + config.getFontSize());
        }

        config.setLineHeight(0.5);
        if (config.getLineHeight() != ReaderDisplayConfig.MIN_LINE_HEIGHT) {
            throw new AssertionError("Line height underflow not clamped: " + config.getLineHeight());
        }

        config.setLineHeight(5.0);
        if (config.getLineHeight() != ReaderDisplayConfig.MAX_LINE_HEIGHT) {
            throw new AssertionError("Line height overflow not clamped: " + config.getLineHeight());
        }

        config.setHorizontalMarginPx(5);
        if (config.getHorizontalMarginPx() != ReaderDisplayConfig.MIN_HORIZONTAL_MARGIN) {
            throw new AssertionError("Horizontal margin underflow not clamped: " + config.getHorizontalMarginPx());
        }

        System.out.println("[PASS] Clamping boundaries validated successfully.");
    }

    private static void testNullSafety() {
        ReaderDisplayConfig config = new ReaderDisplayConfig();

        config.setThemeId(null);
        if (config.getThemeId() == null || config.getThemeId().isBlank()) {
            throw new AssertionError("Null themeId should have safe default");
        }

        config.setTextAlignment(null);
        if (config.getTextAlignment() != ReaderDisplayConfig.TextAlignment.LEFT) {
            throw new AssertionError("Null textAlignment should fallback to LEFT");
        }

        config.setReadingMode(null);
        if (config.getReadingMode() != ReaderDisplayConfig.ReadingMode.PAGED) {
            throw new AssertionError("Null readingMode should fallback to PAGED");
        }

        System.out.println("[PASS] Null-safety validated successfully.");
    }

    private static void testOpdsCatalogEncapsulation() {
        OpdsCatalogConfig opds = new OpdsCatalogConfig();
        List<String> list = opds.getCustomOpdsCatalogs();

        if (list.size() < 3) {
            throw new AssertionError("Expected default catalogs in OPDS config");
        }

        try {
            list.add("http://malicious-catalog.org/opds");
            throw new AssertionError("Direct modification of OPDS catalog list must throw UnsupportedOperationException");
        } catch (UnsupportedOperationException expected) {
            // Success: list is properly encapsulated and unmodifiable
        }

        boolean added = opds.addCatalog("https://valid-library.org/opds");
        if (!added || !opds.getCustomOpdsCatalogs().contains("https://valid-library.org/opds")) {
            throw new AssertionError("addCatalog should add valid new catalog");
        }

        boolean dupAdded = opds.addCatalog("https://valid-library.org/opds");
        if (dupAdded) {
            throw new AssertionError("Duplicate catalog addition must return false");
        }

        System.out.println("[PASS] OPDS encapsulation and immutability validated.");
    }

    private static void testReaderSettingsAdapter() {
        ReaderSettings settings = new ReaderSettings();
        settings.setFontSize(25);
        if (settings.getFontSize() != 25) {
            throw new AssertionError("ReaderSettings adapter failed to set font size");
        }

        settings.setTextAlignment(ReaderSettings.TextAlignment.JUSTIFY);
        if (settings.getTextAlignment() != ReaderSettings.TextAlignment.JUSTIFY) {
            throw new AssertionError("ReaderSettings adapter failed to set text alignment");
        }
        if (!"justify".equals(settings.getTextAlignment().getCssValue())) {
            throw new AssertionError("CSS value mapping broken: " + settings.getTextAlignment().getCssValue());
        }

        List<String> opdsList = settings.getCustomOpdsCatalogs();
        try {
            opdsList.add(null);
            throw new AssertionError("getCustomOpdsCatalogs() must be unmodifiable");
        } catch (UnsupportedOperationException expected) {
            // Success
        }

        System.out.println("[PASS] ReaderSettings backward-compatible adapter validated.");
    }

    private static void testJsonPersistence() throws Exception {
        Path tempFile = Files.createTempFile("luminaread-settings-test-", ".json");
        try {
            SettingsJsonRepository repo = new SettingsJsonRepository(tempFile);
            ReaderDisplayConfig display = new ReaderDisplayConfig();
            display.setFontSize(27);
            display.setThemeId("SEPIA_WARM");
            display.setTextAlignment(ReaderDisplayConfig.TextAlignment.JUSTIFY);
            display.setReadingMode(ReaderDisplayConfig.ReadingMode.SCROLL);

            OpdsCatalogConfig opds = new OpdsCatalogConfig();
            opds.addCatalog("https://my-test-opds.org/feed");

            repo.save(display, opds);

            ReaderDisplayConfig loadedDisplay = new ReaderDisplayConfig();
            OpdsCatalogConfig loadedOpds = new OpdsCatalogConfig();
            boolean success = repo.load(loadedDisplay, loadedOpds);

            if (!success) {
                throw new AssertionError("Failed to load settings from JSON");
            }
            if (loadedDisplay.getFontSize() != 27) {
                throw new AssertionError("Expected fontSize 27, got " + loadedDisplay.getFontSize());
            }
            if (!"SEPIA_WARM".equals(loadedDisplay.getThemeId())) {
                throw new AssertionError("Expected themeId SEPIA_WARM, got " + loadedDisplay.getThemeId());
            }
            if (loadedDisplay.getTextAlignment() != ReaderDisplayConfig.TextAlignment.JUSTIFY) {
                throw new AssertionError("Expected JUSTIFY text alignment");
            }
            if (loadedDisplay.getReadingMode() != ReaderDisplayConfig.ReadingMode.SCROLL) {
                throw new AssertionError("Expected SCROLL reading mode");
            }
            if (!loadedOpds.getCustomOpdsCatalogs().contains("https://my-test-opds.org/feed")) {
                throw new AssertionError("Custom OPDS catalog not restored from JSON");
            }

            System.out.println("[PASS] Settings JSON persistence and restoration validated.");
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }
}
