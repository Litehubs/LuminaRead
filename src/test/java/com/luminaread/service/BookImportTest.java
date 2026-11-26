package com.luminaread.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class BookImportTest {
    @TempDir Path home;

    @Test void folderImportsSupportedFormatsAtTheirOriginalPaths() throws Exception {
        Path folder = Files.createDirectory(home.resolve("source"));
        var names = List.of("book.RTF", "page.htm", "draft.markdown", "book.fb2.zip");
        Files.writeString(folder.resolve("book.RTF"), "{\\rtf1 A book.}");
        Files.writeString(folder.resolve("page.htm"), "<html><body>A book.</body></html>");
        Files.writeString(folder.resolve("draft.markdown"), "# Book\n\nA chapter.");
        try (var zip = new ZipOutputStream(Files.newOutputStream(folder.resolve("book.fb2.zip")))) {
            zip.putNextEntry(new ZipEntry("book.fb2"));
            zip.write("""
                    <FictionBook><body><section><p>A complete chapter.</p></section></body></FictionBook>
                    """.getBytes(StandardCharsets.UTF_8));
        }
        Files.writeString(folder.resolve("other.json"), "{}");
        Files.createDirectory(folder.resolve("folder.pdf"));
        var library = new LibraryStorageService(home);
        var result = new BookImport(library).fromFolder(folder, () -> false, ignored -> {});
        assertTrue(result.failures().isEmpty(), () -> result.failures().toString());
        assertEquals(4, result.total());
        assertEquals(names.stream().sorted().toList(), result.books().stream()
                .map(book -> Path.of(book.getFilePath()).getFileName().toString()).sorted().toList());
        for (var book : result.books()) {
            assertEquals(folder, Path.of(book.getFilePath()).getParent());
            assertFalse(library.loadBookContent(book).getChapters().isEmpty());
        }
    }

    @Test void stopDuringFolderScanPreventsFurtherTraversalAndWrites() throws Exception {
        Path folder = Files.createDirectory(home.resolve("source"));
        for (int i = 0; i < 20; i++) Files.writeString(folder.resolve("book-" + i + ".txt"), "A chapter.");
        var library = new LibraryStorageService(home);
        var checks = new AtomicInteger();
        var result = new BookImport(library).fromFolder(folder, () -> checks.incrementAndGet() >= 4,
                progress -> fail("The import should stop before processing the collected files"));
        assertTrue(result.stopped());
        assertTrue(checks.get() < 10);
        assertTrue(library.getAllBooks().isEmpty());
    }

    @Test void stopAfterCurrentBookLeavesSuccessfulCopyAndSkipsNextFile() throws Exception {
        File first = Files.writeString(home.resolve("first.txt"), "First book.").toFile();
        File second = Files.writeString(home.resolve("second.txt"), "Second book.").toFile();
        var stop = new AtomicBoolean();
        var library = new LibraryStorageService(home) {
            @Override public com.luminaread.model.Book importBookFile(File file, boolean copy) throws Exception {
                var book = super.importBookFile(file, copy);
                stop.set(true);
                return book;
            }
        };
        var progress = new ArrayList<BookImport.Progress>();
        var result = new BookImport(library).fromFiles(List.of(first, second), stop::get, progress::add);
        assertTrue(result.stopped());
        assertEquals(List.of(new BookImport.Progress(0, 2, "first.txt")), progress);
        assertEquals(1, result.books().size());
        assertEquals(library.getBooksDir().resolve("first.txt"), Path.of(result.books().get(0).getFilePath()));
        assertEquals("First book.", Files.readString(Path.of(result.books().get(0).getFilePath())));
        assertFalse(Files.exists(library.getBooksDir().resolve("second.txt")));
        assertEquals(1, new LibraryStorageService(home).getAllBooks().size());
    }

    @Test void aFailedFileReportsItsCauseAndTheNextBookStillImports() throws Exception {
        File missing = home.resolve("missing.txt").toFile();
        File valid = Files.writeString(home.resolve("book.txt"), "A chapter.").toFile();
        var library = new LibraryStorageService(home);
        var result = new BookImport(library).fromFiles(List.of(missing, valid), () -> false, ignored -> {});
        assertFalse(result.stopped());
        assertEquals(2, result.total());
        assertEquals(1, result.books().size());
        assertEquals(1, result.failures().size());
        assertEquals("missing.txt", result.failures().get(0).name());
        assertInstanceOf(IllegalArgumentException.class, result.failures().get(0).cause());
    }
}
