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
    private long playbackGeneration;

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
    public void speakText(String text, int rateWpm, int sleepMinutes, Runnable onStop) {
        Runnable previous;
        synchronized (this) {
            previous = stopLocked();
            if (text != null && !text.isBlank()) {
                onStopCallback = onStop;
                speaking = true;
                long generation = playbackGeneration;
                String cleanText = text.replaceAll("[\\r\\n]+", " ").trim();
                String spokenText = cleanText.substring(0, Math.min(6000, cleanText.length()));
                Thread worker = new Thread(() -> play(spokenText, rateWpm, generation), "LuminaRead-TTS");
                worker.setDaemon(true);
                worker.start();
                if (sleepMinutes > 0) {
                    sleepTimer = new Timer("LuminaRead-SleepTimer", true);
                    sleepTimer.schedule(new TimerTask() {
                        @Override public void run() { stopGeneration(generation); }
                    }, sleepMinutes * 60_000L);
                }
            }
        }
        notifyStopped(previous);
    }

    private void play(String text, int rateWpm, long generation) {
        try {
            ProcessBuilder builder = createProcessBuilder(text, rateWpm);
            Process process;
            synchronized (this) {
                if (!speaking || generation != playbackGeneration) return;
                process = builder.start();
                activeProcess = process;
            }
            process.waitFor();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (Exception ex) {
            java.util.logging.Logger.getLogger(TtsService.class.getName())
                    .log(java.util.logging.Level.WARNING, "Не удалось запустить озвучивание", ex);
        } finally {
            stopGeneration(generation);
        }
    }

    private void stopGeneration(long generation) {
        Runnable callback;
        synchronized (this) {
            callback = generation == playbackGeneration ? stopLocked() : null;
        }
        notifyStopped(callback);
    }

    @Override
    public void stop() {
        Runnable callback;
        synchronized (this) { callback = stopLocked(); }
        notifyStopped(callback);
    }

    /** Caller owns this service's monitor; callbacks are always invoked after releasing it. */
    private Runnable stopLocked() {
        playbackGeneration++;
        speaking = false;
        if (sleepTimer != null) {
            sleepTimer.cancel();
            sleepTimer = null;
        }
        if (activeProcess != null) {
            if (activeProcess.isAlive()) activeProcess.destroyForcibly();
            activeProcess = null;
        }
        Runnable callback = onStopCallback;
        onStopCallback = null;
        return callback;
    }

    private static void notifyStopped(Runnable callback) {
        if (callback == null) return;
        try {
            callback.run();
        } catch (RuntimeException ex) {
            java.util.logging.Logger.getLogger(TtsService.class.getName())
                    .log(java.util.logging.Level.WARNING, "Не удалось обновить состояние озвучивания", ex);
        }
    }

    @Override
    public boolean isSpeaking() {
        return speaking;
    }
}
