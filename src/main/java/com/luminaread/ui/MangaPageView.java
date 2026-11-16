package com.luminaread.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;

final class MangaPageView extends JPanel {
    private BufferedImage image;
    private final JLabel statusLabel;
    private int targetWidth;
    private int alignmentMode;
    private double dimmerOpacity;
    private int pageGap;
    private final boolean clickToTurn;
    private final Runnable onClickLeft;
    private final Runnable onClickRight;
    private final java.util.function.Consumer<MangaPageView> onRetry;

    MangaPageView(int pageNumber, int targetWidth, int alignmentMode, double dimmerOpacity,
                          int pageGap, boolean clickToTurn, Runnable onClickLeft, Runnable onClickRight,
                          java.util.function.Consumer<MangaPageView> onRetry) {
        this.targetWidth = targetWidth;
        this.alignmentMode = alignmentMode;
        this.dimmerOpacity = dimmerOpacity;
        this.pageGap = pageGap;
        this.clickToTurn = clickToTurn;
        this.onClickLeft = onClickLeft;
        this.onClickRight = onClickRight;
        this.onRetry = onRetry;

        setLayout(new BorderLayout());
        setOpaque(false);
        setAlignmentX(Component.CENTER_ALIGNMENT);

        statusLabel = new JLabel("Загрузка стр. " + pageNumber + "...", SwingConstants.CENTER);
        statusLabel.setForeground(new Color(150, 155, 175));
        statusLabel.setFont(new Font("SansSerif", Font.PLAIN, 13));
        setPreferredSize(new Dimension(targetWidth, 520));
        setMaximumSize(new Dimension(Integer.MAX_VALUE, 520));
        add(statusLabel, BorderLayout.CENTER);

        MouseAdapter clickHandler = new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (statusLabel.isVisible() && onRetry != null && e.getClickCount() == 1) {
                    statusLabel.setText("Повторная попытка стр. " + pageNumber + "...");
                    onRetry.accept(MangaPageView.this);
                    return;
                }
                if (clickToTurn) {
                    if (e.getX() < getWidth() / 3) {
                        if (onClickLeft != null) onClickLeft.run();
                    } else {
                        if (onClickRight != null) onClickRight.run();
                    }
                }
            }
        };
        addMouseListener(clickHandler);
        statusLabel.addMouseListener(clickHandler);
    }

    public void setImage(BufferedImage img, int width, int align, double dimmer, int gap) {
        this.image = img;
        this.targetWidth = width;
        this.alignmentMode = align;
        this.dimmerOpacity = dimmer;
        this.pageGap = gap;
        statusLabel.setVisible(false);
        if (img != null && img.getWidth() > 0) {
            int panelW = getWidth() > 0 ? getWidth() : width;
            int drawW = (alignmentMode == MangaPanel.ALIGN_FIT_WIDTH) ? panelW : Math.min(panelW, width);
            int h = (int) Math.round((double) img.getHeight() * drawW / img.getWidth());
            int totalH = h + gap;
            setPreferredSize(new Dimension(drawW, totalH));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, totalH));
        }
        revalidate();
        repaint();
    }

    public void setError(String msg) {
        this.image = null;
        statusLabel.setText("⚠️ " + msg + " (кликните для повтора)");
        statusLabel.setForeground(new Color(255, 138, 101));
        statusLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        statusLabel.setVisible(true);
        revalidate();
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        if (image != null && image.getWidth() > 0) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED);

            int panelW = getWidth();
            int drawW = (alignmentMode == MangaPanel.ALIGN_FIT_WIDTH) ? panelW : Math.min(panelW, targetWidth);
            int drawH = (int) Math.round((double) image.getHeight() * drawW / image.getWidth());

            int drawX = (alignmentMode == MangaPanel.ALIGN_LEFT) ? 0 : Math.max(0, (panelW - drawW) / 2);
            int drawY = 0;

            g2.drawImage(image, drawX, drawY, drawW, drawH, null);

            if (dimmerOpacity > 0.0) {
                g2.setColor(new Color(0, 0, 0, (int) Math.min(240, dimmerOpacity * 255)));
                g2.fillRect(drawX, drawY, drawW, drawH);
            }
            g2.dispose();
        }
    }
}
