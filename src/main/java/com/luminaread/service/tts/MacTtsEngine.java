package com.luminaread.service.tts;

import java.util.Locale;

/**
 * macOS Text-To-Speech engine utilizing the native 'say' command line utility.
 */
public class MacTtsEngine implements TtsEngine {

    @Override
    public ProcessBuilder buildProcess(String textToSpeak, int rateWpm) {
        return new ProcessBuilder("say", "-r", String.valueOf(rateWpm), textToSpeak);
    }

    @Override
    public boolean isAvailable() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac")) {
            return true;
        }
        try {
            Process p = new ProcessBuilder("which", "say").redirectErrorStream(true).start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
