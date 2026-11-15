package com.luminaread.ui;

import javax.swing.SwingWorker;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

/** Owns one replaceable request. Its methods and callbacks run on the Swing event thread. */
final class LatestSwingRequest<T> {
    private SwingWorker<T, Void> current;

    void start(Callable<T> load, Consumer<T> showResult, Consumer<Throwable> showError) {
        cancel();
        current = new SwingWorker<>() {
            @Override protected T doInBackground() throws Exception {
                return load.call();
            }

            @Override protected void done() {
                if (current != this) return;
                current = null;
                try {
                    showResult.accept(get());
                } catch (CancellationException expected) {
                    // Superseded requests should never update the view.
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    showError.accept(ex);
                } catch (ExecutionException ex) {
                    showError.accept(ex.getCause());
                } catch (RuntimeException ex) {
                    showError.accept(ex);
                }
            }
        };
        current.execute();
    }

    void cancel() {
        SwingWorker<T, Void> superseded = current;
        current = null;
        if (superseded != null) superseded.cancel(true);
    }
}
