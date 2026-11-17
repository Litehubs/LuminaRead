package com.luminaread.parser;

import com.luminaread.model.BookContent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class BookIntegrityTest {
    @TempDir Path dir;

    @Test void epubImagesWithSimilarPathsStaySeparate() throws Exception {
        Path book = epub(dir.resolve("images.epub"), Map.of("a/b.png", "first", "a_b.png", "second"));
        List<Path> images = images(new EpubParser().parse(book.toFile()));
        assertEquals(2, images.stream().distinct().count());
        assertEquals(List.of("first", "second"), images.stream().map(path -> {
            try { return Files.readString(path); } catch (Exception ex) { throw new AssertionError(ex); }
        }).sorted().toList());
    }

    @Test void booksWithTheSameNameCannotReplaceEachOthersImages() throws Exception {
        Path a = Files.createDirectory(dir.resolve("a"));
        Path b = Files.createDirectory(dir.resolve("b"));
        var parser = new EpubParser();
        Path first = images(parser.parse(epub(a.resolve("book.epub"), Map.of("cover.png", "first")).toFile())).get(0);
        Path second = images(parser.parse(epub(b.resolve("book.epub"), Map.of("cover.png", "second")).toFile())).get(0);
        assertNotEquals(first, second);
        assertEquals("first", Files.readString(first));
        assertEquals("second", Files.readString(second));
    }

    @Test void epubLinksPreserveLiteralPlusInPaths() throws Exception {
        Path book = epub(dir.resolve("plus.epub"), Map.of("a+b.png", "image"));
        assertEquals(1, images(new EpubParser().parse(book.toFile())).size());
    }

    @Test void fb2KeepsShortSectionsAndTextBetweenSections() throws Exception {
        Path book = dir.resolve("short.fb2");
        Files.writeString(book, "<FictionBook><body><p>Before.</p><section><p>Short.</p></section>" +
                "<p>Between.</p><section><p>A longer chapter with enough text to pass the old cutoff.</p></section>" +
                "<p>After.</p></body><body name='notes'><section><p>Footnote.</p></section></body></FictionBook>");
        String text = new Fb2Parser().parse(book.toFile()).getChapters().stream()
                .map(chapter -> chapter.getPlainText()).reduce("", (a, b) -> a + "\n" + b);
        for (String fragment : List.of("Before.", "Short.", "Between.", "After.", "Footnote.")) {
            assertTrue(text.contains(fragment), "Lost text: " + fragment);
        }
    }

    @Test void fb2TablesAndFootnoteLinksSurviveImportAndPagination() throws Exception {
        Path file = dir.resolve("footnotes.fb2");
        Files.writeString(file, "<FictionBook xmlns:l='http://www.w3.org/1999/xlink'><body><section>"
                + "<p><emphasis>" + "Раздел ".repeat(160) + "</emphasis><a l:href='#note'>1</a></p>"
                + "<table><tr><td>Ячейка</td></tr></table></section></body>"
                + "<body name='notes'><section><p id='note'>Примечание.</p></section></body></FictionBook>");
        var content = new Fb2Parser().parse(file.toFile());
        var pages = PaginationEngine.paginateChapter(content.getChapters().get(0), 600, false);
        assertTrue(pages.size() > 1);
        String joined = String.join("", pages);
        assertTrue(joined.contains("<a href='#note'>1</a>"));
        assertTrue(joined.contains("<table><tr><td>Ячейка</td></tr></table>"));
        assertTrue(content.getChapters().get(1).getHtmlContent().contains("id='note'"));
    }

    @Test void epubKeepsNestedTableAndInlineIllustrationInReadingOrder() throws Exception {
        Path book = dir.resolve("layout.epub");
        String chapter = "<html><body><div><p><strong>" + "Before ".repeat(110)
                + "</strong><img src='image.png'/>After image.</p>"
                + "<table><tr><td>Cell</td></tr></table></div></body></html>";
        try (var zip = new ZipOutputStream(Files.newOutputStream(book))) {
            zip.putNextEntry(new ZipEntry("chapter.xhtml"));
            zip.write(chapter.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("image.png"));
            zip.write("fixture".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        var parsed = new EpubParser().parse(book.toFile());
        var pages = PaginationEngine.paginateChapter(parsed.getChapters().get(0), 600, false);
        String joined = String.join("", pages);
        assertTrue(joined.contains("<strong>"));
        assertTrue(joined.contains("<table><tr><td>Cell</td></tr></table>"));
        assertTrue(joined.contains("<img src=") && joined.indexOf("<img src=") < joined.indexOf("After image."));
    }

    @Test void detectedFormatTakesPriorityOverMisleadingExtension() throws Exception {
        var registry = new BookParserRegistry();
        assertInstanceOf(EpubParser.class, registry.findParser(dir.resolve("book.fb2").toFile(), "EPUB").orElseThrow());
        assertInstanceOf(PdfParser.class, registry.findParser(dir.resolve("book.epub").toFile(), "PDF").orElseThrow());
    }

    @Test void oversizedTextIsRejectedBeforeBuffering() throws Exception {
        Path path = dir.resolve("large.txt");
        try (var file = new java.io.RandomAccessFile(path.toFile(), "rw")) {
            file.setLength(64L * 1024 * 1024 + 1);
        }
        assertThrows(java.io.IOException.class, () -> new TxtMarkdownParser().parse(path.toFile()));
    }

    private Path epub(Path file, Map<String, String> pictures) throws Exception {
        try (var zip = new ZipOutputStream(Files.newOutputStream(file))) {
            StringBuilder html = new StringBuilder("<html><body><p>Chapter text.</p>");
            for (var entry : pictures.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
                html.append("<img src='").append(entry.getKey()).append("'/>");
            }
            zip.putNextEntry(new ZipEntry("chapter.xhtml"));
            zip.write(html.append("</body></html>").toString().getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return file;
    }

    private List<Path> images(BookContent book) {
        String html = book.getChapters().get(0).getHtmlContent();
        return Pattern.compile("<img src='([^']+)'").matcher(html).results()
                .map(match -> Path.of(URI.create(match.group(1)))).toList();
    }
}
