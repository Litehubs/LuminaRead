package com.luminaread.service;

import com.luminaread.model.Book;
import com.luminaread.model.DownloadTask;
import com.luminaread.service.catalog.IBookDownloader;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.DoubleConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;

final class LibraryDownloads {
    private static final Logger LOG = Logger.getLogger(LibraryDownloads.class.getName());
    private final List<DownloadTask> tasks = new CopyOnWriteArrayList<>();
    private final Set<String> active = ConcurrentHashMap.newKeySet();
    private final IBookDownloader downloader;
    private final Runnable changed;

    LibraryDownloads(IBookDownloader downloader, Runnable changed) {
        this.downloader = downloader;
        this.changed = changed;
    }

    List<DownloadTask> tasks() {
        return new ArrayList<>(tasks);
    }

    DownloadTask track(DownloadTask task) {
        tasks.add(0, task);
        changed.run();
        return task;
    }

    void clear() {
        tasks.removeIf(DownloadTask::isFinished);
        changed.run();
    }

    void clear(LibraryStorageService library) throws IOException {
        var queue = new DownloadQueue(library.getBooksDir());
        try {
            for (DownloadTask task : tasks) {
                if (!task.isFinished() || !active.add(task.getId())) continue;
                try {
                    queue.forget(task);
                    tasks.remove(task);
                } finally {
                    active.remove(task.getId());
                }
            }
        } finally {
            changed.run();
        }
    }

    Book run(DownloadTask task, LibraryStorageService library, DoubleConsumer progress) throws Exception {
        if (!active.add(task.getId())) throw new IllegalStateException("Загрузка уже выполняется.");
        try {
            if (task.getImportedBook() != null) return task.getImportedBook();
            var queue = new DownloadQueue(library.getBooksDir());
            File file;
            try {
                var directory = queue.begin(task);
                file = queue.findBook(directory, false);
                if (file == null) {
                    if (task.getSavedFile() != null || task.getUrl().isBlank()) {
                        throw new IOException("Скачанный файл больше недоступен.");
                    }
                    file = downloader.downloadBookFileWithTask(task, directory, progress);
                }
                task.finishTransfer(file);
                changed.run();
            } catch (Exception ex) {
                fail(task, "Не удалось получить файл книги", ex);
                throw ex;
            }
            return importBook(task, library, queue);
        } finally {
            active.remove(task.getId());
        }
    }

    void recover(LibraryStorageService library) throws IOException {
        var queue = new DownloadQueue(library.getBooksDir());
        for (var saved : queue.load()) {
            if (Thread.currentThread().isInterrupted()) return;
            DownloadTask task = saved.task();
            if (!active.add(task.getId())) continue;
            try {
                if (tasks.stream().anyMatch(current -> current.getId().equals(task.getId()))) continue;
                if (!saved.bookId().isBlank()) {
                    task.setImportedBook(library.getAllBooks().stream()
                            .filter(book -> book.getId().equals(saved.bookId())).findFirst().orElse(null));
                    task.setProgressPercent(100);
                    task.setStatus(DownloadTask.Status.COMPLETED);
                    track(task);
                    continue;
                }
                restore(task, library, queue);
            } finally {
                active.remove(task.getId());
            }
        }
    }

    private void restore(DownloadTask task, LibraryStorageService library, DownloadQueue queue) {
        try {
            File file = queue.findBook(queue.directory(task), true);
            if (file == null) {
                task.setErrorMessage("Загрузка не завершена. Скачайте книгу заново.");
                task.setStatus(DownloadTask.Status.FAILED);
                track(task);
                return;
            }
            task.finishTransfer(file);
            track(task);
        } catch (IOException ex) {
            fail(task, "Не удалось восстановить загрузку", ex);
            track(task);
            return;
        }
        try {
            importBook(task, library, queue);
        } catch (IllegalStateException ex) {
            // The task already reports the import error; continue restoring the rest.
        }
    }

    private Book importBook(DownloadTask task, LibraryStorageService library, DownloadQueue queue) {
        File file = task.getSavedFile();
        Book imported;
        try {
            // The library may already have committed before the download record was updated.
            imported = library.getAllBooks().stream().filter(book -> book.getFilePath().equals(file.getAbsolutePath()))
                    .findFirst().orElse(null);
            if (imported == null) imported = library.importBookFile(file, false);
        } catch (Exception ex) {
            String message = "Файл скачан, но добавить его в библиотеку не удалось: " + file.getName();
            fail(task, message, ex);
            throw new IllegalStateException(message, ex);
        }

        try {
            queue.complete(task, imported.getId());
        } catch (IOException ex) {
            LOG.log(Level.WARNING, "Книга добавлена, но не удалось обновить запись загрузки", ex);
        }
        task.setImportedBook(imported);
        task.setStatus(DownloadTask.Status.COMPLETED);
        changed.run();
        return imported;
    }

    private void fail(DownloadTask task, String message, Exception error) {
        LOG.log(Level.WARNING, message, error);
        task.setErrorMessage(message);
        task.setStatus(DownloadTask.Status.FAILED);
        changed.run();
    }
}
