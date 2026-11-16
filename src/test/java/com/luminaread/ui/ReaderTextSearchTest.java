package com.luminaread.ui;

import com.luminaread.model.Chapter;
import com.luminaread.model.ReaderSettings;
import org.junit.jupiter.api.Test;

import javax.swing.text.PlainDocument;
import java.util.List;

import static com.luminaread.ui.ReaderTextSearch.Direction.BACKWARD;
import static com.luminaread.ui.ReaderTextSearch.Direction.FORWARD;
import static org.junit.jupiter.api.Assertions.*;

class ReaderTextSearchTest {
    private static final ReaderTextSearch.Layout PAGED =
            new ReaderTextSearch.Layout(ReaderSettings.ReadingMode.PAGED, 600, false);
    private static final ReaderTextSearch.Layout SCROLL =
            new ReaderTextSearch.Layout(ReaderSettings.ReadingMode.SCROLL, 0, false);
    private static final ReaderTextSearch.Page FIRST = new ReaderTextSearch.Page(0, 0);

    @Test void searchesRenderedTextAcrossInlineMarkupAndEscapes() throws Exception {
        var chapters = List.of(chapter("<p><a title='секрет'>КНИ<b>ГА</b></a>&nbsp;&amp; <i>текст</i></p><!-- секрет -->"));
        var phrase = ReaderTextSearch.query(" книга   & текст ");
        assertEquals(new ReaderTextSearch.Match(FIRST, 0, false),
                ReaderTextSearch.find(chapters, SCROLL, phrase, FIRST, -1, FORWARD));
        assertNull(ReaderTextSearch.find(chapters, SCROLL, ReaderTextSearch.query("секрет"), FIRST, -1, FORWARD));
        assertNull(ReaderTextSearch.find(chapters, SCROLL, ReaderTextSearch.query("amp"), FIRST, -1, FORWARD));
        assertNull(ReaderTextSearch.find(chapters, SCROLL, ReaderTextSearch.query(".*"), FIRST, -1, FORWARD));
    }

    @Test void traversesCurrentAndAdjacentPagesInBothDirectionsAndWraps() throws Exception {
        String filler = "а".repeat(390);
        var chapters = List.of(chapter("<p>слово слово " + filler + "</p><p>слово " + filler + "</p>"),
                chapter("<p>ещё слово</p>"));
        var query = ReaderTextSearch.query("слово");
        var secondPage = new ReaderTextSearch.Page(0, 1);
        var nextChapter = new ReaderTextSearch.Page(1, 0);
        assertEquals(new ReaderTextSearch.Match(FIRST, 0, false),
                ReaderTextSearch.find(chapters, PAGED, query, FIRST, -1, FORWARD));
        assertEquals(new ReaderTextSearch.Match(FIRST, 1, false),
                ReaderTextSearch.find(chapters, PAGED, query, FIRST, 0, FORWARD));
        assertEquals(new ReaderTextSearch.Match(secondPage, 0, false),
                ReaderTextSearch.find(chapters, PAGED, query, FIRST, 1, FORWARD));
        assertEquals(new ReaderTextSearch.Match(nextChapter, 0, false),
                ReaderTextSearch.find(chapters, PAGED, query, secondPage, 0, FORWARD));
        assertEquals(new ReaderTextSearch.Match(FIRST, 0, true),
                ReaderTextSearch.find(chapters, PAGED, query, nextChapter, 0, FORWARD));
        assertEquals(new ReaderTextSearch.Match(nextChapter, 0, true),
                ReaderTextSearch.find(chapters, PAGED, query, FIRST, 0, BACKWARD));
        assertEquals(new ReaderTextSearch.Match(FIRST, 1, false),
                ReaderTextSearch.find(chapters, PAGED, query, secondPage, 0, BACKWARD));

        var spread = new ReaderTextSearch.Layout(ReaderSettings.ReadingMode.PAGED, 600, true);
        for (var layout : List.of(SCROLL, spread)) {
            assertEquals(new ReaderTextSearch.Match(FIRST, 2, false),
                    ReaderTextSearch.find(chapters, layout, query, FIRST, 1, FORWARD));
        }
    }

    @Test void mapsCollapsedWhitespaceBackToExactDocumentOffsets() throws Exception {
        String text = "\n\nкнига\n\u00a0  & текст\nКнига & ТЕКСТ";
        var document = new PlainDocument();
        document.insertString(0, text, null);
        var query = ReaderTextSearch.query("Книга & текст");
        var first = ReaderTextSearch.locate(document, query, 0);
        var second = ReaderTextSearch.locate(document, query, 1);
        assertEquals("книга\n\u00a0  & текст", text.substring(first.start(), first.end()));
        assertEquals("Книга & ТЕКСТ", text.substring(second.start(), second.end()));
        assertNull(ReaderTextSearch.locate(document, query, 2));
    }

    @Test void paginationNeverReturnsAnotherChaptersContentOnHashCollision() throws Exception {
        var first = chapter("<p>Aa</p>");
        var second = chapter("<p>BB</p>");
        assertEquals(first.getHtmlContent().hashCode(), second.getHtmlContent().hashCode());
        assertTrue(PAGED.pages(first).get(0).contains("Aa"));
        assertEquals(new ReaderTextSearch.Match(FIRST, 0, false),
                ReaderTextSearch.find(List.of(second), PAGED, ReaderTextSearch.query("BB"), FIRST, -1, FORWARD));
    }

    private static Chapter chapter(String html) {
        return new Chapter(0, "Глава", html, "");
    }
}
