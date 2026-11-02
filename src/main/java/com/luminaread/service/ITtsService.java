package com.luminaread.service;

/**
 * Service interface for text-to-speech functionality.
 */
public interface ITtsService {

    /**
     * Start speaking the given text asynchronously.
     *
     * @param text the text to read aloud
     * @param rateWpm words per minute reading rate
     * @param sleepMinutes auto-stop timer in minutes (0 to disable)
     * @param onStop callback executed when speaking stops
     */
    void speakText(String text, int rateWpm, int sleepMinutes, Runnable onStop);

    /**
     * Stop active text-to-speech playback and terminate running processes/timers.
     */
    void stop();

    /**
     * Check whether text is currently being spoken.
     *
     * @return true if TTS process is active, false otherwise
     */
    boolean isSpeaking();
}
