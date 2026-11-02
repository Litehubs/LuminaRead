package com.luminaread.service.catalog;

import com.luminaread.model.DownloadTask;

import java.io.File;
import java.nio.file.Path;
import java.util.function.DoubleConsumer;

/**
 * Interface segregation for downloading book files with progress reporting.
 */
public interface IBookDownloader {

    File downloadBookFile(String downloadUrl, String suggestedTitle, String formatHint,
                          Path destinationDir, DoubleConsumer progressCallback) throws Exception;

    File downloadBookFileWithTask(DownloadTask task, Path destinationDir, DoubleConsumer progressCallback) throws Exception;
}
