package com.luminaread.service;

import com.luminaread.model.Book;
import com.luminaread.util.AtomicFile;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

/** Saves small, immutable reading checkpoints independently of the library catalog. */
final class ProgressStore {
    private static final int VERSION_1 = 0x4c525001;
    private static final int VERSION = 0x4c525002;
    private static final Pattern BOOK_ID = Pattern.compile("[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}");
    private static final Logger LOG = Logger.getLogger(ProgressStore.class.getName());

    private final Path dir;
    private final Map<String, Position> pending = new HashMap<>();
    private final Map<String, Position> failed = new HashMap<>();
    private final ThreadPoolExecutor writer = new ThreadPoolExecutor(0, 1, 2, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(), task -> {
                Thread thread = new Thread(task, "LuminaRead-Progress");
                thread.setDaemon(true);
                return thread;
            });
    private boolean scheduled;
    private volatile IOException lastFailure;

    ProgressStore(Path dir) {
        this.dir = dir;
    }

    synchronized void save(Book book) {
        enqueue(book.getId(), Position.from(book));
    }

    synchronized void remove(String id) {
        enqueue(id, null);
    }

    private void enqueue(String id, Position position) {
        if (!validId(id)) return;
        pending.put(id, position);
        if (!scheduled) {
            scheduled = true;
            writer.execute(this::drain);
        }
    }

    boolean hasError() {
        return lastFailure != null;
    }

    /** Ordered behind previously queued writes. Call from a background task. */
    void flush() throws InterruptedException {
        try {
            writer.submit(() -> {
                writePending();
                if (lastFailure != null) throw new UncheckedIOException("Не удалось сохранить прогресс чтения", lastFailure);
            }).get();
        } catch (ExecutionException ex) {
            if (ex.getCause() instanceof RuntimeException error) throw error;
            throw new IllegalStateException(ex.getCause());
        }
    }

    private void drain() {
        writePending();
        synchronized (this) {
            scheduled = !pending.isEmpty();
            if (scheduled) writer.execute(this::drain);
        }
    }

    private void writePending() {
        Map<String, Position> batch;
        synchronized (this) {
            batch = new HashMap<>(failed);
            batch.putAll(pending);
            pending.clear();
            failed.clear();
        }
        IOException failure = null;
        for (var entry : batch.entrySet()) {
            try {
                write(entry.getKey(), entry.getValue());
            } catch (IOException | SecurityException ex) {
                failed.put(entry.getKey(), entry.getValue());
                failure = ex instanceof IOException io ? io : new IOException(ex);
                LOG.log(Level.WARNING, "Не удалось записать прогресс чтения", ex);
            }
        }
        lastFailure = failure;
    }

    private void write(String id, Position position) throws IOException {
        if (position == null && Files.notExists(dir, NOFOLLOW_LINKS)) return;
        if (position != null) Files.createDirectories(dir);
        if (!Files.isDirectory(dir, NOFOLLOW_LINKS)) throw new IOException("Недоступна папка прогресса: " + dir);
        Path file = dir.resolve(id);
        if (position == null) Files.deleteIfExists(file);
        else AtomicFile.write(file, out -> position.write(new DataOutputStream(out)));
    }

    void restore(Book book) {
        if (!validId(book.getId())) return;
        Path file = dir.resolve(book.getId());
        try {
            if (!Files.isDirectory(dir, NOFOLLOW_LINKS) || !Files.isRegularFile(file, NOFOLLOW_LINKS)) return;
            read(book, file);
        } catch (IOException | SecurityException ex) {
            LOG.log(Level.WARNING, "Не удалось восстановить прогресс; используется состояние библиотеки", ex);
        }
    }

    private void read(Book book, Path file) throws IOException {
        try (var in = new DataInputStream(Files.newInputStream(file, NOFOLLOW_LINKS))) {
            int version = in.readInt();
            if (version != VERSION && version != VERSION_1) throw new IOException("Неизвестный формат");
            var position = new Position(in.readInt(), in.readInt(), in.readInt(), in.readDouble(),
                    in.readBoolean(), in.readLong(), in.readLong(), version == VERSION_1 ? -1 : in.readInt());
            if (in.read() != -1) throw new IOException("Лишние данные в записи прогресса");
            position.validate();
            position.apply(book);
        }
    }

    private static boolean validId(String id) {
        return id != null && BOOK_ID.matcher(id).matches();
    }

    private record Position(int chapter, int page, int scroll, double percent,
                            boolean finished, long opened, long seconds, int textOffset) {
        static Position from(Book book) {
            return new Position(book.getCurrentChapterIndex(), book.getCurrentPageInChapter(), book.getScrollOffset(),
                    book.getProgressPercent(), book.isFinished(), book.getLastOpenedTimestamp(), book.getTotalReadingSeconds(),
                    book.getTextOffset());
        }

        void write(DataOutputStream out) throws IOException {
            validate();
            out.writeInt(VERSION);
            out.writeInt(chapter);
            out.writeInt(page);
            out.writeInt(scroll);
            out.writeDouble(percent);
            out.writeBoolean(finished);
            out.writeLong(opened);
            out.writeLong(seconds);
            out.writeInt(textOffset);
        }

        private void validate() throws IOException {
            if (chapter < 0 || page < 0 || scroll < 0 || opened < 0 || seconds < 0
                    || textOffset < -1 || textOffset == Integer.MAX_VALUE
                    || !Double.isFinite(percent) || percent < 0 || percent > 100) {
                throw new IOException("Некорректная позиция чтения");
            }
        }

        void apply(Book book) {
            book.setCurrentChapterIndex(chapter);
            book.setCurrentPageInChapter(page);
            book.setScrollOffset(scroll);
            book.setTextOffset(textOffset);
            book.setProgressPercent(percent);
            book.setFinished(finished);
            book.setLastOpenedTimestamp(opened);
            book.addReadingSeconds(Math.max(0, seconds - book.getTotalReadingSeconds()));
        }
    }
}
