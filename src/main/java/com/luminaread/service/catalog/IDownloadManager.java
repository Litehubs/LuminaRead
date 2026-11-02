package com.luminaread.service.catalog;

import com.luminaread.model.DownloadTask;

import java.util.List;

/**
 * Interface segregation for download task tracking and UI notifications.
 */
public interface IDownloadManager {

    List<DownloadTask> getDownloadTasks();

    void clearCompletedTasks();

    void addDownloadListener(Runnable listener);

    void notifyDownloadListeners();

    DownloadTask createTrackedTask(String title, String formatHint, String url);
}
