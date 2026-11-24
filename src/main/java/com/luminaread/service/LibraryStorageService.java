package com.luminaread.service;

import com.luminaread.config.OpdsCatalogConfig;
import com.luminaread.config.ReaderDisplayConfig;
import com.luminaread.config.SettingsJsonRepository;
import com.luminaread.model.Book;
import com.luminaread.model.BookContent;
import com.luminaread.model.Bookmark;
import com.luminaread.model.Chapter;
import com.luminaread.model.Highlight;
import com.luminaread.model.ReaderSettings;
import com.luminaread.parser.BookParserFactory;
import com.luminaread.util.AtomicFile;
import com.luminaread.util.HtmlSanitizer;
import com.luminaread.util.WeightedCache;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.ObjectInputFilter;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.NoSuchFileException;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

public class LibraryStorageService {
    private static final long MAX_STATE_BYTES = 256L * 1024 * 1024;
    private static final long MAX_COVER_BYTES = 64L * 1024 * 1024;
    private static final int MAX_STATE_ENTRIES = 1_000_000;
    private static final Set<Class<?>> STATE_CLASSES = Set.of(
            PersistentState.class, Book.class, Bookmark.class, Highlight.class, ReaderSettings.class,
            ReaderDisplayConfig.class, OpdsCatalogConfig.class, ReaderDisplayConfig.TextAlignment.class,
            ReaderDisplayConfig.ReadingMode.class, ReaderSettings.TextAlignment.class,
            ReaderSettings.ReadingMode.class, ArrayList.class, String.class, Enum.class
    );

    private static ObjectInputFilter.Status filterState(ObjectInputFilter.FilterInfo input) {
        if (input.depth() > 32 || input.references() > MAX_STATE_ENTRIES || input.streamBytes() > MAX_STATE_BYTES) {
            return ObjectInputFilter.Status.REJECTED;
        }
        Class<?> type = input.serialClass();
        if (type == null) return ObjectInputFilter.Status.UNDECIDED;
        if (type == byte[].class && input.arrayLength() <= MAX_COVER_BYTES
                || type == Object[].class && input.arrayLength() <= MAX_STATE_ENTRIES
                || STATE_CLASSES.contains(type)) {
            return ObjectInputFilter.Status.ALLOWED;
        }
        return ObjectInputFilter.Status.REJECTED;
    }


    private static class PersistentState implements Serializable {
        private static final long serialVersionUID = 1L;
        List<Book> books = new ArrayList<>();
        List<Bookmark> bookmarks = new ArrayList<>();
        List<Highlight> highlights = new ArrayList<>();
        ReaderSettings settings = new ReaderSettings();
        // False for older serialized files, whose settings.json must still be imported.
        boolean settingsEmbeddedAuthoritative;
    }

    private final Path dataDir;
    private final Path booksDir;
    private final Path stateFile;
    private final BookCopies copies;
    private final SettingsJsonRepository settingsJsonRepository = new SettingsJsonRepository();
    private final ProgressStore progress;
    private final Set<String> bookIds = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private PersistentState state;
    private volatile Shelf shelf;
    private final java.util.concurrent.Executor writer = new java.util.concurrent.ThreadPoolExecutor(
            0, 1, 2, java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.LinkedBlockingQueue<>(), task -> {
                Thread thread = new Thread(task, "LuminaRead-Library");
                thread.setDaemon(true);
                return thread;
            });

    private record Shelf(List<Book> books, List<Bookmark> bookmarks, List<Highlight> highlights) {}

    private void publishShelf() {
        shelf = new Shelf(List.copyOf(state.books), List.copyOf(state.bookmarks), List.copyOf(state.highlights));
    }

    public java.util.concurrent.CompletableFuture<Void> writeAsync(Runnable command) {
        return java.util.concurrent.CompletableFuture.runAsync(command, writer);
    }

    public void awaitWrites() throws InterruptedException, java.util.concurrent.ExecutionException {
        writeAsync(() -> {}).get();
    }

    private final WeightedCache<String, ParsedBook> parsedBookCache =
            new WeightedCache<>(8, 64L * 1024 * 1024);

