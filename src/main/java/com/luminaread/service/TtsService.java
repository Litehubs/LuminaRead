package com.luminaread.service;

import java.util.List;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;

public class TtsService {
    private Process activeProcess;
    private volatile boolean speaking = false;
    private Timer sleepTimer;
    private Runnable onStopCallback;

    public static boolean isCommandAvailable(String cmd) {
        try {
            Process p = new ProcessBuilder("which", cmd).redirectErrorStream(true).start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    public static String escapeForPowerShell(String text) {
        if (text == null) return "";
        // In PowerShell single-quoted strings, single quote is escaped by doubling it (' -> '')
        return text.replace("'", "''").replaceAll("[\\r\\n]+", " ").trim();
    }

    public static ProcessBuilder buildWindowsProcess(String textToSpeak, int rateWpm) {
        int winRate = Math.max(-5, Math.min(5, (rateWpm - 175) / 25));
        String escapedText = escapeForPowerShell(textToSpeak);
        String psCmd = "Add-Type -AssemblyName System.Speech; "
                + "$s = New-Object System.Speech.Synthesis.SpeechSynthesizer; "
                + "$s.Rate = " + winRate + "; "
                + "$s.Speak('" + escapedText + "');";
        return new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", psCmd);
    }

    public static ProcessBuilder buildLinuxProcess(String textToSpeak, int rateWpm, boolean forceSpdSay) {
        if (forceSpdSay) {
            int spdRate = Math.max(-100, Math.min(100, (rateWpm - 175) * 100 / 175));
            return new ProcessBuilder("spd-say", "-r", String.valueOf(spdRate), textToSpeak);
        } else {
            return new ProcessBuilder("espeak", "-s", String.valueOf(rateWpm), textToSpeak);
        }
    }

    public static ProcessBuilder buildLinuxProcess(String textToSpeak, int rateWpm) {
        if (isCommandAvailable("espeak")) {
            return buildLinuxProcess(textToSpeak, rateWpm, false);
        } else if (isCommandAvailable("spd-say")) {
            return buildLinuxProcess(textToSpeak, rateWpm, true);
        } else {
            // Default fallback if neither command is detected in PATH
            return buildLinuxProcess(textToSpeak, rateWpm, false);
        }
    }

    public static ProcessBuilder buildMacProcess(String textToSpeak, int rateWpm) {
        return new ProcessBuilder("say", "-r", String.valueOf(rateWpm), textToSpeak);
    }

    public static ProcessBuilder createProcessBuilder(String os, String textToSpeak, int rateWpm) {
        String lowerOs = os == null ? "" : os.toLowerCase(Locale.ROOT);
        if (lowerOs.contains("mac")) {
            return buildMacProcess(textToSpeak, rateWpm);
        } else if (lowerOs.contains("win")) {
            return buildWindowsProcess(textToSpeak, rateWpm);
        } else {
            return buildLinuxProcess(textToSpeak, rateWpm);
        }
    }

    public ProcessBuilder createProcessBuilder(String textToSpeak, int rateWpm) {
        return createProcessBuilder(System.getProperty("os.name", ""), textToSpeak, rateWpm);
    }

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

    public boolean isSpeaking() {
        return speaking;
    }
}
