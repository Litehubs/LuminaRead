package com.luminaread.ui;

import javax.swing.JEditorPane;
import javax.swing.JScrollPane;
import javax.swing.text.DefaultCaret;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.StyleSheet;
import java.awt.Color;
import java.awt.Font;

/** Renders a chapter into the reader with the selected typography and colors. */
final class RanobeReaderRenderer {
    private RanobeReaderRenderer() {}

    static void render(JEditorPane textEditorPane, JScrollPane readerScrollPane, String bodyInnerHtml,
                       Color bg, Color fg, Color acc, String fontFamily, int fontSize,
                       double lineHeight, int textBlockWidth, boolean justifyText) {
        HTMLEditorKit kit = new ReaderHtmlKit();
        StyleSheet styleSheet = kit.getStyleSheet();

        String bgHex = toHex(bg);
        String textHex = toHex(fg);
        String accHex = toHex(acc);
        String alignCss = justifyText ? "justify" : "left";

        int vpWidth = (readerScrollPane != null && readerScrollPane.getViewport().getWidth() > 0)
                ? readerScrollPane.getViewport().getWidth()
                : 900;
        int hMargin = (vpWidth > textBlockWidth) ? Math.max(28, (vpWidth - textBlockWidth) / 2) : 28;

        styleSheet.addRule("body { "
                + "background-color: " + bgHex + "; "
                + "color: " + textHex + "; "
                + "font-family: '" + fontFamily + "', Georgia, serif; "
                + "font-size: " + fontSize + "pt; "
                + "line-height: " + lineHeight + "; "
                + "text-align: " + alignCss + "; "
                + "margin-top: 14px; "
                + "margin-bottom: 30px; "
                + "margin-left: " + hMargin + "px; "
                + "margin-right: " + hMargin + "px; "
                + "}");
        styleSheet.addRule("body, p, div, span, li, blockquote, b, i, em, strong, font { color: " + textHex + "; }");
        styleSheet.addRule("p { margin-top: 0px; margin-bottom: 14px; text-indent: 1.8em; text-align: " + alignCss + "; color: " + textHex + "; }");
        styleSheet.addRule("h1, h2, h3, h4 { color: " + accHex + "; text-align: center; margin-top: 20px; margin-bottom: 14px; }");
        styleSheet.addRule("blockquote { border-left: 3px solid " + accHex + "; margin-left: 14px; padding-left: 12px; font-style: italic; color: " + textHex + "; }");
        styleSheet.addRule("img { max-width: 100%; height: auto; border-radius: 8px; margin: 12px 0; }");

        textEditorPane.setEditorKit(kit);
        textEditorPane.setBackground(bg);
        textEditorPane.setForeground(fg);
        textEditorPane.setCaretColor(fg);
        textEditorPane.setFont(new Font(fontFamily, Font.PLAIN, fontSize));
        readerScrollPane.getViewport().setBackground(bg);
        readerScrollPane.setBackground(bg);

        String cleanHtml = bodyInnerHtml != null ? bodyInnerHtml : "";
        cleanHtml = cleanHtml.replaceAll("(?i)color\\s*:\\s*(#[0-9a-f]{3,6}|black|#[0-9a-f]{6}|rgba?\\([^)]+\\));?", "")
                             .replaceAll("(?i)\\s*color\\s*=\\s*['\"]?(#[0-9a-f]{3,6}|black|#[0-9a-f]{6})['\"]?", "");

        textEditorPane.setText("<html><body>" + cleanHtml + "</body></html>");
        if (textEditorPane.getCaret() instanceof DefaultCaret) {
            ((DefaultCaret) textEditorPane.getCaret()).setUpdatePolicy(DefaultCaret.NEVER_UPDATE);
        }
        textEditorPane.setCaretPosition(0);
    }

    private static String toHex(Color color) {
        return String.format("#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue());
    }
}
