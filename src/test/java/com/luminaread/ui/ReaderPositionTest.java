package com.luminaread.ui;

import com.luminaread.model.Book;
import com.luminaread.model.Bookmark;
import com.luminaread.model.ReaderSettings;
import com.luminaread.service.LibraryStorageService;
import com.luminaread.service.TtsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.JButton;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReaderPositionTest {
    @Test
    void restoresSavedScrollAndUsesTheBookmarksOwnPosition(@TempDir Path home) throws Exception {
        String main = Path.of(ReaderPanel.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        String tests = Path.of(getClass().getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        Path log = home.resolve("reading-position.log");
        Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Djava.awt.headless=true", "-Dfile.encoding=UTF-8", "-Duser.home=" + home,
                "-cp", main + File.pathSeparator + tests, Probe.class.getName())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(child.waitFor(25, TimeUnit.SECONDS), "Reader position probe did not finish");
            assertEquals(0, child.exitValue(), Files.readString(log));
        } finally {
            child.destroyForcibly();
            child.waitFor(5, TimeUnit.SECONDS);
        }
    }

    public static class Probe {
        public static void main(String[] args) throws Exception {
            Path source = Path.of(System.getProperty("user.home"), "long-book.txt");
            Files.writeString(source, "Строка в книге, достаточно длинная для проверки прокрутки.\n\n".repeat(140));

            var storage = new LibraryStorageService();
            storage.getSettings().setReadingMode(ReaderSettings.ReadingMode.SCROLL);
            Book book = storage.importBookFile(source.toFile(), false);
            var contentField = ReaderPanel.class.getDeclaredField("currentContent");
            var scrollField = ReaderPanel.class.getDeclaredField("editorScrollPane");
            contentField.setAccessible(true);
            scrollField.setAccessible(true);

            ReaderPanel[] readers = new ReaderPanel[2];
            try {
                SwingUtilities.invokeAndWait(() -> {
                    readers[0] = createReader(storage);
                    readers[0].openBook(book);
                });
                awaitLayout(readers[0], contentField, scrollField, 0);

                SwingUtilities.invokeAndWait(() -> {
                    scroll(readers[0], scrollField).getVerticalScrollBar().setValue(140);
                    storage.addBookmark(new Bookmark(book.getId(), book.getTitle(),
                            0, "Глава", 0, 140, "Моя закладка", ""));
                    scroll(readers[0], scrollField).getVerticalScrollBar().setValue(420);
                    readers[0].applyTheme(ThemePalette.byId("EINK_MATTE"));
                    readers[0].disposeResources();
                });

                var reopenedStorage = new LibraryStorageService();
                Book reopened = reopenedStorage.getAllBooks().get(0);
                require(reopened.getScrollOffset() == 420, "Closing lost the offset on disk");
                SwingUtilities.invokeAndWait(() -> {
                    readers[1] = createReader(reopenedStorage);
                    readers[1].openBook(reopened);
                });
                awaitLayout(readers[1], contentField, scrollField, 420);

                SwingUtilities.invokeAndWait(() -> {
                    StatsAndNotesPanel notes = new StatsAndNotesPanel(reopenedStorage, (id, position) -> {
                        require(reopened.getId().equals(id), "Bookmark opened the wrong book");
                        readers[1].openBook(reopened, position);
                    });
                    JButton jump = findJump(notes);
                    require(jump != null, "Bookmark is missing");
                    jump.doClick();
                });
                awaitLayout(readers[1], contentField, scrollField, 140);
            } finally {
                SwingUtilities.invokeAndWait(() -> {
                    for (ReaderPanel reader : readers) if (reader != null) reader.disposeResources();
                });
            }
        }

        private static ReaderPanel createReader(LibraryStorageService storage) {
            var reader = new ReaderPanel(storage, new TtsService(), text -> "", () -> {}, theme -> {});
            reader.setSize(960, 700);
            layout(reader);
            return reader;
        }

        private static void awaitLayout(ReaderPanel reader, Field content, Field viewport, int expected) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline) {
                boolean[] ready = new boolean[1];
                SwingUtilities.invokeAndWait(() -> {
                    layout(reader);
                    var bar = scroll(reader, viewport).getVerticalScrollBar();
                    ready[0] = read(content, reader) != null
                            && bar.getMaximum() - bar.getVisibleAmount() >= 420
                            && bar.getValue() == expected;
                });
                if (ready[0]) return;
                Thread.sleep(20);
            }
            throw new AssertionError("Reader did not reach scroll offset " + expected);
        }

        private static JButton findJump(Container parent) {
            for (Component child : parent.getComponents()) {
                if (child instanceof JButton button && "Перейти".equals(button.getText())) return button;
                if (child instanceof Container container) {
                    JButton button = findJump(container);
                    if (button != null) return button;
                }
            }
            return null;
        }

        private static void layout(Container parent) {
            parent.doLayout();
            for (Component child : parent.getComponents()) {
                if (child instanceof Container container) layout(container);
            }
        }

        private static JScrollPane scroll(ReaderPanel reader, Field field) {
            return (JScrollPane) read(field, reader);
        }

        private static Object read(Field field, ReaderPanel reader) {
            try {
                return field.get(reader);
            } catch (IllegalAccessException ex) {
                throw new AssertionError(ex);
            }
        }

        private static void require(boolean condition, String message) {
            if (!condition) throw new AssertionError(message);
        }
    }
}
