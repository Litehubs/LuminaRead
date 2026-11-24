package com.luminaread.service.catalog;

import com.luminaread.model.DownloadTask;
import com.luminaread.parser.BookParserFactory;
import com.luminaread.util.TimedInput;
import com.luminaread.util.Json;

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
import java.util.List;
import java.util.Map;
import java.io.IOException;
import java.util.Locale;
import java.util.function.DoubleConsumer;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * Encapsulates HTTP downloading, GZIP stream handling, file saving,
 * Archive.org direct download link resolution, and progress reporting callbacks.
 */
public class BookDownloader implements IBookDownloader {
    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_0) AppleWebKit/605.1.15 (KHTML, like Gecko) LuminaRead/2.2";

    private static final Pattern UNSAFE_FILENAME_CHARACTERS =
            Pattern.compile("[\\p{Cc}\\p{Cs}\\\\/:*?\"<>|]");
    private static final Pattern WINDOWS_DEVICE_NAME =
            Pattern.compile("CON|PRN|AUX|NUL|CONIN\\$|CONOUT\\$|(?:COM|LPT)[1-9¹²³]",
                    Pattern.CASE_INSENSITIVE);
    private static final int MAX_TITLE_FILENAME_LENGTH = 80;

    private final HttpClient httpClient;
    private final Runnable updateListener;
    private final long maxDownloadBytes;
    private final long maxExpandedBytes;
    private final Duration transferTimeout;

    public BookDownloader() {
        this(null, null);
    }

    public BookDownloader(Runnable updateListener) {
        this(null, updateListener);
    }

    public BookDownloader(HttpClient httpClient, Runnable updateListener) {
        this(httpClient, updateListener, 512L * 1024 * 1024, 1024L * 1024 * 1024);
    }

    public BookDownloader(HttpClient httpClient, Runnable updateListener, long maxDownloadBytes, long maxExpandedBytes) {
        this(httpClient, updateListener, maxDownloadBytes, maxExpandedBytes, Duration.ofMinutes(3));
    }

    public BookDownloader(HttpClient httpClient, Runnable updateListener, long maxDownloadBytes,
                          long maxExpandedBytes, Duration transferTimeout) {
        if (maxDownloadBytes <= 0 || maxExpandedBytes <= 0) throw new IllegalArgumentException("Размеры должны быть положительными");
        if (transferTimeout == null || transferTimeout.toMillis() <= 0) throw new IllegalArgumentException("Timeout must be positive");
        this.httpClient = (httpClient != null) ? httpClient : HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        this.updateListener = updateListener;
        this.maxDownloadBytes = maxDownloadBytes;
        this.maxExpandedBytes = maxExpandedBytes;
        this.transferTimeout = transferTimeout;
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

        Path temporaryFile = null;
        try {
            String effectiveUrl = resolveBestArchiveOrgUrl(task.getUrl(), task.getFormat());

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(effectiveUrl))
                    .header("User-Agent", USER_AGENT)
                    .timeout(Duration.ofSeconds(45))
                    .GET()
                    .build();

            HttpResponse<InputStream> response = httpClient.send(req, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = new TimedInput(response.body(), transferTimeout)) {
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
            if (contentLength > maxDownloadBytes) throw new IllegalStateException("Размер загрузки превышает допустимый предел.");
            task.setTotalBytes(contentLength);

            String exactArchiveExt = "archive.org".equalsIgnoreCase(req.uri().getHost())
                    && req.uri().getPath().startsWith("/download/") ? archiveExtension(task.getFormat()) : null;
            String ext = exactArchiveExt != null ? exactArchiveExt : determineExtension(effectiveUrl, task.getFormat(), contentType,
                    response.headers().firstValue("Content-Disposition").orElse(""));

            String safeBaseName = portableBaseName(task.getTitle());

            if (destinationDir != null) {
                Files.createDirectories(destinationDir);
            }
            Path outputDir = destinationDir != null ? destinationDir : Path.of(".");
            temporaryFile = Files.createTempFile(outputDir, ".luminaread-download-", ".tmp");
            File outFile = temporaryFile.toFile();
            long lastNotifyMs = 0L;

            try (BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(outFile), 65536)) {
                byte[] buffer = new byte[32768];
                long totalRead = 0;
                int read;
                while ((read = in.read(buffer)) != -1) {
                    if (read > maxDownloadBytes - totalRead) throw new IllegalStateException("Размер загрузки превышает допустимый предел.");
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
            if (exactArchiveExt != null && !exactArchiveExt.equals(".txt")) {
                String actualFormat = BookParserFactory.detectFormatLabel(outFile);
                if (!exactArchiveExt.equals(archiveExtension(actualFormat))) {
                    throw new IllegalStateException("Internet Archive вернул файл другого формата.");
                }
            }

            Path savedPath;
            int suffix = 0;
            while (true) {
                String name = safeBaseName + (suffix == 0 ? "" : " (" + suffix + ")") + ext;
                try {
                    savedPath = Files.move(temporaryFile, outputDir.resolve(name));
                    temporaryFile = null;
                    break;
                } catch (java.nio.file.FileAlreadyExistsException ex) {
                    suffix++;
                }
            }
            outFile = savedPath.toFile();

            task.finishTransfer(outFile);
            if (progressCallback != null) {
                progressCallback.accept(1.0);
            }
            notifyUpdate();
            return outFile;
            }
        } catch (Exception ex) {
            task.setErrorMessage(ex.getMessage());
            task.setStatus(DownloadTask.Status.FAILED);
            notifyUpdate();
            throw ex;
        } finally {
            if (temporaryFile != null) Files.deleteIfExists(temporaryFile);
        }
    }

    private static String portableBaseName(String title) {
        String name = UNSAFE_FILENAME_CHARACTERS.matcher(title).replaceAll("_").strip();
        if (name.length() > MAX_TITLE_FILENAME_LENGTH) {
            int end = MAX_TITLE_FILENAME_LENGTH;
            if (Character.isHighSurrogate(name.charAt(end - 1))) end--;
            name = name.substring(0, end);
        }
        name = name.replaceFirst("[. ]+$", "");
        if (name.isBlank()) return "book";

        int firstDot = name.indexOf('.');
        String stem = firstDot < 0 ? name : name.substring(0, firstDot).stripTrailing();
        return WINDOWS_DEVICE_NAME.matcher(stem).matches() ? "_" + name : name;
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
        Path tmp = Files.createTempFile(file.toPath().toAbsolutePath().getParent(), ".luminaread-gunzip-", ".tmp");
        try {
        try (InputStream raw = new FileInputStream(file);
             GZIPInputStream gis = new GZIPInputStream(raw);
             FileOutputStream fos = new FileOutputStream(tmp.toFile())) {
            byte[] buf = new byte[65536];
            int r;
            long total = 0;
            while ((r = gis.read(buf)) > 0) {
                if (r > maxExpandedBytes - total) throw new IllegalStateException("Размер распакованной книги превышает допустимый предел.");
                fos.write(buf, 0, r);
                total += r;
            }
        }
        Files.move(tmp, file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return file;
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    public void validateDownloadedBookFile(File file, String requestedFormat) throws Exception {
        if (file == null || !file.isFile() || file.length() == 0) {
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
                        throw new IllegalStateException("Это издание защищено от прямого скачивания на сайте источника (требуется вход/аренда). Выберите другое издание в списке или формат PDF/TXT.");
                    }
                    throw new IllegalStateException("Вместо книги сервер вернул веб-страницу. Выберите другой источник или формат.");
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
        if (originalUrl == null) return null;
        URI uri = URI.create(originalUrl);
        if (!"archive.org".equalsIgnoreCase(uri.getHost())) return originalUrl;
        String[] path = uri.getPath().split("/", 4);
        if (path.length < 3 || !"download".equals(path[1]) || !path[2].matches("[A-Za-z0-9_][A-Za-z0-9_.-]{0,199}")) {
            return originalUrl;
        }
        String extension = archiveExtension(formatHint);
        if (extension == null) return originalUrl;
        String originalFile = path.length == 4 ? path[3] : "";
        String archiveId = path[2];
        List<?> files = archiveFiles(archiveId);

        if (files != null) {
            String alternative = null;
            for (Object entry : files) {
                if (!(entry instanceof Map<?, ?> file) || !(file.get("name") instanceof String name)
                        || !matchesArchiveFormat(name, extension)
                        || file.get("format") instanceof String kind && kind.toLowerCase(Locale.ROOT).contains("encrypted")) {
                    continue;
                }
                if (name.equals(originalFile)) return originalUrl;
                if (alternative == null) alternative = name;
            }
            if (alternative != null) return buildArchiveDownloadUrl(archiveId, alternative);
        } else if (matchesArchiveFormat(originalFile, extension)) {
            return originalUrl;
        }
        throw new IllegalStateException("В Internet Archive не найден файл формата " + formatHint + ".");
    }

    private List<?> archiveFiles(String archiveId) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://archive.org/metadata/" + archiveId + "/files"))
                    .header("User-Agent", USER_AGENT).timeout(Duration.ofSeconds(10)).GET().build();
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream input = new TimedInput(response.body(), Duration.ofSeconds(10))) {
                if (response.statusCode() != 200) return null;
                byte[] bytes = input.readNBytes(2 * 1024 * 1024 + 1);
                if (bytes.length > 2 * 1024 * 1024) return null;
                if (Json.parse(new String(bytes, StandardCharsets.UTF_8)) instanceof Map<?, ?> metadata) {
                    if (metadata.get("files") instanceof List<?> files) return files;
                    if (metadata.get("result") instanceof List<?> files) return files;
                }
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new java.util.concurrent.CancellationException("Загрузка отменена.");
        } catch (IOException | IllegalArgumentException ex) {
            java.util.logging.Logger.getLogger(BookDownloader.class.getName())
                    .log(java.util.logging.Level.FINE, "Не удалось получить метаданные Internet Archive", ex);
        }
        return null;
    }

    private static String archiveExtension(String format) {
        if (format == null) return null;
        return switch (format.strip().toUpperCase(Locale.ROOT)) {
            case "EPUB" -> ".epub";
            case "PDF" -> ".pdf";
            case "TXT" -> ".txt";
            case "FB2" -> ".fb2";
            case "FB2.ZIP" -> ".fb2.zip";
            default -> null;
        };
    }

    private static boolean matchesArchiveFormat(String name, String extension) {
        String lower = name.toLowerCase(Locale.ROOT);
        return !lower.contains("encrypted") && lower.endsWith(extension);
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
            } catch (RuntimeException ex) {
                java.util.logging.Logger.getLogger(BookDownloader.class.getName())
                        .log(java.util.logging.Level.WARNING, "Ошибка обработчика состояния загрузки", ex);
            }
        }
    }

}
