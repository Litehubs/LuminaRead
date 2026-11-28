package com.luminaread.parser;

import com.luminaread.util.CharsetDetector;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

/** Recognizes built-in formats from file contents and conventional filename suffixes. */
final class BookFormat {
    private static final Logger LOG = Logger.getLogger(BookFormat.class.getName());
    private static final int HEADER_BYTES = 512;
    private static final Pattern PROLOG = Pattern.compile("(?s)\\s+|<\\?.*?\\?>|<!--.*?-->");
    private static final Pattern ROOT = Pattern.compile("(?i)<(fictionbook|html|!doctype\\s+html)(?=[\\s/>])");

    private BookFormat() {}

    static String detect(File file) {
        String suffix = fromName(file);
        if (!file.isFile()) return suffix;
        try {
            byte[] header;
            try (var input = Files.newInputStream(file.toPath())) {
                header = input.readNBytes(HEADER_BYTES);
            }
            if (header.length >= 4) {
                if (header[0] == '%' && header[1] == 'P' && header[2] == 'D' && header[3] == 'F') return "PDF";
                if (zipHeader(header)) return archiveFormat(file, suffix);
                return textFormat(header, suffix);
            }
        } catch (IOException ex) {
            LOG.log(Level.FINE, "Не удалось определить формат по содержимому файла", ex);
        }
        return suffix;
    }

    static String textFormat(byte[] bytes, String suffix) {
        byte[] header = bytes.length <= HEADER_BYTES ? bytes : Arrays.copyOf(bytes, HEADER_BYTES);
        String text = CharsetDetector.decodeBytes(header);
        if (text.startsWith("{\\rtf1")) return "RTF";
        var prolog = PROLOG.matcher(text);
        int offset = 0;
        while (prolog.region(offset, text.length()).lookingAt()) offset = prolog.end();
        var root = ROOT.matcher(text).region(offset, text.length());
        if (root.lookingAt()) return root.group(1).equalsIgnoreCase("fictionbook") ? "FB2" : "HTML";
        return suffix;
    }

    static boolean zipHeader(byte[] bytes) {
        return bytes.length >= 4 && bytes[0] == 'P' && bytes[1] == 'K'
                && ((bytes[2] == 3 && bytes[3] == 4) || (bytes[2] == 5 && bytes[3] == 6)
                || (bytes[2] == 7 && bytes[3] == 8));
    }

    static String fromName(File file) {
        String name = file.getName().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        if (dot < 0) return null;
        return switch (name.substring(dot + 1)) {
            case "zip", "fbz" -> "FB2.ZIP";
            case "fb2" -> "FB2";
            case "epub" -> "EPUB";
            case "pdf" -> "PDF";
            case "cbz" -> "CBZ";
            case "md", "markdown" -> "MD";
            case "htm", "html" -> "HTML";
            case "rtf" -> "RTF";
            default -> null;
        };
    }

    private static String archiveFormat(File file, String suffix) throws IOException {
        boolean fb2 = false;
        boolean xhtml = false;
        boolean images = false;
        try (var archive = new ZipFile(file)) {
            var entries = archive.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                String name = entry.getName().toLowerCase(Locale.ROOT);
                if (name.equals("meta-inf/container.xml") || name.endsWith(".opf")) return "EPUB";
                fb2 |= name.endsWith(".fb2");
                xhtml |= name.endsWith(".xhtml");
                images |= CbzParser.isPageImage(name);
            }
        }
        if (fb2) return "FB2.ZIP";
        if (xhtml) return "EPUB";
        if (images) return "CBZ";
        return "EPUB".equals(suffix) || "CBZ".equals(suffix) ? suffix : "FB2.ZIP";
    }
}
