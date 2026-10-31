package com.luminaread.service;

import com.luminaread.model.DownloadTask;
import com.luminaread.model.OnlineBookItem;
import com.luminaread.util.CharsetDetector;
import com.luminaread.util.HtmlSanitizer;

import javax.swing.SwingUtilities;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.StringReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.DoubleConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class OnlineCatalogService implements IOnlineCatalogService {
    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_0) AppleWebKit/605.1.15 (KHTML, like Gecko) LuminaRead/2.2";

    private static final Pattern GUTENDEX_ID_TITLE_PATTERN = Pattern.compile(
            "\\{\"id\":\\s*(\\d+)\\s*,\\s*\"title\":\\s*\"((?:[^\"\\\\]|\\\\.)*)\"([\\s\\S]*?)\"download_count\":\\s*(\\d+)",
            Pattern.MULTILINE);
    private static final Pattern GUTENDEX_AUTHOR_PATTERN = Pattern.compile(
            "\"authors\":\\s*\\[\\s*\\{[^}]*\"name\":\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern GUTENDEX_LANG_PATTERN = Pattern.compile(
            "\"languages\":\\s*\\[\\s*\"([a-zA-Z\\-]+)\"");
    private static final Pattern ARCHIVE_FILE_NAME_PATTERN = Pattern.compile(
            "\"name\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    private final HttpClient httpClient;
    private final List<DownloadTask> downloadTasks = new CopyOnWriteArrayList<>();
    private final List<Runnable> downloadListeners = new CopyOnWriteArrayList<>();

    public OnlineCatalogService() {
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .connectTimeout(Duration.ofSeconds(12))
                .build();
    }

    public List<DownloadTask> getDownloadTasks() {
        return new ArrayList<>(downloadTasks);
    }

    public void clearCompletedTasks() {
        downloadTasks.removeIf(t -> t.getStatus() != DownloadTask.Status.DOWNLOADING);
        notifyDownloadListeners();
    }

    public void addDownloadListener(Runnable listener) {
        if (listener != null) {
            downloadListeners.add(listener);
        }
    }

    public void notifyDownloadListeners() {
        SwingUtilities.invokeLater(() -> {
            for (Runnable r : downloadListeners) {
                try {
                    r.run();
                } catch (Exception ignored) {
                }
            }
        });
    }

    public DownloadTask createTrackedTask(String title, String formatHint, String url) {
        DownloadTask task = new DownloadTask(title, formatHint, url);
        downloadTasks.add(0, task);
        notifyDownloadListeners();
        return task;
    }

    public List<OnlineBookItem> searchBooks(String query, String sourceFilter, String languageFilter) {
        String cleanQuery = (query == null) ? "" : query.trim();
        List<CompletableFuture<List<OnlineBookItem>>> tasks = new ArrayList<>();

        boolean searchAll = "ALL".equalsIgnoreCase(sourceFilter);

        if (searchAll || "FLIBUSTA".equalsIgnoreCase(sourceFilter)) {
            tasks.add(CompletableFuture.supplyAsync(() -> searchFlibustaOpds(cleanQuery)));
        }
        if (searchAll || "LIBRUSEC".equalsIgnoreCase(sourceFilter)) {
            tasks.add(CompletableFuture.supplyAsync(() -> searchLibrusecOpds(cleanQuery)));
        }
        if (searchAll || "GUTENBERG".equalsIgnoreCase(sourceFilter)) {
            tasks.add(CompletableFuture.supplyAsync(() -> searchGutendex(cleanQuery, languageFilter)));
        }
        if (searchAll || "OPENLIBRARY".equalsIgnoreCase(sourceFilter)) {
            tasks.add(CompletableFuture.supplyAsync(() -> searchOpenLibrary(cleanQuery)));
        }
        if ("STANDARD_EBOOKS".equalsIgnoreCase(sourceFilter)) {
            tasks.add(CompletableFuture.supplyAsync(() ->
                    fetchOpdsFeed("https://standardebooks.org/opds/all?query=" + encodeUrl(cleanQuery), "Standard Ebooks")));
        }

        List<OnlineBookItem> combined = new ArrayList<>();
        for (CompletableFuture<List<OnlineBookItem>> future : tasks) {
            try {
                List<OnlineBookItem> part = future.join();
                if (part != null) {
                    combined.addAll(part);
                }
            } catch (Exception ignored) {
            }
        }
        return combined;
    }

    public List<OnlineBookItem> browseCustomOpds(String opdsUrl, String optionalSearchTerm) {
        if (opdsUrl == null || opdsUrl.isBlank()) return Collections.emptyList();
        String targetUrl = opdsUrl.trim();
        if (optionalSearchTerm != null && !optionalSearchTerm.isBlank()) {
            if (targetUrl.contains("{searchTerms}")) {
                targetUrl = targetUrl.replace("{searchTerms}", encodeUrl(optionalSearchTerm));
            } else if (targetUrl.endsWith("/opds") || targetUrl.endsWith("/opds/")) {
                String base = targetUrl.endsWith("/") ? targetUrl.substring(0, targetUrl.length() - 1) : targetUrl;
                targetUrl = base + "/search?searchTerm=" + encodeUrl(optionalSearchTerm) + "&searchType=books";
            }
        }
        return fetchOpdsFeed(targetUrl, "OPDS Каталог");
    }

    private List<OnlineBookItem> searchFlibustaOpds(String query) {
        String[] mirrors = {
                "https://flibusta.is",
                "http://flibusta.is",
                "https://flibusta.site",
                "http://flibusta.site",
                "https://flibusta.su",
                "http://flibusta.su",
                "https://flibusta.club",
                "http://flibusta.club"
        };
        for (String base : mirrors) {
            String url = query.isEmpty()
                    ? (base + "/opds/new/0/new")
                    : (base + "/opds/search?searchTerm=" + encodeUrl(query) + "&searchType=books");
            List<OnlineBookItem> items = fetchOpdsFeed(url, "Flibusta OPDS");
            if (!items.isEmpty()) {
                return items;
            }
        }
        return Collections.emptyList();
    }

    private List<OnlineBookItem> searchLibrusecOpds(String query) {
        String[] bases = {
                "https://lib.rus.ec",
                "http://lib.rus.ec"
        };
        for (String base : bases) {
            String url = query.isEmpty()
                    ? (base + "/new/opds")
                    : (base + "/searchopds?ask=" + encodeUrl(query));
            List<OnlineBookItem> items = fetchOpdsFeed(url, "Либрусек (lib.rus.ec)");
            if (!items.isEmpty()) {
                return items;
            }
        }
        return Collections.emptyList();
    }

    public List<OnlineBookItem> fetchOpdsFeed(String feedUrl, String sourceLabel) {
        List<OnlineBookItem> results = new ArrayList<>();
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(feedUrl))
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/atom+xml, application/xml, text/xml, */*")
                    .timeout(Duration.ofSeconds(14))
                    .GET()
                    .build();

            HttpResponse<byte[]> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() >= 200 && resp.statusCode() < 300 && resp.body() != null) {
                String xml = CharsetDetector.decodeBytes(resp.body());
                int firstLt = xml.indexOf('<');
                if (firstLt > 0) xml = xml.substring(firstLt);

                xml = escapeBareAmpersands(xml);

                DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                factory.setNamespaceAware(false);
                factory.setValidating(false);
                try {
                    factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
                } catch (Exception ignored) {
                }
                DocumentBuilder builder = factory.newDocumentBuilder();
                Document doc = builder.parse(new InputSource(new StringReader(xml)));

                URI baseUri = URI.create(feedUrl);
                NodeList entries = doc.getElementsByTagName("entry");
                for (int i = 0; i < entries.getLength(); i++) {
                    if (!(entries.item(i) instanceof Element entry)) continue;
                    String title = getChildText(entry, "title");
                    String author = getAuthorName(entry);
                    String summary = HtmlSanitizer.htmlToPlainText(getChildText(entry, "content"));
                    if (summary.isBlank()) {
                        summary = HtmlSanitizer.htmlToPlainText(getChildText(entry, "summary"));
                    }
                    String lang = getChildText(entry, "dc:language");
                    if (lang.isBlank()) lang = getChildText(entry, "dcterms:language");
                    if (lang.isBlank()) lang = getChildText(entry, "language");
                    if (lang.isBlank()) lang = "ru";

                    lang = lang.trim().toLowerCase(Locale.ROOT);
                    if (lang.length() > 2) {
                        int cut = lang.indexOf('-');
                        if (cut < 0) cut = lang.indexOf('_');
                        if (cut > 0) lang = lang.substring(0, cut);
                    }

                    OnlineBookItem bookItem = new OnlineBookItem(title, author, summary, lang, sourceLabel);

                    NodeList links = entry.getElementsByTagName("link");
                    for (int l = 0; l < links.getLength(); l++) {
                        if (!(links.item(l) instanceof Element linkEl)) continue;
                        String href = linkEl.getAttribute("href");
                        String type = linkEl.getAttribute("type").toLowerCase(Locale.ROOT);
                        String rel = linkEl.getAttribute("rel").toLowerCase(Locale.ROOT);
                        if (href == null || href.isBlank()) continue;

                        String absHref;
                        try {
                            absHref = baseUri.resolve(href).toString();
                        } catch (Exception e) {
                            absHref = href;
                        }

                        if (rel.contains("acquisition") || isBookMimeType(type) || hasBookExtension(absHref)) {
                            String fmt = detectFormatFromMimeOrHref(type, absHref);
                            if (fmt != null) {
                                bookItem.addDownloadLink(fmt, absHref);
                            }
                        }
                    }

                    if (bookItem.hasDownloadLinks()) {
                        results.add(bookItem);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return results;
    }

    private List<OnlineBookItem> searchGutendex(String query, String langFilter) {
        List<OnlineBookItem> results = new ArrayList<>();
        try {
            StringBuilder urlSb = new StringBuilder("https://gutendex.com/books/?");
            if (!query.isEmpty()) {
                urlSb.append("search=").append(encodeUrl(query)).append("&");
            }
            if (langFilter != null && !"ALL".equalsIgnoreCase(langFilter) && !langFilter.isBlank()) {
                urlSb.append("languages=").append(encodeUrl(langFilter.toLowerCase(Locale.ROOT)));
            }

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(urlSb.toString()))
                    .header("User-Agent", USER_AGENT)
                    .timeout(Duration.ofSeconds(14))
                    .GET()
                    .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() == 200 && resp.body() != null) {
                parseGutendexJson(resp.body(), results);
            }
        } catch (Exception ignored) {
        }
        return results;
    }

    private void parseGutendexJson(String json, List<OnlineBookItem> out) {
        int resultsIdx = json.indexOf("\"results\"");
        if (resultsIdx < 0) return;

        Matcher m = GUTENDEX_ID_TITLE_PATTERN.matcher(json.substring(resultsIdx));
        while (m.find()) {
            String bookId = m.group(1);
            String title = unescapeJson(m.group(2));
            String bodySegment = m.group(3);

            String author = "Классическая литература";
            Matcher authorMatcher = GUTENDEX_AUTHOR_PATTERN.matcher(bodySegment);
            if (authorMatcher.find()) {
                author = unescapeJson(authorMatcher.group(1));
            }

            String lang = "en";
            Matcher langMatcher = GUTENDEX_LANG_PATTERN.matcher(bodySegment);
            if (langMatcher.find()) {
                lang = langMatcher.group(1);
            }

            OnlineBookItem item = new OnlineBookItem(
                    title,
                    author,
                    "Бесплатное издание из фонда Project Gutenberg (ID #" + bookId + "). Доступно в форматах EPUB, TXT и HTML.",
                    lang,
                    "Project Gutenberg"
            );

            item.addDownloadLink("EPUB", "https://www.gutenberg.org/ebooks/" + bookId + ".epub3.images");
            item.addDownloadLink("TXT", "https://www.gutenberg.org/ebooks/" + bookId + ".txt.utf-8");
            item.addDownloadLink("HTML", "https://www.gutenberg.org/ebooks/" + bookId + ".html.images");

            out.add(item);
        }
    }

    private List<OnlineBookItem> searchOpenLibrary(String query) {
        if (query == null || query.isBlank()) {
            query = "classic literature";
        }
        List<OnlineBookItem> results = new ArrayList<>();
        try {

            String iaSearchUrl = "https://archive.org/advancedsearch.php?q=("
                    + encodeUrl(query)
                    + ")+AND+mediatype:texts+AND+NOT+collection:inlibrary+AND+NOT+collection:printdisabled"
                    + "&fl[]=identifier,title,creator,language&rows=18&output=json";

            HttpRequest iaReq = HttpRequest.newBuilder()
                    .uri(URI.create(iaSearchUrl))
                    .header("User-Agent", USER_AGENT)
                    .timeout(Duration.ofSeconds(14))
                    .GET()
                    .build();

            HttpResponse<String> iaResp = httpClient.send(iaReq, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (iaResp.statusCode() == 200 && iaResp.body() != null) {
                parseArchiveOrgAdvancedJson(iaResp.body(), results);
            }
        } catch (Exception ignored) {
        }

        if (!results.isEmpty()) {
            return results;
        }

        try {
            String url = "https://openlibrary.org/search.json?q=" + encodeUrl(query)
                    + "&fields=title,author_name,ia,first_publish_year,public_scan_b&limit=20";
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", USER_AGENT)
                    .timeout(Duration.ofSeconds(14))
                    .GET()
                    .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() == 200 && resp.body() != null) {
                parseOpenLibraryJson(resp.body(), results);
            }
        } catch (Exception ignored) {
        }
        return results;
    }

    private void parseArchiveOrgAdvancedJson(String json, List<OnlineBookItem> out) {
        int docsIdx = json.indexOf("\"docs\"");
        if (docsIdx < 0) return;

        String[] rawObjects = json.substring(docsIdx).split("\\{\\s*\"");
        for (int i = 1; i < rawObjects.length; i++) {
            String obj = "{\"" + rawObjects[i];
            String iaId = extractJsonString(obj, "identifier");
            String title = extractJsonString(obj, "title");
            if (iaId.isBlank() || title.isBlank()) continue;

            String author = extractJsonString(obj, "creator");
            if (author.isBlank()) author = extractFirstJsonArrayString(obj, "creator");
            if (author.isBlank()) author = "Архив Internet Archive";

            OnlineBookItem item = new OnlineBookItem(
                    title,
                    author,
                    "Свободный доступ в цифровом фонде Internet Archive (ID: " + iaId + ").",
                    "ru/en",
                    "Internet Archive (Open Access)"
            );
            item.addDownloadLink("EPUB", "https://archive.org/download/" + iaId + "/" + iaId + ".epub");
            item.addDownloadLink("PDF", "https://archive.org/download/" + iaId + "/" + iaId + ".pdf");
            item.addDownloadLink("TXT", "https://archive.org/download/" + iaId + "/" + iaId + "_djvu.txt");
            out.add(item);
        }
    }

    private void parseOpenLibraryJson(String json, List<OnlineBookItem> out) {
        int docsIdx = json.indexOf("\"docs\"");
        if (docsIdx < 0) return;

        String[] rawObjects = json.substring(docsIdx).split("\\{\\s*\"");
        for (int i = 1; i < rawObjects.length; i++) {
            String obj = "{\"" + rawObjects[i];
            String title = extractJsonString(obj, "title");
            if (title.isBlank()) continue;

            String iaId = extractFirstJsonArrayString(obj, "ia");
            if (iaId.isBlank()) continue;

            String author = extractFirstJsonArrayString(obj, "author_name");
            if (author.isBlank()) author = "Неизвестный автор";

            String year = extractJsonNumber(obj, "first_publish_year");
            String summary = "Каталог Open Library / Internet Archive"
                    + (year.isEmpty() ? "." : " (год издания: " + year + ").");

            OnlineBookItem item = new OnlineBookItem(title, author, summary, "ru/en", "Open Library / Archive.org");
            item.addDownloadLink("EPUB", "https://archive.org/download/" + iaId + "/" + iaId + ".epub");
            item.addDownloadLink("PDF", "https://archive.org/download/" + iaId + "/" + iaId + ".pdf");
            item.addDownloadLink("TXT", "https://archive.org/download/" + iaId + "/" + iaId + "_djvu.txt");
            out.add(item);
        }
    }

    private String resolveBestArchiveOrgUrl(String originalUrl, String formatHint) {
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

    public File downloadBookFile(String downloadUrl, String suggestedTitle, String formatHint,
                                 Path destinationDir, DoubleConsumer progressCallback) throws Exception {
        DownloadTask task = createTrackedTask(suggestedTitle, formatHint, downloadUrl);
        return downloadBookFileWithTask(task, destinationDir, progressCallback);
    }

    public File downloadBookFileWithTask(DownloadTask task, Path destinationDir, DoubleConsumer progressCallback) throws Exception {
        long startTimeMs = System.currentTimeMillis();
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

            File outFile = destinationDir.resolve(safeBaseName + ext).toFile();
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
                        notifyDownloadListeners();
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
            notifyDownloadListeners();
            return outFile;
        } catch (Exception ex) {
            task.setStatus(DownloadTask.Status.FAILED);
            task.setErrorMessage(ex.getMessage());
            notifyDownloadListeners();
            throw ex;
        }
    }

    private File decompressGzipIfNeeded(File file) throws Exception {
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

    private void validateDownloadedBookFile(File file, String requestedFormat) throws Exception {
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

    private String determineExtension(String url, String formatHint, String contentType, String disposition) {
        String lowerDisp = disposition.toLowerCase(Locale.ROOT);
        if (lowerDisp.contains(".fb2.zip")) return ".fb2.zip";
        if (lowerDisp.contains(".fb2")) return ".fb2";
        if (lowerDisp.contains(".epub")) return ".epub";
        if (lowerDisp.contains(".pdf")) return ".pdf";

        String lowerUrl = url.toLowerCase(Locale.ROOT);
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

        String lowerCt = contentType.toLowerCase(Locale.ROOT);
        if (lowerCt.contains("epub")) return ".epub";
        if (lowerCt.contains("fb2")) return ".fb2";
        if (lowerCt.contains("pdf")) return ".pdf";
        if (lowerCt.contains("html")) return ".html";
        return ".txt";
    }

    private static String escapeBareAmpersands(String xml) {
        StringBuilder sb = new StringBuilder(xml.length() + 64);
        for (int i = 0; i < xml.length(); i++) {
            char c = xml.charAt(i);
            if (c == '&') {
                int semi = xml.indexOf(';', i + 1);
                boolean isEntity = false;
                if (semi > i + 1 && semi - i <= 12) {
                    String body = xml.substring(i + 1, semi);
                    if (body.matches("#[0-9]+") || body.matches("#[xX][0-9a-fA-F]+")
                            || body.matches("[a-zA-Z][a-zA-Z0-9]*")) {
                        isEntity = true;
                    }
                }
                sb.append(isEntity ? "&" : "&amp;");
            } else if (c < 0x20 && c != '\n' && c != '\r' && c != '\t') {

                sb.append(' ');
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private boolean isBookMimeType(String type) {
        return type.contains("epub") || type.contains("fb2") || type.contains("pdf")
                || type.contains("text/plain") || type.contains("zip") || type.contains("mobi");
    }

    private boolean hasBookExtension(String href) {
        String l = href.toLowerCase(Locale.ROOT);
        return l.endsWith(".epub") || l.endsWith(".fb2") || l.endsWith(".fb2.zip")
                || l.endsWith(".pdf") || l.endsWith(".txt") || l.endsWith("/fb2") || l.endsWith("/epub");
    }

    private String detectFormatFromMimeOrHref(String mime, String href) {
        String lHref = href.toLowerCase(Locale.ROOT);
        if (mime.contains("fb2+zip") || lHref.endsWith(".fb2.zip") || lHref.endsWith("/fb2")) return "FB2.ZIP";
        if (mime.contains("fb2") || lHref.endsWith(".fb2")) return "FB2";
        if (mime.contains("epub") || lHref.endsWith(".epub") || lHref.endsWith("/epub")) return "EPUB";
        if (mime.contains("pdf") || lHref.endsWith(".pdf")) return "PDF";
        if (mime.contains("text/plain") || lHref.endsWith(".txt")) return "TXT";
        if (mime.contains("html") || lHref.endsWith(".html")) return "HTML";
        return null;
    }

    private String getChildText(Element parent, String tag) {
        NodeList list = parent.getElementsByTagName(tag);
        if (list.getLength() > 0) {
            return list.item(0).getTextContent().replaceAll("\\s+", " ").trim();
        }
        return "";
    }

    private String getAuthorName(Element entry) {
        NodeList authors = entry.getElementsByTagName("author");
        if (authors.getLength() > 0 && authors.item(0) instanceof Element aEl) {
            String name = getChildText(aEl, "name");
            if (!name.isBlank()) return name;
        }
        return "Неизвестный автор";
    }

    private String extractJsonString(String obj, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(obj);
        return m.find() ? unescapeJson(m.group(1)) : "";
    }

    private String extractFirstJsonArrayString(String obj, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\\[\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(obj);
        return m.find() ? unescapeJson(m.group(1)) : "";
    }

    private String extractJsonNumber(String obj, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*(\\d+)").matcher(obj);
        return m.find() ? m.group(1) : "";
    }

    private String unescapeJson(String s) {
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

    private String encodeUrl(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
