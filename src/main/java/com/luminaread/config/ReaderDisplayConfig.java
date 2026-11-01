package com.luminaread.config;

import java.io.Serializable;
import java.util.Objects;

public class ReaderDisplayConfig implements Serializable {
    private static final long serialVersionUID = 2L;

    public static final int MIN_FONT_SIZE = 12;
    public static final int MAX_FONT_SIZE = 42;
    public static final double MIN_LINE_HEIGHT = 1.1;
    public static final double MAX_LINE_HEIGHT = 2.4;
    public static final int MIN_PARAGRAPH_SPACING = 0;
    public static final int MAX_PARAGRAPH_SPACING = 40;
    public static final int MIN_HORIZONTAL_MARGIN = 16;
    public static final int MAX_HORIZONTAL_MARGIN = 220;
    public static final int MIN_VERTICAL_MARGIN = 12;
    public static final int MAX_VERTICAL_MARGIN = 120;
    public static final int MIN_CHARS_PER_PAGE = 800;
    public static final int MAX_CHARS_PER_PAGE = 8000;
    public static final double MIN_DIMMER_OPACITY = 0.0;
    public static final double MAX_DIMMER_OPACITY = 0.70;
    public static final int MIN_AUTOSCROLL_SPEED = 5;
    public static final int MAX_AUTOSCROLL_SPEED = 250;
    public static final int MIN_TTS_RATE_WPM = 90;
    public static final int MAX_TTS_RATE_WPM = 350;

    public enum ReadingMode {
        PAGED,
        SCROLL;

        public String getDisplayName() {
            return this == PAGED ? "Постраничный режим" : "Плавный свиток (Скролл)";
        }
    }

    public enum TextAlignment {
        LEFT,
        JUSTIFY;

        public String getCssValue() {
            return this == JUSTIFY ? "justify" : "left";
        }

        public String getDisplayName() {
            return this == JUSTIFY ? "По ширине" : "По левому краю (Без растяжения пробелов)";
        }
    }

    private String themeId = "DAY_PAPER";
    private String fontFamily = "Georgia";
    private int fontSize = 19;
    private double lineHeight = 1.6;
    private int paragraphSpacingPx = 12;
    private int horizontalMarginPx = 64;
    private int verticalMarginPx = 36;

    private TextAlignment textAlignment = TextAlignment.LEFT;
    private ReadingMode readingMode = ReadingMode.PAGED;
    private boolean twoColumnSpread = false;
    private int charsPerPage = 2400;

    private double dimmerOpacity = 0.0;
    private boolean warmEyeCareFilter = false;
    private int autoScrollSpeed = 35;
    private int ttsRateWpm = 175;
    private boolean tapZonesEnabled = true;
    private boolean showStatusBarInReader = true;

    public ReaderDisplayConfig() {}

    public String getThemeId() {
        return Objects.requireNonNullElse(themeId, "DAY_PAPER");
    }

    public void setThemeId(String themeId) {
        this.themeId = Objects.requireNonNullElse(themeId, "DAY_PAPER");
    }

    public String getFontFamily() {
        return Objects.requireNonNullElse(fontFamily, "Georgia");
    }

    public void setFontFamily(String fontFamily) {
        this.fontFamily = Objects.requireNonNullElse(fontFamily, "Georgia");
    }

    public int getFontSize() {
        return fontSize;
    }

    public void setFontSize(int fontSize) {
        this.fontSize = Math.clamp(fontSize, MIN_FONT_SIZE, MAX_FONT_SIZE);
    }

    public double getLineHeight() {
        return lineHeight;
    }

    public void setLineHeight(double lineHeight) {
        this.lineHeight = Math.clamp(lineHeight, MIN_LINE_HEIGHT, MAX_LINE_HEIGHT);
    }

    public int getParagraphSpacingPx() {
        return paragraphSpacingPx;
    }

    public void setParagraphSpacingPx(int paragraphSpacingPx) {
        this.paragraphSpacingPx = Math.clamp(paragraphSpacingPx, MIN_PARAGRAPH_SPACING, MAX_PARAGRAPH_SPACING);
    }

    public int getHorizontalMarginPx() {
        return horizontalMarginPx;
    }

    public void setHorizontalMarginPx(int horizontalMarginPx) {
        this.horizontalMarginPx = Math.clamp(horizontalMarginPx, MIN_HORIZONTAL_MARGIN, MAX_HORIZONTAL_MARGIN);
    }

    public int getVerticalMarginPx() {
        return verticalMarginPx;
    }

    public void setVerticalMarginPx(int verticalMarginPx) {
        this.verticalMarginPx = Math.clamp(verticalMarginPx, MIN_VERTICAL_MARGIN, MAX_VERTICAL_MARGIN);
    }

    public TextAlignment getTextAlignment() {
        return Objects.requireNonNullElse(textAlignment, TextAlignment.LEFT);
    }

    public void setTextAlignment(TextAlignment textAlignment) {
        this.textAlignment = Objects.requireNonNullElse(textAlignment, TextAlignment.LEFT);
    }

    public ReadingMode getReadingMode() {
        return Objects.requireNonNullElse(readingMode, ReadingMode.PAGED);
    }

    public void setReadingMode(ReadingMode readingMode) {
        this.readingMode = Objects.requireNonNullElse(readingMode, ReadingMode.PAGED);
    }

    public boolean isTwoColumnSpread() {
        return twoColumnSpread;
    }

    public void setTwoColumnSpread(boolean twoColumnSpread) {
        this.twoColumnSpread = twoColumnSpread;
    }

    public int getCharsPerPage() {
        return charsPerPage;
    }

    public void setCharsPerPage(int charsPerPage) {
        this.charsPerPage = Math.clamp(charsPerPage, MIN_CHARS_PER_PAGE, MAX_CHARS_PER_PAGE);
    }

    public double getDimmerOpacity() {
        return dimmerOpacity;
    }

    public void setDimmerOpacity(double dimmerOpacity) {
        this.dimmerOpacity = Math.clamp(dimmerOpacity, MIN_DIMMER_OPACITY, MAX_DIMMER_OPACITY);
    }

    public boolean isWarmEyeCareFilter() {
        return warmEyeCareFilter;
    }

    public void setWarmEyeCareFilter(boolean warmEyeCareFilter) {
        this.warmEyeCareFilter = warmEyeCareFilter;
    }

    public int getAutoScrollSpeed() {
        return autoScrollSpeed;
    }

    public void setAutoScrollSpeed(int autoScrollSpeed) {
        this.autoScrollSpeed = Math.clamp(autoScrollSpeed, MIN_AUTOSCROLL_SPEED, MAX_AUTOSCROLL_SPEED);
    }

    public int getTtsRateWpm() {
        return ttsRateWpm;
    }

    public void setTtsRateWpm(int ttsRateWpm) {
        this.ttsRateWpm = Math.clamp(ttsRateWpm, MIN_TTS_RATE_WPM, MAX_TTS_RATE_WPM);
    }

    public boolean isTapZonesEnabled() {
        return tapZonesEnabled;
    }

    public void setTapZonesEnabled(boolean tapZonesEnabled) {
        this.tapZonesEnabled = tapZonesEnabled;
    }

    public boolean isShowStatusBarInReader() {
        return showStatusBarInReader;
    }

    public void setShowStatusBarInReader(boolean showStatusBarInReader) {
        this.showStatusBarInReader = showStatusBarInReader;
    }
}
