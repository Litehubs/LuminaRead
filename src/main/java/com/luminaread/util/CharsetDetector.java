package com.luminaread.util;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CharsetDetector {
    private static final Pattern XML_ENCODING_PATTERN =
            Pattern.compile("encoding\\s*=\\s*[\"']([A-Za-z0-9_\\-]+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern META_CHARSET_PATTERN =
            Pattern.compile("<meta\\b[^>]*\\bcharset\\s*=\\s*[\"']?([A-Za-z0-9_\\-]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern META_CONTENT_CHARSET_PATTERN =
            Pattern.compile("<meta\\b[^>]*\\bcontent\\s*=\\s*[\"'][^\"']*charset=([A-Za-z0-9_\\-]+)", Pattern.CASE_INSENSITIVE);

    private CharsetDetector() {}

    public static String decodeBytes(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return "";
        Charset charset = detectCharset(bytes);
        int offset = 0;
        if (hasUtf8Bom(bytes)) offset = 3;
        else if (bytes.length >= 2 && ((bytes[0] & 0xFF) == 0xFE && (bytes[1] & 0xFF) == 0xFF
                || (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xFE)) offset = 2;
        String decoded = new String(bytes, offset, bytes.length - offset, charset);
        return decoded.startsWith("\uFEFF") ? decoded.substring(1) : decoded;
    }

    public static Charset detectCharset(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return StandardCharsets.UTF_8;
        if (hasUtf8Bom(bytes)) return StandardCharsets.UTF_8;
        if (bytes.length >= 2) {
            int b0 = bytes[0] & 0xFF, b1 = bytes[1] & 0xFF;
            if (b0 == 0xFE && b1 == 0xFF) return StandardCharsets.UTF_16BE;
            if (b0 == 0xFF && b1 == 0xFE) return StandardCharsets.UTF_16LE;
        }

        String header = new String(bytes, 0, Math.min(bytes.length, 1024), StandardCharsets.ISO_8859_1);
        Charset declared = declaredCharset(header);
        if (declared != null) return declared;
        if (isValidUtf8(bytes)) return StandardCharsets.UTF_8;

        if (scoreKoi8R(bytes) > scoreWindows1251(bytes) && Charset.isSupported("KOI8-R")) {
            return Charset.forName("KOI8-R");
        }
        if (Charset.isSupported("windows-1251")) return Charset.forName("windows-1251");
        return StandardCharsets.UTF_8;
    }

    private static Charset declaredCharset(String header) {
        Matcher matcher = XML_ENCODING_PATTERN.matcher(header);
        if (!matcher.find()) {
            matcher = META_CHARSET_PATTERN.matcher(header);
            if (!matcher.find()) {
                matcher = META_CONTENT_CHARSET_PATTERN.matcher(header);
                if (!matcher.find()) return null;
            }
        }
        try {
            String name = matcher.group(1).trim();
            return Charset.isSupported(name) ? Charset.forName(name) : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean hasUtf8Bom(byte[] bytes) {
        return bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF
                && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF;
    }

    private static boolean isValidUtf8(byte[] data) {
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data));
            return true;
        } catch (CharacterCodingException ignored) {
            return false;
        }
    }

    private static int scoreWindows1251(byte[] data) {
        int limit = Math.min(data.length, 32768), score = 0;
        for (int i = 0; i < limit; i++) {
            int b = data[i] & 0xFF;
            if (b >= 0xE0 && b <= 0xFF) score += 2;
            else if (b >= 0xC0 && b <= 0xDF) score++;
        }
        return score;
    }

    private static int scoreKoi8R(byte[] data) {
        int limit = Math.min(data.length, 32768), score = 0;
        for (int i = 0; i < limit; i++) {
            int b = data[i] & 0xFF;
            if (b >= 0xC0 && b <= 0xDF) score += 2;
        }
        return (int) (score * 0.85);
    }
}
