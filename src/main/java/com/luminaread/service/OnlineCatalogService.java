package com.luminaread.service;

import com.luminaread.model.DownloadTask;
import com.luminaread.model.OnlineBookItem;
import com.luminaread.service.catalog.BookDownloader;
import com.luminaread.service.catalog.IBookDownloader;
import com.luminaread.service.catalog.OpdsFeedParser;

import javax.swing.SwingUtilities;
import java.io.File;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
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

/**
 * Online catalog service coordinating book search across OPDS and JSON providers,
 * managing download task state and UI notification listeners, and delegating
 * feed parsing to {@link OpdsFeedParser} and file downloading to {@link IBookDownloader}.
 */
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

    private final HttpClient httpClient;
    private final OpdsFeedParser opdsFeedParser;
    private final IBookDownloader bookDownloader;
    private final List<DownloadTask> downloadTasks = new CopyOnWriteArrayList<>();
    private final List<Runnable> downloadListeners = new CopyOnWriteArrayList<>();

    public OnlineCatalogService() {
        this(new OpdsFeedParser(), null, null);
    }

    public OnlineCatalogService(OpdsFeedParser opdsFeedParser, IBookDownloader bookDownloader, HttpClient httpClient) {
        this.httpClient = (httpClient != null) ? httpClient : HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .connectTimeout(Duration.ofSeconds(12))
                .build();
        this.opdsFeedParser = (opdsFeedParser != null) ? opdsFeedParser : new OpdsFeedParser();
        this.bookDownloader = (bookDownloader != null) ? bookDownloader : new BookDownloader(this.httpClient, this::notifyDownloadListeners);
    }

    public OpdsFeedParser getOpdsFeedParser() {
        return opdsFeedParser;
    }

    public IBookDownloader getBookDownloader() {
        return bookDownloader;
    }

    // ==========================================
    // IDownloadManager Implementation
    // ==========================================

    @Override
    public List<DownloadTask> getDownloadTasks() {
        return new ArrayList<>(downloadTasks);
    }

    @Override
    public void clearCompletedTasks() {
        downloadTasks.removeIf(t -> t.getStatus() != DownloadTask.Status.DOWNLOADING);
        notifyDownloadListeners();
    }

    @Override
    public void addDownloadListener(Runnable listener) {
        if (listener != null) {
            downloadListeners.add(listener);
        }
    }

    @Override
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

    @Override
    public DownloadTask createTrackedTask(String title, String formatHint, String url) {
        DownloadTask task = new DownloadTask(title, formatHint, url);
        downloadTasks.add(0, task);
        notifyDownloadListeners();
        return task;
    }

    // ==========================================
    // ICatalogSearchService Implementation
    // ==========================================

    @Override
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

    @Override
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

    @Override
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
                return opdsFeedParser.parse(resp.body(), feedUrl, sourceLabel);
            }
        } catch (Exception ignored) {
        }
        return results;
    }

    // ==========================================
    // IBookDownloader Implementation
    // ==========================================

    @Override
    public File downloadBookFile(String downloadUrl, String suggestedTitle, String formatHint,
                                 Path destinationDir, DoubleConsumer progressCallback) throws Exception {
        DownloadTask task = createTrackedTask(suggestedTitle, formatHint, downloadUrl);
        return bookDownloader.downloadBookFileWithTask(task, destinationDir, progressCallback);
    }

    @Override
    public File downloadBookFileWithTask(DownloadTask task, Path destinationDir, DoubleConsumer progressCallback) throws Exception {
        return bookDownloader.downloadBookFileWithTask(task, destinationDir, progressCallback);
    }

    // ==========================================
    // Internal Search Providers & JSON Parsers
    // ==========================================

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

    private static String encodeUrl(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
