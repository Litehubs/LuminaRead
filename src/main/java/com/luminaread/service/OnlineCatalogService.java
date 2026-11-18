package com.luminaread.service;

import com.luminaread.model.Book;
import com.luminaread.model.DownloadTask;
import com.luminaread.model.OnlineBookItem;
import com.luminaread.service.catalog.BookDownloader;
import com.luminaread.util.BoundedHttpBody;
import com.luminaread.util.HttpRequests;
import com.luminaread.service.catalog.IBookDownloader;
import com.luminaread.service.catalog.OpdsFeedParser;

import javax.swing.SwingUtilities;
import java.io.File;
import java.io.IOException;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ExecutionException;
import java.util.function.DoubleConsumer;
import java.util.regex.Pattern;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Online catalog service coordinating book search across OPDS and JSON providers,
 * managing download task state and UI notification listeners, and delegating
 * feed parsing to {@link OpdsFeedParser} and file downloading to {@link IBookDownloader}.
 */
public class OnlineCatalogService implements IOnlineCatalogService {
    private static final Logger LOG = Logger.getLogger(OnlineCatalogService.class.getName());
    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_0) AppleWebKit/605.1.15 (KHTML, like Gecko) LuminaRead/2.2";

    private static final HttpResponse.BodyHandler<byte[]> FEED_BODY =
            BoundedHttpBody.bounded(4 * 1024 * 1024, "Ответ каталога превышает допустимый размер.");

    private static final Pattern ARCHIVE_ID = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_.-]{0,199}");

    private final HttpClient httpClient;
    private final OpdsFeedParser opdsFeedParser;
    private final IBookDownloader bookDownloader;
    private final LibraryDownloads downloads;
    private final List<Runnable> downloadListeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean downloadNotificationPending = new AtomicBoolean();

    public OnlineCatalogService() {
        this(new OpdsFeedParser(), null, null);
    }

    public OnlineCatalogService(OpdsFeedParser opdsFeedParser, IBookDownloader bookDownloader, HttpClient httpClient) {
        this.httpClient = (httpClient != null) ? httpClient : HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(12))
                .build();
        this.opdsFeedParser = (opdsFeedParser != null) ? opdsFeedParser : new OpdsFeedParser();
        this.bookDownloader = (bookDownloader != null) ? bookDownloader : new BookDownloader(this.httpClient, this::notifyDownloadListeners);
        this.downloads = new LibraryDownloads(this.bookDownloader, this::notifyDownloadListeners);
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
        return downloads.tasks();
    }

    @Override
    public void clearCompletedTasks() {
        downloads.clear();
    }

    public void clearLibraryDownloads(LibraryStorageService library) throws IOException {
        downloads.clear(library);
    }

    public void recoverDownloads(LibraryStorageService library) throws IOException {
        downloads.recover(library);
    }

    @Override
    public void addDownloadListener(Runnable listener) {
        if (listener != null) {
            downloadListeners.add(listener);
        }
    }

    public void removeDownloadListener(Runnable listener) {
        downloadListeners.remove(listener);
    }

    @Override
    public void notifyDownloadListeners() {
        if (!downloadNotificationPending.compareAndSet(false, true)) return;
        SwingUtilities.invokeLater(() -> {
            downloadNotificationPending.set(false);
            for (Runnable r : downloadListeners) {
                try {
                    r.run();
                } catch (RuntimeException ex) {
                    LOG.log(Level.WARNING, "Ошибка обработчика обновления загрузок", ex);
                }
            }
        });
    }

    @Override
    public DownloadTask createTrackedTask(String title, String formatHint, String url) {
        return downloads.track(new DownloadTask(title, formatHint, url));
    }

    public DownloadTask createLibraryDownload(String title, String formatHint, String url) {
        return downloads.track(new DownloadTask(title, formatHint, url, true));
    }

    // ==========================================
    // ICatalogSearchService Implementation
    // ==========================================

    public record SearchResult(List<OnlineBookItem> books, List<String> unavailableSources) {
        public SearchResult {
            books = List.copyOf(books);
            unavailableSources = List.copyOf(unavailableSources);
        }
    }

    @Override
    public List<OnlineBookItem> searchBooks(String query, String sourceFilter, String languageFilter) {
        return searchBooksDetailed(query, sourceFilter, languageFilter).books();
    }

    public SearchResult searchBooksDetailed(String query, String sourceFilter, String languageFilter) {
        String cleanQuery = query == null ? "" : query.trim();
        Map<String, CompletableFuture<List<OnlineBookItem>>> tasks = new LinkedHashMap<>();
        boolean searchAll = "ALL".equalsIgnoreCase(sourceFilter);

        if (searchAll || "FLIBUSTA".equalsIgnoreCase(sourceFilter)) {
            tasks.put("Флибуста", CompletableFuture.supplyAsync(() -> searchFlibustaOpds(cleanQuery)));
        }
        if (searchAll || "LIBRUSEC".equalsIgnoreCase(sourceFilter)) {
            tasks.put("Либрусек", CompletableFuture.supplyAsync(() -> searchLibrusecOpds(cleanQuery)));
        }
        if (searchAll || "GUTENBERG".equalsIgnoreCase(sourceFilter)) {
            tasks.put("Project Gutenberg", CompletableFuture.supplyAsync(() -> searchGutendex(cleanQuery, languageFilter)));
        }
        if (searchAll || "OPENLIBRARY".equalsIgnoreCase(sourceFilter)) {
            tasks.put("Open Library", CompletableFuture.supplyAsync(() -> searchOpenLibrary(cleanQuery)));
        }
        if ("STANDARD_EBOOKS".equalsIgnoreCase(sourceFilter)) {
            tasks.put("Standard Ebooks", CompletableFuture.supplyAsync(() ->
                    fetchOpdsFeed("https://standardebooks.org/opds/all?query=" + encodeUrl(cleanQuery), "Standard Ebooks")));
        }

        return collectSearchResults(tasks);
    }

    static SearchResult collectSearchResults(Map<String, CompletableFuture<List<OnlineBookItem>>> tasks) {
        List<OnlineBookItem> books = new ArrayList<>();
        List<String> unavailable = new ArrayList<>();
        var totalFailure = new IllegalStateException("Не удалось выполнить поиск в выбранных каталогах");
        for (var source : tasks.entrySet()) {
            try {
                books.addAll(source.getValue().get());
            } catch (InterruptedException ex) {
                tasks.values().forEach(task -> task.cancel(false));
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Поиск в каталогах отменён", ex);
            } catch (ExecutionException ex) {
                Throwable cause = ex.getCause();
                LOG.log(Level.WARNING, "Не удалось выполнить поиск в каталоге " + source.getKey(), cause);
                unavailable.add(source.getKey());
                totalFailure.addSuppressed(cause);
            }
        }
        if (!tasks.isEmpty() && unavailable.size() == tasks.size()) throw totalFailure;
        return new SearchResult(books, unavailable);
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
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(feedUrl))
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/atom+xml, application/xml, text/xml, */*")
                    .timeout(Duration.ofSeconds(14))
                    .GET()
                    .build();

            HttpResponse<byte[]> resp = HttpRequests.send(httpClient, req, FEED_BODY);
            if (resp.statusCode() >= 200 && resp.statusCode() < 300 && resp.body() != null) {
                return opdsFeedParser.parseStrict(resp.body(), resp.uri().toString(), sourceLabel);
            }
            throw new java.io.IOException("Каталог вернул HTTP " + resp.statusCode());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Загрузка каталога прервана", ex);
        } catch (Exception ex) {
            throw new IllegalStateException("Не удалось загрузить " + sourceLabel, ex);
        }
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

    public Book downloadAndImportBook(DownloadTask task, LibraryStorageService library,
                                      DoubleConsumer progressCallback) throws Exception {
        return downloads.run(task, library, progressCallback);
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
        RuntimeException lastFailure = null;
        boolean gotResponse = false;
        for (String base : mirrors) {
            String url = query.isEmpty()
                    ? (base + "/opds/new/0/new")
                    : (base + "/opds/search?searchTerm=" + encodeUrl(query) + "&searchType=books");
            try {
                List<OnlineBookItem> items = fetchOpdsFeed(url, "Flibusta OPDS");
                gotResponse = true;
                if (!items.isEmpty()) return items;
            } catch (RuntimeException ex) {
                lastFailure = ex;
                if (Thread.currentThread().isInterrupted()) throw ex;
            }
        }
        if (!gotResponse && lastFailure != null) throw lastFailure;
        return Collections.emptyList();
    }

    private List<OnlineBookItem> searchLibrusecOpds(String query) {
        String[] bases = {
                "https://lib.rus.ec",
                "http://lib.rus.ec"
        };
        RuntimeException lastFailure = null;
        boolean gotResponse = false;
        for (String base : bases) {
            String url = query.isEmpty()
                    ? (base + "/new/opds")
                    : (base + "/searchopds?ask=" + encodeUrl(query));
            try {
                List<OnlineBookItem> items = fetchOpdsFeed(url, "Либрусек (lib.rus.ec)");
                gotResponse = true;
                if (!items.isEmpty()) return items;
            } catch (RuntimeException ex) {
                lastFailure = ex;
                if (Thread.currentThread().isInterrupted()) throw ex;
            }
        }
        if (!gotResponse && lastFailure != null) throw lastFailure;
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

            HttpResponse<byte[]> resp = HttpRequests.send(httpClient, req, FEED_BODY);
            if (resp.statusCode() != 200 || resp.body() == null) throw new java.io.IOException("HTTP " + resp.statusCode());
            if (!(com.luminaread.util.Json.parse(new String(resp.body(), StandardCharsets.UTF_8)) instanceof java.util.Map<?, ?> root)
                    || !(root.get("results") instanceof List<?> books)) throw new java.io.IOException("Некорректный ответ каталога");
            results.addAll(parseGutendexBooks(books));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Поиск Project Gutenberg прерван", ex);
        } catch (Exception ex) {
            throw new IllegalStateException("Не удалось выполнить поиск Project Gutenberg", ex);
        }
        return results;
    }

    static List<OnlineBookItem> parseGutendexBooks(List<?> books) {
        List<OnlineBookItem> results = new ArrayList<>();
        for (Object value : books) {
            if (!(value instanceof java.util.Map<?, ?> book)
                    || !(book.get("id") instanceof Long id) || id <= 0
                    || !(book.get("title") instanceof String title) || title.isBlank()) {
                continue;
            }

            String author = "Классическая литература";
            if (book.get("authors") instanceof List<?> authors) {
                for (Object entry : authors) {
                    if (entry instanceof java.util.Map<?, ?> person
                            && person.get("name") instanceof String name && !name.isBlank()) {
                        author = name;
                        break;
                    }
                }
            }
            String language = "en";
            if (book.get("languages") instanceof List<?> languages
                    && !languages.isEmpty() && languages.get(0) instanceof String code && !code.isBlank()) {
                language = code;
            }

            OnlineBookItem item = new OnlineBookItem(title, author,
                    "Бесплатное издание Project Gutenberg", language, "Project Gutenberg");
            String base = "https://www.gutenberg.org/ebooks/" + id;
            item.addDownloadLink("EPUB", base + ".epub3.images");
            item.addDownloadLink("TXT", base + ".txt.utf-8");
            item.addDownloadLink("HTML", base + ".html.images");
            results.add(item);
        }
        return results;
    }

    private List<OnlineBookItem> searchOpenLibrary(String query) {
        if (query == null || query.isBlank()) {
            query = "classic literature";
        }
        List<OnlineBookItem> results = new ArrayList<>();
        Exception firstFailure = null;
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

            HttpResponse<byte[]> iaResp = HttpRequests.send(httpClient, iaReq, FEED_BODY);
            if (iaResp.statusCode() != 200 || iaResp.body() == null) throw new java.io.IOException("HTTP " + iaResp.statusCode());
            if (!(com.luminaread.util.Json.parse(new String(iaResp.body(), StandardCharsets.UTF_8)) instanceof java.util.Map<?, ?> root)
                    || !(root.get("response") instanceof java.util.Map<?, ?> response)
                    || !(response.get("docs") instanceof List<?> docs)) throw new java.io.IOException("Некорректный ответ каталога");
            results.addAll(parseArchiveBooks(docs, true));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Поиск Internet Archive прерван", ex);
        } catch (Exception ex) {
            firstFailure = ex;
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

            HttpResponse<byte[]> resp = HttpRequests.send(httpClient, req, FEED_BODY);
            if (resp.statusCode() != 200 || resp.body() == null) throw new java.io.IOException("HTTP " + resp.statusCode());
            if (!(com.luminaread.util.Json.parse(new String(resp.body(), StandardCharsets.UTF_8)) instanceof java.util.Map<?, ?> root)
                    || !(root.get("docs") instanceof List<?> docs)) throw new java.io.IOException("Некорректный ответ каталога");
            results.addAll(parseArchiveBooks(docs, false));
            if (firstFailure != null) LOG.log(Level.FINE, "Internet Archive недоступен; использован Open Library", firstFailure);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Поиск Open Library прерван", ex);
        } catch (Exception ex) {
            if (firstFailure != null) ex.addSuppressed(firstFailure);
            throw new IllegalStateException("Не удалось выполнить поиск Open Library", ex);
        }
        return results;
    }

    static List<OnlineBookItem> parseArchiveBooks(List<?> docs, boolean fromInternetArchive) {
        List<OnlineBookItem> books = new ArrayList<>();
        for (Object value : docs) {
            if (!(value instanceof Map<?, ?> document)) continue;
            String title = firstText(document.get("title"));
            String archiveId = firstText(document.get(fromInternetArchive ? "identifier" : "ia"));
            if (title.isBlank() || !ARCHIVE_ID.matcher(archiveId).matches()) continue;

            String author = firstText(document.get(fromInternetArchive ? "creator" : "author_name"));
            if (author.isBlank()) author = fromInternetArchive ? "Архив Internet Archive" : "Неизвестный автор";
            String source = fromInternetArchive ? "Internet Archive (Open Access)" : "Open Library / Archive.org";
            String summary = fromInternetArchive ? "Цифровое издание Internet Archive"
                    : "Каталог Open Library / Internet Archive";
            if (!fromInternetArchive && document.get("first_publish_year") instanceof Long year && year > 0) {
                summary += " · " + year + " г.";
            }

            OnlineBookItem book = new OnlineBookItem(title, author, summary, "ru/en", source);
            String base = "https://archive.org/download/" + archiveId + "/" + archiveId;
            book.addDownloadLink("EPUB", base + ".epub");
            book.addDownloadLink("PDF", base + ".pdf");
            book.addDownloadLink("TXT", base + "_djvu.txt");
            books.add(book);
        }
        return books;
    }

    private static String firstText(Object value) {
        if (value instanceof String text) return text;
        if (value instanceof List<?> list) {
            for (Object entry : list) {
                if (entry instanceof String text && !text.isBlank()) return text;
            }
        }
        return "";
    }

    private static String encodeUrl(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
