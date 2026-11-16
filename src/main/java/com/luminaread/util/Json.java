package com.luminaread.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Json {
    private static final int MAX_NESTING_DEPTH = 128;
    private final String s;
    private int i;

    private Json(String s) { this.s = s; }

    public static Object parse(String text) {
        if (text == null) return null;
        Json parser = new Json(text);
        Object value = parser.readValue(0);
        parser.skipWs();
        if (parser.i != text.length()) throw parser.invalid();
        return value;
    }

    private IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid JSON at position " + i);
    }

    private void skipWs() {
        while (i < s.length() && " \t\r\n".indexOf(s.charAt(i)) >= 0) i++;
    }

    private boolean take(char c) {
        skipWs();
        if (i < s.length() && s.charAt(i) == c) { i++; return true; }
        return false;
    }

    private void require(char c) { if (!take(c)) throw invalid(); }

    private Object readValue(int depth) {
        skipWs();
        if (i >= s.length()) throw invalid();
        char start = s.charAt(i);
        if (depth >= MAX_NESTING_DEPTH && (start == '[' || start == '{')) throw invalid();
        return switch (start) {
            case '{' -> readObject(depth);
            case '[' -> readArray(depth);
            case '"' -> readString();
            case 't' -> readLiteral("true", Boolean.TRUE);
            case 'f' -> readLiteral("false", Boolean.FALSE);
            case 'n' -> readLiteral("null", null);
            default -> readNumber();
        };
    }

    private Object readLiteral(String literal, Object value) {
        if (!s.startsWith(literal, i)) throw invalid();
        i += literal.length();
        return value;
    }

    private Map<String, Object> readObject(int depth) {
        require('{');
        Map<String, Object> result = new LinkedHashMap<>();
        if (take('}')) return result;
        do {
            skipWs();
            if (i >= s.length() || s.charAt(i) != '"') throw invalid();
            String key = readString();
            require(':');
            result.put(key, readValue(depth + 1));
            if (take('}')) return result;
            require(',');
        } while (true);
    }

    private List<Object> readArray(int depth) {
        require('[');
        List<Object> result = new ArrayList<>();
        if (take(']')) return result;
        do {
            result.add(readValue(depth + 1));
            if (take(']')) return result;
            require(',');
        } while (true);
    }

    private String readString() {
        require('"');
        StringBuilder result = new StringBuilder();
        while (i < s.length()) {
            char c = s.charAt(i++);
            if (c == '"') return result.toString();
            if (c < 0x20) throw invalid();
            if (c != '\\') { result.append(c); continue; }
            if (i >= s.length()) throw invalid();
            char escape = s.charAt(i++);
            switch (escape) {
                case '"', '\\', '/' -> result.append(escape);
                case 'b' -> result.append('\b');
                case 'f' -> result.append('\f');
                case 'n' -> result.append('\n');
                case 'r' -> result.append('\r');
                case 't' -> result.append('\t');
                case 'u' -> {
                    if (i + 4 > s.length()) throw invalid();
                    int code = 0;
                    for (int j = 0; j < 4; j++) {
                        char hex = s.charAt(i++);
                        int digit = hex < 128 ? Character.digit(hex, 16) : -1;
                        if (digit < 0) throw invalid();
                        code = code * 16 + digit;
                    }
                    result.append((char) code);
                }
                default -> throw invalid();
            }
        }
        throw invalid();
    }

    private Object readNumber() {
        int start = i;
        if (i < s.length() && s.charAt(i) == '-') i++;
        if (i >= s.length()) throw invalid();
        if (s.charAt(i) == '0') i++;
        else {
            if (s.charAt(i) < '1' || s.charAt(i) > '9') throw invalid();
            digits();
        }
        boolean decimal = false;
        if (i < s.length() && s.charAt(i) == '.') {
            decimal = true; i++; int first = i; digits(); if (i == first) throw invalid();
        }
        if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
            decimal = true; i++;
            if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) i++;
            int first = i; digits(); if (i == first) throw invalid();
        }
        String number = s.substring(start, i);
        try {
            if (decimal) {
                double value = Double.parseDouble(number);
                if (!Double.isFinite(value)) throw invalid();
                return value;
            }
            return Long.parseLong(number);
        } catch (NumberFormatException ex) { throw invalid(); }
    }

    private void digits() {
        while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') i++;
    }
}
