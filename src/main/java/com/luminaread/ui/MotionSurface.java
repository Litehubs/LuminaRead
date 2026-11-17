package com.luminaread.ui;

import javax.swing.JPanel;
import javax.swing.Timer;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/** Paints a short-lived image of the old view over the live, interactive Swing components. */
class MotionSurface extends JPanel {
    enum Effect {
        SLIDE(180), OPEN(220), CLOSE(200), PAGE(190), DISSOLVE(180);

        final long durationNanos;

        Effect(long durationMillis) {
            durationNanos = durationMillis * 1_000_000;
        }
    }

    private static final boolean TRACE = Boolean.getBoolean("luminaread.motion.trace");
    private static final long MAX_SNAPSHOT_PIXELS = 2_500_000;
    private static final int FRAME_INTERVAL_MS = 16;
    private static final int SLIDE_DISTANCE = 18;
    private static final int PAGE_SHADOW_WIDTH = 22;

    private final Timer timer = new Timer(FRAME_INTERVAL_MS, e -> advanceTransition());
    private boolean animationsEnabled;
    private Frame frame;

    private record Frame(BufferedImage image, int width, int height, Effect effect, int direction, long startedAt) {}

    void change(Effect effect, int direction, Runnable update) {
        cancelTransition();
        int width = getWidth();
        int height = getHeight();
        if (!animationsEnabled || !isShowing() || width <= 0 || height <= 0) {
            update.run();
            return;
        }

        long started = TRACE ? System.nanoTime() : 0;
        BufferedImage snapshot = capture(width, height);
        if (TRACE) logTiming("snapshot", effect, started);
        try {
            update.run();
            if (frame == null && animationsEnabled && isShowing() && sameSize(width, height)) {
                frame = new Frame(snapshot, width, height, effect, direction, System.nanoTime());
                timer.restart();
                repaint();
                return;
            }
        } finally {
            if (frame == null || frame.image() != snapshot) snapshot.flush();
            if (TRACE) logTiming("transition", effect, started);
        }
    }

    private BufferedImage capture(int width, int height) {
        double scale = Math.min(1, Math.sqrt(MAX_SNAPSHOT_PIXELS / ((double) width * height)));
        int imageWidth = Math.max(1, (int) (width * scale));
        int imageHeight = Math.max(1, (int) (height * scale));
        BufferedImage image = new BufferedImage(imageWidth, imageHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.scale(imageWidth / (double) width, imageHeight / (double) height);
            printAll(graphics);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    void setAnimationsEnabled(boolean enabled) {
        animationsEnabled = enabled;
        if (!enabled) cancelTransition();
    }

    void cancelTransition() {
        timer.stop();
        if (frame != null) {
            frame.image().flush();
            frame = null;
            repaint();
        }
    }

    private boolean sameSize(int width, int height) {
        return getWidth() == width && getHeight() == height;
    }

    private void advanceTransition() {
        if (frame == null || !isShowing() || !sameSize(frame.width(), frame.height())
                || elapsed() >= frame.effect().durationNanos) {
            cancelTransition();
        } else {
            repaint();
        }
    }

    private long elapsed() {
        return System.nanoTime() - frame.startedAt();
    }

    @Override
    protected boolean isPaintingOrigin() {
        return frame != null;
    }

    @Override
    protected void paintChildren(Graphics graphics) {
        long started = TRACE ? System.nanoTime() : 0;
        super.paintChildren(graphics);
        if (frame == null || isPaintingForPrint() || !sameSize(frame.width(), frame.height())) return;

        double time = Math.min(1, elapsed() / (double) frame.effect().durationNanos);
        double progress = time * time * (3 - 2 * time);
        Graphics2D overlay = (Graphics2D) graphics.create();
        try {
            overlay.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            paintFrame(overlay, frame.effect(), frame.image(), frame.width(), frame.height(), frame.direction(), progress);
        } finally {
            overlay.dispose();
            if (TRACE) logTiming("paint", frame.effect(), started);
        }
    }

    private static void logTiming(String step, Effect effect, long started) {
        System.out.println("motion " + step + " " + effect + " " + (System.nanoTime() - started));
    }

    // The previous view is composited over the destination. There are no live component transforms.
    static void paintFrame(Graphics2D g, Effect effect, BufferedImage image, int width, int height,
                           int direction, double progress) {
        if (progress >= 1) return;
        int x = 0;
        int y = 0;
        int drawWidth = width;
        int drawHeight = height;
        if (effect == Effect.PAGE) {
            int revealed = (int) Math.round(progress * width);
            int edge = direction > 0 ? width - revealed : revealed;
            g.clipRect(direction > 0 ? 0 : edge, 0, width - revealed, height);
            g.drawImage(image, 0, 0, width, height, null);
            int shadow = Math.min(PAGE_SHADOW_WIDTH, width - revealed);
            if (shadow == 0) return;
            int fadeEnd = edge - direction * shadow;
            g.setPaint(new GradientPaint(edge, 0, new Color(0, 0, 0, (int) (64 * Math.sin(Math.PI * progress))),
                    fadeEnd, 0, new Color(0, 0, 0, 0)));
            g.fillRect(Math.min(edge, fadeEnd), 0, shadow, height);
            return;
        }

        if (effect == Effect.SLIDE) {
            x = (int) Math.round(-direction * SLIDE_DISTANCE * progress);
        } else if (effect == Effect.OPEN || effect == Effect.CLOSE) {
            double scale = 1 + (effect == Effect.OPEN ? 0.07 : -0.07) * progress;
            drawWidth = (int) Math.round(width * scale);
            drawHeight = (int) Math.round(height * scale);
            x = (width - drawWidth) / 2;
            y = (height - drawHeight) / 2;
        }
        g.setComposite(AlphaComposite.SrcOver.derive((float) (1 - progress)));
        g.drawImage(image, x, y, drawWidth, drawHeight, null);
    }

    @Override
    public void removeNotify() {
        cancelTransition();
        super.removeNotify();
    }
}
