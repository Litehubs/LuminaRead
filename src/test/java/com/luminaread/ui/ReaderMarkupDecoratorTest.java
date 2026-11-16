package com.luminaread.ui;

import com.luminaread.model.Highlight;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ReaderMarkupDecoratorTest {
    @Test void searchDoesNotAlterAttributesCommentsOrEntities() {
        String html = "<a title='x > book' href='https://example.com/book'>Book &amp; book</a><!-- book -->";
        String result = ReaderMarkupDecorator.decorate(html, List.of(), 0, "book");
        assertTrue(result.contains("href='https://example.com/book'"));
        assertTrue(result.contains("<!-- book -->"));
        assertTrue(result.contains(">Book</b></span> &amp; <span"));
        assertEquals("<p>&amp;</p>", ReaderMarkupDecorator.decorate("<p>&amp;</p>", List.of(), 0, "amp"));
    }

    @Test void searchHandlesUnicodeAndEscapedText() {
        String result = ReaderMarkupDecorator.decorate("<p>КНИГА &amp; книга</p>", List.of(), 0, "книга");
        assertTrue(result.contains(">КНИГА</b></span>"));
        assertTrue(result.contains(">книга</b></span>"));
        assertTrue(ReaderMarkupDecorator.decorate("<p>A &amp; B</p>", List.of(), 0, "A & B")
                .contains(">A &amp; B</b></span>"));
    }

    @Test void highlightsStayInTheirChapterAndInvalidColorsAreRejected() {
        Highlight item = new Highlight("book", "title", 2, "chapter", "sample", "", "red;display:none");
        assertEquals("<p>sample</p>", ReaderMarkupDecorator.decorate("<p>sample</p>", List.of(item), 1, null));
        String result = ReaderMarkupDecorator.decorate("<p>sample</p>", List.of(item), 2, null);
        assertTrue(result.contains("background-color:#FDE047"));
        assertFalse(result.contains("display:none"));
    }
}
