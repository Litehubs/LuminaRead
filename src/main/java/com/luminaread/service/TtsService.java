package com.luminaread.service;

import com.luminaread.service.tts.LinuxTtsEngine;
import com.luminaread.service.tts.MacTtsEngine;
import com.luminaread.service.tts.TtsEngine;
import com.luminaread.service.tts.WindowsTtsEngine;

import java.util.Locale;
import java.util.Objects;
import java.util.Timer;
import java.util.TimerTask;

/**
 * Text-to-speech service managing audio synthesis processes, speech state, and sleep timers.
 * Delegates platform-specific synthesis process creation to {@link TtsEngine} strategies.
 */
public class TtsService implements ITtsService {
    private final TtsEngine engine;
    private Process activeProcess;
    private volatile boolean speaking = false;
    private Timer sleepTimer;
    private Runnable onStopCallback;

    /**
     * Constructs a TTS service using the default engine detected for current host OS.
     */
    public TtsService() {
        this(resolveEngineForOs(System.getProperty("os.name", "")));
    }

    /**
     * Constructs a TTS service with an explicit {@link TtsEngine} strategy.
     *
     * @param engine the TTS engine strategy to use
     */
    public TtsService(TtsEngine engine) {
        this.engine = Objects.requireNonNull(engine, "TtsEngine must not be null");
    }

    /**
     * Returns the active TTS engine strategy.
     */
    public TtsEngine getEngine() {
        return engine;
    }

    /**
     * Resolves the appropriate {@link TtsEngine} strategy for the given operating system name.
     */
    public static TtsEngine resolveEngineForOs(String os) {
        String lowerOs = os == null ? "" : os.toLowerCase(Locale.ROOT);
        if (lowerOs.contains("mac")) {
            return new MacTtsEngine();
        } else if (lowerOs.contains("win")) {
            return new WindowsTtsEngine();
        } else {
            return new LinuxTtsEngine();
        }
    }

    public static boolean isCommandAvailable(String cmd) {
        return LinuxTtsEngine.isCommandAvailable(cmd);
    }

    public static String escapeForPowerShell(String text) {
        return WindowsTtsEngine.escapeForPowerShell(text);
    }

    public static ProcessBuilder buildWindowsProcess(String textToSpeak, int rateWpm) {
        return new WindowsTtsEngine().buildProcess(textToSpeak, rateWpm);
    }

    public static ProcessBuilder buildLinuxProcess(String textToSpeak, int rateWpm, boolean forceSpdSay) {
        return new LinuxTtsEngine().buildProcess(textToSpeak, rateWpm, forceSpdSay);
    }

    public static ProcessBuilder buildLinuxProcess(String textToSpeak, int rateWpm) {
        return new LinuxTtsEngine().buildProcess(textToSpeak, rateWpm);
    }

    public static ProcessBuilder buildMacProcess(String textToSpeak, int rateWpm) {
        return new MacTtsEngine().buildProcess(textToSpeak, rateWpm);
    }

    public static ProcessBuilder createProcessBuilder(String os, String textToSpeak, int rateWpm) {
        return resolveEngineForOs(os).buildProcess(textToSpeak, rateWpm);
    }

    public ProcessBuilder createProcessBuilder(String textToSpeak, int rateWpm) {
        return engine.buildProcess(textToSpeak, rateWpm);
    }

    @Override
    public synchronized void speakText(String text, int rateWpm, int sleepMinutes, Runnable onStop) {
        stop();
        if (text == null || text.isBlank()) {
            return;
        }
        this.onStopCallback = onStop;
        this.speaking = true;

        String cleanText = text.replaceAll("[\\r\\n]+", " ")
                .trim();
        if (cleanText.length() > 6000) {
            cleanText = cleanText.substring(0, 6000);
        }

        final String textToSpeak = cleanText;
        Thread ttsThread = new Thread(() -> {
            try {
                ProcessBuilder pb = createProcessBuilder(textToSpeak, rateWpm);
                synchronized (TtsService.this) {
                    if (!speaking) return;
                    activeProcess = pb.start();
                }
                activeProcess.waitFor();
            } catch (Exception ignored) {
            } finally {
                synchronized (TtsService.this) {
                    speaking = false;
                    activeProcess = null;
                    if (onStopCallback != null) {
                        onStopCallback.run();
                    }
                }
            }
        }, "LuminaRead-TTS-Thread");
        ttsThread.setDaemon(true);
        ttsThread.start();

        if (sleepMinutes > 0) {
            sleepTimer = new Timer("LuminaRead-SleepTimer", true);
            sleepTimer.schedule(new TimerTask() {
                @Override
                public void run() {
                    stop();
                }
            }, sleepMinutes * 60_000L);
        }
    }

    @Override
    public synchronized void stop() {
        speaking = false;
        if (sleepTimer != null) {
            sleepTimer.cancel();
            sleepTimer = null;
        }
        if (activeProcess != null && activeProcess.isAlive()) {
            activeProcess.destroyForcibly();
            activeProcess = null;
        }
        if (onStopCallback != null) {
            Runnable cb = onStopCallback;
            onStopCallback = null;
            cb.run();
        }
    }

    @Override
    public boolean isSpeaking() {
        return speaking;
    }
}
