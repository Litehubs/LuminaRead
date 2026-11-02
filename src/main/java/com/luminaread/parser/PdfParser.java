package com.luminaread.parser;

import com.luminaread.model.BookContent;
import com.luminaread.model.Chapter;
import com.luminaread.util.HtmlSanitizer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PdfParser implements BookParser {
    private static final Pattern PAGE_MARKER = Pattern.compile("===LUMINA_PAGE_(\\d+)===");

    private static final int PAGES_PER_CHAPTER = 1;
    private static final long MAX_EXTRACTED_TEXT_BYTES = 128L * 1024 * 1024;

    @Override
    public boolean supports(File file, String formatHint) {
        if (formatHint != null && "PDF".equalsIgnoreCase(formatHint)) {
            return true;
        }
        if (file != null && file.getName().toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            return true;
        }
        return false;
    }

    @Override
    public String getSupportedFormat() {
        return "PDF";
    }

    @Override
    public BookContent parse(File file) throws Exception {
        if (file == null || !file.isFile() || !file.canRead()) {
            throw new IllegalArgumentException("PDF-файл не найден или недоступен для чтения.");
        }
        if (file.length() == 0) {
            throw new IllegalArgumentException("PDF-файл пустой.");
        }

        BookContent content = new BookContent();
        content.setTitle(stripExtension(file.getName()));
        content.setAuthor("PDF-документ");

        boolean extracted = false;
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")) {
            extracted = extractWithPdfKit(file, content);
        }
        if (!extracted) {
            String text = extractWithPdfToText(file);
            if (text != null && !text.isBlank()) {
                addTextPages(text.split("\\f", -1), content);
                extracted = !content.getChapters().isEmpty();
            }
        }

        if (!extracted) {
            String message = "Не удалось извлечь текст из PDF. Возможно, страницы отсканированы или защищены. "
                    + "Для сканированного документа нужен OCR (распознавание текста).";
            content.addChapter(new Chapter(0, "Информация о PDF", "<p>" + HtmlSanitizer.escapeHtml(message) + "</p>", message));
        }
        return content;
    }

    private boolean extractWithPdfKit(File pdfFile, BookContent content) {
        Path tempOutput = null;
        try {
            Path cacheDir = pdfCacheDirectory(pdfFile);
            Files.createDirectories(cacheDir);
            tempOutput = Files.createTempFile("luminaread-pdf-text-", ".txt");

            String script = """
                    ObjC.import('Foundation');
                    ObjC.import('PDFKit');
                    ObjC.import('AppKit');
                    var args = $.NSProcessInfo.processInfo.arguments;
                    var pdfPath = ObjC.unwrap(args.objectAtIndex(5));
                    var outDir = ObjC.unwrap(args.objectAtIndex(6));
                    var doc = $.PDFDocument.alloc.initWithURL($.NSURL.fileURLWithPath(pdfPath));
                    if (!doc) throw new Error('Cannot open PDF');
                    for (var i = 0; i < doc.pageCount; i++) {
                        var page = doc.pageAtIndex(i);
                        var txt = page.string ? ObjC.unwrap(page.string) : '';
                        txt = txt ? txt : '';
                        // Render every page, not just scanned pages. A PDF text layer loses
                        // layout and embedded images when flattened into paragraphs.
                        var img = page.thumbnailOfSizeForBox($.NSMakeSize(1100, 1500), $.kPDFDisplayBoxMediaBox);
                        if (img) {
                            var data = $.NSData.dataWithData(img.TIFFRepresentation);
                            data.writeToFileAtomically(outDir + '/page_' + (i + 1) + '.tiff', true);
                        }
                        console.log('===LUMINA_PAGE_' + (i + 1) + '===\\n' + txt + '\\n');
                    }
                    """;

            ProcessBuilder pb = new ProcessBuilder("/usr/bin/osascript", "-l", "JavaScript", "-e", script,
                    pdfFile.getAbsolutePath(), cacheDir.toAbsolutePath().toString());
            pb.redirectErrorStream(true);
            pb.redirectOutput(tempOutput.toFile());
            Process process = pb.start();
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
                return false;
            }
            if (process.exitValue() != 0 || Files.size(tempOutput) > MAX_EXTRACTED_TEXT_BYTES) {
                if (Boolean.getBoolean("luminaread.debugPdf")) {
                    System.err.println("PDFKit output was empty or invalid; process exit code=" + process.exitValue());
                }
                return false;
            }

            convertCachedThumbnails(cacheDir);
            Path cover = cacheDir.resolve("page_1.png");
            if (Files.isRegularFile(cover) && Files.size(cover) <= 20L * 1024 * 1024) {
                content.setCoverImage(Files.readAllBytes(cover));
            }
            readPdfKitPages(tempOutput, cacheDir, content);
            return !content.getChapters().isEmpty();
        } catch (Exception ignored) {
            if (Boolean.getBoolean("luminaread.debugPdf")) {
                ignored.printStackTrace(System.err);
                try { if (tempOutput != null) System.err.println("PDFKit output: " + Files.readString(tempOutput, StandardCharsets.UTF_8)); }
                catch (Exception ignoredOutput) {}
            }
            return false;
        } finally {
            try { if (tempOutput != null) Files.deleteIfExists(tempOutput); } catch (Exception ignored) {}
        }
    }

    private void convertCachedThumbnails(Path cacheDir) {
        try (var files = Files.newDirectoryStream(cacheDir, "page_*.tiff")) {
            for (Path tiff : files) {
                Path png = tiff.resolveSibling(tiff.getFileName().toString().replaceFirst("\\.tiff$", ".png"));
                try {
                    BufferedImage image = ImageIO.read(tiff.toFile());
                    if (image != null) ImageIO.write(image, "png", png.toFile());
                } catch (Exception ignored) {
                } finally {
                    Files.deleteIfExists(tiff);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void readPdfKitPages(Path textFile, Path cacheDir, BookContent content) throws Exception {
        StringBuilder pageText = new StringBuilder();
        StringBuilder chapterHtml = new StringBuilder();
        StringBuilder chapterPlain = new StringBuilder();
        int pageNumber = 0;
        int chapterStartPage = 1;
        int chapterIndex = 0;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                Files.newInputStream(textFile), StandardCharsets.UTF_8), 64 * 1024)) {
            String line;
            while ((line = reader.readLine()) != null) {
                Matcher marker = PAGE_MARKER.matcher(line.trim());
                if (marker.matches()) {
                    if (pageNumber > 0) {
                        appendPage(pageNumber, pageText.toString(), cacheDir, chapterHtml, chapterPlain);
                        if (pageNumber % PAGES_PER_CHAPTER == 0) {
                            chapterIndex = flushChapter(content, chapterIndex, chapterStartPage, pageNumber,
                                    chapterHtml, chapterPlain);
                            chapterStartPage = pageNumber + 1;
                        }
                    }
                    pageNumber = Integer.parseInt(marker.group(1));
                    pageText.setLength(0);
                } else if (pageNumber > 0) {
                    if (!pageText.isEmpty()) pageText.append('\n');
                    pageText.append(line);
                }
            }
        }

        if (pageNumber > 0) {
            appendPage(pageNumber, pageText.toString(), cacheDir, chapterHtml, chapterPlain);
            chapterIndex = flushChapter(content, chapterIndex, chapterStartPage, pageNumber,
                    chapterHtml, chapterPlain);
        }
        if (pageNumber == 0) {
            throw new IllegalStateException("PDFKit не вернул текстовые страницы.");
        }
    }

    private void appendPage(int pageNumber, String rawText, Path cacheDir,
                            StringBuilder html, StringBuilder plain) throws Exception {
        String text = rawText == null ? "" : rawText.strip();
        Path pageImage = cacheDir.resolve("page_" + pageNumber + ".png");
        boolean hasImage = Files.isRegularFile(pageImage) && Files.size(pageImage) <= 20L * 1024 * 1024;

        html.append("<h3>Страница ").append(pageNumber).append("</h3>\n");
        if (hasImage) {
            html.append("<div style='text-align:center; margin:8px 0;'><img src='")
                    .append(HtmlSanitizer.escapeHtml(pageImage.toUri().toString()))
                    .append("' alt='Страница PDF ").append(pageNumber).append("'/></div>\n");

            if (!text.isEmpty()) {
                String normalized = normalizePdfParagraph(text);
                if (!plain.isEmpty()) plain.append("\n\n");
                plain.append(normalized);
            }
        } else if (!text.isEmpty()) {
            String[] paragraphs = text.split("(?:\\R\\s*){2,}");
            for (String paragraph : paragraphs) {
                String normalized = normalizePdfParagraph(paragraph);
                if (!normalized.isBlank()) {
                    html.append("<p>").append(HtmlSanitizer.escapeHtml(normalized)).append("</p>\n");
                    if (!plain.isEmpty()) plain.append("\n\n");
                    plain.append(normalized);
                }
            }
        } else if (hasImage) {
            if (!plain.isEmpty()) plain.append('\n');
            plain.append("[Скан страницы ").append(pageNumber).append("]");
        } else {
            if (!plain.isEmpty()) plain.append('\n');
            plain.append("[На странице ").append(pageNumber).append(" нет текстового слоя]");
        }
    }

    private int flushChapter(BookContent content, int chapterIndex, int startPage, int endPage,
                             StringBuilder html, StringBuilder plain) {
        if (!plain.isEmpty()) {
            String title = startPage == endPage ? "Страница " + startPage
                    : "Страницы " + startPage + "–" + endPage;
            content.addChapter(new Chapter(chapterIndex++, title, html.toString(), plain.toString().trim()));
        }
        html.setLength(0);
        plain.setLength(0);
        return chapterIndex;
    }

    private String extractWithPdfToText(File file) {
        Path tempOutput = null;
        Path tempError = null;
        try {
            tempOutput = Files.createTempFile("luminaread-pdftotext-", ".txt");
            tempError = Files.createTempFile("luminaread-pdftotext-error-", ".log");
            ProcessBuilder pb = new ProcessBuilder("pdftotext", "-enc", "UTF-8", file.getAbsolutePath(), "-");
            pb.redirectOutput(tempOutput.toFile());
            pb.redirectError(tempError.toFile());
            Process process = pb.start();
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
                return null;
            }
            if (process.exitValue() != 0 || Files.size(tempOutput) > MAX_EXTRACTED_TEXT_BYTES) return null;
            return Files.readString(tempOutput, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return null;
        } finally {
            try { if (tempOutput != null) Files.deleteIfExists(tempOutput); } catch (Exception ignored) {}
            try { if (tempError != null) Files.deleteIfExists(tempError); } catch (Exception ignored) {}
        }
    }

    private void addTextPages(String[] pages, BookContent content) {
        StringBuilder html = new StringBuilder();
        StringBuilder plain = new StringBuilder();
        int start = 1;
        int chapterIndex = 0;
        for (int i = 0; i < pages.length; i++) {
            String text = pages[i] == null ? "" : pages[i].strip();
            if (!text.isEmpty()) {
                int number = i + 1;
                html.append("<h3>Страница ").append(number).append("</h3>\n");
                for (String paragraph : text.split("(?:\\R\\s*){2,}")) {
                    String normalized = normalizePdfParagraph(paragraph);
                    if (!normalized.isBlank()) {
                        html.append("<p>").append(HtmlSanitizer.escapeHtml(normalized)).append("</p>\n");
                        if (!plain.isEmpty()) plain.append("\n\n");
                        plain.append(normalized);
                    }
                }
            }
            if ((i + 1) % 10 == 0 || i == pages.length - 1) {
                if (!plain.isEmpty()) {
                    String title = start == i + 1 ? "Страница " + start : "Страницы " + start + "–" + (i + 1);
                    content.addChapter(new Chapter(chapterIndex++, title, html.toString(), plain.toString().trim()));
                }
                html.setLength(0);
                plain.setLength(0);
                start = i + 2;
            }
        }
    }

    private String normalizePdfParagraph(String paragraph) {
        StringBuilder normalized = new StringBuilder();
        for (String line : paragraph.split("\\R")) {
            String trimmed = HtmlSanitizer.normalizeInlineWhitespace(line).trim();
            if (!trimmed.isEmpty()) {
                if (!normalized.isEmpty()) normalized.append(' ');
                normalized.append(trimmed);
            }
        }
        return normalized.toString();
    }

    private Path pdfCacheDirectory(File file) throws Exception {
        String identity = file.getCanonicalPath() + "|" + file.length() + "|" + file.lastModified();
        String cacheKey = UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString();
        return Path.of(System.getProperty("user.home"), ".luminaread", "cache", "pdf_" + cacheKey);
    }

    private String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
