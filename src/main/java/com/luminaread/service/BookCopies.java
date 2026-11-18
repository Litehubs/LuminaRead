package com.luminaread.service;

import java.io.IOException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.WRITE;

/** Stages local imports on the destination filesystem. The caller holds the profile and import locks. */
final class BookCopies {
    record Copy(Path path, boolean created) {}

    private final Path books;
    private final Path staging;

    BookCopies(Path books) {
        this.books = books;
        this.staging = books.resolve(".luminaread-import");
    }

    void recover() throws IOException {
        if (!Files.isDirectory(staging, NOFOLLOW_LINKS)) return;
        Path partial = staging.resolve("partial");
        if (Files.isRegularFile(partial, NOFOLLOW_LINKS)) Files.delete(partial);
        try {
            Files.delete(staging);
        } catch (DirectoryNotEmptyException ignored) {
            // Only our partial file belongs to us.
        }
    }

    Copy publish(Path source) throws IOException {
        String name = source.getFileName().toString();
        Path partial = staging.resolve("partial");
        boolean staged = false;
        int number = 1;
        try {
            while (true) {
                Path target = books.resolve(numberedName(name, number));
                if (Files.exists(target, NOFOLLOW_LINKS)) {
                    if (sameBook(target, staged ? partial : source)) return new Copy(target, false);
                    number++;
                    continue;
                }
                if (!staged) {
                    Files.createDirectories(staging);
                    if (!Files.isDirectory(staging, NOFOLLOW_LINKS)) throw new IOException("Недоступна папка импорта");
                    try (var out = Files.newOutputStream(partial, CREATE_NEW, WRITE)) {
                        staged = true;
                        long expected = Files.size(source);
                        if (Files.copy(source, out) != expected || expected == 0) {
                            throw new IOException("Размер книги изменился при копировании");
                        }
                    }
                }
                try {
                    Files.move(partial, target);
                    return new Copy(target, true);
                } catch (FileAlreadyExistsException ignored) {
                    // Compare the file that appeared while copying before choosing another name.
                }
            }
        } finally {
            if (staged) recover();
        }
    }

    private static boolean sameBook(Path candidate, Path source) throws IOException {
        return Files.isRegularFile(candidate, NOFOLLOW_LINKS) && Files.size(candidate) == Files.size(source)
                && Files.mismatch(candidate, source) == -1;
    }

    private static String numberedName(String name, int number) {
        if (number == 1) return name;
        String compound = ".fb2.zip";
        int dot = name.toLowerCase(Locale.ROOT).endsWith(compound) ? name.length() - compound.length() : name.lastIndexOf('.');
        int split = dot > 0 ? dot : name.length();
        return name.substring(0, split) + " (" + number + ")" + name.substring(split);
    }
}
