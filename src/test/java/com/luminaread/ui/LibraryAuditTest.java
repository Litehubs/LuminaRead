package com.luminaread.ui;

import com.luminaread.model.Book;
import com.luminaread.model.Bookmark;
import com.luminaread.model.Highlight;
import com.luminaread.service.LibraryStorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JSplitPane;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class LibraryAuditTest {
    @Test void importFromFolderUsesTheSameFileFilter(@TempDir Path folder) throws Exception {
        var expected = List.of("book.RTF", "page.htm", "draft.markdown", "book.fb2.zip");
        Files.writeString(folder.resolve("book.RTF"), "{\\rtf1 A book.}");
        Files.writeString(folder.resolve("page.htm"), "<html><body>A book.</body></html>");
        Files.writeString(folder.resolve("draft.markdown"), "# Book\n\nA chapter.");
        try (var zip = new ZipOutputStream(Files.newOutputStream(folder.resolve("book.fb2.zip")))) {
            zip.putNextEntry(new ZipEntry("book.fb2"));
            zip.write(fb2("").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        Files.writeString(folder.resolve("other.json"), "{}");
        Files.createDirectory(folder.resolve("folder.pdf"));
        var files = LibraryPanel.booksIn(folder);
        assertEquals(expected.stream().sorted().toList(), files.stream().map(java.io.File::getName).sorted().toList());
        var library = inProfile(folder, LibraryStorageService::new);
        for (var file : files) {
            assertTrue(LibraryPanel.BOOK_FILES.accept(file));
            assertFalse(library.loadBookContent(library.importBookFile(file, false)).getChapters().isEmpty());
        }
    }

    @Test void reimportReplacesAndRemovesCoverWithTheSameBookId(@TempDir Path folder) throws Exception {
        Path book = folder.resolve("cover.fb2");
        var library = inProfile(folder, LibraryStorageService::new);
        Files.writeString(book, fb2(cover(Color.RED)));
        Book first = library.importBookFile(book.toFile(), false);
        var red = ModernUiFactory.getOrCreateBookCoverIcon(first, 40, 60);
        assertEquals(Color.RED.getRGB(), ((BufferedImage) red.getImage()).getRGB(20, 30));
        assertSame(red, ModernUiFactory.getOrCreateBookCoverIcon(first, 40, 60));

        Files.writeString(book, fb2(cover(Color.BLUE)));
        Book changed = library.importBookFile(book.toFile(), false);
        assertEquals(first.getId(), changed.getId());
        var blue = ModernUiFactory.getOrCreateBookCoverIcon(changed, 40, 60);
        assertEquals(Color.BLUE.getRGB(), ((BufferedImage) blue.getImage()).getRGB(20, 30));

        Files.writeString(book, fb2(""));
        Book noCover = library.importBookFile(book.toFile(), false);
        assertNull(noCover.getCoverImage());
        assertNotSame(blue, ModernUiFactory.getOrCreateBookCoverIcon(noCover, 40, 60));
    }

    @Test void cacheEvictsOldCoversWhileKeepingRecentlyUsedOnes() {
        var oldest = new Book("First", "Author", "/first", "TXT");
        var oldIcon = ModernUiFactory.getOrCreateBookCoverIcon(oldest, 24, 32);
        var last = oldest;
        for (int i = 0; i < 300; i++) {
            last = new Book("Book " + i, "Author", "/book" + i, "TXT");
            ModernUiFactory.getOrCreateBookCoverIcon(last, 24, 32);
        }
        var recent = ModernUiFactory.getOrCreateBookCoverIcon(last, 24, 32);
        assertSame(recent, ModernUiFactory.getOrCreateBookCoverIcon(last, 24, 32));
        assertNotSame(oldIcon, ModernUiFactory.getOrCreateBookCoverIcon(oldest, 24, 32));
    }

    @Test void notesPagesKeepLargeCollectionsReachableAndClampAfterDeletion(@TempDir Path folder) throws Exception {
        var bookmarks = new CopyOnWriteArrayList<>(IntStream.range(0, 2001)
                .mapToObj(i -> new Bookmark("book", "Book", i, "Chapter", i, 12, "Bookmark " + i, "")).toList());
        var highlights = new CopyOnWriteArrayList<>(IntStream.range(0, 2001)
                .mapToObj(i -> new Highlight("book", "Book", i, "Chapter", "Quote " + i, "Note", "#abc")).toList());
        var library = inProfile(folder, () -> new LibraryStorageService() {
            @Override public List<Bookmark> getBookmarks(String id) { return List.copyOf(bookmarks); }
            @Override public List<Highlight> getHighlights(String id) { return List.copyOf(highlights); }
            @Override public void removeBookmark(String id) { bookmarks.removeIf(note -> note.getId().equals(id)); }
            @Override public void removeHighlight(String id) { highlights.removeIf(note -> note.getId().equals(id)); }
        });
        var jumped = new AtomicReference<ReadingPosition>();
        var panel = new AtomicReference<StatsAndNotesPanel>();
        SwingUtilities.invokeAndWait(() -> {
            panel.set(new StatsAndNotesPanel(library, (book, position) -> jumped.set(position)));
            var split = split(panel.get());
            assertTrue(children(split).count() < 400);
            click(split.getLeftComponent(), "Последняя");
            click(split.getRightComponent(), "Последняя");
            click(split.getLeftComponent(), "Перейти");
            assertEquals(bookmarks.get(2000).getId(), jumped.get().bookmarkId());
            assertEquals(2000, jumped.get().pageIndex());
            click(split.getRightComponent(), "Открыть");
            assertEquals(2000, jumped.get().chapterIndex());
            click(split.getLeftComponent(), "Удалить");
            click(split.getRightComponent(), "Удалить");
        });
        library.awaitWrites();
        SwingUtilities.invokeAndWait(() -> {
            assertEquals(2000, bookmarks.size());
            assertEquals(2000, highlights.size());
            var split = split(panel.get());
            click(split.getLeftComponent(), "Перейти");
            assertEquals(1980, jumped.get().chapterIndex());
            click(split.getRightComponent(), "Открыть");
            assertEquals(1980, jumped.get().chapterIndex());
        });
    }

    private static <T> T inProfile(Path folder, Supplier<T> create) {
        String previous = System.getProperty("user.home");
        System.setProperty("user.home", folder.toString());
        try {
            return create.get();
        } finally {
            System.setProperty("user.home", previous);
        }
    }

    private static JSplitPane split(StatsAndNotesPanel panel) {
        return children(panel).filter(JSplitPane.class::isInstance).map(JSplitPane.class::cast).findFirst().orElseThrow();
    }

    private static void click(Component root, String text) {
        children(root).filter(JButton.class::isInstance).map(JButton.class::cast)
                .filter(button -> text.equals(button.getText()) && button.isEnabled()).findFirst().orElseThrow().doClick(0);
    }

    private static Stream<Component> children(Component root) {
        return root instanceof Container container
                ? Stream.concat(Stream.of(root), Stream.of(container.getComponents()).flatMap(LibraryAuditTest::children))
                : Stream.of(root);
    }

    private static String cover(Color color) throws Exception {
        var image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(color);
        graphics.fillRect(0, 0, 4, 4);
        graphics.dispose();
        var png = new ByteArrayOutputStream();
        ImageIO.write(image, "png", png);
        return Base64.getEncoder().encodeToString(png.toByteArray());
    }

    private static String fb2(String cover) {
        String coverPage = cover.isEmpty() ? "" : "<coverpage><image l:href='#cover'/></coverpage>";
        String binary = cover.isEmpty() ? "" : "<binary id='cover' content-type='image/png'>" + cover + "</binary>";
        return """
                <FictionBook xmlns:l="http://www.w3.org/1999/xlink"><description><title-info>
                <book-title>Library audit</book-title>%s</title-info></description>
                <body><section><p>A complete chapter.</p></section></body>%s</FictionBook>
                """.formatted(coverPage, binary);
    }
}
