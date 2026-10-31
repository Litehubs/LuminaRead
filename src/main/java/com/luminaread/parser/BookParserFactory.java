package com.luminaread.parser;

import com.luminaread.model.BookContent;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class BookParserFactory {
    private static final Fb2Parser FB2_PARSER = new Fb2Parser();
    private static final EpubParser EPUB_PARSER = new EpubParser();
    private static final TxtMarkdownParser TXT_PARSER = new TxtMarkdownParser();
    private static final CbzParser CBZ_PARSER = new CbzParser();
    private static final PdfParser PDF_PARSER = new PdfParser();

    private BookParserFactory() {}

    public static String detectFormatLabel(File file) {
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
        return "TXT";
    }

    public static BookContent parseBook(File file) throws Exception {
        String format = detectFormatLabel(file);
        BookContent content = switch (format) {
            case "FB2", "FB2.ZIP" -> FB2_PARSER.parse(file);
            case "EPUB" -> EPUB_PARSER.parse(file);
            case "PDF" -> PDF_PARSER.parse(file);
            case "CBZ" -> CBZ_PARSER.parse(file);
            default -> TXT_PARSER.parse(file);
        };

        if (content.getChapters().isEmpty() && !"TXT".equals(format)) {
            BookContent fallback = TXT_PARSER.parse(file);
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
        String name = file.getName().toLowerCase(Locale.ROOT);
        if (name.endsWith(".epub")) return "EPUB";
        if (name.endsWith(".cbz")) return "CBZ";
        return "FB2.ZIP";
    }
}
