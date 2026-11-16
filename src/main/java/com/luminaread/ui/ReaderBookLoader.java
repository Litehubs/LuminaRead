package com.luminaread.ui;

import com.luminaread.model.Book;
import com.luminaread.model.BookContent;
import javax.swing.SwingWorker;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

/** Owns the reader's current asynchronous load and prevents outdated results reaching the view. */
final class ReaderBookLoader implements AutoCloseable {
    @FunctionalInterface interface LoadBook { BookContent load(Book book) throws Exception; }
    private final LoadBook loadBook;
    private SwingWorker<BookContent, Void> current;
    private long generation;
    private boolean closed;

    ReaderBookLoader(LoadBook loadBook) { this.loadBook = Objects.requireNonNull(loadBook); }

    void open(Book book, Consumer<BookContent> success, Consumer<Throwable> failure) {
        Objects.requireNonNull(book);
        Objects.requireNonNull(success);
        Objects.requireNonNull(failure);
        if (closed) throw new IllegalStateException("Reader is closed");
        cancel();
        long request = generation;
        current = new SwingWorker<>() {
            @Override protected BookContent doInBackground() throws Exception { return loadBook.load(book); }
            @Override protected void done() {
                if (closed || request != generation) return;
                current = null;
                try { success.accept(get()); }
                catch (CancellationException ignored) { /* Cancellation is an expected navigation action. */ }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); failure.accept(ex); }
                catch (ExecutionException ex) { failure.accept(ex.getCause() != null ? ex.getCause() : ex); }
                catch (RuntimeException ex) { failure.accept(ex); }
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
