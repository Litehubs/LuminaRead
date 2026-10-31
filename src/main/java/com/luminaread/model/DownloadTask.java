package com.luminaread.model;

import java.io.File;
import java.util.UUID;

public class DownloadTask {
    public enum Status {
        DOWNLOADING("Качается..."),
        COMPLETED("Завершено (100%)"),
        FAILED("Ошибка загрузки");

        private final String label;
        Status(String label) { this.label = label; }
        public String getLabel() { return label; }
    }

    private final String id;
    private final String title;
    private final String format;
    private final String url;

    private volatile long bytesDownloaded;
    private volatile long totalBytes;
    private volatile int progressPercent;
    private volatile double speedKbps;
    private volatile Status status;
    private volatile String errorMessage;
    private volatile File savedFile;
    private volatile Book importedBook;

    public DownloadTask(String title, String format, String url) {
        this.id = UUID.randomUUID().toString();
        this.title = (title == null || title.isBlank()) ? "Книга из сети" : title.trim();
        this.format = (format == null || format.isBlank()) ? "АВТО" : format.toUpperCase();
        this.url = url != null ? url.trim() : "";
        this.status = Status.DOWNLOADING;
        this.progressPercent = 0;
        this.totalBytes = -1L;
    }

    public String getId() { return id; }
    public String getTitle() { return title; }
    public String getFormat() { return format; }
    public String getUrl() { return url; }

    public long getBytesDownloaded() { return bytesDownloaded; }
    public void setBytesDownloaded(long bytesDownloaded) { this.bytesDownloaded = bytesDownloaded; }

    public long getTotalBytes() { return totalBytes; }
    public void setTotalBytes(long totalBytes) { this.totalBytes = totalBytes; }

    public int getProgressPercent() { return progressPercent; }
    public void setProgressPercent(int progressPercent) {
        this.progressPercent = Math.max(0, Math.min(100, progressPercent));
    }

    public double getSpeedKbps() { return speedKbps; }
    public void setSpeedKbps(double speedKbps) { this.speedKbps = Math.max(0.0, speedKbps); }

    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public File getSavedFile() { return savedFile; }
    public void setSavedFile(File savedFile) { this.savedFile = savedFile; }

    public Book getImportedBook() { return importedBook; }
    public void setImportedBook(Book importedBook) { this.importedBook = importedBook; }

    public String getFormattedProgressDetails() {
        String downloadedStr = formatBytes(bytesDownloaded);
        String totalStr = totalBytes > 0 ? formatBytes(totalBytes) : "неизвестно";
        if (status == Status.COMPLETED) {
            return String.format("100%%  •  Скачано: %s  •  Готово к чтению", downloadedStr);
        } else if (status == Status.FAILED) {
            return "Ошибка: " + (errorMessage != null ? errorMessage : "не удалось скачать файл");
        }
        return String.format("%d%%  •  %s из %s  •  %.0f КБ/с",
                progressPercent, downloadedStr, totalStr, speedKbps);
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " Б";
        double kb = bytes / 1024.0;
        if (kb < 1024.0) return String.format("%.1f КБ", kb);
        double mb = kb / 1024.0;
        return String.format("%.2f МБ", mb);
    }
}
