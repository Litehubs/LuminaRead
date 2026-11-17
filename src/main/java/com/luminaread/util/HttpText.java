package com.luminaread.util;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** Common response boundary for catalog clients using URL connections. */
public final class HttpText {
    private static final int MAX_BYTES = 4 * 1024 * 1024;

    private HttpText() {}

    public static String read(HttpURLConnection connection) throws IOException {
        try {
            int status = connection.getResponseCode();
            // Never propagate arbitrary server error pages into exception messages or logs.
            if (status < 200 || status >= 300) throw new IOException("HTTP " + status);
            if (connection.getContentLengthLong() > MAX_BYTES) throw new IOException("Ответ сервера слишком большой.");
            try (var input = new TimedInput(connection.getInputStream(), Duration.ofSeconds(30))) {
                return new String(BoundedInput.read(input, MAX_BYTES), StandardCharsets.UTF_8);
            }
        } finally {
            connection.disconnect();
        }
    }
}
