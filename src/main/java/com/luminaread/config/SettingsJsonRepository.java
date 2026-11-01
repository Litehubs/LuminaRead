package com.luminaread.config;

import com.luminaread.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Resilient JSON-based repository for persisting and loading application preferences
 * (ReaderDisplayConfig and OpdsCatalogConfig) instead of fragile Java serialization.
 */
public class SettingsJsonRepository {

    private final Path filePath;

    public SettingsJsonRepository() {
        this(Path.of(System.getProperty("user.home"), ".luminaread", "settings.json"));
    }

    public SettingsJsonRepository(Path filePath) {
        this.filePath = filePath != null ? filePath
                : Path.of(System.getProperty("user.home"), ".luminaread", "settings.json");
    }

    public Path getFilePath() {
        return filePath;
    }

    public synchronized void save(ReaderDisplayConfig display, OpdsCatalogConfig opds) {
        save(display, opds, filePath);
    }

    public synchronized void save(ReaderDisplayConfig display, OpdsCatalogConfig opds, Path targetPath) {
        if (display == null && opds == null) return;
        try {
            if (targetPath.getParent() != null) {
                Files.createDirectories(targetPath.getParent());
            }

            StringBuilder sb = new StringBuilder();
            sb.append("{\n");
            sb.append("  \"version\": 1,\n");

            if (display != null) {
                sb.append("  \"display\": {\n");
                sb.append("    \"themeId\": ").append(quote(display.getThemeId())).append(",\n");
                sb.append("    \"fontFamily\": ").append(quote(display.getFontFamily())).append(",\n");
                sb.append("    \"fontSize\": ").append(display.getFontSize()).append(",\n");
                sb.append("    \"lineHeight\": ").append(display.getLineHeight()).append(",\n");
                sb.append("    \"paragraphSpacingPx\": ").append(display.getParagraphSpacingPx()).append(",\n");
                sb.append("    \"horizontalMarginPx\": ").append(display.getHorizontalMarginPx()).append(",\n");
                sb.append("    \"verticalMarginPx\": ").append(display.getVerticalMarginPx()).append(",\n");
                sb.append("    \"textAlignment\": ").append(quote(display.getTextAlignment().name())).append(",\n");
                sb.append("    \"readingMode\": ").append(quote(display.getReadingMode().name())).append(",\n");
                sb.append("    \"twoColumnSpread\": ").append(display.isTwoColumnSpread()).append(",\n");
                sb.append("    \"charsPerPage\": ").append(display.getCharsPerPage()).append(",\n");
                sb.append("    \"dimmerOpacity\": ").append(display.getDimmerOpacity()).append(",\n");
                sb.append("    \"warmEyeCareFilter\": ").append(display.isWarmEyeCareFilter()).append(",\n");
                sb.append("    \"autoScrollSpeed\": ").append(display.getAutoScrollSpeed()).append(",\n");
                sb.append("    \"ttsRateWpm\": ").append(display.getTtsRateWpm()).append(",\n");
                sb.append("    \"tapZonesEnabled\": ").append(display.isTapZonesEnabled()).append(",\n");
                sb.append("    \"showStatusBarInReader\": ").append(display.isShowStatusBarInReader()).append("\n");
                sb.append("  }");
            }

            if (opds != null) {
                if (display != null) sb.append(",\n");
                sb.append("  \"opds\": {\n");
                sb.append("    \"catalogs\": [\n");
                List<String> list = opds.getCustomOpdsCatalogs();
                for (int i = 0; i < list.size(); i++) {
                    sb.append("      ").append(quote(list.get(i)));
                    if (i < list.size() - 1) sb.append(",");
                    sb.append("\n");
                }
                sb.append("    ]\n");
                sb.append("  }\n");
            } else {
                sb.append("\n");
            }

            sb.append("}\n");
            Files.writeString(targetPath, sb.toString(), StandardCharsets.UTF_8);
        } catch (Exception ignored) {}
    }

    public synchronized boolean load(ReaderDisplayConfig display, OpdsCatalogConfig opds) {
        return load(display, opds, filePath);
    }