    private record BookFile(Path path, long size, FileTime modified, FileTime created, Object key) {
        static BookFile read(Path path) throws IOException {
            Path absolute = path.toAbsolutePath();
            var attrs = Files.readAttributes(absolute, BasicFileAttributes.class);
            if (!attrs.isRegularFile()) throw new IOException("Путь книги не указывает на файл: " + absolute);
            return new BookFile(absolute, attrs.size(), attrs.lastModifiedTime(), attrs.creationTime(), attrs.fileKey());
        }
    }

    private record ParsedBook(BookFile file, String format, BookContent content) {
        static ParsedBook read(BookFile source) throws Exception {
            File file = source.path().toFile();
            String format = BookParserFactory.detectFormatLabel(file);
            BookContent content = BookParserFactory.parseBook(file);
            return new ParsedBook(source, format, content);
        }

        void checkSource() throws IOException {
            if (!file.equals(BookFile.read(file.path()))) {
                throw new IOException("Файл книги изменился во время чтения. Откройте книгу снова.");
            }
        }
    }

    private void cacheBook(Book book, ParsedBook parsed) {
        parsedBookCache.put(book.getId(), parsed, parsed.content().estimatedBytes());
    }

    public LibraryStorageService() {
        this(Path.of(System.getProperty("user.home")));
    }

    LibraryStorageService(Path home) {
        this.dataDir = home.resolve(".luminaread");
        this.booksDir = home.resolve("LuminaRead_Books");
        this.stateFile = dataDir.resolve("library_state.bin");
        this.progress = new ProgressStore(dataDir.resolve("progress"));
        this.copies = new BookCopies(booksDir);
        initDirectories();
        loadState();
        publishShelf();
    }

    private void initDirectories() {
        try {
            Files.createDirectories(dataDir);
            Files.createDirectories(booksDir);
            copies.recover();
        } catch (IOException ex) {
            throw new UncheckedIOException("Не удалось создать папки библиотеки", ex);
        }
    }

    private void loadState() {
        if (Files.exists(stateFile)) {
            try (var input = new BufferedInputStream(Files.newInputStream(stateFile), 32768);
                 var ois = new ObjectInputStream(input)) {
                if (Files.size(stateFile) > MAX_STATE_BYTES) throw new IOException("Файл библиотеки слишком большой");
                ObjectInputFilter filter = LibraryStorageService::filterState;
                ObjectInputFilter existing = ois.getObjectInputFilter();
                ois.setObjectInputFilter(existing == null ? filter : ObjectInputFilter.merge(filter, existing));
                Object obj = ois.readObject();
                if (obj instanceof PersistentState loaded) {
                    this.state = loaded;
                    if (this.state.settings == null) {
                        this.state.settings = new ReaderSettings();
                    }
                    Path jsonSettingsFile = dataDir.resolve("settings.json");
                    if (!this.state.settingsEmbeddedAuthoritative && Files.exists(jsonSettingsFile)) {
                        loadLegacySettings(jsonSettingsFile);
                    }
                    restoreProgress();
                    return;
                }
                throw new IOException("Неизвестный формат библиотеки");
            } catch (IOException | ClassNotFoundException | RuntimeException ex) {
                throw new IllegalStateException("Не удалось прочитать библиотеку: " + stateFile
                        + ". Файл сохранён без изменений.", ex);
            }
        }
        bookIds.clear();
        this.state = new PersistentState();
        Path jsonSettingsFile = dataDir.resolve("settings.json");
        if (Files.exists(jsonSettingsFile)) {
            loadLegacySettings(jsonSettingsFile);
        }
    }

    private void loadLegacySettings(Path settingsFile) {
        if (!settingsJsonRepository.load(state.settings.getDisplayConfig(), state.settings.getOpdsConfig(), settingsFile)) {
            throw new IllegalStateException("Не удалось прочитать настройки: " + settingsFile
                    + ". Файл сохранён без изменений.");
        }
    }

    private void restoreProgress() {
        bookIds.clear();
        for (Book book : state.books) {
            bookIds.add(book.getId());
            progress.restore(book);
        }
    }

    public void saveProgress(Book book) {
        synchronized (progress) {
            if (bookIds.contains(book.getId())) progress.save(book);
        }
    }

    public boolean hasProgressError() {
        return progress.hasError();
    }

    public void flushProgress() throws InterruptedException {
        progress.flush();
    }

