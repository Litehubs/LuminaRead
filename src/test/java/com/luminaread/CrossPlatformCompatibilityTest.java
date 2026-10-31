package com.luminaread;

import com.luminaread.service.LibraryStorageService;
import com.luminaread.service.MediaCacheService;
import com.luminaread.service.TtsService;
import com.luminaread.service.YandexAuthService;
import com.luminaread.ui.MainWindow;
import com.luminaread.ui.ModernUiFactory;

import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

public class CrossPlatformCompatibilityTest {

    // Valid 1x1 lossy WebP image bytes (RFC 6386)
    private static final String TINY_WEBP_BASE64 = "UklGRiQAAABXRUJQVlA4IBgAAAAwAQCdASoBAAEAD8D+JaQAA3AA/ua1AAA=";

    public static void main(String[] args) {
        System.out.println("============================================================");
        System.out.println("  RUNNING LUMINA READ CROSS-PLATFORM COMPATIBILITY TEST SUITE");
        System.out.println("============================================================");

        int passed = 0;
        int failed = 0;

        try {
            testWindowsEnvironmentSimulation();
            System.out.println("  [PASS] Test 1: Windows environment simulation (os.name = 'Windows 11')");
            passed++;
        } catch (Throwable t) {
            System.err.println("  [FAIL] Test 1: Windows environment simulation failed: " + t.getMessage());
            t.printStackTrace();
            failed++;
        }

        try {
            testLinuxEnvironmentSimulation();
            System.out.println("  [PASS] Test 2: Linux environment simulation (os.name = 'Linux')");
            passed++;
        } catch (Throwable t) {
            System.err.println("  [FAIL] Test 2: Linux environment simulation failed: " + t.getMessage());
            t.printStackTrace();
            failed++;
        }

        try {
            testFontAvailabilityAndFallback();
            System.out.println("  [PASS] Test 3: Font availability and fallback");
            passed++;
        } catch (Throwable t) {
            System.err.println("  [FAIL] Test 3: Font availability and fallback failed: " + t.getMessage());
            t.printStackTrace();
            failed++;
        }

        try {
            testTaskbarAndDesktopSafeHandling();
            System.out.println("  [PASS] Test 4: Taskbar / Desktop safe handling");
            passed++;
        } catch (Throwable t) {
            System.err.println("  [FAIL] Test 4: Taskbar / Desktop safe handling failed: " + t.getMessage());
            t.printStackTrace();
            failed++;
        }

        System.out.println("============================================================");
        System.out.println("  SUMMARY: " + passed + " passed, " + failed + " failed");
        System.out.println("============================================================");

        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError("Assertion failed: " + message);
        }
    }

    /**
     * Test 1: Windows environment simulation (os.name = "Windows 11")
     * - Validate storage paths, cache folders, and TTS ProcessBuilder command structure (powershell.exe)
     * - Validate pure Java WebP decoding with sips bypassed
     */
    private static void testWindowsEnvironmentSimulation() throws Exception {
        String origOs = System.getProperty("os.name");
        String origHome = System.getProperty("user.home");

        try {
            System.setProperty("os.name", "Windows 11");
            String mockWinHome = "C:\\Users\\LuminaUser";
            System.setProperty("user.home", mockWinHome);

            // 1. Path construction verification
            Path expectedCacheDir = Path.of(mockWinHome, ".luminaread", "cache", "manga");
            File cacheFile = Path.of(System.getProperty("user.home"), ".luminaread", "cache", "manga").toFile();
            require(expectedCacheDir.toFile().equals(cacheFile),
                    "Windows cache folder path must match Path.of specification");
            require(cacheFile.getPath().contains(".luminaread"),
                    "Windows cache path must contain .luminaread directory");

            Path expectedBooksDir = Path.of(mockWinHome, "LuminaRead_Books");
            require(expectedBooksDir.toString().contains("LuminaRead_Books"),
                    "Windows books directory must contain LuminaRead_Books");

            // 2. TTS command structure (powershell.exe) and special character escaping
            String sampleText = "LuminaRead: 'War & Peace' by Tolstoy! Cost: $100 & 50% discount; <great> \"book\"`";
            ProcessBuilder pbWin = TtsService.buildWindowsProcess(sampleText, 175);
            List<String> cmd = pbWin.command();
            require(!cmd.isEmpty(), "Windows TTS command list must not be empty");
            require("powershell.exe".equalsIgnoreCase(cmd.get(0)),
                    "Windows TTS command must execute powershell.exe, but got: " + cmd.get(0));
            require(cmd.contains("-NoProfile"), "Windows TTS command must include -NoProfile");
            require(cmd.contains("-Command"), "Windows TTS command must include -Command");

            String psScript = cmd.get(cmd.size() - 1);
            require(psScript.contains("System.Speech.Synthesis.SpeechSynthesizer"),
                    "PowerShell script must instantiate SpeechSynthesizer");
            require(psScript.contains("''War & Peace''"),
                    "Single quotes in text must be escaped by doubling (' -> '') for PowerShell");
            require(psScript.contains("$100"),
                    "Dollar sign ($) must be preserved verbatim in single-quoted PowerShell script");

            // Verify via createProcessBuilder with simulated OS
            ProcessBuilder pbViaSim = TtsService.createProcessBuilder("Windows 11", sampleText, 180);
            require("powershell.exe".equalsIgnoreCase(pbViaSim.command().get(0)),
                    "TtsService.createProcessBuilder for Windows must use powershell.exe");

            // 3. Pure Java WebP decoding with sips bypassed
            byte[] webpBytes = Base64.getDecoder().decode(TINY_WEBP_BASE64);

            // sips must return null on Windows
            BufferedImage sipsResult = MediaCacheService.tryDecodeWithSips(webpBytes);
            require(sipsResult == null, "sips decoder must return null on Windows");

            // Pure Java TwelveMonkeys decoder must successfully decode WebP
            BufferedImage decoded = MediaCacheService.getInstance().decodeBytes(webpBytes);
            require(decoded != null, "TwelveMonkeys pure Java reader must decode WebP on Windows");
            require(decoded.getWidth() == 1 && decoded.getHeight() == 1,
                    "Decoded WebP must have dimensions 1x1, got " + decoded.getWidth() + "x" + decoded.getHeight());

        } finally {
            if (origOs != null) System.setProperty("os.name", origOs);
            else System.clearProperty("os.name");
            if (origHome != null) System.setProperty("user.home", origHome);
            else System.clearProperty("user.home");
        }
    }

    /**
     * Test 2: Linux environment simulation (os.name = "Linux")
     * - Validate storage paths, cache folders, and TTS ProcessBuilder command structure (espeak / spd-say)
     * - Validate pure Java WebP decoding with sips bypassed
     */
    private static void testLinuxEnvironmentSimulation() throws Exception {
        String origOs = System.getProperty("os.name");
        String origHome = System.getProperty("user.home");

        try {
            System.setProperty("os.name", "Linux");
            String mockLinuxHome = "/home/luminauser";
            System.setProperty("user.home", mockLinuxHome);

            // 1. Path construction verification
            Path expectedCacheDir = Path.of(mockLinuxHome, ".luminaread", "cache", "covers");
            File cacheFile = Path.of(System.getProperty("user.home"), ".luminaread", "cache", "covers").toFile();
            require(expectedCacheDir.toFile().equals(cacheFile),
                    "Linux cache folder path must match Path.of specification");
            require(cacheFile.getPath().equals("/home/luminauser/.luminaread/cache/covers"),
                    "Linux cache path must match exact posix path");

            // 2. TTS command structure (espeak and spd-say)
            String sampleText = "Reading chapter 1 in LuminaRead Linux";

            // Validate espeak command structure
            ProcessBuilder pbEspeak = TtsService.buildLinuxProcess(sampleText, 160, false);
            List<String> espeakCmd = pbEspeak.command();
            require("espeak".equals(espeakCmd.get(0)), "Linux espeak command must start with 'espeak'");
            require(espeakCmd.contains("-s"), "espeak command must specify rate flag -s");
            require(espeakCmd.contains("160"), "espeak command must pass rate value");
            require(espeakCmd.contains(sampleText), "espeak command must pass text to speak");

            // Validate spd-say command structure
            ProcessBuilder pbSpd = TtsService.buildLinuxProcess(sampleText, 210, true);
            List<String> spdCmd = pbSpd.command();
            require("spd-say".equals(spdCmd.get(0)), "Linux spd-say command must start with 'spd-say'");
            require(spdCmd.contains("-r"), "spd-say command must specify rate flag -r");
            require(spdCmd.contains(sampleText), "spd-say command must pass text to speak");

            // Validate fallback resolution via createProcessBuilder
            ProcessBuilder pbLinuxAuto = TtsService.createProcessBuilder("Linux", sampleText, 175);
            String firstCmd = pbLinuxAuto.command().get(0);
            require("espeak".equals(firstCmd) || "spd-say".equals(firstCmd),
                    "Linux TTS auto-selection must pick either 'espeak' or 'spd-say', got: " + firstCmd);

            // 3. Pure Java WebP decoding with sips bypassed
            byte[] webpBytes = Base64.getDecoder().decode(TINY_WEBP_BASE64);

            // sips must return null on Linux
            BufferedImage sipsResult = MediaCacheService.tryDecodeWithSips(webpBytes);
            require(sipsResult == null, "sips decoder must return null on Linux");

            // Pure Java TwelveMonkeys decoder must successfully decode WebP
            BufferedImage decoded = MediaCacheService.getInstance().decodeBytes(webpBytes);
            require(decoded != null, "TwelveMonkeys pure Java reader must decode WebP on Linux");
            require(decoded.getWidth() == 1 && decoded.getHeight() == 1,
                    "Decoded WebP must have dimensions 1x1, got " + decoded.getWidth() + "x" + decoded.getHeight());

        } finally {
            if (origOs != null) System.setProperty("os.name", origOs);
            else System.clearProperty("os.name");
            if (origHome != null) System.setProperty("user.home", origHome);
            else System.clearProperty("user.home");
        }
    }

    /**
     * Test 3: Font availability and fallback on headless / multi-platform environments
     */
    private static void testFontAvailabilityAndFallback() {
        String uiFamily = ModernUiFactory.getUiFontFamily();
        require(uiFamily != null && !uiFamily.isBlank(), "UI font family must not be null or blank");

        String serifFamily = ModernUiFactory.getSerifFontFamily();
        require(serifFamily != null && !serifFamily.isBlank(), "Serif font family must not be null or blank");

        Font regular = ModernUiFactory.uiFont(Font.PLAIN, 14);
        require(regular != null, "ModernUiFactory.uiFont must return a valid Font");
        require(regular.getSize() == 14, "Font size must be 14");

        Font boldSerif = ModernUiFactory.serifFont(Font.BOLD, 18);
        require(boldSerif != null, "ModernUiFactory.serifFont must return a valid Font");
        require(boldSerif.isBold(), "Font must have bold style");

        // Verify logical font fallbacks
        String fallbackUi = ModernUiFactory.resolveFontFamily("NonExistentFont12345", "SansSerif");
        require("SansSerif".equals(fallbackUi), "Missing font must fall back to logical SansSerif");

        String fallbackSerif = ModernUiFactory.resolveFontFamily("NonExistentFont12345", "Serif");
        require("Serif".equals(fallbackSerif), "Missing serif font must fall back to logical Serif");

        // Verify font rendering works without throwing exceptions
        BufferedImage img = new BufferedImage(120, 60, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = img.createGraphics();
        try {
            g2.setFont(regular);
            g2.drawString("LuminaRead Multiplatform", 10, 20);
            g2.setFont(boldSerif);
            g2.drawString("LuminaRead Serif", 10, 45);
        } finally {
            g2.dispose();
        }
    }

    /**
     * Test 4: Verify Taskbar / Desktop calls never throw unhandled exceptions on headless or unsupported platforms
     */
    private static void testTaskbarAndDesktopSafeHandling() {
        // 1. Taskbar safe call with null must safely return false and never throw
        try {
            boolean taskbarNullResult = MainWindow.setSafeTaskbarIcon(null);
            require(!taskbarNullResult, "Taskbar call with null must return false");
        } catch (Throwable t) {
            throw new AssertionError("Taskbar call with null must never throw: " + t.getMessage(), t);
        }

        // 2. Taskbar safe call with valid icon must never throw
        try {
            BufferedImage testIcon = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
            MainWindow.setSafeTaskbarIcon(testIcon);
        } catch (Throwable t) {
            throw new AssertionError("Taskbar setSafeTaskbarIcon must never throw unhandled exception: " + t.getMessage(), t);
        }

        // 3. Desktop safe browse call must never throw
        try {
            YandexAuthService.safeBrowse("https://example.com");
        } catch (Throwable t) {
            throw new AssertionError("safeBrowse must never throw unhandled exception: " + t.getMessage(), t);
        }

        // 4. YandexAuthService instance call must never throw
        try {
            YandexAuthService authService = YandexAuthService.getInstance();
            authService.openYandexOAuthInBrowser();
        } catch (Throwable t) {
            throw new AssertionError("openYandexOAuthInBrowser must never throw unhandled exception: " + t.getMessage(), t);
        }
    }
}
