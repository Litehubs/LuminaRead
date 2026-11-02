package com.luminaread.service.catalog;

import com.luminaread.model.DownloadTask;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.DoubleConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * Encapsulates HTTP downloading, GZIP stream handling, file saving,
 * Archive.org direct download link resolution, and progress reporting callbacks.
 */
public class BookDownloader implements IBookDownloader {
    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_0) AppleWebKit/605.1.15 (KHTML, like Gecko) LuminaRead/2.2";

    private static final Pattern ARCHIVE_FILE_NAME_PATTERN = Pattern.compile(
            "\"name\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    private final HttpClient httpClient;
    private final Runnable updateListener;

    public BookDownloader() {
        this(null, null);
    }

    public BookDownloader(Runnable updateListener) {
        this(null, updateListener);
    }

    public BookDownloader(HttpClient httpClient, Runnable updateListener) {
        this.httpClient = (httpClient != null) ? httpClient : HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        this.updateListener = updateListener;
    }

    @Override
    public File downloadBookFile(String downloadUrl, String suggestedTitle, String formatHint,
                                 Path destinationDir, DoubleConsumer progressCallback) throws Exception {
        DownloadTask task = new DownloadTask(suggestedTitle, formatHint, downloadUrl);
        return downloadBookFileWithTask(task, destinationDir, progressCallback);
    }

    @Override
    public File downloadBookFileWithTask(DownloadTask task, Path destinationDir, DoubleConsumer progressCallback) throws Exception {
        if (task == null) {
            throw new IllegalArgumentException("Download task cannot be null");
        }
        long startTimeMs = System.currentTimeMillis();
        task.setStatus(DownloadTask.Status.DOWNLOADING);
        notifyUpdate();

        try {
            String effectiveUrl = resolveBestArchiveOrgUrl(task.getUrl(), task.getFormat());

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(effectiveUrl))
                    .header("User-Agent", USER_AGENT)
                    .timeout(Duration.ofSeconds(45))
                    .GET()
                    .build();

            HttpResponse<InputStream> response = httpClient.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Сервер вернул код ответа HTTP " + response.statusCode()
                        + " (возможно, книга защищена авторским правом или недоступна для свободного скачивания).");
            }

            String contentType = response.headers().firstValue("Content-Type").orElse("");
            String finalUriStr = response.uri().toString().toLowerCase(Locale.ROOT);

            if (finalUriStr.contains("/account/login") || finalUriStr.contains("borrow")) {
                throw new IllegalStateException("Эта книга в каталоге Open Library доступна только по читательскому билету (режим Borrow). Выберите другое издание или каталог (например, Flibusta OPDS или Gutenberg).");
            }

            long contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
            task.setTotalBytes(contentLength);

            String ext = determineExtension(effectiveUrl, task.getFormat(), contentType,
                    response.headers().firstValue("Content-Disposition").orElse(""));

            String safeBaseName = task.getTitle().replaceAll("[\\\\/:*?\"<>|]", "_").trim();
            if (safeBaseName.length() > 80) {
                safeBaseName = safeBaseName.substring(0, 80).trim();
            }
            if (safeBaseName.isEmpty()) {
                safeBaseName = "book";
            }

            if (destinationDir != null) {
                Files.createDirectories(destinationDir);
            }
            Path outPath = (destinationDir != null)
                    ? destinationDir.resolve(safeBaseName + ext)
                    : Path.of(safeBaseName + ext);
            File outFile = outPath.toFile();
            long lastNotifyMs = 0L;

            try (InputStream in = response.body();
                 BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(outFile), 65536)) {
                byte[] buffer = new byte[32768];
                long totalRead = 0;
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    totalRead += read;
                    task.setBytesDownloaded(totalRead);

                    long now = System.currentTimeMillis();
                    double elapsedSec = Math.max(0.1, (now - startTimeMs) / 1000.0);
                    task.setSpeedKbps((totalRead / 1024.0) / elapsedSec);

                    if (contentLength > 0) {
                        double ratio = Math.min(1.0, (double) totalRead / contentLength);
                        task.setProgressPercent((int) Math.round(ratio * 100));
                        if (progressCallback != null) {
                            progressCallback.accept(ratio);
                        }
                    } else {
                        int estimatedPct = (int) Math.min(95, (totalRead / 15360));
                        task.setProgressPercent(Math.max(5, estimatedPct));
                        if (progressCallback != null) {
                            progressCallback.accept(task.getProgressPercent() / 100.0);
                        }
                    }

                    if (now - lastNotifyMs > 80) {
                        lastNotifyMs = now;
                        notifyUpdate();
                    }
                }
            }

            outFile = decompressGzipIfNeeded(outFile);
            validateDownloadedBookFile(outFile, task.getFormat());

            task.setProgressPercent(100);
            task.setStatus(DownloadTask.Status.COMPLETED);
            task.setSavedFile(outFile);
            if (progressCallback != null) {
                progressCallback.accept(1.0);
            }
            notifyUpdate();
            return outFile;
        } catch (Exception ex) {
            task.setStatus(DownloadTask.Status.FAILED);
            task.setErrorMessage(ex.getMessage());
            notifyUpdate();
            throw ex;
        }
    }

    public File decompressGzipIfNeeded(File file) throws Exception {
        if (file == null || !file.exists() || file.length() < 2) {
            return file;
        }
        int b0;
        int b1;
        try (InputStream is = new FileInputStream(file)) {
            b0 = is.read();
            b1 = is.read();
        }
        if (b0 != 0x1F || b1 != 0x8B) {
            return file;
        }
        File tmp = new File(file.getParentFile(), file.getName() + ".gunzip.tmp");
        try (InputStream raw = new FileInputStream(file);
             GZIPInputStream gis = new GZIPInputStream(raw);
             FileOutputStream fos = new FileOutputStream(tmp)) {
            byte[] buf = new byte[65536];
            int r;
            while ((r = gis.read(buf)) > 0) {
                fos.write(buf, 0, r);
            }
        }
        Path target = file.toPath();
        Files.deleteIfExists(target);
        Files.move(tmp.toPath(), target);
        return file;
    }

    public void validateDownloadedBookFile(File file, String requestedFormat) throws Exception {
        if (file == null || !file.exists() || file.length() < 64) {
            if (file != null) Files.deleteIfExists(file.toPath());
            throw new IllegalStateException("Скачанный файл пуст (0 КБ). Попробуйте другой формат или источник.");
        }

        byte[] header = new byte[(int) Math.min(1024, file.length())];
        try (InputStream is = new FileInputStream(file)) {
            int r = is.read(header);
            if (r > 0) {
                String headText = new String(header, 0, r, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
                boolean looksLikeHtml = headText.contains("<!doctype html") || headText.contains("<html");
                boolean requestedHtml = requestedFormat != null && requestedFormat.toUpperCase(Locale.ROOT).contains("HTML");
                if (looksLikeHtml && !requestedHtml) {
                    if (headText.contains("archive.org") || headText.contains("login") || headText.contains("borrow")
                            || headText.contains("404") || headText.contains("cloudflare")) {
                        Files.deleteIfExists(file.toPath());
                        throw new IllegalStateException("Это издание защищено от прямого скачивания на сайте источника (требуется вход/аренда). Выберите другое издание в списке или формат PDF/TXT.");
                    }
                }
            }
        }
    }

    public String determineExtension(String url, String formatHint, String contentType, String disposition) {
        String lowerDisp = (disposition == null) ? "" : disposition.toLowerCase(Locale.ROOT);
        if (lowerDisp.contains(".fb2.zip")) return ".fb2.zip";
        if (lowerDisp.contains(".fb2")) return ".fb2";
        if (lowerDisp.contains(".epub")) return ".epub";
        if (lowerDisp.contains(".pdf")) return ".pdf";

        String lowerUrl = (url == null) ? "" : url.toLowerCase(Locale.ROOT);
        if (lowerUrl.contains(".fb2.zip") || lowerUrl.endsWith("/fb2")) return ".fb2.zip";
        if (lowerUrl.endsWith(".fb2")) return ".fb2";
        if (lowerUrl.contains(".epub") || lowerUrl.endsWith("/epub")) return ".epub";
        if (lowerUrl.endsWith(".pdf")) return ".pdf";
        if (lowerUrl.endsWith(".cbz")) return ".cbz";
        if (lowerUrl.endsWith(".md")) return ".md";
        if (lowerUrl.endsWith(".txt")) return ".txt";
        if (lowerUrl.contains(".html")) return ".html";

        if (formatHint != null) {
            String h = formatHint.toUpperCase(Locale.ROOT);
            if (h.contains("FB2.ZIP")) return ".fb2.zip";
            if (h.contains("FB2")) return ".fb2";
            if (h.contains("EPUB")) return ".epub";
            if (h.contains("PDF")) return ".pdf";
            if (h.contains("CBZ")) return ".cbz";
            if (h.contains("HTML")) return ".html";
            if (h.contains("MD")) return ".md";
            if (h.contains("TXT")) return ".txt";
        }

        String lowerCt = (contentType == null) ? "" : contentType.toLowerCase(Locale.ROOT);
        if (lowerCt.contains("epub")) return ".epub";
        if (lowerCt.contains("fb2")) return ".fb2";
        if (lowerCt.contains("pdf")) return ".pdf";
        if (lowerCt.contains("html")) return ".html";
        return ".txt";
    }

    public String resolveBestArchiveOrgUrl(String originalUrl, String formatHint) {
        if (originalUrl == null || !originalUrl.contains("archive.org/download/")) {
            return originalUrl;
        }
        try {
            String afterDownload = originalUrl.substring(originalUrl.indexOf("archive.org/download/") + "archive.org/download/".length());
            int slash = afterDownload.indexOf('/');
            if (slash <= 0) return originalUrl;
            String iaId = afterDownload.substring(0, slash);

            String metaUrl = "https://archive.org/metadata/" + iaId + "/files";
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(metaUrl))
                    .header("User-Agent", USER_AGENT)
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200 || resp.body() == null) {
                return originalUrl;
            }

            List<String> fileNames = new ArrayList<>();
            Matcher m = ARCHIVE_FILE_NAME_PATTERN.matcher(resp.body());
            while (m.find()) {
                String fn = unescapeJson(m.group(1));
                if (!fn.isBlank()) fileNames.add(fn);
            }

            String targetExt = ".epub";
            if (formatHint != null) {
                String h = formatHint.toUpperCase(Locale.ROOT);
                if (h.contains("PDF")) targetExt = ".pdf";
                else if (h.contains("FB2")) targetExt = ".fb2";
                else if (h.contains("TXT")) targetExt = ".txt";
            }

            for (String fn : fileNames) {
                String lower = fn.toLowerCase(Locale.ROOT);
                if (lower.endsWith(targetExt) && !lower.contains("encrypted")) {
                    return buildArchiveDownloadUrl(iaId, fn);
                }
            }

            String[] fallbackExts = {".fb2.zip", ".fb2", ".epub", ".pdf", "_djvu.txt", ".txt"};
            for (String ext : fallbackExts) {
                for (String fn : fileNames) {
                    String lower = fn.toLowerCase(Locale.ROOT);
                    if (lower.endsWith(ext) && !lower.contains("encrypted")) {
                        return buildArchiveDownloadUrl(iaId, fn);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return originalUrl;
    }

    private String buildArchiveDownloadUrl(String iaId, String fileName) {
        String encodedFile = URLEncoder.encode(fileName, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("%2F", "/");
        return "https://archive.org/download/" + iaId + "/" + encodedFile;
    }

    private void notifyUpdate() {
        if (updateListener != null) {
            try {
                updateListener.run();
            } catch (Exception ignored) {
            }
        }
    }

    private static String unescapeJson(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(++i);
                switch (next) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'n', 'r', 't' -> sb.append(' ');
                    case 'u' -> {
                        if (i + 4 < s.length()) {
                            String hex = s.substring(i + 1, i + 5);
                            try {
                                sb.append((char) Integer.parseInt(hex, 16));
                                i += 4;
                            } catch (NumberFormatException e) {
                                sb.append("\\u").append(hex);
                            }
                        }
                    }
                    default -> sb.append(next);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString().trim();
    }
}
