package com.luminaread.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.SwingUtilities;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ProgressStoreTest {
    @TempDir Path home;

    @Test void latestCheckpointSurvivesRestartWithoutRewritingTheLibrary() throws Exception {
        var storage = new LibraryStorageService(home);
        Path source = Files.writeString(home.resolve("book.txt"), "A book with some readable text.");
        var book = storage.importBookFile(source.toFile(), false);
        Path library = home.resolve(".luminaread/library_state.bin");
        byte[] original = Files.readAllBytes(library);

        SwingUtilities.invokeAndWait(() -> {
            for (int offset = 0; offset <= 500; offset++) {
                book.setScrollOffset(offset);
                storage.saveProgress(book);
            }
            book.setCurrentChapterIndex(4);
            book.setProgressPercent(63);
            book.setLastOpenedTimestamp(123456);
            book.addReadingSeconds(42);
            storage.saveProgress(book);
        });

        storage.flushProgress();
        assertArrayEquals(original, Files.readAllBytes(library));
        var restored = new LibraryStorageService(home).getAllBooks().get(0);
        assertEquals(500, restored.getScrollOffset());
        assertEquals(4, restored.getCurrentChapterIndex());
        assertEquals(63.0, restored.getProgressPercent());
        assertEquals(123456, restored.getLastOpenedTimestamp());
        assertEquals(42, restored.getTotalReadingSeconds());

        storage.removeBook(book, false);
        storage.saveProgress(book);
        storage.flushProgress();
        assertFalse(Files.exists(home.resolve(".luminaread/progress").resolve(book.getId())));
        assertTrue(Files.exists(source));
    }

    @Test void failedWriteIsRetriedAtCloseAndDamagedCheckpointFallsBack() throws Exception {
        var storage = new LibraryStorageService(home);
        var book = storage.importBookFile(Files.writeString(home.resolve("book.txt"), "Readable text.").toFile(), false);
        Path path = home.resolve(".luminaread/progress").resolve(book.getId());
        Files.createDirectories(path);
        book.setScrollOffset(271);
        storage.saveProgress(book);
        assertThrows(UncheckedIOException.class, storage::flushProgress);
        assertTrue(storage.hasProgressError());
        assertEquals(0, new LibraryStorageService(home).getAllBooks().get(0).getScrollOffset());

        Files.delete(path);
        storage.flushProgress();
        assertFalse(storage.hasProgressError());
        assertEquals(271, new LibraryStorageService(home).getAllBooks().get(0).getScrollOffset());

        Files.writeString(path, "damaged");
        assertEquals(0, new LibraryStorageService(home).getAllBooks().get(0).getScrollOffset());
        assertEquals("damaged", Files.readString(path));
    }
}
