package com.luminaread.service;

import com.luminaread.model.Book;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.SwingUtilities;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.abort;

class ProgressStoreTest {
    @TempDir Path home;

    private Book importBook(LibraryStorageService storage) throws Exception {
        Path file = Files.writeString(home.resolve("book.txt"), "Readable text.");
        return storage.importBookFile(file.toFile(), false);
    }

    @Test void retriesFailedRemoval() throws Exception {
        var storage = new LibraryStorageService(home);
        Path source = Files.writeString(home.resolve("book.txt"), "Readable text.");
        var book = storage.importBookFile(source.toFile(), false);
        Path checkpoint = home.resolve(".luminaread/progress").resolve(book.getId());
        Files.createDirectories(checkpoint);
        Path obstacle = Files.createFile(checkpoint.resolve("occupied"));

        storage.removeBook(book, false);
        var error = assertThrows(UncheckedIOException.class, storage::flushProgress);
        assertInstanceOf(DirectoryNotEmptyException.class, error.getCause());
        assertTrue(storage.hasProgressError());
        assertTrue(new LibraryStorageService(home).getAllBooks().isEmpty());

        Files.delete(obstacle);
        Files.delete(checkpoint);
        Files.createFile(checkpoint);
        storage.flushProgress();
        assertFalse(storage.hasProgressError());
        assertFalse(Files.exists(checkpoint));
        assertTrue(Files.exists(source));
    }

    @Test void invalidPositionPreservesCatalogProgress() throws Exception {
        var storage = new LibraryStorageService(home);
        var book = importBook(storage);
        book.setScrollOffset(27);
        storage.saveState();
        book.setScrollOffset(41);
        book.setProgressPercent(85);
        storage.saveProgress(book);
        storage.flushProgress();
        Path file = home.resolve(".luminaread/progress").resolve(book.getId());
        byte[] data = Files.readAllBytes(file);
        ByteBuffer.wrap(data).putDouble(4 * Integer.BYTES, 120);
        Files.write(file, data);

        var restored = new LibraryStorageService(home).getAllBooks().get(0);
        assertEquals(27, restored.getScrollOffset());
        assertEquals(0, restored.getProgressPercent());
        assertArrayEquals(data, Files.readAllBytes(file));
    }

    @Test void skipsLinkedProgressDirectory() throws Exception {
        var storage = new LibraryStorageService(home);
        var book = importBook(storage);
        book.setScrollOffset(42);
        storage.saveProgress(book);
        storage.flushProgress();
        Path dir = home.resolve(".luminaread/progress");
        Path moved = Files.move(dir, home.resolve("moved"));
        try {
            Files.createSymbolicLink(dir, moved);
        } catch (IOException | UnsupportedOperationException ex) {
            abort("Symbolic links unavailable: " + ex);
        }
        Path outside = moved.resolve(book.getId());
        byte[] original = Files.readAllBytes(outside);

        assertEquals(0, new LibraryStorageService(home).getAllBooks().get(0).getScrollOffset());
        book.setScrollOffset(63);
        storage.saveProgress(book);
        assertThrows(UncheckedIOException.class, storage::flushProgress);
        assertArrayEquals(original, Files.readAllBytes(outside));
        storage.removeBook(book, false);
        assertThrows(UncheckedIOException.class, storage::flushProgress);
        Files.delete(dir);
        storage.flushProgress();
        assertArrayEquals(original, Files.readAllBytes(outside));
    }

    @Test void validUpdateSupersedesFailedSave() throws Exception {
        var storage = new LibraryStorageService(home);
        var book = importBook(storage);
        book.setScrollOffset(55);
        storage.saveProgress(book);
        storage.flushProgress();

        book.setScrollOffset(72);
        book.setProgressPercent(Double.NaN);
        storage.saveProgress(book);
        assertThrows(UncheckedIOException.class, storage::flushProgress);
        assertEquals(55, new LibraryStorageService(home).getAllBooks().get(0).getScrollOffset());

        book.setProgressPercent(25);
        storage.saveProgress(book);
        storage.flushProgress();
        var restored = new LibraryStorageService(home).getAllBooks().get(0);
        assertEquals(72, restored.getScrollOffset());
        assertEquals(25, restored.getProgressPercent());
        assertFalse(storage.hasProgressError());
    }

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

    @Test void oldCheckpointsOpenAndAreUpgradedAfterTheFirstCapture() throws Exception {
        var storage = new LibraryStorageService(home);
        var book = importBook(storage);
        Path dir = Files.createDirectories(home.resolve(".luminaread/progress"));
        Path file = dir.resolve(book.getId());
        try (var out = new java.io.DataOutputStream(Files.newOutputStream(file))) {
            out.writeInt(0x4c525001);
            out.writeInt(2);
            out.writeInt(3);
            out.writeInt(40);
            out.writeDouble(30);
            out.writeBoolean(false);
            out.writeLong(0);
            out.writeLong(0);
        }
        var reopened = new LibraryStorageService(home);
        var legacy = reopened.getAllBooks().get(0);
        assertEquals(40, legacy.getScrollOffset());
        assertEquals(-1, legacy.getTextOffset());
        legacy.setTextOffset(128);
        reopened.saveProgress(legacy);
        reopened.flushProgress();
        assertEquals(128, new LibraryStorageService(home).getAllBooks().get(0).getTextOffset());
    }

    @Test void failedWriteIsRetriedAtCloseAndDamagedCheckpointFallsBack() throws Exception {
        var storage = new LibraryStorageService(home);
        var book = importBook(storage);
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
