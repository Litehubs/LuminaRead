package com.luminaread.service.tts;

import java.util.Locale;

/**
 * Windows Text-To-Speech engine utilizing PowerShell and System.Speech.Synthesis.SpeechSynthesizer.
 */
public class WindowsTtsEngine implements TtsEngine {

    /**
     * Escapes text safely for embedding into single-quoted PowerShell command arguments.
     *
     * @param text input string
     * @return escaped string with doubled single quotes and flattened newlines
     */
    public static String escapeForPowerShell(String text) {
        if (text == null) return "";
        // In PowerShell single-quoted strings, single quote is escaped by doubling it (' -> '')
        return text.replace("'", "''").replaceAll("[\\r\\n]+", " ").trim();
    }

    @Override
    public ProcessBuilder buildProcess(String textToSpeak, int rateWpm) {
        int winRate = Math.max(-5, Math.min(5, (rateWpm - 175) / 25));
        String escapedText = escapeForPowerShell(textToSpeak);
        String psCmd = "Add-Type -AssemblyName System.Speech; "
                + "$s = New-Object System.Speech.Synthesis.SpeechSynthesizer; "
                + "$s.Rate = " + winRate + "; "
                + "$s.Speak('" + escapedText + "');";
        return new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", psCmd);
    }

    @Override
    public boolean isAvailable() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("win");
    }
}
