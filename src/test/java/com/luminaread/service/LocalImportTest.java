package com.luminaread.service;

import com.luminaread.model.BookContent;
import com.luminaread.parser.BookParser;
import com.luminaread.parser.BookParserRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class LocalImportTest {
    @TempDir Path home;

    @Test void simultaneousCopiesPublishOneBook() throws Exception {
        var library = new LibraryStorageService(home);
        Path source = Files.writeString(home.resolve("book.txt"), "A chapter. ".repeat(2000));
        var start = new CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(4);
        try {
            var imports = java.util.stream.IntStream.range(0, 4).mapToObj(i -> workers.submit(() -> {
                start.await();
                return library.importBookFile(source.toFile(), true).getId();
            })).toList();
            start.countDown();
            var ids = new HashSet<String>();
            for (var task : imports) ids.add(task.get(15, TimeUnit.SECONDS));
            assertEquals(1, ids.size());
            assertEquals(1, new LibraryStorageService(home).getAllBooks().size());
            try (var files = Files.list(library.getBooksDir())) { assertEquals(1, files.count()); }
        } finally {
            workers.shutdownNow();
        }
    }

    @Test void startupDiscardsOnlyAnUnfinishedCopy() throws Exception {
        Path books = home.resolve("LuminaRead_Books");
        Path staging = Files.createDirectories(books.resolve(".luminaread-import"));
        Files.writeString(staging.resolve("partial"), "incomplete");
        Path other = Files.writeString(staging.resolve("keep.txt"), "unrelated");
        Path existing = Files.createFile(books.resolve("empty.txt"));
        var library = new LibraryStorageService(home);
        assertFalse(Files.exists(staging.resolve("partial")));
        Path original = Files.writeString(home.resolve("book.txt"), "Complete manuscript.");
        var book = library.importBookFile(original.toFile(), true);
        assertEquals(books.resolve("book.txt"), Path.of(book.getFilePath()));
        assertEquals(-1, Files.mismatch(original, Path.of(book.getFilePath())));
        assertEquals("unrelated", Files.readString(other));
        assertEquals(0, Files.size(existing));
    }

    @Test void retryAdoptsACopyPublishedBeforeProcessExit() throws Exception {
        Path source = Files.writeString(home.resolve("story.txt"), "Already published manuscript.");
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), ExitDuringParse.class.getName(), home.toString())
                .redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(15, TimeUnit.SECONDS));
            assertEquals(42, process.exitValue(), () -> "Child did not stop inside the parser");
        } finally {
            process.destroyForcibly();
        }
        var library = new LibraryStorageService(home);
        assertTrue(library.getAllBooks().isEmpty());
        Path published = library.getBooksDir().resolve("story.txt");
        assertEquals(-1, Files.mismatch(source, published));
        assertEquals(published, Path.of(library.importBookFile(source.toFile(), true).getFilePath()));
        assertEquals(1, new LibraryStorageService(home).getAllBooks().size());
        try (var files = Files.list(library.getBooksDir())) { assertEquals(1, files.count()); }
    }

    public static final class ExitDuringParse {
        public static void main(String[] args) throws Exception {
            var home = Path.of(args[0]);
            BookParserRegistry.getInstance().registerParser(new BookParser() {
                @Override public String getSupportedFormat() { return "TXT"; }
                @Override public BookContent parse(File file) {
                    Runtime.getRuntime().halt(42);
                    throw new AssertionError();
                }
            });
            new LibraryStorageService(home).importBookFile(home.resolve("story.txt").toFile(), true);
        }
    }
}
