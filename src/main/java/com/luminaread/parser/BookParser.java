package com.luminaread.parser;

import com.luminaread.model.BookContent;

import java.io.File;
import java.util.Locale;

/**
 * Strategy interface for parsing digital book file formats into structured {@link BookContent}.
 */
public interface BookParser {

    /**
     * Parse the given file into structured {@link BookContent}.
     *
     * @param file the book file to parse
     * @return parsed book content including metadata and chapters
     * @throws Exception if parsing fails
     */
    BookContent parse(File file) throws Exception;

    /**
     * Determines whether this parser supports parsing the given file and/or format hint.
     *
     * @param file the file to inspect (may be null if querying by formatHint)
     * @param formatHint format label hint, e.g. "FB2", "EPUB", "PDF" (may be null if querying by file)
     * @return true if this parser can handle the file/hint, false otherwise
     */
    default boolean supports(File file, String formatHint) {
        String supported = getSupportedFormat();
        if (supported != null && formatHint != null && supported.equalsIgnoreCase(formatHint)) {
            return true;
        }
        if (supported != null && file != null) {
            String name = file.getName().toLowerCase(Locale.ROOT);
            return name.endsWith("." + supported.toLowerCase(Locale.ROOT));
        }
        return false;
    }

    /**
     * Returns the primary format label supported by this parser (e.g. "FB2", "EPUB", "PDF", "CBZ", "TXT").
     *
     * @return format label string
     */
    default String getSupportedFormat() {
        return "UNKNOWN";
    }
}
