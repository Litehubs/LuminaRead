package com.luminaread.ui;

import java.awt.Color;
import java.util.LinkedHashMap;
import java.util.Map;

public class ThemePalette {
    private final String id;
    private final String displayName;
    private final Color background;
    private final Color surface;
    private final Color cardBackground;
    private final Color border;
    private final Color textPrimary;
    private final Color textSecondary;
    private final Color accent;
    private final boolean dark;

    public static final Map<String, ThemePalette> PRESETS = new LinkedHashMap<>();

    static {
        register(new ThemePalette(
                "DAY_PAPER", "Чистый свет (Бумага)",
                new Color(0xFAF8F5), new Color(0xF1EFEA), new Color(0xFFFFFF),
                new Color(0xE2DFD7), new Color(0x1E1E24), new Color(0x646470),
                new Color(0x4F46E5), false
        ));
        register(new ThemePalette(
                "WARM_SEPIA", "Классическая сепия",
                new Color(0xF4ECD8), new Color(0xEAE0C8), new Color(0xFAF3E3),
                new Color(0xD8CCB0), new Color(0x3B2F2F), new Color(0x6E5D53),
                new Color(0xB45309), false
        ));
        register(new ThemePalette(
                "AMOLED_DARK", "Глубокая ночь (AMOLED)",
                new Color(0x0D0E12), new Color(0x16181F), new Color(0x1D202A),
                new Color(0x2B2F3C), new Color(0xE4E4E7), new Color(0x9CA3AF),
                new Color(0x6366F1), true
        ));
        register(new ThemePalette(
                "NORD_TWILIGHT", "Сумерки (Nord)",
                new Color(0x1E222A), new Color(0x252A34), new Color(0x2E3440),
                new Color(0x3B4252), new Color(0xECEFF4), new Color(0xA3B1C6),
                new Color(0x38BDF8), true
        ));
        register(new ThemePalette(
                "EINK_MATTE", "Электронные чернила (E-Ink)",
                new Color(0xEDEDED), new Color(0xE0E0E0), new Color(0xF7F7F7),
                new Color(0xCCCCCC), new Color(0x111111), new Color(0x4A4A4A),
                new Color(0x222222), false
        ));
        register(new ThemePalette(
                "FOREST_SAGE", "Мятный комфорт (Защита глаз)",
                new Color(0xE8F2EA), new Color(0xDCE9DF), new Color(0xF2F8F3),
                new Color(0xC5DAC9), new Color(0x1B3022), new Color(0x4A6B53),
                new Color(0x15803D), false
        ));
    }

    private static void register(ThemePalette palette) {
        PRESETS.put(palette.id, palette);
    }

    public static ThemePalette byId(String id) {
        if (id == null) return PRESETS.get("DAY_PAPER");
        return PRESETS.getOrDefault(id, PRESETS.get("DAY_PAPER"));
    }

    public ThemePalette(String id, String displayName, Color background, Color surface,
                        Color cardBackground, Color border, Color textPrimary,
                        Color textSecondary, Color accent, boolean dark) {
        this.id = id;
        this.displayName = displayName;
        this.background = background;
        this.surface = surface;
        this.cardBackground = cardBackground;
        this.border = border;
        this.textPrimary = textPrimary;
        this.textSecondary = textSecondary;
        this.accent = accent;
        this.dark = dark;
    }

    public String getId() { return id; }
    public String getDisplayName() { return displayName; }
    public Color getBackground() { return background; }
    public Color getSurface() { return surface; }
    public Color getCardBackground() { return cardBackground; }
    public Color getBorder() { return border; }
    public Color getTextPrimary() { return textPrimary; }
    public Color getTextSecondary() { return textSecondary; }
    public Color getAccent() { return accent; }
    public boolean isDark() { return dark; }

    public static String toHex(Color c) {
        return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }

    @Override
    public String toString() {
        return displayName;
    }
}
