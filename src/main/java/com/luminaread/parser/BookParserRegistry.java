package com.luminaread.parser;

import com.luminaread.model.BookContent;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Extensible registry and dispatcher of {@link BookParser} instances.
 * Adheres to the Open/Closed Principle (OCP): new format parsers can be registered
 * dynamically at runtime without modifying existing parser or factory classes.
 */
public class BookParserRegistry {

    private static final BookParserRegistry INSTANCE = new BookParserRegistry();

    private final List<BookParser> parsers = new CopyOnWriteArrayList<>();
    private volatile BookParser fallbackParser;

    public BookParserRegistry() {
        initDefaultParsers();
    }

    /**
     * Returns the global default singleton registry instance.
     */
    public static BookParserRegistry getInstance() {
        return INSTANCE;
    }

    private void initDefaultParsers() {
        TxtMarkdownParser txtParser = new TxtMarkdownParser();
        parsers.add(new Fb2Parser());
        parsers.add(new EpubParser());
        parsers.add(new PdfParser());
        parsers.add(new CbzParser());
        parsers.add(txtParser);
        this.fallbackParser = txtParser;
    }

    /**
     * Registers a new {@link BookParser}.
     * Specialized parsers are inserted ahead of the fallback plain-text parser so they take precedence.
     *
     * @param parser the parser instance to register
     */
    public void registerParser(BookParser parser) {
        Objects.requireNonNull(parser, "parser must not be null");
        int insertIdx = 0;
        for (int i = 0; i < parsers.size(); i++) {
            if (parsers.get(i) instanceof TxtMarkdownParser) {
                insertIdx = i;
                break;
            }
            insertIdx = i + 1;
        }
        parsers.add(insertIdx, parser);
    }

    /**
     * Registers a new {@link BookParser} at the specified priority index.
     *
     * @param index insertion index
     * @param parser the parser instance to register
     */
    public void registerParser(int index, BookParser parser) {
        Objects.requireNonNull(parser, "parser must not be null");
        parsers.add(index, parser);
    }

    /**
     * Unregisters a previously registered {@link BookParser}.
     *
     * @param parser the parser instance to remove
     * @return true if removed, false if not found
     */
    public boolean unregisterParser(BookParser parser) {
        return parsers.remove(parser);
    }

    /**
     * Returns an unmodifiable view of all registered parsers in evaluation order.
     */
    public List<BookParser> getRegisteredParsers() {
        return Collections.unmodifiableList(parsers);
    }

    /**
     * Returns the fallback parser used when specialized parsing produces empty chapters or fails.
     */
    public BookParser getFallbackParser() {
        return fallbackParser;
    }

    /**
     * Configures the fallback parser.
     */
    public void setFallbackParser(BookParser fallbackParser) {
        this.fallbackParser = fallbackParser;
    }

    /**
     * Finds the first registered parser that supports the specified file and/or format hint.
     *
     * @param file the book file to check (may be null)
     * @param formatHint detected format hint (may be null)
     * @return matching BookParser if found
     */
    public Optional<BookParser> findParser(File file, String formatHint) {
        for (BookParser parser : parsers) {
            if (parser.supports(file, formatHint)) {
                return Optional.of(parser);
            }
        }
        if (formatHint != null) {
            for (BookParser parser : parsers) {
                if (formatHint.equalsIgnoreCase(parser.getSupportedFormat())) {
                    return Optional.of(parser);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Inspects magic headers, zip entries, and file extensions to determine the format label.
     *
     * @param file the file to inspect
     * @return format label string (e.g. "PDF", "EPUB", "FB2", "FB2.ZIP", "CBZ", "HTML", "MD", "RTF", "TXT")
     */
    public String detectFormatLabel(File file) {
        if (file != null && file.exists() && file.isFile() && file.length() >= 4) {
            byte[] header = new byte[512];
            int read = 0;
            try (InputStream is = new FileInputStream(file)) {
                read = is.read(header);
            } catch (Exception ignored) {
            }

            if (read >= 4) {
                if (header[0] == '%' && header[1] == 'P' && header[2] == 'D' && header[3] == 'F') {
                    return "PDF";
                }

                if (header[0] == 'P' && header[1] == 'K') {
                    return inspectZipType(file);
                }

                String headStr = new String(header, 0, read, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
                if (headStr.contains("<fictionbook")) {
                    return "FB2";
                }
                if (headStr.contains("<!doctype html") || headStr.contains("<html")) {
                    return "HTML";
                }
            }
        }

        String name = file != null ? file.getName().toLowerCase(Locale.ROOT) : "";
        if (name.endsWith(".fb2.zip") || name.endsWith(".fbz")) return "FB2.ZIP";
        if (name.endsWith(".fb2")) return "FB2";
        if (name.endsWith(".epub")) return "EPUB";
        if (name.endsWith(".pdf")) return "PDF";
        if (name.endsWith(".cbz")) return "CBZ";
        if (name.endsWith(".md") || name.endsWith(".markdown")) return "MD";
        if (name.endsWith(".html") || name.endsWith(".htm")) return "HTML";
        if (name.endsWith(".rtf")) return "RTF";
        if (name.endsWith(".zip")) {
            return inspectZipType(file);
        }

        // Query registered custom parsers to see if any matches this file
        if (file != null) {
            for (BookParser parser : parsers) {
                if (!(parser instanceof TxtMarkdownParser) && parser.supports(file, null)) {
                    return parser.getSupportedFormat();
                }
            }
        }

        return "TXT";
    }

    /**
     * Parses the book file by detecting its format, selecting the matching registered parser,
     * and applying the fallback parser if needed.
     *
     * @param file the book file to parse
     * @return structured BookContent
     * @throws Exception if parsing fails
     */
    public BookContent parseBook(File file) throws Exception {
        String format = detectFormatLabel(file);
        BookParser parser = findParser(file, format).orElse(fallbackParser);
        if (parser == null) {
            throw new IllegalArgumentException("No suitable parser found for: " + (file != null ? file.getName() : "null"));
        }

        BookContent content = parser.parse(file);

        if (content.getChapters().isEmpty() && !(parser instanceof TxtMarkdownParser) && fallbackParser != null) {
            BookContent fallback = fallbackParser.parse(file);
            if (!fallback.getChapters().isEmpty()) {
                return fallback;
            }
        }
        return content;
    }

    private static String inspectZipType(File file) {
        try (ZipFile zf = new ZipFile(file)) {
            boolean hasImages = false;
            var entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String entryName = entry.getName().toLowerCase(Locale.ROOT);
                if (entryName.endsWith(".opf") || entryName.equals("meta-inf/container.xml")
                        || entryName.endsWith(".xhtml")) {
                    return "EPUB";
                }
                if (entryName.endsWith(".fb2")) {
                    return "FB2.ZIP";
                }
                if (entryName.endsWith(".jpg") || entryName.endsWith(".jpeg") || entryName.endsWith(".png")) {
                    hasImages = true;
                }
            }
            if (hasImages) return "CBZ";
        } catch (Exception ignored) {
        }
        String name = file != null ? file.getName().toLowerCase(Locale.ROOT) : "";
        if (name.endsWith(".epub")) return "EPUB";
        if (name.endsWith(".cbz")) return "CBZ";
        return "FB2.ZIP";
    }
}
