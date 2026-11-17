package com.luminaread.parser;

import com.luminaread.model.Chapter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PaginationIntegrityTest {
    @Test void splitsAStyledParagraphWithoutDroppingAnImageOrDuplicatingAnchors() {
        String text = "Глава &amp; примечание. ".repeat(90);
        String html = "<div id='chapter'><p><em title='a > b'>" + text + "<img src='book.png'>"
                + "Окончание.</em></p><p><a href='#note'>Сноска</a></p><p id='note'>Примечание.</p></div>";
        var pages = paginate(html, false);
        assertTrue(pages.size() > 2);
        assertTrue(pages.stream().allMatch(page -> page.startsWith("<div") && page.endsWith("</div>")));
        String all = String.join("", pages);
        assertEquals(1, count(all, "id='chapter'"));
        assertEquals(1, count(all, "src='book.png'"));
        assertEquals(90, count(all, "&amp;"));
        assertTrue(all.indexOf("src='book.png'") < all.indexOf("Окончание."));
        assertTrue(all.contains("href='#note'"));
        assertTrue(all.contains("id='note'"));
        assertTrue(pages.stream().filter(page -> page.contains("Глава")).allMatch(page -> page.contains("<em title='a > b'>")));
    }

    @Test void keepsWholeTablesAndListsInSpreads() {
        String table = "<table><tr><td rowspan='2'>" + "Текст ".repeat(160) + "</td><td>Ячейка</td></tr>"
                + "<tr><td>Последняя строка</td></tr></table>";
        String list = "<ol start='4'><li>" + "Пункт ".repeat(120) + "</li><li>Хвост</li></ol>";
        var spreads = paginate("<p>До таблицы</p>" + table + list + "<p>После списка</p>", true);
        assertTrue(spreads.stream().anyMatch(page -> page.contains(table)));
        assertTrue(spreads.stream().anyMatch(page -> page.contains(list)));
        assertTrue(String.join("", spreads).contains("После списка"));
    }

    @Test void splitsLongTextWithoutPunctuationAndKeepsUnicodePairs() {
        String raw = "㊗𐐀".repeat(655);
        var pages = paginate(raw, false);
        assertTrue(pages.size() > 1);
        assertEquals(raw, String.join("", pages));
        assertTrue(pages.stream().noneMatch(page -> Character.isHighSurrogate(page.charAt(page.length() - 1))));
    }

    private static List<String> paginate(String html, boolean spread) {
        return PaginationEngine.paginateChapter(new Chapter(0, "Chapter", html, ""), 600, spread);
    }

    private static long count(String text, String part) {
        return java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(part)).matcher(text).results().count();
    }
}
