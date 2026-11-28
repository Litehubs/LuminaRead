package com.luminaread.parser;

import com.luminaread.model.BookContent;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/** Selects a registered parser for the detected book format. */
public class BookParserRegistry {

    private static final BookParserRegistry INSTANCE = new BookParserRegistry();

    private final List<BookParser> parsers = new CopyOnWriteArrayList<>();
    private final BookParser textParser = new TxtMarkdownParser();
    private volatile BookParser fallbackParser = textParser;

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
        parsers.add(new Fb2Parser());
        parsers.add(new EpubParser());
        parsers.add(new PdfParser());
        parsers.add(new CbzParser());
        parsers.add(textParser);
    }

    /**
     * Registers a new {@link BookParser}.
     * Specialized parsers are inserted ahead of the fallback plain-text parser so they take precedence.
     *
     * @param parser the parser instance to register
     */
    public synchronized void registerParser(BookParser parser) {
        Objects.requireNonNull(parser, "parser must not be null");
        int index = parsers.indexOf(textParser);
        parsers.add(index < 0 ? parsers.size() : index, parser);
    }

    /**
     * Registers a new {@link BookParser} at the specified priority index.
     *
     * @param index insertion index
     * @param parser the parser instance to register
     */
    public synchronized void registerParser(int index, BookParser parser) {
        Objects.requireNonNull(parser, "parser must not be null");
        parsers.add(index, parser);
    }

    /**
     * Unregisters a previously registered {@link BookParser}.
     *
     * @param parser the parser instance to remove
     * @return true if removed, false if not found
     */
    public synchronized boolean unregisterParser(BookParser parser) {
        return parsers.remove(parser);
    }

    /**
     * Returns an unmodifiable view of all registered parsers in evaluation order.
     */
    public List<BookParser> getRegisteredParsers() {
        return Collections.unmodifiableList(parsers);
    }

    /**
     * Returns the fallback parser used when specialized parsing produces empty chapters.
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
        // A detected signature is more reliable than a filename supplied by a server.
        if (formatHint != null) {
            for (BookParser parser : parsers) {
                if (parser.supports(null, formatHint)) return Optional.of(parser);
            }
        }
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
        if (file != null) {
            String format = BookFormat.detect(file);
            if (format != null) return format;
            for (BookParser parser : parsers) {
                if (parser.supports(file, null)) return parser.getSupportedFormat();
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
        return read(file).content();
    }

    public record Parsed(String format, BookContent content) {}

    public Parsed read(File file) throws Exception {
        String format = detectFormatLabel(file);
        BookParser fallback = fallbackParser;
        BookParser parser = findParser(file, format).orElse(fallback);
        if (parser == null) throw new IllegalArgumentException("Нет парсера для формата " + format);

        BookContent content = parser.parse(file);
        if (content.getChapters().isEmpty() && fallback != null && parser != fallback) {
            BookContent alternative = fallback.parse(file);
            if (!alternative.getChapters().isEmpty()) content = alternative;
        }
        return new Parsed(format, content);
    }
}
