package com.luminaread.service;

import com.luminaread.model.DownloadTask;
import com.luminaread.model.OnlineBookItem;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.function.DoubleConsumer;

public interface IOnlineCatalogService {

    List<DownloadTask> getDownloadTasks();

    void clearCompletedTasks();

    void addDownloadListener(Runnable listener);

    void notifyDownloadListeners();

    DownloadTask createTrackedTask(String title, String formatHint, String url);

    List<OnlineBookItem> searchBooks(String query, String sourceFilter, String languageFilter);

    List<OnlineBookItem> browseCustomOpds(String opdsUrl, String optionalSearchTerm);

    List<OnlineBookItem> fetchOpdsFeed(String feedUrl, String sourceLabel);

    File downloadBookFile(String downloadUrl, String suggestedTitle, String formatHint,
                          Path destinationDir, DoubleConsumer progressCallback) throws Exception;

    File downloadBookFileWithTask(DownloadTask task, Path destinationDir, DoubleConsumer progressCallback) throws Exception;
}