    public synchronized void saveState() {
        try {
            state.settingsEmbeddedAuthoritative = true;
            AtomicFile.write(stateFile, output -> {
                ObjectOutputStream oos = new ObjectOutputStream(new BufferedOutputStream(output, 32768));
                oos.writeObject(state);
                oos.flush();
            });
        } catch (IOException ex) {
            throw new UncheckedIOException("Не удалось сохранить библиотеку: " + stateFile, ex);
        }
        // The library is committed. The JSON copy serves export and legacy readers.
        try {
            settingsJsonRepository.save(state.settings.getDisplayConfig(), state.settings.getOpdsConfig(),
                    dataDir.resolve("settings.json"));
        } catch (UncheckedIOException | SecurityException ex) {
            java.util.logging.Logger.getLogger(LibraryStorageService.class.getName())
                    .log(java.util.logging.Level.WARNING, "Библиотека сохранена; не удалось обновить копию настроек", ex);
        }
    }

    private <T> void changeList(List<T> list, Runnable change) {
        List<T> before = new ArrayList<>(list);
        change.run();
        try {
            saveState();
            publishShelf();
        } catch (UncheckedIOException | SecurityException ex) {
            list.clear();
            list.addAll(before);
            throw ex;
        }
    }

    public Path getBooksDir() {
        return booksDir;
    }

    public ReaderSettings getSettings() {
        return state.settings;
    }

    public List<Book> getAllBooks() {
        return new ArrayList<>(shelf.books());
    }

    public List<Book> getFilteredBooks(String shelfFilter, String searchQuery, String sortMode) {
        List<Book> booksSnapshot = shelf.books();
        String q = searchQuery != null ? searchQuery.trim().toLowerCase(Locale.ROOT) : "";
        List<Book> filtered = booksSnapshot.stream()
                .filter(b -> {
                    if ("READING".equals(shelfFilter)) {
                        return b.getProgressPercent() > 0.1 && !b.isFinished();
                    } else if ("FAVORITES".equals(shelfFilter)) {
                        return b.isFavorite();
                    } else if ("FINISHED".equals(shelfFilter)) {
                        return b.isFinished() || b.getProgressPercent() >= 99.0;
                    }
                    return true;
                })
                .filter(b -> {
                    if (q.isEmpty()) return true;
                    return b.getTitle().toLowerCase(Locale.ROOT).contains(q)
                            || b.getAuthor().toLowerCase(Locale.ROOT).contains(q)
                            || b.getFormat().toLowerCase(Locale.ROOT).contains(q)
                            || b.getSeries().toLowerCase(Locale.ROOT).contains(q);
                })
                .collect(Collectors.toList());

        if ("TITLE".equals(sortMode)) {
            filtered.sort(Comparator.comparing(Book::getTitle, String.CASE_INSENSITIVE_ORDER));
        } else if ("AUTHOR".equals(sortMode)) {
            filtered.sort(Comparator.comparing(Book::getAuthor, String.CASE_INSENSITIVE_ORDER));
        } else if ("PROGRESS".equals(sortMode)) {
            filtered.sort(Comparator.comparingDouble(Book::getProgressPercent).reversed());
        } else {
            filtered.sort((a, b) -> {
                long tA = Math.max(a.getLastOpenedTimestamp(), a.getAddedTimestamp());
                long tB = Math.max(b.getLastOpenedTimestamp(), b.getAddedTimestamp());
                return Long.compare(tB, tA);
            });
        }
        return filtered;
    }

    public Book importBookFile(File sourceFile, boolean copyToLibraryFolder) throws Exception {
        if (sourceFile == null || !sourceFile.isFile() || !sourceFile.canRead()) {
            throw new IllegalArgumentException("Файл не найден: " + sourceFile);
        }
        if (sourceFile.length() == 0) throw new IllegalArgumentException("Файл пустой: " + sourceFile.getName());

        Path source = sourceFile.toPath().toAbsolutePath().normalize();
        // The app owns one profile lock. Serialize imports without holding the shelf/state monitor during I/O.
        synchronized (copies) {
            if (!copyToLibraryFolder || source.startsWith(booksDir)) return indexFile(source.toFile());
            var copy = copies.publish(source);
            try {
                return indexFile(copy.path().toFile());
            } catch (Exception ex) {
                if (copy.created()) {
                    try {
                        Files.deleteIfExists(copy.path());
                    } catch (IOException cleanup) {
                        ex.addSuppressed(cleanup);
                    }
                }
                throw ex;
            }
        }
    }

