package com.luminaread.ui;

import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.KeyboardFocusManager;
import java.awt.RenderingHints;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

final class BookCard extends JPanel {
    private final Color focusColor;
    private final Color hoverColor;
    private boolean hovered;
    private float arrival;

    BookCard(int gap, Color focusColor, Color hoverColor) {
        super(new BorderLayout(gap, 0));
        this.focusColor = focusColor;
        this.hoverColor = hoverColor;
    }

    void trackInteraction() {
        MouseAdapter mouse = new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent event) { hover(true); }
            @Override public void mouseExited(MouseEvent event) {
                hover(contains(SwingUtilities.convertPoint(event.getComponent(), event.getPoint(), BookCard.this)));
            }
        };
        FocusAdapter focus = new FocusAdapter() {
            @Override public void focusGained(FocusEvent event) { repaint(); }
            @Override public void focusLost(FocusEvent event) { repaint(); }
        };
        listen(this, mouse, focus);
    }

    private void listen(Component component, MouseAdapter mouse, FocusAdapter focus) {
        component.addMouseListener(mouse);
        if (component.isFocusable()) component.addFocusListener(focus);
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) listen(child, mouse, focus);
        }
    }

    private void hover(boolean value) {
        if (hovered == value) return;
        hovered = value;
        repaint();
    }

    void setArrival(float strength) {
        arrival = strength;
        if (isShowing()) repaint();
    }

    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        if (arrival <= 0 || isPaintingForPrint()) return;
        graphics.setColor(new Color(hoverColor.getRed(), hoverColor.getGreen(), hoverColor.getBlue(),
                Math.round(32 * arrival)));
        graphics.fillRoundRect(1, 1, getWidth() - 2, getHeight() - 2, 10, 10);
    }

    @Override protected void paintBorder(Graphics graphics) {
        super.paintBorder(graphics);
        var focus = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        boolean focused = focus != null && SwingUtilities.isDescendingFrom(focus, this);
        if ((!hovered && !focused) || isPaintingForPrint()) return;
        Graphics2D outline = (Graphics2D) graphics.create();
        try {
            outline.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            outline.setColor(focused ? focusColor : hoverColor);
            outline.drawRoundRect(1, 1, getWidth() - 3, getHeight() - 3, 8, 8);
            if (focused) outline.drawRoundRect(2, 2, getWidth() - 5, getHeight() - 5, 7, 7);
        } finally {
            outline.dispose();
        }
    }
}
