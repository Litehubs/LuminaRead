package com.luminaread.parser;

import com.luminaread.model.BookContent;

import java.io.File;

/**
 * Backward-compatible facade for detecting formats and parsing books.
 * Delegates all detection and parsing logic to {@link BookParserRegistry}.
 */
public final class BookParserFactory {

    private BookParserFactory() {}

    /**
     * Inspects magic headers, zip entries, and file extensions to determine the format label.
     * Delegates to {@link BookParserRegistry#detectFormatLabel(File)}.
     *
     * @param file the book file to check
     * @return format label string (e.g. "PDF", "EPUB", "FB2", "FB2.ZIP", "CBZ", "HTML", "MD", "RTF", "TXT")
     */
    public static String detectFormatLabel(File file) {
        return BookParserRegistry.getInstance().detectFormatLabel(file);
    }

    /**
     * Parses the book file using the matching parser in {@link BookParserRegistry}.
     * Delegates to {@link BookParserRegistry#parseBook(File)}.
     *
     * @param file the book file to parse
     * @return structured BookContent
     * @throws Exception if parsing fails
     */
    public static BookContent parseBook(File file) throws Exception {
        return BookParserRegistry.getInstance().parseBook(file);
    }
}
