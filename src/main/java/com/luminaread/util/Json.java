package com.luminaread.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Json {

    private final String s;
    private int i;

    private Json(String s) {
        this.s = s;
    }

    public static Object parse(String text) {
        if (text == null) return null;
        Json p = new Json(text);
        Object v = p.readValue();
        return v;
    }

    private void skipWs() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
    }

    private Object readValue() {
        skipWs();
        if (i >= s.length()) return null;
        char c = s.charAt(i);
        switch (c) {
            case '{': return readObject();
            case '[': return readArray();
            case '"': return readString();
            case 't': i += 4; return Boolean.TRUE;
            case 'f': i += 5; return Boolean.FALSE;
            case 'n': i += 4; return null;
            default: return readNumber();
        }
    }

    private Map<String, Object> readObject() {
        Map<String, Object> map = new LinkedHashMap<>();
        i++;
        skipWs();
        if (i < s.length() && s.charAt(i) == '}') { i++; return map; }
        while (i < s.length()) {
            skipWs();
            if (i >= s.length() || s.charAt(i) != '"') break;
            String key = readString();
            skipWs();
            if (i < s.length() && s.charAt(i) == ':') i++;
            map.put(key, readValue());
            skipWs();
            if (i >= s.length()) break;
            char c = s.charAt(i++);
            if (c == '}') break;

        }
        return map;
    }

    private List<Object> readArray() {
        List<Object> list = new ArrayList<>();
        i++;
        skipWs();
        if (i < s.length() && s.charAt(i) == ']') { i++; return list; }
        while (i < s.length()) {
            list.add(readValue());
            skipWs();
            if (i >= s.length()) break;
            char c = s.charAt(i++);
            if (c == ']') break;

        }
        return list;
    }

    private String readString() {
        i++;
        StringBuilder sb = new StringBuilder();
        while (i < s.length()) {
            char c = s.charAt(i++);
            if (c == '"') break;
            if (c == '\\') {
                if (i >= s.length()) break;
                char e = s.charAt(i++);
                switch (e) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'u':
                        if (i + 4 <= s.length()) {
                            try {
                                sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                                i += 4;
                            } catch (NumberFormatException ex) {
                                sb.append('u');
                            }
                        } else {
                            sb.append('u');
                        }
                        break;
                    default: sb.append(e);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private Object readNumber() {
        int start = i;
        while (i < s.length() && "-+0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
        String num = s.substring(start, i);
        if (num.isEmpty()) return null;
        if (num.indexOf('.') >= 0 || num.indexOf('e') >= 0 || num.indexOf('E') >= 0) {
            try { return Double.parseDouble(num); } catch (NumberFormatException e) { return null; }
        }
        try { return Long.parseLong(num); } catch (NumberFormatException e) { return null; }
    }
}
