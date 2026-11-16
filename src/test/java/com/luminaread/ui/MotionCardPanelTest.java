package com.luminaread.ui;

import org.junit.jupiter.api.Test;

import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.*;

class MotionCardPanelTest {
    @Test
    void switchesImmediatelyAndDropsOldFramesWhenMotionIsDisabled() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            MotionCardPanel deck = new MotionCardPanel("book");
            JPanel library = card(Color.RED);
            JPanel book = card(Color.GREEN);
            JPanel catalog = card(Color.BLUE);
            deck.addCard("library", library);
            deck.addCard("book", book);
            deck.addCard("catalog", catalog);
            deck.setSize(64, 64);
            deck.addNotify();
            try {
                deck.doLayout();
                deck.setAnimationsEnabled(true);
                deck.showCard("book");
                assertFalse(library.isVisible());
                assertTrue(book.isVisible());
                assertNotEquals(Color.GREEN.getRGB(), paintedColor(deck));

                deck.showCard("catalog");
                assertFalse(book.isVisible());
                assertTrue(catalog.isVisible());
                deck.setAnimationsEnabled(false);
                assertEquals(Color.BLUE.getRGB(), paintedColor(deck));

                deck.showCard("library");
                deck.showCard("unknown");
                assertTrue(library.isVisible());
                assertEquals(Color.RED.getRGB(), paintedColor(deck));
            } finally {
                deck.removeNotify();
            }
        });
    }

    private static JPanel card(Color color) {
        JPanel card = new JPanel();
        card.setBackground(color);
        return card;
    }

    private static int paintedColor(MotionCardPanel deck) {
        BufferedImage frame = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        var graphics = frame.createGraphics();
        try {
            deck.paint(graphics);
            return frame.getRGB(32, 32);
        } finally {
            graphics.dispose();
            frame.flush();
        }
    }
}
