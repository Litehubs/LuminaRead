package com.luminaread.service.tts;

import java.util.Locale;

/**
 * Linux Text-To-Speech engine utilizing 'espeak' or 'spd-say' command-line synthesizers.
 */
public class LinuxTtsEngine implements TtsEngine {

    private final Boolean forceSpdSay;

    public LinuxTtsEngine() {
        this(null);
    }

    public LinuxTtsEngine(Boolean forceSpdSay) {
        this.forceSpdSay = forceSpdSay;
    }

    @Override
    public ProcessBuilder buildProcess(String textToSpeak, int rateWpm) {
        boolean useSpdSay;
        if (forceSpdSay != null) {
            useSpdSay = forceSpdSay;
        } else {
            if (isCommandAvailable("espeak")) {
                useSpdSay = false;
            } else if (isCommandAvailable("spd-say")) {
                useSpdSay = true;
            } else {
                // Default fallback if neither command is detected in PATH
                useSpdSay = false;
            }
        }
        return buildProcess(textToSpeak, rateWpm, useSpdSay);
    }

    public ProcessBuilder buildProcess(String textToSpeak, int rateWpm, boolean forceSpd) {
        if (forceSpd) {
            int spdRate = Math.max(-100, Math.min(100, (rateWpm - 175) * 100 / 175));
            return new ProcessBuilder("spd-say", "-r", String.valueOf(spdRate), textToSpeak);
        } else {
            return new ProcessBuilder("espeak", "-s", String.valueOf(rateWpm), textToSpeak);
        }
    }

    @Override
    public boolean isAvailable() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("linux") || isCommandAvailable("espeak") || isCommandAvailable("spd-say");
    }

    public static boolean isCommandAvailable(String cmd) {
        try {
            Process p = new ProcessBuilder("which", cmd).redirectErrorStream(true).start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
