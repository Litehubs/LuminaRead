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

    @Test void openingChecksTheSourceBeforeReusingParsedText() throws Exception {
        var storage = new LibraryStorageService(home);
        Path original = Files.writeString(home.resolve("chapter.txt"), "Alpha in the original source.");
        var book = storage.importBookFile(original.toFile(), false);
        var initial = storage.loadBookContent(book);
        assertSame(initial, storage.loadBookContent(book));

        Path replacement = Files.writeString(home.resolve("replacement.txt"), "Bravo in the updated source.");
        Files.move(replacement, original, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.setLastModifiedTime(original, java.nio.file.attribute.FileTime.fromMillis(1234567890000L));
        var updated = storage.loadBookContent(book);
        assertNotSame(initial, updated);
        assertTrue(updated.getChapters().get(0).getPlainText().contains("Bravo"));

        Path moved = Files.move(original, home.resolve("moved.txt"));
        assertThrows(IOException.class, () -> storage.loadBookContent(book));
        var reimported = storage.importBookFile(moved.toFile(), false);
        assertTrue(storage.loadBookContent(reimported).getChapters().get(0).getPlainText().contains("Bravo"));
        Files.delete(moved);
        assertThrows(IOException.class, () -> storage.loadBookContent(reimported));
    }

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

    @Test void interruptedWriterDoesNotPublishAStagedFile() throws Exception {
        Path library = Files.writeString(home.resolve("library.bin"), "previous");
        try {
            assertThrows(java.io.InterruptedIOException.class, () -> AtomicFile.write(library, output -> {
                output.write("valid replacement".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                Thread.currentThread().interrupt();
            }));
        } finally {
            Thread.interrupted();
        }
        assertEquals("previous", Files.readString(library));
    }

    @Test void killedWriterLeavesThePreviousFileReadable() throws Exception {
        Path library = Files.writeString(home.resolve("library.bin"), "previous");
        Path staged = home.resolve("staged");
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), StagingProbe.class.getName(),
                library.toString(), staged.toString()).start();
        try {
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while (!Files.exists(staged) && process.isAlive() && System.nanoTime() < deadline) Thread.sleep(10);
            assertTrue(Files.exists(staged), "The child must finish staging before it is killed");
            process.destroyForcibly();
            assertTrue(process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals("previous", Files.readString(library));
        } finally {
            process.destroyForcibly();
        }
    }

    public static final class StagingProbe {
        public static void main(String[] args) throws IOException {
            AtomicFile.write(Path.of(args[0]), output -> {
                output.write("complete next version".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                output.flush();
                Files.createFile(Path.of(args[1]));
                try {
                    new java.util.concurrent.CountDownLatch(1).await();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new java.io.InterruptedIOException();
                }
            });
        }
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs({org.junit.jupiter.api.condition.OS.MAC, org.junit.jupiter.api.condition.OS.LINUX})
    void readOnlyProfileKeepsTheLastCommittedCatalog() throws Exception {
        var storage = new LibraryStorageService(home);
        var book = storage.importBookFile(Files.writeString(home.resolve("book.txt"), "Readable words.").toFile(), false);
        Path directory = home.resolve(".luminaread");
        var original = Files.getPosixFilePermissions(directory);
        try {
            Files.setPosixFilePermissions(directory, java.nio.file.attribute.PosixFilePermissions.fromString("r-x------"));
            org.junit.jupiter.api.Assumptions.assumeFalse(Files.isWritable(directory));
            assertThrows(UncheckedIOException.class, () -> storage.toggleFavorite(book.getId()));
            assertFalse(book.isFavorite());
            assertFalse(new LibraryStorageService(home).getAllBooks().get(0).isFavorite());
        } finally {
            Files.setPosixFilePermissions(directory, original);
        }
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
        assertDoesNotThrow(storage::saveState);
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
        String markdown = Files.readString(exported);
        assertTrue(markdown.contains("Выделенные цитаты и заметки (1)"));
        assertFalse(markdown.contains("<img"));
        assertTrue(markdown.contains("&lt;img"));
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
        var bookmark = new com.luminaread.model.Bookmark(book.getId(), "Book", 0, "Chapter", 0, 0, "Note", "");
        storage.addBookmark(bookmark);
        Path state = home.resolve(".luminaread/library_state.bin");
        Path backup = Files.move(state, state.resolveSibling("backup"));
        Files.createDirectory(state);
        assertThrows(UncheckedIOException.class, () -> storage.removeBook(book, true));
        assertTrue(Files.exists(file), "Original book must survive a failed save");
        assertEquals(java.util.List.of(book), storage.getAllBooks());
        assertEquals(java.util.List.of(bookmark), storage.getBookmarks(book.getId()));
        Files.delete(state);
        Files.move(backup, state);
        assertEquals(book.getId(), new LibraryStorageService(home).getAllBooks().get(0).getId());
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
