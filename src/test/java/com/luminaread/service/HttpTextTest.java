package com.luminaread.service;

import com.luminaread.util.HttpText;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class HttpTextTest {
    @Test void successfulResponsePreservesTextAndClosesConnection() throws Exception {
        var connection = new Connection(200, "{\n\"title\":\"Книга\"\n}".getBytes(StandardCharsets.UTF_8));
        assertEquals("{\n\"title\":\"Книга\"\n}", HttpText.read(connection));
        assertTrue(connection.closed);
        assertTrue(connection.disconnected);
    }

    @Test void oversizedResponseWithoutLengthIsRejectedAndClosed() throws Exception {
        var connection = new Connection(200, new byte[4 * 1024 * 1024 + 1]);
        assertThrows(IOException.class, () -> HttpText.read(connection));
        assertTrue(connection.closed);
        assertTrue(connection.disconnected);
    }

    @Test void serverErrorBodyIsNotExposed() throws Exception {
        var connection = new Connection(500, "secret-from-server".getBytes(StandardCharsets.UTF_8));
        assertEquals("HTTP 500", assertThrows(IOException.class, () -> HttpText.read(connection)).getMessage());
        assertFalse(connection.opened);
        assertTrue(connection.disconnected);
    }

    private static final class Connection extends HttpURLConnection {
        private final int status;
        private final byte[] body;
        private boolean closed, disconnected, opened;

        Connection(int status, byte[] body) throws Exception {
            super(URI.create("http://localhost/catalog").toURL());
            this.status = status;
            this.body = body;
        }

        @Override public int getResponseCode() { return status; }
        @Override public long getContentLengthLong() { return -1; }
        @Override public InputStream getInputStream() {
            opened = true;
            return new ByteArrayInputStream(body) {
                @Override public void close() { closed = true; }
            };
        }
        @Override public void disconnect() { disconnected = true; }
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() {}
    }
}
