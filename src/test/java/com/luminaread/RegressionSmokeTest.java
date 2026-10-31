package com.luminaread;

import com.luminaread.model.Book;
import com.luminaread.model.BookContent;
import com.luminaread.parser.BookParserFactory;
import com.luminaread.service.LibraryStorageService;
import com.luminaread.service.DictionaryService;
import com.luminaread.service.TtsService;
import com.luminaread.ui.ReaderPanel;
import com.luminaread.util.CharsetDetector;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import javax.swing.SwingUtilities;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RegressionSmokeTest {
    private RegressionSmokeTest() {}

    public static void main(String[] args) throws Exception {
        testCharsets();
        testPlainTextParsing();
        if (supportsPdfExtraction()) testPdfImportAndDuplicateNames();
        else System.out.println("SKIP PDF smoke test: PDFKit or pdftotext is not available on this OS");
        if (supportsPdfExtraction() && args.length > 0) testScannedPdf(Path.of(args[0]));
        if (supportsPdfExtraction() && args.length > 1) testSelectablePdf(Path.of(args[1]));
        System.out.println("PASS LuminaRead regression smoke tests");
    }

    private static void testCharsets() throws Exception {
        String russian = "Название: Избранная русская лирика";
        byte[] cp1251 = russian.getBytes(Charset.forName("windows-1251"));
        require(russian.equals(CharsetDetector.decodeBytes(cp1251)), "Windows-1251 Cyrillic must decode exactly");

        byte[] withBom = new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] utf8 = "Текст UTF-8".getBytes(StandardCharsets.UTF_8);
        byte[] bomText = new byte[withBom.length + utf8.length];
        System.arraycopy(withBom, 0, bomText, 0, withBom.length);
        System.arraycopy(utf8, 0, bomText, withBom.length, utf8.length);
        require("Текст UTF-8".equals(CharsetDetector.decodeBytes(bomText)), "UTF-8 BOM must not appear in the text");

        byte[] longData = new byte[65_540];
        java.util.Arrays.fill(longData, (byte) 'a');
        longData[65_538] = (byte) 0xFF;
        require(!StandardCharsets.UTF_8.equals(CharsetDetector.detectCharset(longData)),
                "UTF-8 validation must inspect data beyond the first 64 KB");

        String html = "<meta charset=windows-1251><p>Привет</p>";
        require(html.equals(CharsetDetector.decodeBytes(html.getBytes(Charset.forName("windows-1251")))),
                "HTML meta charset must be honored");
    }

    private static void testPlainTextParsing() throws Exception {
        Path temp = Files.createTempFile("luminaread-cp1251-", ".txt");
        try {
            String body = "Название: Избранная русская лирика\nАвтор: А. С. Пушкин\n\nУ лукоморья дуб зелёный;\nЗлатая цепь на дубе том.";
            Files.write(temp, body.getBytes(Charset.forName("windows-1251")));
            BookContent parsed = BookParserFactory.parseBook(temp.toFile());
            require(parsed.getTotalWords() >= 8, "CP1251 text must produce readable words");
            require(parsed.getChapters().get(0).getPlainText().contains("У лукоморья дуб зелёный"),
                    "CP1251 text should retain its Cyrillic words and punctuation");
        } finally {
            Files.deleteIfExists(temp);
        }

        Path rtf = Files.createTempFile("luminaread-rtf-", ".rtf");
        try {
            Files.writeString(rtf, "{\\rtf1\\ansi\\ansicpg1251 \\'cf\\'f0\\'e8\\'e2\\'e5\\'f2}", StandardCharsets.US_ASCII);
            BookContent parsed = BookParserFactory.parseBook(rtf.toFile());
            require(parsed.getChapters().get(0).getPlainText().contains("Привет"),
                    "RTF hexadecimal Cyrillic escapes should decode correctly: " + parsed.getChapters().get(0).getPlainText());
        } finally {
            Files.deleteIfExists(rtf);
        }
    }

    private static void testPdfImportAndDuplicateNames() throws Exception {
        Path sandbox = Files.createTempDirectory("luminaread-regression-");
        String oldHome = System.getProperty("user.home");
        System.setProperty("user.home", sandbox.toString());
        try {
            Path firstSource = sandbox.resolve("first");
            Path secondSource = sandbox.resolve("second");
            Files.createDirectories(firstSource);
            Files.createDirectories(secondSource);
            Path firstPdf = firstSource.resolve("book.pdf");
            Path secondPdf = secondSource.resolve("book.pdf");
            writeMinimalPdf(firstPdf, "PDF smoke test first");
            writeMinimalPdf(secondPdf, "PDF smoke test second");

            LibraryStorageService storage = new LibraryStorageService();
            Book first = storage.importBookFile(firstPdf.toFile(), true);
            Book second = storage.importBookFile(secondPdf.toFile(), true);
            require(!first.getFilePath().equals(second.getFilePath()), "Same-name imports must not overwrite each other");

            String firstText = storage.loadBookContent(first).getChapters().get(0).getPlainText();
            String secondText = storage.loadBookContent(second).getChapters().get(0).getPlainText();
            require(firstText.contains("PDF smoke test first"), "First PDF should display its extracted text");
            require(secondText.contains("PDF smoke test second"), "Second PDF should display its extracted text");

            LibraryStorageService coldStorage = new LibraryStorageService();
            Book coldBook = coldStorage.getAllBooks().stream()
                    .filter(book -> book.getFilePath().equals(first.getFilePath())).findFirst().orElseThrow();
            testReaderOpenDoesNotBlock(coldStorage, coldBook);
        } finally {
            if (oldHome == null) System.clearProperty("user.home");
            else System.setProperty("user.home", oldHome);
            try (Stream<Path> paths = Files.walk(sandbox)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private static void testReaderOpenDoesNotBlock(LibraryStorageService storage, Book book) throws Exception {
        ReaderPanel[] panel = new ReaderPanel[1];
        SwingUtilities.invokeAndWait(() -> panel[0] = new ReaderPanel(storage, new TtsService(),
                new DictionaryService(), () -> {}, theme -> {}));
        SwingUtilities.invokeAndWait(() -> {
            panel[0].openBook(book);
            require(!hasLoadedContent(panel[0]), "Reader open should leave the UI thread before PDF parsing completes");
        });

        long deadline = System.currentTimeMillis() + 30_000;
        AtomicBoolean loaded = new AtomicBoolean();
        while (System.currentTimeMillis() < deadline && !loaded.get()) {
            SwingUtilities.invokeAndWait(() -> loaded.set(hasLoadedContent(panel[0])));
            if (!loaded.get()) Thread.sleep(20);
        }
        SwingUtilities.invokeAndWait(panel[0]::stopActiveFeatures);
        require(loaded.get(), "The reader background load should eventually complete");
    }

    private static boolean hasLoadedContent(ReaderPanel panel) {
        try {
            var field = ReaderPanel.class.getDeclaredField("currentContent");
            field.setAccessible(true);
            return field.get(panel) != null;
        } catch (Exception ex) {
            throw new AssertionError("Unable to inspect reader load state", ex);
        }
    }

    private static void testScannedPdf(Path pdfFile) throws Exception {
        Path testHome = Files.createTempDirectory("luminaread-scanned-pdf-");
        String oldHome = System.getProperty("user.home");
        System.setProperty("user.home", testHome.toString());
        try {
            BookContent parsed = BookParserFactory.parseBook(pdfFile.toFile());
            require(!parsed.getChapters().isEmpty(), "A scanned PDF should still import as page images");
            require(parsed.getCoverImage() != null, "The scanned PDF should have a rendered cover thumbnail");
            require(parsed.getChapters().stream().anyMatch(ch -> ch.getHtmlContent().contains("<img")),
                    "Scanned PDF pages should render as images in the reader");
        } finally {
            if (oldHome == null) System.clearProperty("user.home");
            else System.setProperty("user.home", oldHome);
            try (Stream<Path> paths = Files.walk(testHome)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private static void testSelectablePdf(Path pdfFile) throws Exception {
        Path testHome = Files.createTempDirectory("luminaread-selectable-pdf-");
        String oldHome = System.getProperty("user.home");
        System.setProperty("user.home", testHome.toString());
        try {
            BookContent parsed = BookParserFactory.parseBook(pdfFile.toFile());
            String allText = parsed.getChapters().stream().map(ch -> ch.getPlainText())
                    .reduce("", (a, b) -> a + "\n" + b);
            require(parsed.getTotalWords() > 100, "A selectable text PDF should expose its text layer");
            require(allText.contains("Программирование"), "Russian PDF text should be extracted in reading order");
            require(!parsed.getChapters().isEmpty()
                            && parsed.getChapters().stream().allMatch(ch -> ch.getHtmlContent().contains("<img")),
                    "Every selectable PDF page should render as a page image to preserve its original layout and illustrations");
        } finally {
            if (oldHome == null) System.clearProperty("user.home");
            else System.setProperty("user.home", oldHome);
            try (Stream<Path> paths = Files.walk(testHome)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private static boolean supportsPdfExtraction() {
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")) return true;
        try {
            Process process = new ProcessBuilder("pdftotext", "-v").redirectErrorStream(true).start();
            return process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static void writeMinimalPdf(Path destination, String text) throws Exception {
        String stream = "BT /F1 14 Tf 72 720 Td (" + text.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")
                + ") Tj ET\n";
        List<String> objects = List.of(
                "<< /Type /Catalog /Pages 2 0 R >>",
                "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>",
                "<< /Length " + stream.getBytes(StandardCharsets.US_ASCII).length + " >>\nstream\n" + stream + "endstream",
                "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"
        );
        ByteArrayOutputStream pdf = new ByteArrayOutputStream();
        pdf.write("%PDF-1.4\n".getBytes(StandardCharsets.US_ASCII));
        List<Integer> offsets = new ArrayList<>();
        for (int i = 0; i < objects.size(); i++) {
            offsets.add(pdf.size());
            pdf.write(((i + 1) + " 0 obj\n" + objects.get(i) + "\nendobj\n").getBytes(StandardCharsets.US_ASCII));
        }
        int xrefOffset = pdf.size();
        pdf.write(("xref\n0 " + (objects.size() + 1) + "\n0000000000 65535 f \n").getBytes(StandardCharsets.US_ASCII));
        for (int offset : offsets) {
            pdf.write(String.format(Locale.ROOT, "%010d 00000 n \n", offset).getBytes(StandardCharsets.US_ASCII));
        }
        pdf.write(("trailer\n<< /Size " + (objects.size() + 1) + " /Root 1 0 R >>\nstartxref\n"
                + xrefOffset + "\n%%EOF\n").getBytes(StandardCharsets.US_ASCII));
        Files.write(destination, pdf.toByteArray());
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
