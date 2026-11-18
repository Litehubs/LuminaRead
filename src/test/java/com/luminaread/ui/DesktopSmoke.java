package com.luminaread.ui;

import com.luminaread.model.Book;
import com.luminaread.model.ReaderSettings;
import com.luminaread.service.LibraryStorageService;
import com.luminaread.service.OnlineCatalogService;
import com.luminaread.service.TtsService;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JEditorPane;
import javax.swing.KeyStroke;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowEvent;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Visible-frame smoke scenario, run separately from the headless unit suite with an isolated profile. */
public final class DesktopSmoke {
    private DesktopSmoke() {}

    public static void main(String[] args) throws Exception {
        if (GraphicsEnvironment.isHeadless()) throw new AssertionError("Desktop display required");
        Path profile = Path.of(System.getProperty("user.home"));
        require(Files.isDirectory(profile) && !Files.exists(profile.resolve(".luminaread")), "Use an empty profile");
        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        ModernUiFactory.installGlobalUiFonts();

        var storage = new PausableLibrary();
        storage.getSettings().setReadingMode(ReaderSettings.ReadingMode.PAGED);
        Path first = book(profile, "one.txt");
        Path second = book(profile, "two.txt");
        Path third = book(profile, "three.txt");
        MainWindow[] windows = new MainWindow[1];
        try {
            onEdt(() -> {
                windows[0] = new MainWindow(storage, new OnlineCatalogService(), new TtsService(), text -> "");
                windows[0].setSize(1040, 720);
                windows[0].setVisible(true);
                find(windows[0], LibraryPanel.class).importFiles(new File[]{first.toFile(), second.toFile()});
            });
            await(() -> storage.getAllBooks().size() == 2 && dialog("Импорт книг") == null, "bulk import");
            storage.pause = true;
            onEdt(() -> find(windows[0], LibraryPanel.class).importFiles(new File[]{third.toFile(), first.toFile()}));
            require(storage.entered.await(10, TimeUnit.SECONDS), "first file did not start");
            onEdt(() -> button(dialog("Импорт книг"), "Остановить импорт").doClick());
            storage.complete.countDown();
            await(() -> dialog("Импорт остановлен") != null, "cooperative cancellation");
            onEdt(() -> dialog("Импорт остановлен").dispose());
            require(storage.getAllBooks().size() == 3, "Cancellation lost a completed file");
            require(storage.attempts == 1, "Cancellation started another file");

            Book book = storage.getAllBooks().get(0);
            // Some CI desktops have no window manager. Exercise native focus when the OS grants it.
            boolean[] checkFocus = {java.util.Arrays.asList(args).contains("--check-focus")};
            onEdt(() -> {
                var shelf = find(windows[0], LibraryPanel.class);
                checkFocus[0] |= windows[0].isFocused();
                if (checkFocus[0]) find(shelf, JTextField.class).requestFocusInWindow();
            });
            if (checkFocus[0]) {
                await(() -> find(find(windows[0], LibraryPanel.class), JTextField.class).isFocusOwner(), "focus search");
                onEdt(() -> {
                    var search = find(find(windows[0], LibraryPanel.class), JTextField.class);
                    search.setText(book.getTitle());
                    require(search.isFocusOwner(), "Typing in the shelf lost keyboard focus");
                });
            }
            onEdt(() -> windows[0].openBookInReader(book));
            await(() -> {
                var reader = find(windows[0], ReaderPanel.class);
                return reader.isShowing() && find(reader, JEditorPane.class).getText().contains("Smoke paragraph");
            }, "reader open");
            onEdt(() -> {
                for (ThemePalette theme : ThemePalette.PRESETS.values()) {
                    windows[0].applyGlobalTheme(theme);
                    windows[0].validate();
                    require(find(windows[0], ReaderPanel.class).isShowing(), "Theme hid the reader");
                }
                var reader = find(windows[0], ReaderPanel.class);
                Object key = reader.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                        .get(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0));
                require(key != null && reader.getActionMap().get(key) != null, "Missing keyboard navigation");
                reader.getActionMap().get(key).actionPerformed(new ActionEvent(reader, 0, "desktop smoke"));
            });
            await(() -> book.getCurrentPageInChapter() == 1, "keyboard page turn");
            onEdt(() -> button(find(windows[0], ReaderPanel.class), "< Полка").doClick());
            await(() -> find(windows[0], LibraryPanel.class).isShowing(), "return to library");
            if (checkFocus[0]) {
                await(() -> windows[0].getFocusOwner() instanceof JButton focused
                        && (focused.getText().equals("Читать") || focused.getText().equals("Продолжить чтение"))
                        && SwingUtilities.isDescendingFrom(focused, find(windows[0], LibraryPanel.class)), "focus opened book");
            }
            onEdt(() -> windows[0].dispatchEvent(new WindowEvent(windows[0], WindowEvent.WINDOW_CLOSING)));
            await(() -> !windows[0].isDisplayable(), "window close and progress flush");
            var reopened = new LibraryStorageService();
            require(reopened.getAllBooks().stream().anyMatch(b -> b.getId().equals(book.getId())
                    && b.getCurrentPageInChapter() == 1), "Restart lost the page");

            System.out.println("Desktop smoke passed: " + System.getProperty("os.name") + " / " + UIManager.getLookAndFeel().getName());
        } finally {
            storage.complete.countDown();
            onEdt(() -> {
                for (Window window : Window.getWindows()) window.dispose();
            });
        }
    }

    private static Path book(Path dir, String name) throws Exception {
        return Files.writeString(dir.resolve(name), "Название: " + "Длинное название книги ".repeat(30) + "\n\n"
                + "Smoke paragraph with words and punctuation. ".repeat(500));
    }

    private static void await(BooleanSupplier condition, String step) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            boolean[] complete = new boolean[1];
            onEdt(() -> complete[0] = condition.getAsBoolean());
            if (complete[0]) return;
            Thread.sleep(40);
        }
        throw new IllegalStateException("Timed out: " + step);
    }

    private static void onEdt(Runnable action) throws Exception { SwingUtilities.invokeAndWait(action); }

    private static JDialog dialog(String title) {
        for (Window window : Window.getWindows()) {
            if (window instanceof JDialog dialog && dialog.isShowing() && dialog.getTitle().equals(title)) return dialog;
        }
        return null;
    }

    private static JButton button(Container root, String text) {
        for (Component c : root.getComponents()) {
            if (c instanceof JButton b && text.equals(b.getText())) return b;
            if (c instanceof Container child) {
                JButton nested = button(child, text);
                if (nested != null) return nested;
            }
        }
        return null;
    }

    private static <T> T find(Container root, Class<T> type) {
        if (type.isInstance(root)) return type.cast(root);
        for (Component c : root.getComponents()) {
            if (c instanceof Container child) {
                T nested = find(child, type);
                if (nested != null) return nested;
            }
        }
        return null;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class PausableLibrary extends LibraryStorageService {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch complete = new CountDownLatch(1);
        volatile boolean pause;
        volatile int attempts;

        @Override public Book importBookFile(File file, boolean copy) throws Exception {
            if (pause) {
                attempts++;
                entered.countDown();
                require(complete.await(10, TimeUnit.SECONDS), "Import gate timed out");
            }
            return super.importBookFile(file, copy);
        }
    }
}
