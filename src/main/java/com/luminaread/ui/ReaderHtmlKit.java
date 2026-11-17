package com.luminaread.ui;

import javax.swing.text.BadLocationException;
import javax.swing.text.Element;
import javax.swing.text.MutableAttributeSet;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.View;
import javax.swing.text.ViewFactory;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.StyleSheet;
import javax.swing.text.html.parser.ParserDelegator;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Admits book formatting while keeping resource loading under the reader's control. */
final class ReaderHtmlKit extends HTMLEditorKit {
    private static final Set<String> TAGS = Set.of(
            "html", "head", "body", "p", "div", "span", "br", "hr", "h1", "h2", "h3", "h4", "h5", "h6",
            "b", "strong", "i", "em", "u", "s", "strike", "small", "big", "tt", "sub", "sup", "font",
            "pre", "code", "blockquote", "center", "ul", "ol", "li", "dl", "dt", "dd",
            "table", "tr", "td", "th", "caption", "a", "img");
    private static final Set<String> ATTRIBUTES = Set.of(
            "id", "title", "alt", "align", "valign", "width", "height", "color", "bgcolor", "face", "size",
            "colspan", "rowspan", "cellpadding", "cellspacing", "border", "start");
    private static final Set<String> INLINE_STYLES = Set.of(
            "color", "background-color", "text-align", "text-indent", "text-decoration", "font-family",
            "font-size", "font-style", "font-weight", "line-height", "margin", "margin-top", "margin-bottom",
            "margin-left", "margin-right", "padding", "padding-top", "padding-bottom", "padding-left",
            "padding-right", "border", "border-left", "white-space");
    private static final Pattern CSS_VALUE = Pattern.compile("[\\p{L}0-9 .,%#'\"-]{1,160}");

    private final ViewFactory viewFactory;

    ReaderHtmlKit() {
        this(Path.of(System.getProperty("user.home"), ".luminaread", "cache"));
    }

    ReaderHtmlKit(Path imageCache) {
        StyleSheet styles = new StyleSheet();
        styles.addStyleSheet(super.getStyleSheet());
        setStyleSheet(styles);
        viewFactory = new HTMLFactory() {
            @Override public View create(Element element) {
                if (element.getAttributes().getAttribute(StyleConstants.NameAttribute) == HTML.Tag.IMG) {
                    return new GuardedImageView(element, imageCache);
                }
                return super.create(element);
            }
        };
    }

    static String pageHtml(String fragment) {
        return "<html><body>" + fragment + "</body></html>";
    }

    static String preparePage(String fragment) {
        return fragment.replace("&nbsp;", " ")
                .replace("&#160;", " ")
                .replace("&shy;", "")
                .replace("\u00A0", " ")
                .replace("\u00AD", "")
                .replaceAll("[ \\t\\x0B\\f\\r\\u2000-\\u200A]{2,}", " ");
    }

    @Override public ViewFactory getViewFactory() { return viewFactory; }

    @Override protected Parser getParser() {
        return new Parser() {
            @Override public void parse(Reader input, ParserCallback output, boolean ignoreCharset) throws IOException {
                // An in-document charset declaration must not trigger a re-read from a URL.
                new ParserDelegator().parse(input, new SafeCallback(output), true);
            }
        };
    }

    private static class SafeCallback extends ParserCallback {
        private final ParserCallback output;
        private boolean inStyle;

        SafeCallback(ParserCallback output) { this.output = output; }

        @Override public void handleStartTag(HTML.Tag tag, MutableAttributeSet attrs, int pos) {
            if (tag == HTML.Tag.STYLE) inStyle = true;
            if (!inStyle && TAGS.contains(tag.toString())) output.handleStartTag(tag, cleanAttributes(tag, attrs), pos);
        }

        @Override public void handleEndTag(HTML.Tag tag, int pos) {
            if (tag == HTML.Tag.STYLE) inStyle = false;
            if (!inStyle && TAGS.contains(tag.toString())) output.handleEndTag(tag, pos);
        }

        @Override public void handleSimpleTag(HTML.Tag tag, MutableAttributeSet attrs, int pos) {
            if (!inStyle && TAGS.contains(tag.toString())) output.handleSimpleTag(tag, cleanAttributes(tag, attrs), pos);
        }

        @Override public void handleText(char[] text, int pos) {
            if (!inStyle) output.handleText(text, pos);
        }

        @Override public void handleEndOfLineString(String eol) { output.handleEndOfLineString(eol); }
        @Override public void flush() throws BadLocationException { output.flush(); }
    }

    private static MutableAttributeSet cleanAttributes(HTML.Tag tag, MutableAttributeSet source) {
        MutableAttributeSet clean = new SimpleAttributeSet();
        var keys = source.getAttributeNames();
        while (keys.hasMoreElements()) {
            Object key = keys.nextElement();
            Object value = source.getAttribute(key);
            if (key == ParserCallback.IMPLIED || ATTRIBUTES.contains(key.toString())
                    || tag == HTML.Tag.IMG && key == HTML.Attribute.SRC
                    || tag == HTML.Tag.A && key == HTML.Attribute.HREF && value.toString().startsWith("#")) {
                clean.addAttribute(key, value);
            } else if (key == HTML.Attribute.STYLE) {
                String style = cleanStyle(value.toString());
                if (!style.isEmpty()) clean.addAttribute(key, style);
            }
        }
        if (tag == HTML.Tag.A && !clean.isDefined(HTML.Attribute.ID)
                && source.isDefined(HTML.Attribute.NAME)) {
            clean.addAttribute(HTML.Attribute.ID, source.getAttribute(HTML.Attribute.NAME));
        }
        return clean;
    }

    private static String cleanStyle(String source) {
        StringBuilder clean = new StringBuilder();
        for (String declaration : source.split(";")) {
            int separator = declaration.indexOf(':');
            if (separator < 0) continue;
            String property = declaration.substring(0, separator).strip().toLowerCase(Locale.ROOT);
            String value = declaration.substring(separator + 1).strip();
            if (INLINE_STYLES.contains(property) && CSS_VALUE.matcher(value).matches()) {
                clean.append(property).append(':').append(value).append(';');
            }
        }
        return clean.toString();
    }
}
