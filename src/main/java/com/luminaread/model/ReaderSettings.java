package com.luminaread.model;

import com.luminaread.config.OpdsCatalogConfig;
import com.luminaread.config.ReaderDisplayConfig;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public class ReaderSettings implements Serializable {
    private static final long serialVersionUID = 2L;

    public enum ReadingMode {
        PAGED("Постраничный режим"),
        SCROLL("Плавный свиток (Скролл)");

        private final String displayName;
        ReadingMode(String displayName) { this.displayName = displayName; }
        public String getDisplayName() { return displayName; }

        public ReaderDisplayConfig.ReadingMode toConfig() {
            return this == SCROLL ? ReaderDisplayConfig.ReadingMode.SCROLL : ReaderDisplayConfig.ReadingMode.PAGED;
        }

        public static ReadingMode fromConfig(ReaderDisplayConfig.ReadingMode mode) {
            return mode == ReaderDisplayConfig.ReadingMode.SCROLL ? SCROLL : PAGED;
        }
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

        public ReaderDisplayConfig.TextAlignment toConfig() {
            return this == JUSTIFY ? ReaderDisplayConfig.TextAlignment.JUSTIFY : ReaderDisplayConfig.TextAlignment.LEFT;
        }

        public static TextAlignment fromConfig(ReaderDisplayConfig.TextAlignment alignment) {
            return alignment == ReaderDisplayConfig.TextAlignment.JUSTIFY ? JUSTIFY : LEFT;
        }
    }

    private final ReaderDisplayConfig displayConfig = new ReaderDisplayConfig();
    private final OpdsCatalogConfig opdsConfig = new OpdsCatalogConfig();

    public ReaderSettings() {}

    public ReaderDisplayConfig getDisplayConfig() { return displayConfig; }
    public OpdsCatalogConfig getOpdsConfig() { return opdsConfig; }

    public String getThemeId() { return displayConfig.getThemeId(); }
    public void setThemeId(String themeId) { displayConfig.setThemeId(themeId); }

    public String getFontFamily() { return displayConfig.getFontFamily(); }
    public void setFontFamily(String fontFamily) { displayConfig.setFontFamily(fontFamily); }

    public int getFontSize() { return displayConfig.getFontSize(); }
    public void setFontSize(int fontSize) { displayConfig.setFontSize(fontSize); }

    public double getLineHeight() { return displayConfig.getLineHeight(); }
    public void setLineHeight(double lineHeight) { displayConfig.setLineHeight(lineHeight); }

    public int getParagraphSpacingPx() { return displayConfig.getParagraphSpacingPx(); }
    public void setParagraphSpacingPx(int paragraphSpacingPx) { displayConfig.setParagraphSpacingPx(paragraphSpacingPx); }

    public int getHorizontalMarginPx() { return displayConfig.getHorizontalMarginPx(); }
    public void setHorizontalMarginPx(int horizontalMarginPx) { displayConfig.setHorizontalMarginPx(horizontalMarginPx); }

    public int getVerticalMarginPx() { return displayConfig.getVerticalMarginPx(); }
    public void setVerticalMarginPx(int verticalMarginPx) { displayConfig.setVerticalMarginPx(verticalMarginPx); }

    public TextAlignment getTextAlignment() {
        return TextAlignment.fromConfig(displayConfig.getTextAlignment());
    }
    public void setTextAlignment(TextAlignment textAlignment) {
        displayConfig.setTextAlignment(Objects.requireNonNullElse(textAlignment, TextAlignment.LEFT).toConfig());
    }

    public ReadingMode getReadingMode() {
        return ReadingMode.fromConfig(displayConfig.getReadingMode());
    }
    public void setReadingMode(ReadingMode readingMode) {
        displayConfig.setReadingMode(Objects.requireNonNullElse(readingMode, ReadingMode.PAGED).toConfig());
    }

    public boolean isTwoColumnSpread() { return displayConfig.isTwoColumnSpread(); }
    public void setTwoColumnSpread(boolean twoColumnSpread) { displayConfig.setTwoColumnSpread(twoColumnSpread); }

    public int getCharsPerPage() { return displayConfig.getCharsPerPage(); }
    public void setCharsPerPage(int charsPerPage) { displayConfig.setCharsPerPage(charsPerPage); }

    public double getDimmerOpacity() { return displayConfig.getDimmerOpacity(); }
    public void setDimmerOpacity(double dimmerOpacity) { displayConfig.setDimmerOpacity(dimmerOpacity); }

    public boolean isWarmEyeCareFilter() { return displayConfig.isWarmEyeCareFilter(); }
    public void setWarmEyeCareFilter(boolean warmEyeCareFilter) { displayConfig.setWarmEyeCareFilter(warmEyeCareFilter); }

    public int getAutoScrollSpeed() { return displayConfig.getAutoScrollSpeed(); }
    public void setAutoScrollSpeed(int autoScrollSpeed) { displayConfig.setAutoScrollSpeed(autoScrollSpeed); }

    public int getTtsRateWpm() { return displayConfig.getTtsRateWpm(); }
    public void setTtsRateWpm(int ttsRateWpm) { displayConfig.setTtsRateWpm(ttsRateWpm); }

    public boolean isTapZonesEnabled() { return displayConfig.isTapZonesEnabled(); }
    public void setTapZonesEnabled(boolean tapZonesEnabled) { displayConfig.setTapZonesEnabled(tapZonesEnabled); }

    public boolean isShowStatusBarInReader() { return displayConfig.isShowStatusBarInReader(); }
    public void setShowStatusBarInReader(boolean showStatusBarInReader) { displayConfig.setShowStatusBarInReader(showStatusBarInReader); }

    public List<String> getCustomOpdsCatalogs() {
        return opdsConfig.getCustomOpdsCatalogs();
    }

    public void addCustomOpdsCatalog(String url) {
        opdsConfig.addCatalog(url);
    }
}
