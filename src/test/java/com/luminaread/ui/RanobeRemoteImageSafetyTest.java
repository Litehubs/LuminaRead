package com.luminaread.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.swing.text.Element;
import javax.swing.text.ElementIterator;
import javax.swing.text.StyleConstants;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.ImageView;
import java.io.StringReader;
import static org.junit.jupiter.api.Assertions.*;

class RanobeRemoteImageSafetyTest {
    @Test void rendererAllowsLocalImagesButRefusesRemoteImagesInDifferentHtmlForms(@TempDir java.nio.file.Path dir) throws Exception {
        assertNull(imageUrl(dir, "<p><IMG SRC=\"https://example.com/image.png\"></p>"));
        assertNull(imageUrl(dir, "<p><img src=http://example.com/image.png></p>"));
        assertNull(imageUrl(dir, "<p><img src = 'https://example.com/image.png'></p>"));
        assertNull(imageUrl(dir, "<p><img src='file://remotehost/image.png'></p>"));
        var file = dir.resolve("image.png");
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", file.toFile());
        assertEquals(file.toRealPath().toUri().toURL(), imageUrl(dir, "<p><img src='" + file.toUri() + "'></p>"));
        var cache = java.nio.file.Files.createDirectory(dir.resolve("cache"));
        assertNull(imageUrl(cache, "<img src='" + file.toUri() + "'>"));
        // Creating symlinks can require elevated privileges on Windows.
        if (!System.getProperty("os.name").startsWith("Windows")) {
            var symlink = java.nio.file.Files.createSymbolicLink(cache.resolve("outside.png"), file);
            assertNull(imageUrl(cache, "<img src='" + symlink.toUri() + "'>"));
        }
        var unknown = dir.resolve("unknown.png");
        java.nio.file.Files.write(unknown, new byte[] {1, 2, 3, 4});
        assertNull(imageUrl(dir, "<p><img src='" + unknown.toUri() + "'></p>"));
        byte[] huge = java.nio.file.Files.readAllBytes(file);
        java.nio.ByteBuffer.wrap(huge).putInt(16, 6000).putInt(20, 6000);
        var crc = new java.util.zip.CRC32(); crc.update(huge, 12, 17);
        java.nio.ByteBuffer.wrap(huge).putInt(29, (int) crc.getValue());
        java.nio.file.Files.write(file, huge);
        assertNull(imageUrl(dir, "<p><img src='" + file.toUri() + "'></p>"));
    }

    @Test void rendererIgnoresExternalStylesAndFramesButKeepsTextAndFormatting(@TempDir java.nio.file.Path dir)
            throws Exception {
        var requests = new java.util.concurrent.atomic.AtomicInteger();
        var server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            try (exchange) { exchange.sendResponseHeaders(204, -1); }
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/resource";
            HTMLEditorKit kit = new ReaderHtmlKit(dir);
            var doc = kit.createDefaultDocument();
            kit.read(new StringReader("<html><head><base href='" + url + "'>"
                    + "<link rel='stylesheet' type='text/css' href='" + url + "'>"
                    + "<style>@import url('" + url + "');</style></head>"
                    + "<body background='" + url + "'><p style='color:#123456;background-image:url(" + url + ")'>"
                    + "Regular <b>chapter text</b></p><frame src='" + url + "'></body></html>"), doc, 0);

            assertTrue(doc.getText(0, doc.getLength()).contains("Regular chapter text"));
            assertNull(((javax.swing.text.html.HTMLDocument) doc).getBase());
            var elements = new ElementIterator(doc);
            boolean hasColor = false;
            for (Element element = elements.first(); element != null; element = elements.next()) {
                assertNotEquals(HTML.Tag.FRAME, element.getAttributes().getAttribute(StyleConstants.NameAttribute));
                assertNull(element.getAttributes().getAttribute(HTML.Attribute.BACKGROUND));
                assertNull(element.getAttributes().getAttribute(javax.swing.text.html.CSS.Attribute.BACKGROUND_IMAGE));
                Object color = element.getAttributes().getAttribute(javax.swing.text.html.CSS.Attribute.COLOR);
                hasColor |= "#123456".equals(String.valueOf(color));
            }
            var output = new java.io.StringWriter();
            kit.write(output, doc, 0, doc.getLength());
            assertTrue(output.toString().contains("<b>chapter text</b>"));
            assertTrue(hasColor, "Safe inline color should survive parsing");
            assertEquals(0, requests.get());
        } finally {
            server.stop(0);
        }
    }

    private static java.net.URL imageUrl(java.nio.file.Path cache, String html) throws Exception {
        HTMLEditorKit kit = new ReaderHtmlKit(cache);
        var document = kit.createDefaultDocument();
        kit.read(new StringReader(html), document, 0);
        ElementIterator it = new ElementIterator(document);
        for (Element element = it.first(); element != null; element = it.next()) {
            if (element.getAttributes().getAttribute(StyleConstants.NameAttribute) == HTML.Tag.IMG) {
                return ((ImageView) kit.getViewFactory().create(element)).getImageURL();
            }
        }
        fail("Image element was not parsed");
        return null;
    }
}
