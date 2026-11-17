package com.luminaread.parser;

import com.luminaread.model.BookContent;
import com.luminaread.model.Chapter;
import com.luminaread.util.HtmlSanitizer;
import com.luminaread.util.Json;

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
    private static final long MAX_SOURCE_BYTES = 512L * 1024 * 1024;
    static final long MAX_EXTRACTED_TEXT_BYTES = 16L * 1024 * 1024;
    private static final long MAX_THUMBNAIL_BYTES = 96L * 1024 * 1024;
    static final int MAX_PAGES = 5000;

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

        if (Files.size(file.toPath()) > MAX_SOURCE_BYTES) {
            throw new java.io.IOException("PDF превышает допустимый размер");
        }

        BookContent content = new BookContent();
        content.setTitle(stripExtension(file.getName()));
        content.setAuthor("PDF-документ");

        boolean extracted = false;
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")) {
            extracted = extractWithPdfKit(file, content);
        }
        if (!extracted && !Thread.currentThread().isInterrupted()) {
            String text = extractWithPdfBox(file);
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
        Path stagedImages = null;
        Process process = null;
        try {
            Path cacheDir = pdfCacheDirectory(pdfFile);
            stagedImages = Files.createTempDirectory("luminaread-pdf-pages-");
            tempOutput = Files.createTempFile("luminaread-pdf-text-", ".txt");
            tempError = Files.createTempFile("luminaread-pdf-error-", ".log");

            String script = """
                    ObjC.import('Foundation');
                    ObjC.import('PDFKit');
                    ObjC.import('AppKit');
                    var args = $.NSProcessInfo.processInfo.arguments;
                    var pdfPath = ObjC.unwrap(args.objectAtIndex(5));
                    var outDir = ObjC.unwrap(args.objectAtIndex(6));
                    var thumbLimit = Number(ObjC.unwrap(args.objectAtIndex(7)));
                    var textLimit = Number(ObjC.unwrap(args.objectAtIndex(8)));
                    var pageLimit = Number(ObjC.unwrap(args.objectAtIndex(9)));
                    var doc = $.PDFDocument.alloc.initWithURL($.NSURL.fileURLWithPath(pdfPath));
                    if (!doc) throw new Error('Cannot open PDF');
                    if (doc.pageCount > pageLimit) throw new Error('PDF page limit');
                    var output = $.NSFileHandle.fileHandleWithStandardOutput;
                    var thumbBytes = 0, textBytes = 0;
                    for (var i = 0; i < doc.pageCount; i++) {
                        var page = doc.pageAtIndex(i);
                        var txt = page.string ? ObjC.unwrap(page.string) : '';
                        var line = JSON.stringify([i + 1, txt || '']) + '\\n';
                        var encoded = $(line).dataUsingEncoding($.NSUTF8StringEncoding);
                        var encodedBytes = Number(encoded.length);
                        if (encodedBytes > textLimit - textBytes) throw new Error('PDF text limit');
                        textBytes += encodedBytes;
                        output.writeData(encoded);
                        if (thumbBytes >= thumbLimit) continue;
                        var width = 1100, height = 1500;
                        var img = page.thumbnailOfSizeForBox($.NSMakeSize(width, height), $.kPDFDisplayBoxMediaBox);
                        if (!img) continue;
                        var bitmap = $.NSBitmapImageRep.imageRepWithData(img.TIFFRepresentation);
                        var data = bitmap.representationUsingTypeProperties($.NSPNGFileType, $({}));
                        var bytes = data ? Number(data.length) : 0;
                        var extension = '.png';
                        var share = Math.floor((thumbLimit - thumbBytes) / (doc.pageCount - i));
                        if (bytes > share) {
                            var jpegOptions = $({'NSImageCompressionFactor': 0.55});
                            data = bitmap.representationUsingTypeProperties($.NSJPEGFileType, jpegOptions);
                            bytes = data ? Number(data.length) : 0;
                            extension = '.jpg';
                            for (var attempt = 0; bytes > share && width > 320 && attempt < 4; attempt++) {
                                var scale = Math.min(0.8, 0.9 * Math.sqrt(share / bytes));
                                width = Math.max(320, Math.floor(width * scale));
                                height = Math.round(width * 1500 / 1100);
                                img = page.thumbnailOfSizeForBox($.NSMakeSize(width, height), $.kPDFDisplayBoxMediaBox);
                                bitmap = $.NSBitmapImageRep.imageRepWithData(img.TIFFRepresentation);
                                data = bitmap.representationUsingTypeProperties($.NSJPEGFileType, jpegOptions);
                                bytes = data ? Number(data.length) : 0;
                            }
                        }
                        if (bytes > 0 && bytes <= thumbLimit - thumbBytes) {
                            if (data.writeToFileAtomically(outDir + '/page_' + (i + 1) + extension, true)) {
                                thumbBytes += bytes;
                            }
                        } else {
                            thumbBytes = thumbLimit;
                        }
                    }
                    '';
                    """;

            ProcessBuilder pb = new ProcessBuilder("/usr/bin/osascript", "-l", "JavaScript", "-e", script,
                    pdfFile.getAbsolutePath(), stagedImages.toAbsolutePath().toString(), Long.toString(MAX_THUMBNAIL_BYTES),
                    Long.toString(MAX_EXTRACTED_TEXT_BYTES), Integer.toString(MAX_PAGES));
            pb.redirectOutput(tempOutput.toFile());
            pb.redirectError(tempError.toFile());
            process = pb.start();
            if (!waitForOutput(process, tempOutput, tempError, 60)) {
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

            publishThumbnails(stagedImages, cacheDir);
            Path cover = pageImage(cacheDir, 1);
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
            deleteStaging(stagedImages);
        }
    }

    private static boolean waitForOutput(Process process, Path output, Path diagnostics, int seconds)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
            if (System.nanoTime() >= deadline || Files.size(output) > MAX_EXTRACTED_TEXT_BYTES
                    || Files.size(diagnostics) > 1024 * 1024) return false;
        }
        return true;
    }

    private void publishThumbnails(Path staged, Path cache) throws java.io.IOException {
        try (var files = Files.newDirectoryStream(staged, "page_*.*")) {
            for (Path source : files) {
                if (Files.size(source) > 20L * 1024 * 1024) continue;
                Path target = cache.resolve(source.getFileName());
                try {
                    com.luminaread.util.GeneratedCache.write(target, out -> Files.copy(source, out));
                } catch (java.io.IOException ex) {
                    logPdfFallback("Не удалось сохранить миниатюру PDF", ex);
                    return;
                }
            }
        }
    }

    private void deleteStaging(Path staged) {
        if (staged == null) return;
        try (var paths = Files.newDirectoryStream(staged)) {
            for (Path path : paths) Files.deleteIfExists(path);
        } catch (java.io.IOException ex) {
            logPdfFallback("Не удалось очистить временные миниатюры", ex);
        }
        deleteTemporaryFile(staged);
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

    private static Path pageImage(Path cache, int number) {
        Path png = cache.resolve("page_" + number + ".png");
        return Files.isRegularFile(png) ? png : cache.resolve("page_" + number + ".jpg");
    }

    private void appendPage(int pageNumber, String rawText, Path cacheDir,
                            StringBuilder html, StringBuilder plain) throws Exception {
        String text = rawText == null ? "" : rawText.strip();
        Path pageImage = pageImage(cacheDir, pageNumber);
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
            plain.append("Страница ").append(pageNumber).append(" недоступна без изображения.");
            html.append("<p>").append(plain).append("</p>");
        }
    }

    private String extractWithPdfBox(File file) {
        Path tempOutput = null;
        Path tempError = null;
        Process process = null;
        try {
            tempOutput = Files.createTempFile("luminaread-pdfbox-", ".txt");
            tempError = Files.createTempFile("luminaread-pdfbox-error-", ".log");
            boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
            Path java = Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java");
            ProcessBuilder pb = new ProcessBuilder(java.toString(), "-Xmx256m", "-XX:+ExitOnOutOfMemoryError",
                    "-Djava.awt.headless=true", "-Dpdfbox.fontcache.skipchecksums=true",
                    "-Duser.home=" + System.getProperty("user.home"), "-cp", System.getProperty("java.class.path"),
                    PdfTextWorker.class.getName(), file.getAbsolutePath());
            pb.redirectOutput(tempOutput.toFile());
            pb.redirectError(tempError.toFile());
            process = pb.start();
            // The first extraction may have to index system fonts, particularly on Windows.
            if (!waitForOutput(process, tempOutput, tempError, windows ? 75 : 30)) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
                reportWorkerFailure("PDF worker exceeded its transfer or time limit", tempError);
                return null;
            }
            if (process.exitValue() != 0 || Files.size(tempOutput) > MAX_EXTRACTED_TEXT_BYTES) {
                reportWorkerFailure("PDF worker exited with code " + process.exitValue(), tempError);
                return null;
            }
            return Files.readString(tempOutput, StandardCharsets.UTF_8);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception ex) {
            java.util.logging.Logger.getLogger(PdfParser.class.getName())
                    .log(java.util.logging.Level.FINE, "Не удалось извлечь текст PDF", ex);
            return null;
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
            deleteTemporaryFile(tempOutput);
            deleteTemporaryFile(tempError);
        }
    }

    private static void reportWorkerFailure(String message, Path diagnostics) throws java.io.IOException {
        if (!Boolean.getBoolean("luminaread.debugPdf")) return;
        try (var input = Files.newInputStream(diagnostics)) {
            System.err.println(message + ": " + new String(input.readNBytes(4096), StandardCharsets.UTF_8));
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
        String identity = file.getCanonicalPath() + "|" + file.length() + "|" + file.lastModified() + "|budget-v3";
        String cacheKey = UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString();
        return Path.of(System.getProperty("user.home"), ".luminaread", "cache", "pdf_" + cacheKey);
    }

    private String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
