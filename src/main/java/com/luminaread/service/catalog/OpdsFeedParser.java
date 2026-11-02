package com.luminaread.service.catalog;

import com.luminaread.model.OnlineBookItem;
import com.luminaread.util.CharsetDetector;
import com.luminaread.util.HtmlSanitizer;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.io.StringReader;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Encapsulates parsing of Atom/OPDS XML feeds.
 * Responsible for XML Document parsing, DOM traversal, and building OnlineBookItem
 * models with titles, authors, summaries, cover URLs, and format-specific download links.
 */
public class OpdsFeedParser {

    public List<OnlineBookItem> parse(byte[] xmlBytes, String baseUrl, String sourceLabel) {
        if (xmlBytes == null || xmlBytes.length == 0) {
            return Collections.emptyList();
        }
        String xml = CharsetDetector.decodeBytes(xmlBytes);
        return parse(xml, baseUrl, sourceLabel);
    }

    public List<OnlineBookItem> parse(InputStream inputStream, String baseUrl, String sourceLabel) {
        if (inputStream == null) {
            return Collections.emptyList();
        }
        try {
            byte[] bytes = inputStream.readAllBytes();
            return parse(bytes, baseUrl, sourceLabel);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    public List<OnlineBookItem> parse(String xmlContent, String baseUrl, String sourceLabel) {
        if (xmlContent == null || xmlContent.isBlank()) {
            return Collections.emptyList();
        }

        List<OnlineBookItem> results = new ArrayList<>();
        try {
            String xml = xmlContent;
            int firstLt = xml.indexOf('<');
            if (firstLt > 0) {
                xml = xml.substring(firstLt);
            } else if (firstLt < 0) {
                return Collections.emptyList();
            }

            xml = escapeBareAmpersands(xml);

            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setValidating(false);
            try {
                factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
                factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
                factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            } catch (Exception ignored) {
            }

            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new org.xml.sax.ErrorHandler() {
                @Override
                public void warning(org.xml.sax.SAXParseException e) {}
                @Override
                public void error(org.xml.sax.SAXParseException e) {}
                @Override
                public void fatalError(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXException {
                    throw e;
                }
            });
            Document doc = builder.parse(new InputSource(new StringReader(xml)));

            URI baseUri = null;
            if (baseUrl != null && !baseUrl.isBlank()) {
                try {
                    baseUri = URI.create(baseUrl);
                } catch (Exception ignored) {
                }
            }

            NodeList entries = doc.getElementsByTagName("entry");
            for (int i = 0; i < entries.getLength(); i++) {
                if (!(entries.item(i) instanceof Element entry)) continue;

                String title = getChildText(entry, "title");
                String author = getAuthorName(entry);
                String summary = HtmlSanitizer.htmlToPlainText(getChildText(entry, "content"));
                if (summary.isBlank()) {
                    summary = HtmlSanitizer.htmlToPlainText(getChildText(entry, "summary"));
                }

                String lang = getChildText(entry, "dc:language");
                if (lang.isBlank()) lang = getChildText(entry, "dcterms:language");
                if (lang.isBlank()) lang = getChildText(entry, "language");
                if (lang.isBlank()) lang = "ru";

                lang = lang.trim().toLowerCase(Locale.ROOT);
                if (lang.length() > 2) {
                    int cut = lang.indexOf('-');
                    if (cut < 0) cut = lang.indexOf('_');
                    if (cut > 0) lang = lang.substring(0, cut);
                }

                OnlineBookItem bookItem = new OnlineBookItem(title, author, summary, lang, sourceLabel);

                NodeList links = entry.getElementsByTagName("link");
                for (int l = 0; l < links.getLength(); l++) {
                    if (!(links.item(l) instanceof Element linkEl)) continue;
                    String href = linkEl.getAttribute("href");
                    String type = linkEl.getAttribute("type").toLowerCase(Locale.ROOT);
                    String rel = linkEl.getAttribute("rel").toLowerCase(Locale.ROOT);
                    if (href == null || href.isBlank()) continue;

                    String absHref;
                    if (baseUri != null) {
                        try {
                            absHref = baseUri.resolve(href).toString();
                        } catch (Exception e) {
                            absHref = href;
                        }
                    } else {
                        absHref = href;
                    }

                    // Extract book cover thumbnail if available
                    if (rel.contains("image") || rel.contains("thumbnail") || type.startsWith("image/")) {
                        if (bookItem.getCoverUrl() == null || rel.contains("cover") || rel.contains("thumbnail")) {
                            bookItem.setCoverUrl(absHref);
                        }
                    }

                    // Extract acquisition / downloadable format links
                    if (rel.contains("acquisition") || isBookMimeType(type) || hasBookExtension(absHref)) {
                        String fmt = detectFormatFromMimeOrHref(type, absHref);
                        if (fmt != null) {
                            bookItem.addDownloadLink(fmt, absHref);
                        }
                    }
                }

                if (bookItem.hasDownloadLinks()) {
                    results.add(bookItem);
                }
            }
        } catch (Exception ignored) {
            // Malformed XML or unparseable input silently returns already parsed entries or empty list
        }
        return results;
    }

    public static String escapeBareAmpersands(String xml) {
        if (xml == null) return "";
        StringBuilder sb = new StringBuilder(xml.length() + 64);
        for (int i = 0; i < xml.length(); i++) {
            char c = xml.charAt(i);
            if (c == '&') {
                int semi = xml.indexOf(';', i + 1);
                boolean isEntity = false;
                if (semi > i + 1 && semi - i <= 12) {
                    String body = xml.substring(i + 1, semi);
                    if (body.matches("#[0-9]+") || body.matches("#[xX][0-9a-fA-F]+")
                            || body.matches("[a-zA-Z][a-zA-Z0-9]*")) {
                        isEntity = true;
                    }
                }
                sb.append(isEntity ? "&" : "&amp;");
            } else if (c < 0x20 && c != '\n' && c != '\r' && c != '\t') {
                sb.append(' ');
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    public static boolean isBookMimeType(String type) {
        if (type == null) return false;
        return type.contains("epub") || type.contains("fb2") || type.contains("pdf")
                || type.contains("text/plain") || type.contains("zip") || type.contains("mobi");
    }

    public static boolean hasBookExtension(String href) {
        if (href == null) return false;
        String l = href.toLowerCase(Locale.ROOT);
        return l.endsWith(".epub") || l.endsWith(".fb2") || l.endsWith(".fb2.zip")
                || l.endsWith(".pdf") || l.endsWith(".txt") || l.endsWith("/fb2") || l.endsWith("/epub");
    }

    public static String detectFormatFromMimeOrHref(String mime, String href) {
        String lMime = (mime == null) ? "" : mime.toLowerCase(Locale.ROOT);
        String lHref = (href == null) ? "" : href.toLowerCase(Locale.ROOT);

        if (lMime.contains("fb2+zip") || lHref.endsWith(".fb2.zip") || lHref.endsWith("/fb2")) return "FB2.ZIP";
        if (lMime.contains("fb2") || lHref.endsWith(".fb2")) return "FB2";
        if (lMime.contains("epub") || lHref.endsWith(".epub") || lHref.endsWith("/epub")) return "EPUB";
        if (lMime.contains("pdf") || lHref.endsWith(".pdf")) return "PDF";
        if (lMime.contains("text/plain") || lHref.endsWith(".txt")) return "TXT";
        if (lMime.contains("html") || lHref.endsWith(".html")) return "HTML";
        return null;
    }

    private static String getChildText(Element parent, String tag) {
        NodeList list = parent.getElementsByTagName(tag);
        if (list.getLength() > 0) {
            return list.item(0).getTextContent().replaceAll("\\s+", " ").trim();
        }
        return "";
    }

    private static String getAuthorName(Element entry) {
        NodeList authors = entry.getElementsByTagName("author");
        if (authors.getLength() > 0 && authors.item(0) instanceof Element aEl) {
            String name = getChildText(aEl, "name");
            if (!name.isBlank()) return name;
        }
        return "Неизвестный автор";
    }
}
