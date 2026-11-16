package com.luminaread.ui;

import org.junit.jupiter.api.Test;

import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.*;

class MotionSurfaceTest {
    @Test
    void pageTurnRevealsOppositeEdgesInEachDirection() {
        var forward = frameAt(MotionSurface.Effect.PAGE, 1, 0.5);
        var backward = frameAt(MotionSurface.Effect.PAGE, -1, 0.5);

        assertEquals(Color.RED.getRGB(), forward.getRGB(4, 32));
        assertEquals(Color.BLUE.getRGB(), forward.getRGB(60, 32));
        assertEquals(Color.BLUE.getRGB(), backward.getRGB(4, 32));
        assertEquals(Color.RED.getRGB(), backward.getRGB(60, 32));
        assertNotEquals(Color.RED.getRGB(), forward.getRGB(31, 32));
    }

    @Test
    void openingClosingAndThemeChangesRevealTheLiveView() {
        for (var effect : new MotionSurface.Effect[]{
                MotionSurface.Effect.OPEN, MotionSurface.Effect.CLOSE, MotionSurface.Effect.DISSOLVE}) {
            assertEquals(Color.RED.getRGB(), frameAt(effect, 1, 0).getRGB(32, 32));
            int mid = frameAt(effect, 1, 0.5).getRGB(32, 32);
            assertNotEquals(Color.RED.getRGB(), mid);
            assertNotEquals(Color.BLUE.getRGB(), mid);
            assertEquals(Color.BLUE.getRGB(), frameAt(effect, 1, 1).getRGB(32, 32));
        }
    }

    @Test
    void animatesAcrossSidebarResizeAndDiscardsSnapshotOnWindowResize() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            MotionSurface workspace = new MotionSurface();
            workspace.setLayout(new BorderLayout());
            MotionCardPanel deck = new MotionCardPanel("reader");
            deck.useTransitionSurface(workspace);
            JPanel sidebar = new JPanel();
            sidebar.setBackground(Color.RED);
            sidebar.setPreferredSize(new java.awt.Dimension(24, 64));
            JPanel catalog = new JPanel();
            catalog.setBackground(Color.RED);
            JPanel reader = new JPanel();
            reader.setBackground(Color.BLUE);
            deck.addCard("catalog", catalog);
            deck.addCard("reader", reader);
            workspace.add(deck, BorderLayout.CENTER);
            workspace.add(sidebar, BorderLayout.WEST);
            workspace.setSize(64, 64);
            workspace.addNotify();
            try {
                workspace.doLayout();
                deck.doLayout();
                workspace.setAnimationsEnabled(true);
                deck.showCard("reader", () -> sidebar.setVisible(false));
                workspace.doLayout();
                deck.doLayout();

                assertFalse(sidebar.isVisible());
                assertTrue(reader.isVisible());
                assertEquals(64, reader.getWidth());
                assertNotEquals(Color.BLUE.getRGB(), paintLive(workspace));

                workspace.setSize(72, 64);
                workspace.doLayout();
                deck.doLayout();
                assertEquals(Color.BLUE.getRGB(), paintLive(workspace));
            } finally {
                workspace.removeNotify();
            }
        });
    }

    private static BufferedImage frameAt(MotionSurface.Effect effect, int direction, double progress) {
        var image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        var outgoing = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        var previous = outgoing.createGraphics();
        try {
            graphics.setColor(Color.BLUE);
            graphics.fillRect(0, 0, 64, 64);
            previous.setColor(Color.RED);
            previous.fillRect(0, 0, 64, 64);
            MotionSurface.paintFrame(graphics, effect, outgoing, 64, 64, direction, progress);
            return image;
        } finally {
            previous.dispose();
            graphics.dispose();
            outgoing.flush();
        }
    }

    private static int paintLive(MotionSurface workspace) {
        var image = new BufferedImage(workspace.getWidth(), workspace.getHeight(), BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try {
            workspace.paint(graphics);
            return image.getRGB(32, 32);
        } finally {
            graphics.dispose();
            image.flush();
        }
    }
}
