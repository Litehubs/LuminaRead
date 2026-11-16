package com.luminaread.ui;

import com.luminaread.model.Book;
import com.luminaread.model.DownloadTask;
import com.luminaread.service.LibraryStorageService;
import com.luminaread.service.OnlineCatalogService;

import java.util.function.Consumer;

final class DownloadJobs implements AutoCloseable {
    private final OnlineCatalogService catalog;
    private final LibraryStorageService library;
    private final PanelWorkers workers = new PanelWorkers(1);
    private boolean closed;

    DownloadJobs(OnlineCatalogService catalog, LibraryStorageService library) {
        this.catalog = catalog;
        this.library = library;
    }

    void start(DownloadTask task, Consumer<Book> success, Consumer<Throwable> failure) {
        if (closed) return;
        workers.submit(() -> !closed, () -> catalog.downloadAndImportBook(task, library, null), success, failure);
    }

    @Override
    public void close() {
        closed = true;
        workers.close();
    }
}
