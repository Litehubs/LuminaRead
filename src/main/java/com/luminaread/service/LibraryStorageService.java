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

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.ObjectInputFilter;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    private final SettingsJsonRepository settingsJsonRepository = new SettingsJsonRepository();
    private PersistentState state;

    private final Map<String, BookContent> parsedBookCache = new LinkedHashMap<>(10, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, BookContent> eldest) {
            return size() > 8;
        }
    };

    public LibraryStorageService() {
        this(Path.of(System.getProperty("user.home")));
    }

    LibraryStorageService(Path home) {
        this.dataDir = home.resolve(".luminaread");
        this.booksDir = home.resolve("LuminaRead_Books");
        this.stateFile = dataDir.resolve("library_state.bin");
        initDirectories();
        loadState();
    }

    private void initDirectories() {
        try {
            Files.createDirectories(dataDir);
            Files.createDirectories(booksDir);
        } catch (IOException ex) {
            throw new UncheckedIOException("Не удалось создать папки библиотеки", ex);
        }
    }

    public synchronized void loadState() {
        if (Files.exists(stateFile)) {
            try (ObjectInputStream ois = new ObjectInputStream(
                    new BufferedInputStream(new FileInputStream(stateFile.toFile()), 32768))) {
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
                    return;
                }
                throw new IOException("Неизвестный формат библиотеки");
            } catch (IOException | ClassNotFoundException | RuntimeException ex) {
                throw new IllegalStateException("Не удалось прочитать библиотеку: " + stateFile
                        + ". Файл сохранён без изменений.", ex);
            }
        }
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

    public synchronized void saveState() {
        try {
            state.settingsEmbeddedAuthoritative = true;
            AtomicFile.write(stateFile, output -> {
                ObjectOutputStream oos = new ObjectOutputStream(new BufferedOutputStream(output, 32768));
                oos.writeObject(state);
                oos.flush();
            });
            Path jsonSettingsFile = dataDir.resolve("settings.json");
            settingsJsonRepository.save(this.state.settings.getDisplayConfig(),
                    this.state.settings.getOpdsConfig(), jsonSettingsFile);
        } catch (IOException ex) {
            throw new UncheckedIOException("Не удалось сохранить библиотеку: " + stateFile, ex);
        }
    }

    public Path getBooksDir() {
        return booksDir;
    }

    public ReaderSettings getSettings() {
        return state.settings;
    }

    public List<Book> getAllBooks() {
        synchronized (this) {
            return new ArrayList<>(state.books);
        }
    }

    public List<Book> getFilteredBooks(String shelfFilter, String searchQuery, String sortMode) {
        List<Book> booksSnapshot;
        synchronized (this) {
            booksSnapshot = new ArrayList<>(state.books);
        }
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

        File targetFile = sourceFile;
        boolean copiedForImport = false;
        if (copyToLibraryFolder && !sourceFile.toPath().startsWith(booksDir)) {
            ImportDestination destination = reserveImportDestination(sourceFile.toPath());
            Path dest = destination.path();
            if (destination.reserved()) {
                Path temp = null;
                try {
                    temp = Files.createTempFile(booksDir, ".luminaread-import-", ".tmp");
                    Files.copy(sourceFile.toPath(), temp, StandardCopyOption.REPLACE_EXISTING);
                    try {
                        Files.move(temp, dest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    } catch (AtomicMoveNotSupportedException ignored) {
                        Files.move(temp, dest, StandardCopyOption.REPLACE_EXISTING);
                    }
                    copiedForImport = true;
                } catch (Exception ex) {
                    deleteAfterFailure(dest, ex);
                    if (temp != null) deleteAfterFailure(temp, ex);
                    throw ex;
                }
            }
            targetFile = dest.toFile();
        }

        String format;
        BookContent parsed;
        try {
            format = BookParserFactory.detectFormatLabel(targetFile);
            parsed = BookParserFactory.parseBook(targetFile);
        } catch (Exception ex) {
            if (copiedForImport) deleteAfterFailure(targetFile.toPath(), ex);
            throw ex;
        }

        String absPath = targetFile.getAbsolutePath();
        synchronized (this) {
            for (Book existing : state.books) {
                if (existing.getFilePath().equals(absPath)) {
                    ensureNonEmptyChapters(existing, targetFile, parsed);
                    existing.setFormat(format);
                    existing.setTotalWords(parsed.getTotalWords());
                    if (parsed.getCoverImage() != null) {
                        existing.setCoverImage(parsed.getCoverImage());
                    }
                    parsedBookCache.put(existing.getId(), parsed);
                    saveState();
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

            state.books.add(0, book);
            parsedBookCache.put(book.getId(), parsed);
            saveState();
            return book;
        }
    }

    private ImportDestination reserveImportDestination(Path source) throws Exception {
        String fileName = source.getFileName().toString();
        String suffix = fileName.toLowerCase(Locale.ROOT).endsWith(".fb2.zip") ? ".fb2.zip"
                : fileName.substring(Math.max(0, fileName.lastIndexOf('.')));
        String base = fileName.substring(0, fileName.length() - suffix.length());
        for (int index = 2; ; index++) {
            Path candidate = booksDir.resolve(index == 2 ? fileName : base + " (" + index + ")" + suffix);
            if (Files.exists(candidate)) {
                if (Files.isRegularFile(candidate) && Files.size(candidate) == Files.size(source)
                        && Files.mismatch(candidate, source) == -1) return new ImportDestination(candidate, false);
                continue;
            }
            synchronized (this) {
                if (!Files.exists(candidate)) {
                    try {
                        Files.createFile(candidate);
                        return new ImportDestination(candidate, true);
                    } catch (java.nio.file.FileAlreadyExistsException ignored) {
                        // Another import reserved this name; try the next suffix.
                    }
                }
            }
        }
    }

    private record ImportDestination(Path path, boolean reserved) {}

    private static void deleteAfterFailure(Path file, Exception failure) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }

    public BookContent loadBookContent(Book book) throws Exception {
        synchronized (this) {
            BookContent cached = parsedBookCache.get(book.getId());
            if (cached != null && !cached.getChapters().isEmpty() && book.getTotalWords() > 0) {
                return cached;
            }
        }
        File file = new File(book.getFilePath());
        if (!file.exists()) {
            throw new IllegalStateException("Файл книги не найден по пути: " + book.getFilePath());
        }
        String realFormat = BookParserFactory.detectFormatLabel(file);
        BookContent parsed = BookParserFactory.parseBook(file);
        ensureNonEmptyChapters(book, file, parsed);

        synchronized (this) {
            book.setFormat(realFormat);
            book.setTotalWords(parsed.getTotalWords());
            if (book.getCoverImage() == null && parsed.getCoverImage() != null) {
                book.setCoverImage(parsed.getCoverImage());
            }
            parsedBookCache.put(book.getId(), parsed);
            saveState();
        }
        return parsed;
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
        state.books.removeIf(b -> b.getId().equals(book.getId()));
        state.bookmarks.removeIf(bm -> bm.getBookId().equals(book.getId()));
        state.highlights.removeIf(hl -> hl.getBookId().equals(book.getId()));
        parsedBookCache.remove(book.getId());
        // Persist the library change before touching the user's original file.
        saveState();
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
            state.bookmarks.add(0, bookmark);
            saveState();
        }
    }

    public synchronized void removeBookmark(String bookmarkId) {
        state.bookmarks.removeIf(b -> b.getId().equals(bookmarkId));
        saveState();
    }

    public synchronized List<Bookmark> getBookmarks(String bookIdFilter) {
        if (bookIdFilter == null || bookIdFilter.isBlank()) {
            return new ArrayList<>(state.bookmarks);
        }
        return state.bookmarks.stream()
                .filter(b -> bookIdFilter.equals(b.getBookId()))
                .collect(Collectors.toList());
    }

    public synchronized void addHighlight(Highlight highlight) {
        if (highlight != null) {
            state.highlights.add(0, highlight);
            saveState();
        }
    }

    public synchronized void removeHighlight(String highlightId) {
        state.highlights.removeIf(h -> h.getId().equals(highlightId));
        saveState();
    }

    public synchronized List<Highlight> getHighlights(String bookIdFilter) {
        if (bookIdFilter == null || bookIdFilter.isBlank()) {
            return new ArrayList<>(state.highlights);
        }
        return state.highlights.stream()
                .filter(h -> bookIdFilter.equals(h.getBookId()))
                .collect(Collectors.toList());
    }

    public void exportNotesAndHighlightsToMarkdown(File targetFile) throws Exception {
        List<Highlight> highlights;
        List<Bookmark> bookmarks;
        synchronized (this) {
            highlights = List.copyOf(state.highlights);
            bookmarks = List.copyOf(state.bookmarks);
        }
        StringBuilder md = new StringBuilder();
        md.append("# Экспорт цитат, заметок и закладок — LuminaRead\n\n");

        md.append("## Выделенные цитаты и заметки (").append(highlights.size()).append(")\n\n");
        if (highlights.isEmpty()) {
            md.append("_Нет сохраненных цитат._\n\n");
        } else {
            for (Highlight h : highlights) {
                md.append("### ").append(h.getBookTitle()).append(" — ").append(h.getChapterTitle()).append("\n");
                md.append("> ").append(h.getSelectedText().replace("\n", "\n> ")).append("\n\n");
                if (!h.getNote().isBlank()) {
                    md.append("**Заметка:** ").append(h.getNote()).append("\n\n");
                }
                md.append("*Дата: ").append(h.getFormattedDate()).append("*\n\n");
            }
        }

        md.append("## Закладки (").append(bookmarks.size()).append(")\n\n");
        if (bookmarks.isEmpty()) {
            md.append("_Нет сохраненных закладок._\n");
        } else {
            for (Bookmark bm : bookmarks) {
                md.append("* **").append(bm.getBookTitle()).append("** (")
                  .append(bm.getChapterTitle()).append(", стр. ").append(bm.getPageIndex() + 1)
                  .append(") — ").append(bm.getLabel())
                  .append(" _(").append(bm.getFormattedDate()).append(")_\n");
            }
        }
        byte[] contents = md.toString().getBytes(StandardCharsets.UTF_8);
        AtomicFile.write(targetFile.toPath(), output -> output.write(contents));
    }
}
