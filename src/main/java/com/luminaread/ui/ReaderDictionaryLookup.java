package com.luminaread.ui;

import com.luminaread.service.IDictionaryService;
import javax.swing.SwingWorker;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

/** Manages the dictionary request associated with the active reader screen. */
final class ReaderDictionaryLookup implements AutoCloseable {
    private final IDictionaryService dictionary;
    private SwingWorker<String, Void> current;
    private long generation;
    private boolean closed;

    ReaderDictionaryLookup(IDictionaryService dictionary) { this.dictionary = Objects.requireNonNull(dictionary); }

    void lookup(String query, Consumer<String> success, Consumer<Throwable> failure) {
        Objects.requireNonNull(query);
        Objects.requireNonNull(success);
        Objects.requireNonNull(failure);
        if (closed) throw new IllegalStateException("Reader is closed");
        cancel();
        long request = generation;
        current = new SwingWorker<>() {
            @Override protected String doInBackground() { return dictionary.lookupAndTranslate(query); }
            @Override protected void done() {
                if (closed || request != generation) return;
                current = null;
                try { success.accept(get()); }
                catch (CancellationException ignored) { /* Navigation cancelled the request. */ }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); failure.accept(ex); }
                catch (ExecutionException ex) { failure.accept(ex.getCause() != null ? ex.getCause() : ex); }
            }
        };
        current.execute();
    }

    void cancel() {
        generation++;
        if (current != null) { current.cancel(true); current = null; }
    }

    @Override public void close() { if (!closed) { cancel(); closed = true; } }
}
