package com.luminaread.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;

public class SmoothScrollUtil {

    private static final String SCROLLER_KEY = "LUMINA_SMOOTH_SCROLLER";

    public static JScrollPane enhanceAndReturn(JScrollPane sp) {
        enhance(sp);
        return sp;
    }

    public static void enhance(JScrollPane scrollPane) {
        if (scrollPane == null) return;
        scrollPane.getVerticalScrollBar().setUnitIncrement(36);
        scrollPane.getVerticalScrollBar().setBlockIncrement(180);
        scrollPane.getHorizontalScrollBar().setUnitIncrement(48);
        scrollPane.getHorizontalScrollBar().setBlockIncrement(240);

        SmoothScroller scroller = (SmoothScroller) scrollPane.getClientProperty(SCROLLER_KEY);
        if (scroller == null) {
            scroller = new SmoothScroller(scrollPane);
            scrollPane.putClientProperty(SCROLLER_KEY, scroller);
        }
    }

    public static void smoothScrollBy(JScrollPane sp, double delta) {
        if (sp == null) return;
        Object obj = sp.getClientProperty(SCROLLER_KEY);
        if (obj instanceof SmoothScroller) {
            ((SmoothScroller) obj).scrollBy(delta);
        } else {
            JScrollBar bar = sp.getVerticalScrollBar();
            if (bar != null) bar.setValue((int) Math.round(bar.getValue() + delta));
        }
    }

    public static void resetScroll(JScrollPane sp, int y) {
        if (sp == null) return;
        Object obj = sp.getClientProperty(SCROLLER_KEY);
        if (obj instanceof SmoothScroller) {
            ((SmoothScroller) obj).resetTo(y);
        } else {
            JScrollBar bar = sp.getVerticalScrollBar();
            if (bar != null) bar.setValue(y);
            if (sp.getViewport() != null) sp.getViewport().setViewPosition(new Point(0, y));
        }
    }

    public static void syncTarget(JScrollPane sp, int y) {
        if (sp == null) return;
        Object obj = sp.getClientProperty(SCROLLER_KEY);
        if (obj instanceof SmoothScroller) {
            ((SmoothScroller) obj).syncTarget(y);
        }
    }

    public static class SmoothScroller implements MouseWheelListener {
        private final JScrollPane scrollPane;
        private final Timer animTimer;
        private double targetY;
        private double currentY;
        private boolean isSelfAdjusting = false;

        public SmoothScroller(JScrollPane scrollPane) {
            this.scrollPane = scrollPane;
            JScrollBar vBar = scrollPane.getVerticalScrollBar();
            this.currentY = vBar.getValue();
            this.targetY = currentY;

            this.animTimer = new Timer(16, e -> onTick());
            this.animTimer.setCoalesce(true);

            vBar.addAdjustmentListener(e -> {
                if (!isSelfAdjusting) {
                    targetY = e.getValue();
                    currentY = targetY;
                }
            });

            scrollPane.setWheelScrollingEnabled(false);
            scrollPane.addMouseWheelListener(this);
        }

        @Override
        public void mouseWheelMoved(MouseWheelEvent e) {
            if (e.isShiftDown()) {

                JScrollBar hBar = scrollPane.getHorizontalScrollBar();
                if (hBar != null && hBar.isVisible()) {
                    double rot = e.getPreciseWheelRotation();
                    if (Math.abs(rot) < 0.001) rot = e.getWheelRotation();
                    int delta = (int) Math.round(rot * 55.0);
                    hBar.setValue(Math.max(hBar.getMinimum(), Math.min(hBar.getMaximum(), hBar.getValue() + delta)));
                }
                e.consume();
                return;
            }

            JScrollBar vBar = scrollPane.getVerticalScrollBar();
            if (vBar == null) return;
            int extent = scrollPane.getViewport().getHeight();
            int max = vBar.getMaximum() - extent;
            if (max <= 0) return;

            double rot = e.getPreciseWheelRotation();
            if (Math.abs(rot) < 0.001) rot = e.getWheelRotation();

            double factor = (Math.abs(rot) < 0.3) ? 42.0 : 62.0;
            double delta = rot * factor;

            targetY = Math.max(0, Math.min(max, targetY + delta));

            if (!animTimer.isRunning()) {
                currentY = vBar.getValue();
                animTimer.start();
            }
            e.consume();
        }

        private void onTick() {
            JScrollBar vBar = scrollPane.getVerticalScrollBar();
            if (vBar == null) {
                animTimer.stop();
                return;
            }
            int extent = scrollPane.getViewport().getHeight();
            int max = vBar.getMaximum() - extent;
            if (max <= 0) {
                animTimer.stop();
                return;
            }

            targetY = Math.max(0, Math.min(max, targetY));
            double diff = targetY - currentY;

            if (Math.abs(diff) < 0.75) {
                currentY = targetY;
                isSelfAdjusting = true;
                try {
                    vBar.setValue((int) Math.round(targetY));
                } finally {
                    isSelfAdjusting = false;
                }
                animTimer.stop();
            } else {

                currentY += diff * 0.24;
                isSelfAdjusting = true;
                try {
                    vBar.setValue((int) Math.round(currentY));
                } finally {
                    isSelfAdjusting = false;
                }
            }
        }

        public void scrollBy(double delta) {
            JScrollBar vBar = scrollPane.getVerticalScrollBar();
            if (vBar == null) return;
            int extent = scrollPane.getViewport().getHeight();
            int max = vBar.getMaximum() - extent;
            if (max <= 0) return;

            targetY = Math.max(0, Math.min(max, targetY + delta));
            if (!animTimer.isRunning()) {
                currentY = vBar.getValue();
                animTimer.start();
            }
        }

        public void resetTo(int y) {
            animTimer.stop();
            targetY = y;
            currentY = y;
            isSelfAdjusting = true;
            try {
                JScrollBar vBar = scrollPane.getVerticalScrollBar();
                if (vBar != null) vBar.setValue(y);
                if (scrollPane.getViewport() != null) {
                    scrollPane.getViewport().setViewPosition(new Point(0, y));
                }
            } finally {
                isSelfAdjusting = false;
            }
        }

        public void syncTarget(int y) {
            targetY = y;
            currentY = y;
        }
    }

    public static class ResponsiveListPanel extends JPanel implements Scrollable {
        public ResponsiveListPanel(LayoutManager layout) {
            super(layout);
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
            return 45;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
            return 220;
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }
}
