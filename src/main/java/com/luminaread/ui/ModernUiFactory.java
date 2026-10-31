package com.luminaread.ui;

import com.luminaread.model.Book;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JTextField;
import javax.swing.UIManager;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class ModernUiFactory {
    private static final Map<String, ImageIcon> COVER_CACHE = new ConcurrentHashMap<>();
    private static final String UI_FONT_FAMILY = detectBestUiFontFamily();
    private static final String SERIF_FONT_FAMILY = detectBestSerifFontFamily();

    private ModernUiFactory() {}

    private static String detectBestUiFontFamily() {
        try {
            if (!GraphicsEnvironment.isHeadless()) {
                String[] available = GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames(Locale.ROOT);
                Set<String> set = new HashSet<>(Arrays.asList(available));

                String[] preferred = {"Verdana", "Trebuchet MS", "Tahoma", "Georgia", "DejaVu Sans", "SansSerif"};
                for (String candidate : preferred) {
                    if (set.contains(candidate)) {
                        return candidate;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return "SansSerif";
    }

    private static String detectBestSerifFontFamily() {
        try {
            if (!GraphicsEnvironment.isHeadless()) {
                String[] available = GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames(Locale.ROOT);
                Set<String> set = new HashSet<>(Arrays.asList(available));
                String[] preferred = {"Georgia", "Palatino", "Times New Roman", "DejaVu Serif", "Serif"};
                for (String candidate : preferred) {
                    if (set.contains(candidate)) {
                        return candidate;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return "Serif";
    }

    public static String resolveFontFamily(String preferredName, String fallbackLogicalName) {
        try {
            if (GraphicsEnvironment.isHeadless()) {
                return fallbackLogicalName;
            }
            String[] available = GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames(Locale.ROOT);
            for (String font : available) {
                if (font.equalsIgnoreCase(preferredName)) {
                    return font;
                }
            }
        } catch (Throwable ignored) {
        }
        return fallbackLogicalName;
    }

    public static String getUiFontFamily() {
        return UI_FONT_FAMILY;
    }

    public static String getSerifFontFamily() {
        return SERIF_FONT_FAMILY;
    }

    public static Font uiFont(int style, int size) {
        return new Font(UI_FONT_FAMILY, style, size);
    }

    public static Font serifFont(int style, int size) {
        return new Font(SERIF_FONT_FAMILY, style, size);
    }

    public static void installGlobalUiFonts() {
        Font regular = uiFont(Font.PLAIN, 13);
        Font bold = uiFont(Font.BOLD, 13);
        var keys = UIManager.getDefaults().keys();
        while (keys.hasMoreElements()) {
            Object key = keys.nextElement();
            Object value = UIManager.get(key);
            if (value instanceof javax.swing.plaf.FontUIResource) {
                String keyStr = key.toString().toLowerCase(Locale.ROOT);
                UIManager.put(key, new javax.swing.plaf.FontUIResource(
                        keyStr.contains("title") || keyStr.contains("bold") || keyStr.contains("button") ? bold : regular));
            }
        }
    }

    public static void enableHighQualityRendering(Graphics2D g2) {
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g2.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
    }

    public static JButton createPrimaryButton(String text, ThemePalette theme) {
        JButton btn = new JButton(text);
        btn.setFont(uiFont(Font.BOLD, 12));
        btn.setForeground(Color.WHITE);
        btn.setBackground(theme.getAccent());
        btn.setOpaque(true);
        btn.setBorderPainted(false);
        btn.setFocusPainted(false);
        btn.setAlignmentX(Component.LEFT_ALIGNMENT);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.setBorder(BorderFactory.createEmptyBorder(8, 14, 8, 14));
        return btn;
    }

    public static JButton createFlatButton(String text, ThemePalette theme) {
        JButton btn = new JButton(text);

        btn.setFont(uiFont(Font.BOLD, 12));
        btn.setForeground(theme.getTextPrimary());
        btn.setBackground(theme.getSurface());
        btn.setOpaque(true);
        btn.setFocusPainted(false);
        btn.setAlignmentX(Component.LEFT_ALIGNMENT);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(theme.getBorder(), 1, true),
                BorderFactory.createEmptyBorder(6, 12, 6, 12)
        ));
        return btn;
    }

    public static void styleTextField(JTextField field, ThemePalette theme) {
        field.setFont(uiFont(Font.PLAIN, 13));
        field.setBackground(theme.getCardBackground());
        field.setForeground(theme.getTextPrimary());
        field.setCaretColor(theme.getTextPrimary());
        field.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(theme.getBorder(), 1, true),
                BorderFactory.createEmptyBorder(6, 10, 6, 10)
        ));
    }

    public static ImageIcon getOrCreateBookCoverIcon(Book book, int width, int height) {
        String cacheKey = book.getId() + "_" + width + "x" + height;
        ImageIcon cached = COVER_CACHE.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = img.createGraphics();
        enableHighQualityRendering(g2);

        boolean renderedEmbedded = false;
        if (book.getCoverImage() != null && book.getCoverImage().length > 0) {
            try {
                BufferedImage rawCover = ImageIO.read(new ByteArrayInputStream(book.getCoverImage()));
                if (rawCover != null) {
                    Image scaled = rawCover.getScaledInstance(width, height, Image.SCALE_SMOOTH);
                    g2.drawImage(scaled, 0, 0, null);
                    renderedEmbedded = true;
                }
            } catch (Exception ignored) {
            }
        }

        if (!renderedEmbedded) {
            renderProceduralCover(g2, book, width, height);
        }

        g2.setColor(new Color(255, 255, 255, 45));
        g2.setStroke(new BasicStroke(1f));
        g2.drawRoundRect(0, 0, width - 1, height - 1, 10, 10);
        g2.dispose();

        ImageIcon icon = new ImageIcon(img);
        COVER_CACHE.put(cacheKey, icon);
        return icon;
    }

    private static void renderProceduralCover(Graphics2D g2, Book book, int width, int height) {
        int hash = Math.abs((book.getTitle() + book.getAuthor()).hashCode());
        Color[][] palettes = {
                {new Color(0x1E293B), new Color(0x334155)},
                {new Color(0x312E81), new Color(0x4338CA)},
                {new Color(0x14532D), new Color(0x166534)},
                {new Color(0x4C1D95), new Color(0x6D28D9)},
                {new Color(0x7C2D12), new Color(0x9A3412)},
                {new Color(0x134E4A), new Color(0x0F766E)},
                {new Color(0x3F2E21), new Color(0x5C4033)}
        };
        Color[] pair = palettes[hash % palettes.length];

        g2.setPaint(new GradientPaint(0, 0, pair[0], width, height, pair[1]));
        g2.fillRoundRect(0, 0, width, height, 10, 10);

        g2.setColor(new Color(0, 0, 0, 55));
        g2.fillRect(0, 0, 10, height);

        String fmt = book.getFormat();
        g2.setFont(uiFont(Font.BOLD, 10));
        FontMetrics fmBadge = g2.getFontMetrics();
        int bw = fmBadge.stringWidth(fmt) + 12;
        g2.setColor(new Color(255, 255, 255, 38));
        g2.fillRoundRect(width - bw - 8, 8, bw, 18, 6, 6);
        g2.setColor(Color.WHITE);
        g2.drawString(fmt, width - bw - 2, 21);

        g2.setFont(serifFont(Font.BOLD, 13));
        g2.setColor(Color.WHITE);
        drawWrappedText(g2, book.getTitle(), 16, 42, width - 26, 4);

        g2.setFont(uiFont(Font.BOLD, 11));
        g2.setColor(new Color(225, 230, 240));
        drawWrappedText(g2, book.getAuthor(), 16, height - 34, width - 26, 2);
    }

    private static void drawWrappedText(Graphics2D g2, String text, int x, int startY, int maxWidth, int maxLines) {
        if (text == null || text.isBlank()) return;
        FontMetrics fm = g2.getFontMetrics();
        String[] words = text.split("\\s+");
        StringBuilder line = new StringBuilder();
        int y = startY;
        int linesDrawn = 0;

        for (String w : words) {
            String test = line.isEmpty() ? w : (line + " " + w);
            if (fm.stringWidth(test) > maxWidth && !line.isEmpty()) {
                g2.drawString(line.toString(), x, y);
                line = new StringBuilder(w);
                y += fm.getHeight();
                linesDrawn++;
                if (linesDrawn >= maxLines - 1) break;
            } else {
                line = new StringBuilder(test);
            }
        }
        if (!line.isEmpty() && linesDrawn < maxLines) {
            String last = line.toString();
            if (fm.stringWidth(last) > maxWidth && last.length() > 8) {
                last = last.substring(0, Math.min(last.length(), 14)) + "...";
            }
            g2.drawString(last, x, y);
        }
    }
}
