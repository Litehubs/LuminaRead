package com.luminaread.parser;

import com.luminaread.model.BookContent;
import com.luminaread.model.Chapter;
import com.luminaread.util.HtmlSanitizer;
import com.luminaread.util.Json;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class PdfParser implements BookParser {
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
        Path tempError = null;
        Process process = null;
        try {
            Path cacheDir = pdfCacheDirectory(pdfFile);
            Files.createDirectories(cacheDir);
            tempOutput = Files.createTempFile("luminaread-pdf-text-", ".txt");
            tempError = Files.createTempFile("luminaread-pdf-error-", ".log");

            String script = """
                    ObjC.import('Foundation');
                    ObjC.import('PDFKit');
                    ObjC.import('AppKit');
                    var args = $.NSProcessInfo.processInfo.arguments;
                    var pdfPath = ObjC.unwrap(args.objectAtIndex(5));
                    var outDir = ObjC.unwrap(args.objectAtIndex(6));
                    var doc = $.PDFDocument.alloc.initWithURL($.NSURL.fileURLWithPath(pdfPath));
                    if (!doc) throw new Error('Cannot open PDF');
                    var output = $.NSFileHandle.fileHandleWithStandardOutput;
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
                        var pageText = JSON.stringify([i + 1, txt]) + '\\n';
                        output.writeData($(pageText).dataUsingEncoding($.NSUTF8StringEncoding));
                    }
                    '';
                    """;

            ProcessBuilder pb = new ProcessBuilder("/usr/bin/osascript", "-l", "JavaScript", "-e", script,
                    pdfFile.getAbsolutePath(), cacheDir.toAbsolutePath().toString());
            pb.redirectOutput(tempOutput.toFile());
            pb.redirectError(tempError.toFile());
            process = pb.start();
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
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception ex) {
            java.util.logging.Logger.getLogger(PdfParser.class.getName())
                    .log(java.util.logging.Level.FINE, "PDFKit недоступен; пробуем другой способ чтения", ex);
            return false;
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
            deleteTemporaryFile(tempOutput);
            deleteTemporaryFile(tempError);
        }
    }

    private void convertCachedThumbnails(Path cacheDir) {
        try (var files = Files.newDirectoryStream(cacheDir, "page_*.tiff")) {
            for (Path tiff : files) {
                Path png = tiff.resolveSibling(tiff.getFileName().toString().replaceFirst("\\.tiff$", ".png"));
                try {
                    if (com.luminaread.util.ImageSizeGuard.inspect(tiff.toFile())
                            != com.luminaread.util.ImageSizeGuard.Result.ACCEPTABLE) continue;
                    BufferedImage image = ImageIO.read(tiff.toFile());
                    if (image != null) ImageIO.write(image, "png", png.toFile());
                } catch (Exception ex) {
                    logPdfFallback("Не удалось преобразовать миниатюру PDF", ex);
                } finally {
                    Files.deleteIfExists(tiff);
                }
            }
        } catch (Exception ex) {
            logPdfFallback("Не удалось обработать миниатюры PDF", ex);
        }
    }

    private void readPdfKitPages(Path textFile, Path cacheDir, BookContent content) throws Exception {
        List<Chapter> chapters = new ArrayList<>();
        try (var reader = Files.newBufferedReader(textFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue; // osascript may append an empty result line.
                int page = chapters.size() + 1;
                if (!(Json.parse(line) instanceof List<?> record) || record.size() != 2
                        || !(record.get(0) instanceof Number number) || number.doubleValue() != page
                        || !(record.get(1) instanceof String text)) {
                    throw new java.io.IOException("PDFKit вернул повреждённую запись страницы");
                }
                var html = new StringBuilder();
                var plain = new StringBuilder();
                appendPage(page, text, cacheDir, html, plain);
                chapters.add(new Chapter(page - 1, "Страница " + page, html.toString(), plain.toString()));
            }
        }
        if (chapters.isEmpty()) throw new java.io.IOException("PDFKit не вернул страницы");
        chapters.forEach(content::addChapter);
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
            } else {
                plain.append("[Скан страницы ").append(pageNumber).append("]");
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
        } else {
            if (!plain.isEmpty()) plain.append('\n');
            plain.append("[На странице ").append(pageNumber).append(" нет текстового слоя]");
        }
    }

    private String extractWithPdfToText(File file) {
        Path tempOutput = null;
        Path tempError = null;
        Process process = null;
        try {
            tempOutput = Files.createTempFile("luminaread-pdftotext-", ".txt");
            tempError = Files.createTempFile("luminaread-pdftotext-error-", ".log");
            ProcessBuilder pb = new ProcessBuilder("pdftotext", "-enc", "UTF-8", file.getAbsolutePath(), "-");
            pb.redirectOutput(tempOutput.toFile());
            pb.redirectError(tempError.toFile());
            process = pb.start();
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
                return null;
            }
            if (process.exitValue() != 0 || Files.size(tempOutput) > MAX_EXTRACTED_TEXT_BYTES) return null;
            return Files.readString(tempOutput, StandardCharsets.UTF_8);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception ex) {
            java.util.logging.Logger.getLogger(PdfParser.class.getName())
                    .log(java.util.logging.Level.FINE, "pdftotext недоступен; пробуем другой способ чтения", ex);
            return null;
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
            deleteTemporaryFile(tempOutput);
            deleteTemporaryFile(tempError);
        }
    }

    private static void deleteTemporaryFile(Path path) {
        if (path == null) return;
        try { Files.deleteIfExists(path); }
        catch (java.io.IOException ex) { logPdfFallback("Не удалось удалить временный файл PDF", ex); }
    }

    private static void logPdfFallback(String message, Throwable error) {
        java.util.logging.Logger.getLogger(PdfParser.class.getName())
                .log(java.util.logging.Level.FINE, message, error);
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
