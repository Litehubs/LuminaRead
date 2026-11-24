package com.luminaread.ui;

import com.luminaread.model.Book;
import com.luminaread.model.BookContent;
import com.luminaread.model.Chapter;
import com.luminaread.model.Highlight;
import com.luminaread.service.LibraryStorageService;
import com.luminaread.service.TtsService;
import org.junit.jupiter.api.Test;

import javax.swing.JEditorPane;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

class ReaderQuoteSaveTest {
    @Test void markerAppearsAfterTheWriteAndOnlyCoversTheRequestedOccurrence() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var book = new Book("Test", "Author", "dummy", "TXT");
        var content = new BookContent();
        content.addChapter(new Chapter(0, "Chapter", "<p>Hello <em>world</em>!</p><p>Hello world!</p>", ""));
        var quote = new Highlight(book.getId(), "Test", 0, "Chapter", "Hello world!", "", "#abc");
        quote.setTextRange(0, 11);
        var storage = new LibraryStorageService() {
            private volatile boolean saved;

            @Override public BookContent loadBookContent(Book ignored) { return content; }

            @Override public List<Highlight> getHighlights(String id) { return saved ? List.of(quote) : List.of(); }

            @Override public void addHighlight(Highlight ignored) {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Quote write was not released");
                    saved = true;
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(ex);
                }
            }
        };
        var holder = new ReaderPanel[1];
        var editor = new JEditorPane[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                holder[0] = new ReaderPanel(storage, new TtsService(), text -> "", () -> {}, theme -> {}) {
                    @Override public boolean isShowing() { return true; }
                };
                editor[0] = editorIn(holder[0]);
                holder[0].openBook(book);
            });
            await(() -> editor[0].getText().contains("world"));
            SwingUtilities.invokeAndWait(() -> holder[0].saveQuote(quote, content));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> assertEquals(0, editor[0].getHighlighter().getHighlights().length));
            release.countDown();
            await(() -> editor[0].getHighlighter().getHighlights().length == 1);
            SwingUtilities.invokeAndWait(() -> {
                var marker = editor[0].getHighlighter().getHighlights()[0];
                var doc = editor[0].getDocument();
                try {
                    assertEquals("Hello world!", doc.getText(marker.getStartOffset(), marker.getEndOffset() - marker.getStartOffset()));
                    assertEquals(doc.getText(0, doc.getLength()).indexOf("Hello world!"), marker.getStartOffset());
                } catch (javax.swing.text.BadLocationException ex) {
                    throw new AssertionError(ex);
                }
            });
        } finally {
            release.countDown();
            if (holder[0] != null) SwingUtilities.invokeAndWait(holder[0]::disposeResources);
        }
    }

    private static JEditorPane editorIn(Container root) {
        for (Component child : root.getComponents()) {
            if (child instanceof JEditorPane editor) return editor;
            if (child instanceof Container nested) {
                var found = editorIn(nested);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        do {
            boolean[] met = {false};
            SwingUtilities.invokeAndWait(() -> met[0] = condition.getAsBoolean());
            if (met[0]) return;
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        fail("Reader state was not updated");
    }
}
