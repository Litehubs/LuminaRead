package com.luminaread.ui;

import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.Timer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/** Owns the timer and movement policy used by the readers. Runs on the Swing event thread. */
final class AutoScrollController {
    private final JScrollPane pane;
    private final IntSupplier speed;
    private final int delayMillis;
    private final boolean waitForScrollableContent;
    private final IntConsumer positionChanged;
    private final Runnable reachedEnd;
    private Timer timer;
    private double fractionalPixels;

    AutoScrollController(JScrollPane pane, IntSupplier speed, int delayMillis,
                         boolean waitForScrollableContent, IntConsumer positionChanged, Runnable reachedEnd) {
        this.pane = java.util.Objects.requireNonNull(pane);
        this.speed = java.util.Objects.requireNonNull(speed);
        if (delayMillis <= 0) throw new IllegalArgumentException("delayMillis must be positive");
        this.delayMillis = delayMillis;
        this.waitForScrollableContent = waitForScrollableContent;
        this.positionChanged = java.util.Objects.requireNonNull(positionChanged);
        this.reachedEnd = java.util.Objects.requireNonNull(reachedEnd);
    }

    void start() {
        stop();
        fractionalPixels = 0;
        timer = new Timer(delayMillis, event -> tick());
        timer.start();
    }

    void stop() {
        if (timer != null) { timer.stop(); timer = null; }
    }

    boolean isRunning() { return timer != null && timer.isRunning(); }

    void tick() {
        JScrollBar bar = pane.getVerticalScrollBar();
        int max = Math.max(0, bar.getMaximum() - bar.getVisibleAmount());
        if (max == 0 && waitForScrollableContent) return;
        if (bar.getValue() >= max - 2) {
            stop();
            reachedEnd.run();
            return;
        }
        fractionalPixels += Math.max(0, speed.getAsInt()) * delayMillis / 1000.0;
        int step = (int) fractionalPixels;
        if (step > 0) {
            fractionalPixels -= step;
            int next = Math.min(max, bar.getValue() + step);
            bar.setValue(next);
            positionChanged.accept(next);
        }
    }
}
