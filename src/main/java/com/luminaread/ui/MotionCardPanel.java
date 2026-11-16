package com.luminaread.ui;

import javax.swing.JPanel;
import javax.swing.Timer;
import java.awt.AlphaComposite;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.Map;

final class MotionCardPanel extends JPanel {
    private static final long DURATION_NANOS = 180_000_000L;
    private static final int FRAME_INTERVAL_MS = 16;
    private static final int SLIDE_PIXELS = 12;
    private static final long MAX_SNAPSHOT_PIXELS = 2_500_000;

    private final CardLayout cards = new CardLayout();
    private final Map<String, Integer> cardOrder = new LinkedHashMap<>();
    private final Timer timer = new Timer(FRAME_INTERVAL_MS, event -> advanceTransition());
    private String currentCard;
    private boolean animationsEnabled;
    private BufferedImage outgoing;
    private long startedAt;
    private int direction;

    MotionCardPanel() {
        setLayout(cards);
    }

    void addCard(String key, Component component) {
        if (cardOrder.putIfAbsent(key, cardOrder.size()) != null) {
            throw new IllegalArgumentException("Duplicate card: " + key);
        }
        add(component, key);
        if (currentCard == null) {
            currentCard = key;
        }
    }

    void showCard(String key) {
        Integer targetIndex = cardOrder.get(key);
        if (targetIndex == null || key.equals(currentCard)) {
            return;
        }
        cancelTransition();
        int width = getWidth();
        int height = getHeight();
        if (animationsEnabled && isShowing() && width > 0 && height > 0
                && (long) width * height <= MAX_SNAPSHOT_PIXELS) {
            outgoing = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = outgoing.createGraphics();
            try {
                printAll(graphics);
            } finally {
                graphics.dispose();
            }
            direction = Integer.compare(targetIndex, cardOrder.get(currentCard));
        }
        cards.show(this, key);
        currentCard = key;
        if (outgoing != null) {
            startedAt = System.nanoTime();
            timer.start();
        }
        repaint();
    }

    void setAnimationsEnabled(boolean enabled) {
        animationsEnabled = enabled;
        if (!enabled) {
            cancelTransition();
        }
    }

    void cancelTransition() {
        timer.stop();
        if (outgoing != null) {
            outgoing.flush();
            outgoing = null;
            repaint();
        }
    }

    private void advanceTransition() {
        if (outgoing == null || !isShowing() || getWidth() != outgoing.getWidth() || getHeight() != outgoing.getHeight()
                || System.nanoTime() - startedAt >= DURATION_NANOS) {
            cancelTransition();
        } else {
            repaint();
        }
    }

    @Override
    protected void paintChildren(Graphics graphics) {
        super.paintChildren(graphics);
        if (outgoing == null || isPaintingForPrint()
                || getWidth() != outgoing.getWidth() || getHeight() != outgoing.getHeight()) {
            return;
        }
        double progress = Math.min(1.0, (System.nanoTime() - startedAt) / (double) DURATION_NANOS);
        double remaining = (1 - progress) * (1 - progress) * (1 - progress);
        int offset = (int) Math.round(-direction * SLIDE_PIXELS * (1 - remaining));
        Graphics2D overlay = (Graphics2D) graphics.create();
        try {
            overlay.setComposite(AlphaComposite.SrcOver.derive((float) (0.9 * remaining)));
            overlay.drawImage(outgoing, 0, offset, null);
        } finally {
            overlay.dispose();
        }
    }

    @Override
    public void removeNotify() {
        cancelTransition();
        super.removeNotify();
    }
}