    private Book indexFile(File targetFile) throws Exception {
        ParsedBook document = ParsedBook.read(BookFile.read(targetFile.toPath()));
        String absPath = targetFile.getAbsolutePath();
        BookContent parsed = document.content();
        String format = document.format();
        synchronized (this) {
            document.checkSource();
            for (Book existing : state.books) {
                if (existing.getFilePath().equals(absPath)) {
                    ensureNonEmptyChapters(existing, targetFile, parsed);
                    updateMetadata(existing, format, parsed, true);
                    cacheBook(existing, document);
                    return existing;
                }
            }

            Book book = new Book(parsed.getTitle(), parsed.getAuthor(), absPath, format);
            ensureNonEmptyChapters(book, targetFile, parsed);
            book.setDescription(parsed.getDescription());
            book.setSeries(parsed.getSeries());
            book.setLanguage(parsed.getLanguage());
            book.setCoverImage(parsed.getCoverImage());
            book.setTotalWords(parsed.getTotalWords());

            changeList(state.books, () -> state.books.add(0, book));
            bookIds.add(book.getId());
            cacheBook(book, document);
            return book;
        }
    }

    public BookContent loadBookContent(Book book) throws Exception {
        BookFile file;
        try {
            file = BookFile.read(Path.of(book.getFilePath()));
        } catch (NoSuchFileException ex) {
            parsedBookCache.remove(book.getId());
            throw new IOException("Файл книги не найден по пути: " + book.getFilePath(), ex);
        }
        ParsedBook cached = parsedBookCache.get(book.getId());
        if (cached != null && cached.file().equals(file) && book.getTotalWords() > 0) return cached.content();

        ParsedBook parsed = ParsedBook.read(file);
        ensureNonEmptyChapters(book, file.path().toFile(), parsed.content());

        synchronized (this) {
            parsed.checkSource();
            if (bookIds.contains(book.getId())) {
                updateMetadata(book, parsed.format(), parsed.content(), false);
                cacheBook(book, parsed);
            }
        }
        return parsed.content();
    }

    private void updateMetadata(Book book, String format, BookContent parsed, boolean replaceCover) {
        String oldFormat = book.getFormat();
        int oldWords = book.getTotalWords();
        byte[] oldCover = book.getCoverImage();
        byte[] cover = replaceCover || oldCover == null ? parsed.getCoverImage() : oldCover;
        int words = parsed.getTotalWords();
        if (format.equals(oldFormat) && words == oldWords && java.util.Arrays.equals(cover, oldCover)) return;
        book.setFormat(format);
        book.setTotalWords(words);
        book.setCoverImage(cover);
        try {
            saveState();
        } catch (UncheckedIOException | SecurityException ex) {
            book.setFormat(oldFormat);
            book.setTotalWords(oldWords);
            book.setCoverImage(oldCover);
            throw ex;
        }
    }

    private void ensureNonEmptyChapters(Book book, File file, BookContent parsed) {
        if (!parsed.getChapters().isEmpty()) {
            return;
        }
        long len = file != null && file.exists() ? file.length() : 0L;
        String title = (book != null && book.getTitle() != null) ? book.getTitle() : "Книга";
        String infoHtml = "<h2>" + HtmlSanitizer.escapeHtml(title) + "</h2>\n"
                + "<p>К сожалению, внутри скачанного файла (<code>"
                + (file != null ? HtmlSanitizer.escapeHtml(file.getName()) : "") + "</code>, размер: " + len + " байт) не обнаружено открытого текстового слоя или глав.</p>\n"
                + "<p><b>Почему это могло произойти:</b></p>\n"
                + "<p>1) В каталоге Open Library / Internet Archive эта современная книга защищена авторским правом (режим <i>Borrow / Controlled Digital Lending</i>) и недоступна для свободного скачивания без аккаунта;</p>\n"
                + "<p>2) Или онлайн-зеркало вернуло пустой ответ вместо архива EPUB/FB2.</p>\n"
                + "<p><b>Что делать:</b> попробуйте выбрать другой источник в разделе <b>«Скачать книги»</b> (например, <i>Flibusta OPDS</i> или <i>Project Gutenberg</i>), скачать другой формат (PDF / FB2 / TXT) или открыть локальный файл книги с диска.</p>";
        String plain = "Внутри скачанного файла не обнаружено открытого текстового слоя. Попробуйте скачать другое издание или формат.";
        parsed.addChapter(new Chapter(0, "Информация о файле", infoHtml, plain));
    }

