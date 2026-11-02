package com.luminaread.service.tts;

/**
 * Strategy interface for operating-system specific Text-to-Speech synthesis engines.
 */
public interface TtsEngine {

    /**
     * Builds the OS-specific {@link ProcessBuilder} command to synthesize and speak the text.
     *
     * @param textToSpeak the plain text to synthesize
     * @param rateWpm target speech rate in words per minute
     * @return configured ProcessBuilder ready to start
     */
    ProcessBuilder buildProcess(String textToSpeak, int rateWpm);

    /**
     * Checks if this speech engine is available and supported on the current environment.
     *
     * @return true if executable binary or synthesis subsystem is available, false otherwise
     */
    boolean isAvailable();
}
