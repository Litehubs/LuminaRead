package com.luminaread.ui;

import com.luminaread.model.Highlight;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReaderMarkupDecoratorTest {
    @Test void savedHighlightsDoNotAlterAttributesCommentsOrEntities() {
        Highlight item = new Highlight("book", "title", 0, "chapter", "book", "", "#FDE047");
        String html = "<a title='x > book' href='https://example.com/book'>Book &amp; book</a><!-- book -->";
        String result = ReaderMarkupDecorator.decorate(html, List.of(item), 0);
        assertTrue(result.contains("href='https://example.com/book'"));
        assertTrue(result.contains("<!-- book -->"));
        assertTrue(result.contains(">Book &amp; <span"));
    }

    @Test void highlightsStayInTheirChapterAndInvalidColorsAreRejected() {
        Highlight item = new Highlight("book", "title", 2, "chapter", "sample", "", "red;display:none");
        assertEquals("<p>sample</p>", ReaderMarkupDecorator.decorate("<p>sample</p>", List.of(item), 1));
        String result = ReaderMarkupDecorator.decorate("<p>sample</p>", List.of(item), 2);
        assertTrue(result.contains("background-color:#FDE047"));
        assertFalse(result.contains("display:none"));
    }
}