    public synchronized boolean load(ReaderDisplayConfig display, OpdsCatalogConfig opds, Path sourcePath) {
        if (!Files.exists(sourcePath)) return false;
        try {
            String text = Files.readString(sourcePath, StandardCharsets.UTF_8);
            Object parsed = Json.parse(text);
            if (!(parsed instanceof Map)) return false;
            Map<?, ?> root = (Map<?, ?>) parsed;

            if (display != null && root.containsKey("display")) {
                Object dispObj = root.get("display");
                if (dispObj instanceof Map) {
                    Map<?, ?> dm = (Map<?, ?>) dispObj;
                    if (dm.get("themeId") != null) display.setThemeId(String.valueOf(dm.get("themeId")));
                    if (dm.get("fontFamily") != null) display.setFontFamily(String.valueOf(dm.get("fontFamily")));
                    if (dm.get("fontSize") != null) display.setFontSize(asInt(dm.get("fontSize"), display.getFontSize()));
                    if (dm.get("lineHeight") != null) display.setLineHeight(asDouble(dm.get("lineHeight"), display.getLineHeight()));
                    if (dm.get("paragraphSpacingPx") != null) display.setParagraphSpacingPx(asInt(dm.get("paragraphSpacingPx"), display.getParagraphSpacingPx()));
                    if (dm.get("horizontalMarginPx") != null) display.setHorizontalMarginPx(asInt(dm.get("horizontalMarginPx"), display.getHorizontalMarginPx()));
                    if (dm.get("verticalMarginPx") != null) display.setVerticalMarginPx(asInt(dm.get("verticalMarginPx"), display.getVerticalMarginPx()));

                    if (dm.get("textAlignment") != null) {
                        try {
                            display.setTextAlignment(ReaderDisplayConfig.TextAlignment.valueOf(String.valueOf(dm.get("textAlignment"))));
                        } catch (Exception ignored) {}
                    }
                    if (dm.get("readingMode") != null) {
                        try {
                            display.setReadingMode(ReaderDisplayConfig.ReadingMode.valueOf(String.valueOf(dm.get("readingMode"))));
                        } catch (Exception ignored) {}
                    }
                    if (dm.get("twoColumnSpread") != null) display.setTwoColumnSpread(Boolean.parseBoolean(String.valueOf(dm.get("twoColumnSpread"))));
                    if (dm.get("charsPerPage") != null) display.setCharsPerPage(asInt(dm.get("charsPerPage"), display.getCharsPerPage()));
                    if (dm.get("dimmerOpacity") != null) display.setDimmerOpacity(asDouble(dm.get("dimmerOpacity"), display.getDimmerOpacity()));
                    if (dm.get("warmEyeCareFilter") != null) display.setWarmEyeCareFilter(Boolean.parseBoolean(String.valueOf(dm.get("warmEyeCareFilter"))));
                    if (dm.get("autoScrollSpeed") != null) display.setAutoScrollSpeed(asInt(dm.get("autoScrollSpeed"), display.getAutoScrollSpeed()));
                    if (dm.get("ttsRateWpm") != null) display.setTtsRateWpm(asInt(dm.get("ttsRateWpm"), display.getTtsRateWpm()));
                    if (dm.get("tapZonesEnabled") != null) display.setTapZonesEnabled(Boolean.parseBoolean(String.valueOf(dm.get("tapZonesEnabled"))));
                    if (dm.get("showStatusBarInReader") != null) display.setShowStatusBarInReader(Boolean.parseBoolean(String.valueOf(dm.get("showStatusBarInReader"))));
                }
            }

            if (opds != null && root.containsKey("opds")) {
                Object opdsObj = root.get("opds");
                if (opdsObj instanceof Map) {
                    Object catList = ((Map<?, ?>) opdsObj).get("catalogs");
                    if (catList instanceof List) {
                        List<String> list = new ArrayList<>();
                        for (Object o : (List<?>) catList) {
                            if (o != null) list.add(o.toString());
                        }
                        opds.setCatalogs(list);
                    }
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static int asInt(Object val, int def) {
        if (val == null) return def;
        try {
            if (val instanceof Number) return ((Number) val).intValue();
            return (int) Double.parseDouble(val.toString());
        } catch (Exception e) {
            return def;
        }
    }

    private static double asDouble(Object val, double def) {
        if (val == null) return def;
        try {
            if (val instanceof Number) return ((Number) val).doubleValue();
            return Double.parseDouble(val.toString());
        } catch (Exception e) {
            return def;
        }
    }

    private static String quote(String s) {
        if (s == null) return "\"\"";
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c == '"') sb.append("\\\"");
            else if (c == '\\') sb.append("\\\\");
            else if (c == '\n') sb.append("\\n");
            else if (c == '\r') sb.append("\\r");
            else if (c == '\t') sb.append("\\t");
            else sb.append(c);
        }
        sb.append("\"");
        return sb.toString();
    }
}
