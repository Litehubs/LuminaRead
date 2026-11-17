package com.luminaread.util;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Owns a response stream and closes it if its body transfer stalls beyond the deadline. */
public final class TimedInput extends FilterInputStream {
    private final CountDownLatch finished = new CountDownLatch(1);
    private volatile boolean timedOut;

    public TimedInput(InputStream input, Duration timeout) {
        super(input);
        long millis = timeout.toMillis();
        if (millis <= 0) throw new IllegalArgumentException("Timeout must be positive");
        Thread watchdog = new Thread(() -> {
            try {
                if (!finished.await(millis, TimeUnit.MILLISECONDS)) {
                    timedOut = true;
                    in.close();
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } catch (IOException ignored) {
                // The reading thread reports the timeout; closing a failed connection may also fail.
            }
        }, "LuminaRead-TransferTimeout");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    @Override public int read() throws IOException {
        byte[] value = new byte[1];
        return read(value, 0, 1) < 0 ? -1 : value[0] & 0xff;
    }

    @Override public int read(byte[] bytes, int offset, int length) throws IOException {
        check();
        try {
            int count = in.read(bytes, offset, length);
            check();
            return count;
        } catch (IOException ex) {
            check();
            throw ex;
        }
    }

    private void check() throws IOException {
        if (timedOut) throw new HttpTimeoutException("Сервер не завершил передачу вовремя.");
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Загрузка отменена.");
    }

    @Override public void close() throws IOException {
        finished.countDown();
        in.close();
    }
}
