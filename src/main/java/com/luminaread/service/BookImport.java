package com.luminaread.service;

import com.luminaread.model.Book;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public final class BookImport {
    private static final Set<String> EXTENSIONS = Set.of(
            "fb2", "fbz", "zip", "epub", "pdf", "txt", "md", "markdown", "html", "htm", "rtf", "cbz"
    );

    private final LibraryStorageService library;

    public BookImport(LibraryStorageService library) {
        this.library = library;
    }

    public record Progress(int index, int total, String name) {}

    public record Failure(String name, Exception cause) {}

    public record Result(List<Book> books, List<Failure> failures, boolean stopped, int total) {
        public Result {
            books = List.copyOf(books);
            failures = List.copyOf(failures);
        }
    }

    public static boolean supports(File file) {
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        return dot > 0 && EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    public Result fromFolder(Path folder, BooleanSupplier stop, Consumer<Progress> progress)
            throws IOException, InterruptedException {
        List<File> books;
        try (var paths = Files.walk(folder, 3)) {
            books = paths.takeWhile(path -> !stop.getAsBoolean())
                    .filter(Files::isRegularFile)
                    .map(Path::toFile)
                    .filter(BookImport::supports)
                    .toList();
        }
        return importBooks(books, false, stop, progress);
    }

    public Result fromFiles(List<File> files, BooleanSupplier stop, Consumer<Progress> progress)
            throws InterruptedException {
        return importBooks(files, true, stop, progress);
    }

    private Result importBooks(List<File> files, boolean copy, BooleanSupplier stop, Consumer<Progress> progress)
            throws InterruptedException {
        List<Book> books = new ArrayList<>();
        List<Failure> failures = new ArrayList<>();
        for (int i = 0; i < files.size(); i++) {
            if (stop.getAsBoolean()) return new Result(books, failures, true, files.size());
            File file = files.get(i);
            progress.accept(new Progress(i, files.size(), file.getName()));
            try {
                books.add(library.importBookFile(file, copy));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw ex;
            } catch (Exception ex) {
                failures.add(new Failure(file.getName(), ex));
            }
        }
        return new Result(books, failures, files.isEmpty() && stop.getAsBoolean(), files.size());
    }
}
