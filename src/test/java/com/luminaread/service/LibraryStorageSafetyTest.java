package com.luminaread.service;

import com.luminaread.util.AtomicFile;
import com.luminaread.model.Book;
import com.luminaread.parser.BookParser;
import com.luminaread.parser.BookParserRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class LibraryStorageSafetyTest {
    @TempDir Path home;

    @Test void failedReplacementPreservesOriginal() throws Exception {
        Path file = home.resolve("state");
        Files.writeString(file, "original");
        assertThrows(IOException.class, () -> AtomicFile.write(file, out -> {
            out.write("incomplete".getBytes());
            throw new IOException("disk failure");
        }));
        assertEquals("original", Files.readString(file));
        try (var files = Files.list(home)) { assertEquals(1, files.count()); }
    }

    @Test void corruptLibraryIsNeverReplacedByEmptyState() throws Exception {
        Path dir = Files.createDirectories(home.resolve(".luminaread"));
        Path file = dir.resolve("library_state.bin");
        Files.writeString(file, "damaged library");
        assertThrows(IllegalStateException.class, () -> new LibraryStorageService(home));
        assertEquals("damaged library", Files.readString(file));
        Files.delete(file);
    }

    @Test void rejectsUnexpectedSerializedTypeBeforeItsReadHookRuns() throws Exception {
        Path file = Files.createDirectories(home.resolve(".luminaread")).resolve("library_state.bin");
        try (var output = new java.io.ObjectOutputStream(Files.newOutputStream(file))) {
            output.writeObject(new ForeignState());
        }
        byte[] before = Files.readAllBytes(file);
        var error = assertThrows(IllegalStateException.class, () -> new LibraryStorageService(home));
        assertInstanceOf(java.io.InvalidClassException.class, error.getCause());
        assertArrayEquals(before, Files.readAllBytes(file));
    }

    private static final class ForeignState implements java.io.Serializable {
        @java.io.Serial private static final long serialVersionUID = 1L;

        @java.io.Serial
        private void readObject(java.io.ObjectInputStream input) {
            throw new AssertionError("Unexpected deserialization hook executed");
        }
    }

    @Test void embeddedSettingsRemainAuthoritativeAfterJsonWriteFailure() throws Exception {
        LibraryStorageService storage = new LibraryStorageService(home);
        storage.saveState();
        Path json = home.resolve(".luminaread/settings.json");
        Files.delete(json);
        Files.createDirectory(json);
        storage.getSettings().getDisplayConfig().setFontSize(27);
        assertThrows(UncheckedIOException.class, storage::saveState);
        LibraryStorageService reloaded = new LibraryStorageService(home);
        assertEquals(27, reloaded.getSettings().getDisplayConfig().getFontSize());
    }

    @Test void invalidLegacySettingsAreNotSilentlyOverwritten() throws Exception {
        Path dir = Files.createDirectories(home.resolve(".luminaread"));
        Path json = dir.resolve("settings.json");
        Files.writeString(json, "not json");
        assertThrows(IllegalStateException.class, () -> new LibraryStorageService(home));
        assertEquals("not json", Files.readString(json));
    }

    @Test void malformedLegacySectionIsRejectedBeforeApplyingPreferences() throws Exception {
        var file = home.resolve("settings.json");
        var display = new com.luminaread.config.ReaderDisplayConfig();
        var repository = new com.luminaread.config.SettingsJsonRepository();
        int fontSize = display.getFontSize();
        Files.writeString(file, """
                {"display":{"fontSize":27},"opds":{"catalogs":{}}}
                """);
        assertFalse(repository.load(display, new com.luminaread.config.OpdsCatalogConfig(), file));
        assertEquals(fontSize, display.getFontSize());

        double originalLineHeight = display.getLineHeight();
        Files.writeString(file, """
                {"display":{"fontSize":4294967297,"lineHeight":"NaN"}}
                """);
        assertTrue(repository.load(display, null, file));
        assertEquals(fontSize, display.getFontSize());
        assertEquals(originalLineHeight, display.getLineHeight());
    }

    @Test void showsAnnotationsAsTextAndFallsBackForInvalidLegacyColor() throws Exception {
        var storage = new LibraryStorageService(home);
        String markup = "<img src='file:/no-such-image.png'>";
        var highlight = new com.luminaread.model.Highlight("book", markup, 0, markup, markup, markup, "#abc");
        assertEquals("#aabbcc", highlight.getColorHex());
        var legacyColor = highlight.getClass().getDeclaredField("colorHex");
        legacyColor.setAccessible(true);
        legacyColor.set(highlight, "invalid legacy color");
        storage.addHighlight(highlight);
        storage.addBookmark(new com.luminaread.model.Bookmark("book", markup, 0, markup, 0, 0, markup, ""));

        javax.swing.SwingUtilities.invokeAndWait(() -> {
            var panel = new com.luminaread.ui.StatsAndNotesPanel(storage, (book, chapter) -> {});
            panel.refreshData();
            assertEquals(2, countEscapedAnnotationLabels(panel));
        });

        Path exported = home.resolve("notes.md");
        Files.writeString(exported, "old contents");
        storage.exportNotesAndHighlightsToMarkdown(exported.toFile());
        assertTrue(Files.readString(exported).contains("Выделенные цитаты и заметки (1)"));
    }

    private static int countEscapedAnnotationLabels(java.awt.Container container) {
        int result = 0;
        for (java.awt.Component child : container.getComponents()) {
            if (child instanceof javax.swing.JLabel label && label.getText() != null
                    && label.getText().contains("no-such-image.png")) {
                assertFalse(label.getText().contains("<img"));
                assertTrue(label.getText().contains("&lt;img"));
                result++;
            } else if (child instanceof java.awt.Container nested) {
                result += countEscapedAnnotationLabels(nested);
            }
        }
        return result;
    }

    @Test void failedSaveNeverDeletesOriginalBook() throws Exception {
        Path file = home.resolve("book.txt");
        Files.writeString(file, "A book with enough text to be imported into the library.");
        LibraryStorageService storage = new LibraryStorageService(home);
        Book book = storage.importBookFile(file.toFile(), false);
        Path json = home.resolve(".luminaread/settings.json");
        Files.delete(json);
        Files.createDirectory(json);
        assertThrows(UncheckedIOException.class, () -> storage.removeBook(book, true));
        assertTrue(Files.exists(file), "Original book must survive a failed save");
    }

    @Test void unknownBookCannotDeleteAFile() throws Exception {
        Path file = home.resolve("unrelated.txt");
        Files.writeString(file, "User data");
        LibraryStorageService storage = new LibraryStorageService(home);
        storage.removeBook(new Book("Other", "", file.toString(), "TXT"), true);
        assertEquals("User data", Files.readString(file));
    }

    @Test void deletionFailureIsReported() throws Exception {
        Path file = home.resolve("book.txt");
        Files.writeString(file, "A book with enough text to be imported into the library.");
        LibraryStorageService storage = new LibraryStorageService(home);
        Book book = storage.importBookFile(file.toFile(), false);
        Path nonEmpty = Files.createDirectory(home.resolve("nonempty"));
        Files.writeString(nonEmpty.resolve("keep.txt"), "data");
        book.setFilePath(nonEmpty.toString());
        var error = assertThrows(UncheckedIOException.class, () -> storage.removeBook(book, true));
        assertTrue(error.getMessage().contains("файл не удалось удалить"));
        assertTrue(Files.exists(nonEmpty.resolve("keep.txt")));
    }

    @Test void failedCopiedImportLeavesExistingBookAndSourceUntouched() throws Exception {
        LibraryStorageService storage = new LibraryStorageService(home);
        Path original = storage.getBooksDir().resolve("broken.failimport");
        Files.writeString(original, "existing book data");
        Path source = home.resolve("broken.failimport");
        Files.writeString(source, "not an epub archive");

        BookParser parser = new BookParser() {
            @Override public String getSupportedFormat() { return "FAILIMPORT"; }
            @Override public com.luminaread.model.BookContent parse(java.io.File file) throws IOException {
                throw new IOException("simulated parsing failure");
            }
        };
        BookParserRegistry registry = BookParserRegistry.getInstance();
        registry.registerParser(parser);
        try {
            IOException error = assertThrows(IOException.class, () -> storage.importBookFile(source.toFile(), true));
            assertEquals("simulated parsing failure", error.getMessage());
        } finally {
            registry.unregisterParser(parser);
        }
        assertEquals("existing book data", Files.readString(original));
        assertEquals("not an epub archive", Files.readString(source));
        try (var files = Files.list(storage.getBooksDir())) {
            assertEquals(1, files.count(), "Failed import must not leave a reserved or temporary file");
        }
    }
}
