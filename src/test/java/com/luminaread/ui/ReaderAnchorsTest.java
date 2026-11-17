package com.luminaread.ui;

import com.luminaread.model.Chapter;
import com.luminaread.model.ReaderSettings;
import com.luminaread.parser.EpubParser;
import com.luminaread.parser.Fb2Parser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class ReaderAnchorsTest {
    @Test void tracksTheSameTextAcrossScrollPagesAndSpreads() throws Exception {
        String marker = "Точное место чтения.";
        var chapter = new Chapter(0, "Chapter",
                ("<p>Повторяющийся текст с <em>выделением</em>.</p>").repeat(96)
                        + "<p>" + marker + "</p>" + "<p>Продолжение.</p>".repeat(50), "");
        var kit = new ReaderHtmlKit();
        var scroll = new ReaderTextSearch.Layout(ReaderSettings.ReadingMode.SCROLL, 0, false);
        var original = ReaderTextSearch.readPage(kit, scroll.pages(chapter).get(0));
        int model = original.getText(0, original.getLength()).indexOf(marker);
        int position = new ReaderAnchors(scroll.pages(chapter)).capture(0, original, model);
        assertTrue(position > 1000);

        for (boolean spread : List.of(false, true)) {
            for (int size : List.of(800, 1600)) {
                var pages = new ReaderTextSearch.Layout(ReaderSettings.ReadingMode.PAGED, size, spread).pages(chapter);
                var target = new ReaderAnchors(pages).locate(position);
                assertNotNull(target);
                assertTrue(target.page() > 0);
                var document = ReaderTextSearch.readPage(kit, pages.get(target.page()));
                int offset = ReaderAnchors.modelOffset(document, target.offset());
                assertEquals(marker, document.getText(offset, marker.length()));
            }
        }
    }

    @Test void crossChapterFb2NoteUsesSectionIdentifier(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("notes.fb2"), """
                <FictionBook xmlns:l="http://www.w3.org/1999/xlink"><body><section><p>
                Текст книги <a l:href="#note">1</a></p></section></body>
                <body name="notes"><section id="note"><p>Текст примечания.</p></section></body></FictionBook>
                """);
        var content = new Fb2Parser().parse(file.toFile());
        var layout = new ReaderTextSearch.Layout(ReaderSettings.ReadingMode.PAGED, 800, false);
        assertEquals(new ReaderLinks.Target(1, 0, "note"), ReaderLinks.find(content.getChapters(), 0, layout, "#note"));
    }

    @Test void epubRelativeLinkTargetsItsOwnFileEvenWhenOtherIdsMatch(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("notes.epub");
        try (var zip = new ZipOutputStream(Files.newOutputStream(file))) {
            entry(zip, "main.opf", """
                    <package><manifest><item id="chapter" href="text/main.xhtml" media-type="application/xhtml+xml"/>
                    <item id="note" href="notes/n.xhtml" media-type="application/xhtml+xml"/></manifest>
                    <spine><itemref idref="chapter"/></spine></package>
                    """);
            entry(zip, "text/main.xhtml", "<p id='same'>Text <a href='../notes/n.xhtml#same'>note</a></p>");
            entry(zip, "notes/n.xhtml", "<p id='same'>Actual footnote</p>");
        }
        var chapters = new EpubParser().parse(file.toFile()).getChapters();
        int source = chapters.get(0).getSourcePath().startsWith("text/") ? 0 : 1;
        var matcher = java.util.regex.Pattern.compile("href='([^']+)'").matcher(chapters.get(source).getHtmlContent());
        assertTrue(matcher.find());
        var layout = new ReaderTextSearch.Layout(ReaderSettings.ReadingMode.SCROLL, 0, false);
        assertEquals(new ReaderLinks.Target(1 - source, 0, "same"),
                ReaderLinks.find(chapters, source, layout, matcher.group(1)));
        assertNull(ReaderLinks.find(chapters, source, layout, "https://example.org/#same"));
    }

    private static void entry(ZipOutputStream zip, String name, String content) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
