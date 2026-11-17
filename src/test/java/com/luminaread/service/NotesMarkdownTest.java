package com.luminaread.service;

import com.luminaread.model.Bookmark;
import com.luminaread.model.Highlight;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NotesMarkdownTest {
    @Test void rendersAnnotationsAsTextWithTheirLineBreaks() {
        var highlight = new Highlight("book", "# Title\r\n## injected", 0, "*Chapter*",
                "First\r\n- second\r\n\r\n<img src=\"https://example.test/pixel\">",
                "[link](javascript:alert(1))\n&copy;", "#fc0");
        var bookmark = new Bookmark("book", "# Book", 0, "_chapter_", 0, 0,
                "One\r\n*not a separate item*", "");

        String result = NotesMarkdown.render(List.of(highlight), List.of(bookmark));
        assertTrue(result.contains("### \\# Title \\#\\# injected — \\*Chapter\\*"));
        assertTrue(result.contains("> First\\\n> \\- second\n>\n> &lt;img"));
        assertTrue(result.contains("> \\[link\\]\\(javascript\\:alert\\(1\\)\\)\\\n> &amp;copy;"));
        assertTrue(result.contains("One \\*not a separate item\\*"));
        assertFalse(result.contains("\n## injected"));
        assertFalse(result.contains("<img"));
        assertFalse(result.contains("\r"));
    }
}
