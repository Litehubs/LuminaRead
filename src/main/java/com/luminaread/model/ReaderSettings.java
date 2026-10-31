package com.luminaread.model;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class ReaderSettings implements Serializable {
    private static final long serialVersionUID = 1L;

    public enum ReadingMode {
        PAGED("Постраничный режим"),
        SCROLL("Плавный свиток (Скролл)");

        private final String displayName;
        ReadingMode(String displayName) { this.displayName = displayName; }
        public String getDisplayName() { return displayName; }
    }

    public enum TextAlignment {
        LEFT("left", "По левому краю (Без растяжения пробелов)"),
        JUSTIFY("justify", "По ширине");

        private final String cssValue;
        private final String displayName;
        TextAlignment(String cssValue, String displayName) {
            this.cssValue = cssValue;
            this.displayName = displayName;
        }
        public String getCssValue() { return cssValue; }
        public String getDisplayName() { return displayName; }
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

    private final List<String> customOpdsCatalogs = new ArrayList<>();

    public ReaderSettings() {
        customOpdsCatalogs.add("http://flibusta.is/opds");
        customOpdsCatalogs.add("https://www.gutenberg.org/ebooks/search.opds/");
        customOpdsCatalogs.add("https://standardebooks.org/opds/all");
    }

    public String getThemeId() { return themeId; }
    public void setThemeId(String themeId) { this.themeId = themeId; }

    public String getFontFamily() { return fontFamily; }
    public void setFontFamily(String fontFamily) { this.fontFamily = fontFamily; }

    public int getFontSize() { return fontSize; }
    public void setFontSize(int fontSize) { this.fontSize = Math.max(12, Math.min(42, fontSize)); }

    public double getLineHeight() { return lineHeight; }
    public void setLineHeight(double lineHeight) { this.lineHeight = Math.max(1.1, Math.min(2.4, lineHeight)); }

    public int getParagraphSpacingPx() { return paragraphSpacingPx; }
    public void setParagraphSpacingPx(int paragraphSpacingPx) {
        this.paragraphSpacingPx = Math.max(0, Math.min(40, paragraphSpacingPx));
    }

    public int getHorizontalMarginPx() { return horizontalMarginPx; }
    public void setHorizontalMarginPx(int horizontalMarginPx) {
        this.horizontalMarginPx = Math.max(16, Math.min(220, horizontalMarginPx));
    }

    public int getVerticalMarginPx() { return verticalMarginPx; }
    public void setVerticalMarginPx(int verticalMarginPx) {
        this.verticalMarginPx = Math.max(12, Math.min(120, verticalMarginPx));
    }

    public TextAlignment getTextAlignment() { return textAlignment != null ? textAlignment : TextAlignment.LEFT; }
    public void setTextAlignment(TextAlignment textAlignment) { this.textAlignment = textAlignment; }

    public ReadingMode getReadingMode() { return readingMode; }
    public void setReadingMode(ReadingMode readingMode) { this.readingMode = readingMode; }

    public boolean isTwoColumnSpread() { return twoColumnSpread; }
    public void setTwoColumnSpread(boolean twoColumnSpread) { this.twoColumnSpread = twoColumnSpread; }

    public int getCharsPerPage() { return charsPerPage; }
    public void setCharsPerPage(int charsPerPage) {
        this.charsPerPage = Math.max(800, Math.min(8000, charsPerPage));
    }

    public double getDimmerOpacity() { return dimmerOpacity; }
    public void setDimmerOpacity(double dimmerOpacity) {
        this.dimmerOpacity = Math.max(0.0, Math.min(0.70, dimmerOpacity));
    }

    public boolean isWarmEyeCareFilter() { return warmEyeCareFilter; }
    public void setWarmEyeCareFilter(boolean warmEyeCareFilter) { this.warmEyeCareFilter = warmEyeCareFilter; }

    public int getAutoScrollSpeed() { return autoScrollSpeed; }
    public void setAutoScrollSpeed(int autoScrollSpeed) {
        this.autoScrollSpeed = Math.max(5, Math.min(250, autoScrollSpeed));
    }

    public int getTtsRateWpm() { return ttsRateWpm; }
    public void setTtsRateWpm(int ttsRateWpm) {
        this.ttsRateWpm = Math.max(90, Math.min(350, ttsRateWpm));
    }

    public boolean isTapZonesEnabled() { return tapZonesEnabled; }
    public void setTapZonesEnabled(boolean tapZonesEnabled) { this.tapZonesEnabled = tapZonesEnabled; }

    public boolean isShowStatusBarInReader() { return showStatusBarInReader; }
    public void setShowStatusBarInReader(boolean showStatusBarInReader) {
        this.showStatusBarInReader = showStatusBarInReader;
    }

    public List<String> getCustomOpdsCatalogs() { return customOpdsCatalogs; }
    public void addCustomOpdsCatalog(String url) {
        if (url != null && !url.isBlank() && !customOpdsCatalogs.contains(url.trim())) {
            customOpdsCatalogs.add(url.trim());
        }
    }
}
