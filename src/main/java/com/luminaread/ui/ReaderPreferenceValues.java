package com.luminaread.ui;

import java.util.Map;

/** Reads each saved preference independently so one damaged value cannot hide the rest. */
final class ReaderPreferenceValues {
    private ReaderPreferenceValues() {}

    static int integer(Map<String, String> values, String key, int fallback, int min, int max) {
        String raw = values.get(key);
        if (raw == null) return fallback;
        try { return Math.max(min, Math.min(max, Integer.parseInt(raw))); }
        catch (NumberFormatException ex) { return fallback; }
    }

    static double finiteDouble(Map<String, String> values, String key, double fallback) {
        String raw = values.get(key);
        if (raw == null) return fallback;
        try {
            double value = Double.parseDouble(raw);
            return Double.isFinite(value) && value > 0 ? value : fallback;
        } catch (NumberFormatException ex) { return fallback; }
    }

    static boolean bool(Map<String, String> values, String key, boolean fallback) {
        String raw = values.get(key);
        if ("true".equalsIgnoreCase(raw)) return true;
        if ("false".equalsIgnoreCase(raw)) return false;
        return fallback;
    }
}
