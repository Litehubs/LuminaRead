package com.luminaread.ui;

import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.awt.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class CatalogCardTest {
    @Test void opensSelectedItemAndPassesCoverToLoader() throws Exception {
        AtomicBoolean opened = new AtomicBoolean();
        JLabel[] cover = new JLabel[1];
        SwingUtilities.invokeAndWait(() -> {
            JPanel card = CatalogCard.create("Title", "Source", "A long description", 6,
                    Color.GREEN, "Open", Color.BLUE, label -> cover[0] = label, () -> opened.set(true));
            assertNotNull(cover[0]);
            assertEquals("A long...", find(card, JTextArea.class).getText());
            find(card, JButton.class).doClick();
        });
        assertTrue(opened.get());
    }

    @Test void catalogMetadataIsDisplayedAsPlainText() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            String markup = "<html><b>Remote title</b>";
            JPanel card = CatalogCard.create(markup, "Source", "", 6, Color.GREEN, "Open",
                    Color.BLUE, label -> {}, () -> {});
            JLabel title = findLabel(card, markup);
            assertNotNull(title);
            assertNull(title.getClientProperty(javax.swing.plaf.basic.BasicHTML.propertyKey));
        });
    }

    @Test void removingSavedEntryPersistsItAndRestoresListOnDiskFailure() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var history = new java.util.LinkedHashMap<String, String>();
            history.put("first", "First");
            history.put("second", "Second");
            var saved = new java.util.LinkedHashMap<String, String>();
            var refreshed = new AtomicBoolean();
            JPanel card = CatalogCard.create("First", "Source", "", 6, Color.GREEN, "Open",
                    Color.BLUE, label -> {}, () -> {}, "Убрать из истории", () ->
                            CatalogListControls.edit(new JPanel(), history, () -> history.remove("first"),
                                    () -> saved.putAll(history), () -> refreshed.set(true)));

            JButton remove = MangaDetailsRaceTest.Probe.findButton(card, "Убрать из истории");
            assertNotNull(remove);
            remove.doClick();
            assertEquals(java.util.Map.of("second", "Second"), history);
            assertEquals(history, saved);
            assertTrue(refreshed.get());

            CatalogListControls.edit(card, history, history::clear, () -> {
                throw new java.io.UncheckedIOException(new java.io.IOException("simulated write failure"));
            }, () -> fail("A failed save must not refresh the list"));
            assertEquals(saved, history);
        });
    }

    private static JLabel findLabel(Container root, String text) {
        for (Component child : root.getComponents()) {
            if (child instanceof JLabel label && text.equals(label.getText())) return label;
            if (child instanceof Container nested) {
                JLabel match = findLabel(nested, text);
                if (match != null) return match;
            }
        }
        return null;
    }

    private static <T extends Component> T find(Container root, Class<T> type) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child)) return type.cast(child);
            if (child instanceof Container nested) {
                T found = find(nested, type);
                if (found != null) return found;
            }
        }
        return null;
    }
}
