package com.luminaread.ui;

import com.luminaread.model.Book;
import com.luminaread.model.ReaderSettings;
import com.luminaread.service.LibraryStorageService;
import com.luminaread.service.OnlineCatalogService;
import com.luminaread.service.TtsService;

import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JEditorPane;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.KeyboardFocusManager;
import java.awt.Window;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** Measures repeatable library-to-reader transitions on a real desktop. */
public final class MotionProbe {
    private MotionProbe() {}

    public static void main(String[] args) throws Exception {
        Path profile = Path.of(System.getProperty("user.home"));
        if (Files.exists(profile.resolve(".luminaread"))) throw new IllegalArgumentException("Use a temporary profile");
        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        ModernUiFactory.installGlobalUiFonts();
        LibraryStorageService library = new LibraryStorageService();
        library.getSettings().setReduceMotion(Boolean.getBoolean("luminaread.reduceMotion"));
        Book book = fillLibrary(library, profile);
        var windows = new MainWindow[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                var window = new MainWindow(library, new OnlineCatalogService(), new TtsService(), text -> "");
                windows[0] = window;
                window.setSize(1420, 900);
                window.setVisible(true);
                if (Boolean.getBoolean("luminaread.motion.checkFocus")) {
                    var desktop = java.awt.Desktop.getDesktop();
                    if (desktop.isSupported(java.awt.Desktop.Action.APP_REQUEST_FOREGROUND)) desktop.requestForeground(true);
                    window.toFront();
                    window.requestFocus();
                }
                System.out.println("displayScale " + window.getGraphicsConfiguration().getDefaultTransform().getScaleX());
            });
            for (int turn = 0; turn < 8; turn++) {
                SwingUtilities.invokeAndWait(() -> windows[0].openBookInReader(book));
                waitForText(windows[0]);
                if (Boolean.getBoolean("luminaread.motion.checkFocus")) {
                    waitForFocus(JEditorPane.class);
                }
                SwingUtilities.invokeAndWait(() -> {
                    var reader = find(windows[0], ReaderPanel.class);
                    reader.navigateForward();
                    reader.navigateForward();
                });
                // Yield to the native repaint queue while the transition is in flight.
                Thread.sleep(80);
                SwingUtilities.invokeAndWait(() -> {
                    var reader = find(windows[0], ReaderPanel.class);
                    click(reader, "< Полка");
                    if (reader.isShowing()) throw new AssertionError("The library did not replace the reader");
                });
                if (Boolean.getBoolean("luminaread.motion.checkFocus")) {
                    waitForFocus(BookCard.class);
                }
            }
            if (Boolean.getBoolean("luminaread.motion.checkFocus")) verifyReflow(windows[0], library, profile);
            SwingUtilities.invokeAndWait(() -> windows[0].dispatchEvent(
                    new WindowEvent(windows[0], WindowEvent.WINDOW_CLOSING)));
            for (int i = 0; i < 500 && windows[0].isDisplayable(); i++) Thread.sleep(20);
            if (windows[0].isDisplayable()) throw new AssertionError("Window stayed open");
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                for (Window window : Window.getWindows()) window.dispose();
            });
        }
    }

    private static Book fillLibrary(LibraryStorageService library, Path profile) throws Exception {
        var image = new BufferedImage(130, 180, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(new Color(58, 106, 144));
        graphics.fillRect(0, 0, 130, 180);
        graphics.setColor(Color.WHITE);
        graphics.drawString("LuminaRead", 8, 60);
        graphics.dispose();
        var cover = new ByteArrayOutputStream();
        ImageIO.write(image, "png", cover);
        Book first = null;
        for (int i = 0; i < 40; i++) {
            Path file = Files.writeString(profile.resolve("book-" + i + ".txt"),
                    "Название: Book " + i + "\n\n" + "Motion fixture with enough words to turn pages.\n\n".repeat(240));
            Book book = library.importBookFile(file.toFile(), false);
            book.setCoverImage(cover.toByteArray());
            if (first == null) first = book;
        }
        library.saveState();
        return first;
    }

    private static void waitForText(MainWindow window) throws Exception {
        for (int i = 0; i < 250; i++) {
            boolean[] ready = new boolean[1];
            SwingUtilities.invokeAndWait(() -> ready[0] = find(find(window, ReaderPanel.class), JEditorPane.class)
                    .getText().contains("Motion fixture"));
            if (ready[0]) return;
            Thread.sleep(20);
        }
        throw new AssertionError("Book did not open");
    }

    private static void verifyReflow(MainWindow window, LibraryStorageService library, Path profile) throws Exception {
        StringBuilder source = new StringBuilder();
        for (int i = 0; i < 160; i++) {
            source.append("Paragraph ").append(i).append(" with enough visible text to verify wrapping ")
                    .append("after changing the font and viewport.".repeat(3)).append("\n\n");
        }
        Book book = library.importBookFile(Files.writeString(profile.resolve("reflow.txt"), source).toFile(), false);
        library.getSettings().setReadingMode(ReaderSettings.ReadingMode.SCROLL);
        SwingUtilities.invokeAndWait(() -> window.openBookInReader(book));
        String marker = "Paragraph 60";
        var scrollField = ReaderPanel.class.getDeclaredField("editorScrollPane");
        scrollField.setAccessible(true);
        for (int i = 0; i < 200; i++) {
            boolean[] moved = new boolean[1];
            SwingUtilities.invokeAndWait(() -> {
                var reader = find(window, ReaderPanel.class);
                var editor = find(reader, JEditorPane.class);
                var target = markerBounds(editor, marker);
                if (target == null) return;
                try {
                    var bar = ((JScrollPane) scrollField.get(reader)).getVerticalScrollBar();
                    if (bar.getMaximum() < target.getMaxY()) return;
                    bar.setValue((int) target.getY());
                    reader.stopActiveFeatures();
                    moved[0] = true;
                } catch (IllegalAccessException ex) {
                    throw new AssertionError(ex);
                }
            });
            if (moved[0]) break;
            if (i == 199) throw new AssertionError("Initial paragraph was not laid out");
            Thread.sleep(20);
        }
        if (book.getTextOffset() < 100) throw new AssertionError("The viewport position was not captured");

        SwingUtilities.invokeAndWait(() -> {
            library.getSettings().setFontSize(29);
            find(window, ReaderPanel.class).applyTheme(ThemePalette.byId("NIGHT_AMOLED"));
        });
        awaitVisibleMarker(window, marker);
        SwingUtilities.invokeAndWait(() -> window.setSize(1080, 730));
        awaitVisibleMarker(window, marker);
        SwingUtilities.invokeAndWait(() -> {
            library.getSettings().setReadingMode(ReaderSettings.ReadingMode.PAGED);
            library.getSettings().setTwoColumnSpread(true);
            find(window, ReaderPanel.class).applyTheme(ThemePalette.byId("DAY_PAPER"));
        });
        awaitVisibleMarker(window, marker);
        System.out.println("Reader keeps the marked paragraph after font, viewport and spread changes");
    }

    private static java.awt.geom.Rectangle2D markerBounds(JEditorPane editor, String marker) {
        try {
            var document = editor.getDocument();
            int offset = document.getText(0, document.getLength()).indexOf(marker);
            return offset < 0 ? null : editor.modelToView2D(offset);
        } catch (javax.swing.text.BadLocationException ex) {
            throw new AssertionError(ex);
        }
    }

    private static void awaitVisibleMarker(MainWindow window, String marker) throws Exception {
        for (int i = 0; i < 250; i++) {
            boolean[] visible = new boolean[1];
            SwingUtilities.invokeAndWait(() -> {
                var editor = find(find(window, ReaderPanel.class), JEditorPane.class);
                var target = markerBounds(editor, marker);
                var viewport = editor.getVisibleRect();
                visible[0] = target != null && target.getMaxY() > viewport.y
                        && target.getY() < viewport.getMaxY();
            });
            if (visible[0]) return;
            Thread.sleep(20);
        }
        throw new AssertionError("Reflow lost " + marker);
    }

    private static void waitForFocus(Class<? extends Component> type) throws Exception {
        for (int i = 0; i < 250; i++) {
            boolean[] ready = new boolean[1];
            SwingUtilities.invokeAndWait(() -> {
                Component focus = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
                while (focus != null) {
                    if (type.isInstance(focus)) {
                        ready[0] = true;
                        return;
                    }
                    focus = focus.getParent();
                }
            });
            if (ready[0]) return;
            Thread.sleep(20);
        }
        throw new AssertionError("Focus did not reach " + type.getSimpleName() + ", owner "
                + KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner());
    }

    private static boolean click(Container panel, String text) {
        for (Component child : panel.getComponents()) {
            if (child instanceof JButton button && text.equals(button.getText())) {
                button.doClick(0);
                return true;
            }
            if (child instanceof Container nested && click(nested, text)) return true;
        }
        return false;
    }

    private static <T> T find(Container panel, Class<T> type) {
        for (Component child : panel.getComponents()) {
            if (type.isInstance(child)) return type.cast(child);
            if (child instanceof Container nested) {
                T match = find(nested, type);
                if (match != null) return match;
            }
        }
        return null;
    }
}
