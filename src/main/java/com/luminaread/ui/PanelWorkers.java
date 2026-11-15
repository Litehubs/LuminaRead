package com.luminaread.ui;

import javax.swing.SwingWorker;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Owns background Swing workers for the lifetime of one panel. */
final class PanelWorkers implements Executor, AutoCloseable {
    private final ExecutorService images;
    private final Set<SwingWorker<?, ?>> workers = new HashSet<>();
    private boolean closed;

    PanelWorkers(int imageThreads) {
        images = Executors.newFixedThreadPool(imageThreads);
    }

    @Override public void execute(Runnable command) {
        images.execute(command);
    }

    synchronized boolean start(SwingWorker<?, ?> worker) {
        if (closed) return false;
        workers.add(worker);
        worker.addPropertyChangeListener(event -> {
            if ("state".equals(event.getPropertyName()) && event.getNewValue() == SwingWorker.StateValue.DONE) {
                synchronized (PanelWorkers.this) { workers.remove(worker); }
            }
        });
        worker.execute();
        return true;
    }

    /** Runs a request off the EDT and delivers its result only to the current view. */
    <T> void submit(BooleanSupplier isCurrent, Callable<T> task, Consumer<T> success,
                    Consumer<Throwable> failure) {
        start(new SwingWorker<T, Void>() {
            @Override protected T doInBackground() throws Exception {
                return task.call();
            }

            @Override protected void done() {
                if (!isCurrent.getAsBoolean() || isCancelled()) return;
                T result;
                try {
                    result = get();
                } catch (CancellationException expected) {
                    return;
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    failure.accept(ex);
                    return;
                } catch (ExecutionException ex) {
                    failure.accept(ex.getCause());
                    return;
                }
                try {
                    success.accept(result);
                } catch (RuntimeException ex) {
                    failure.accept(ex);
                }
            }
        });
    }

    @Override public void close() {
        Set<SwingWorker<?, ?>> pending;
        synchronized (this) {
            if (closed) return;
            closed = true;
            images.shutdownNow();
            pending = new HashSet<>(workers);
            workers.clear();
        }
        pending.forEach(worker -> worker.cancel(true));
    }
}
