package com.luminaread.ui;

import org.junit.jupiter.api.Test;
import javax.swing.JEditorPane;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import java.awt.Color;
import static org.junit.jupiter.api.Assertions.*;

class RanobeReaderRendererTest {
    @Test void rendersChapterWithSelectedColorsAndRemovesInlineBlackText() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JEditorPane editor = new JEditorPane();
            JScrollPane scroll = new JScrollPane(editor);
            RanobeReaderRenderer.render(editor, scroll, "<p style='color:black'>Chapter text</p>",
                    Color.BLACK, Color.WHITE, Color.GREEN, "Georgia", 19, 1.6, 840, false);
            try {
                var document = editor.getDocument();
                assertTrue(document.getText(0, document.getLength()).contains("Chapter text"));
            } catch (javax.swing.text.BadLocationException ex) { throw new AssertionError(ex); }
            assertEquals(Color.BLACK, editor.getBackground());
            assertEquals(Color.WHITE, editor.getForeground());
            assertFalse(editor.getText().contains("color:black"));
        });
    }
}