    public synchronized void removeBook(Book book, boolean deleteFileFromDisk) {
        if (book == null) return;
        Book stored = state.books.stream().filter(b -> b.getId().equals(book.getId())).findFirst().orElse(null);
        if (stored == null) return;
        var bookmarks = new ArrayList<>(state.bookmarks);
        var highlights = new ArrayList<>(state.highlights);
        state.bookmarks.removeIf(bm -> bm.getBookId().equals(book.getId()));
        state.highlights.removeIf(hl -> hl.getBookId().equals(book.getId()));
        try {
            changeList(state.books, () -> state.books.remove(stored));
        } catch (UncheckedIOException | SecurityException ex) {
            state.bookmarks = bookmarks;
            state.highlights = highlights;
            throw ex;
        }
        synchronized (progress) {
            bookIds.remove(book.getId());
            progress.remove(book.getId());
        }
        parsedBookCache.remove(book.getId());
        if (deleteFileFromDisk) {
            try {
                Files.deleteIfExists(Path.of(stored.getFilePath()));
            } catch (IOException ex) {
                throw new UncheckedIOException("Книга удалена из библиотеки, но файл не удалось удалить: " + stored.getFilePath(), ex);
            }
        }
    }

    public synchronized void addBookmark(Bookmark bookmark) {
        if (bookmark != null) {
            changeList(state.bookmarks, () -> state.bookmarks.add(0, bookmark));
        }
    }

    public synchronized void migrateBookmark(String id, int offset) {
        for (Bookmark bookmark : state.bookmarks) {
            if (!bookmark.getId().equals(id) || bookmark.getTextOffset() >= 0) continue;
            bookmark.setTextOffset(offset);
            try {
                saveState();
            } catch (UncheckedIOException | SecurityException ex) {
                bookmark.setTextOffset(-1);
                throw ex;
            }
            return;
        }
    }

    public synchronized void toggleFavorite(String bookId) {
        for (Book book : state.books) {
            if (!book.getId().equals(bookId)) continue;
            boolean previous = book.isFavorite();
            book.setFavorite(!previous);
            try {
                saveState();
            } catch (UncheckedIOException | SecurityException ex) {
                book.setFavorite(previous);
                throw ex;
            }
            return;
        }
    }

    public synchronized void removeBookmark(String bookmarkId) {
        changeList(state.bookmarks, () -> state.bookmarks.removeIf(b -> b.getId().equals(bookmarkId)));
    }

    public List<Bookmark> getBookmarks(String bookIdFilter) {
        if (bookIdFilter == null || bookIdFilter.isBlank()) {
            return new ArrayList<>(shelf.bookmarks());
        }
        return shelf.bookmarks().stream()
                .filter(b -> bookIdFilter.equals(b.getBookId()))
                .collect(Collectors.toList());
    }

    public synchronized void addHighlight(Highlight highlight) {
        if (highlight != null) {
            changeList(state.highlights, () -> state.highlights.add(0, highlight));
        }
    }

    public synchronized void removeHighlight(String highlightId) {
        changeList(state.highlights, () -> state.highlights.removeIf(h -> h.getId().equals(highlightId)));
    }

    public List<Highlight> getHighlights(String bookIdFilter) {
        if (bookIdFilter == null || bookIdFilter.isBlank()) {
            return new ArrayList<>(shelf.highlights());
        }
        return shelf.highlights().stream()
                .filter(h -> bookIdFilter.equals(h.getBookId()))
                .collect(Collectors.toList());
    }

    public void exportNotesAndHighlightsToMarkdown(File targetFile) throws Exception {
        Shelf snapshot = shelf;
        byte[] contents = NotesMarkdown.render(snapshot.highlights(), snapshot.bookmarks())
                .getBytes(StandardCharsets.UTF_8);
        AtomicFile.write(targetFile.toPath(), output -> output.write(contents));
    }
}
