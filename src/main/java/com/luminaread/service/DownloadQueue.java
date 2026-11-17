package com.luminaread.service;

import com.luminaread.model.DownloadTask;
import com.luminaread.util.AtomicFile;
import com.luminaread.util.BoundedInput;
import com.luminaread.util.Json;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

/** A download keeps its validated file in one stable directory throughout import and recovery. */
final class DownloadQueue {
    private static final Logger LOG = Logger.getLogger(DownloadQueue.class.getName());
    private static final String RECORD = ".job.json";
    private static final int RECORD_LIMIT = 16 * 1024;
    private static final Set<String> BOOK_EXTENSIONS = Set.of(
            "cbz", "epub", "fb2", "htm", "html", "markdown", "md", "pdf", "rtf", "txt");
    private final Path root;

    DownloadQueue(Path books) {
        root = books.resolve("Downloads");
    }

    record Saved(DownloadTask task, String bookId) {}

    Path begin(DownloadTask task) throws IOException {
        Path directory = directory(task);
        Files.createDirectories(root);
        checkDirectory(root);
        Files.createDirectories(directory);
        checkDirectory(directory);
        save(task, "");
        return directory;
    }

    void complete(DownloadTask task, String bookId) throws IOException {
        checkDirectory(root);
        checkDirectory(directory(task));
        save(task, bookId);
    }

    void forget(DownloadTask task) throws IOException {
        Path directory = directory(task);
        if (Files.notExists(directory, NOFOLLOW_LINKS)) return;
        checkDirectory(root);
        checkDirectory(directory);
        Files.deleteIfExists(directory.resolve(RECORD));
        try {
            Files.delete(directory);
        } catch (java.nio.file.DirectoryNotEmptyException ignored) {
            // The directory remains the permanent location of any downloaded book.
        }
    }

    List<Saved> load() throws IOException {
        List<Saved> jobs = new ArrayList<>();
        if (Files.notExists(root, NOFOLLOW_LINKS)) return jobs;
        checkDirectory(root);
        try (var directories = Files.newDirectoryStream(root)) {
            for (Path directory : directories) {
                if (!Files.isDirectory(directory, NOFOLLOW_LINKS)) continue;
                String name = directory.getFileName().toString();
                Path record = directory.resolve(RECORD);
                if (!Files.isRegularFile(record, NOFOLLOW_LINKS)) continue;
                try {
                    UUID id = UUID.fromString(name);
                    if (!id.toString().equals(name)) continue;
                    jobs.add(read(id, record));
                } catch (IOException | IllegalArgumentException ex) {
                    LOG.log(Level.WARNING, "Не удалось прочитать запись загрузки: " + name, ex);
                }
            }
        }
        return jobs;
    }

    File findBook(Path directory, boolean cleanTemps) throws IOException {
        checkDirectory(root);
        checkDirectory(directory);
        File result = null;
        try (var files = Files.newDirectoryStream(directory)) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                if (!Files.isRegularFile(file, NOFOLLOW_LINKS)) continue;
                if (name.startsWith(".luminaread-") && name.endsWith(".tmp")) {
                    if (cleanTemps) Files.deleteIfExists(file);
                    continue;
                }
                if (!isBook(name)) continue;
                if (result != null) throw new IOException("В папке загрузки найдено несколько файлов.");
                result = file.toFile();
            }
        }
        return result;
    }

    private static boolean isBook(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        return lower.endsWith(".fb2.zip") || dot >= 0 && BOOK_EXTENSIONS.contains(lower.substring(dot + 1));
    }

    Path directory(DownloadTask task) {
        return root.resolve(UUID.fromString(task.getId()).toString());
    }

    private void save(DownloadTask task, String bookId) throws IOException {
        Path record = directory(task).resolve(RECORD);
        byte[] data = Json.writeStringObject(Map.of(
                "title", task.getTitle(), "format", task.getFormat(), "book", bookId)).getBytes(UTF_8);
        if (data.length > RECORD_LIMIT) throw new IOException("Данные загрузки слишком длинные.");
        AtomicFile.write(record, output -> output.write(data));
    }

    private static Saved read(UUID id, Path record) throws IOException {
        try (InputStream input = Files.newInputStream(record, NOFOLLOW_LINKS)) {
            Object value = Json.parse(new String(BoundedInput.read(input, RECORD_LIMIT), UTF_8));
            if (value instanceof Map<?, ?> fields
                    && fields.get("title") instanceof String title
                    && fields.get("format") instanceof String format
                    && fields.get("book") instanceof String bookId) {
                return new Saved(DownloadTask.restore(id, title, format), bookId);
            }
            throw new IOException("Некорректная запись загрузки.");
        }
    }

    private static void checkDirectory(Path directory) throws IOException {
        if (!Files.isDirectory(directory, NOFOLLOW_LINKS)) {
            throw new IOException("Недоступна папка загрузки: " + directory);
        }
    }
}
