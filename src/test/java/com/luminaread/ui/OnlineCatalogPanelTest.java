package com.luminaread.ui;

import com.luminaread.model.OnlineBookItem;
import com.luminaread.service.LibraryStorageService;
import com.luminaread.service.OnlineCatalogService;
import com.luminaread.service.OnlineCatalogService.SearchResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.swing.*;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;

class OnlineCatalogPanelTest {
    @Test void latestRequestWinsAndErrorsStopTheProgressIndicator(@TempDir Path home) throws Exception {
        String previous = System.getProperty("user.home");
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        try {
            System.setProperty("user.home", home.toString());
            OnlineCatalogPanel[] panel = new OnlineCatalogPanel[1];
            SwingUtilities.invokeAndWait(() -> panel[0] = new OnlineCatalogPanel(new LibraryStorageService(), new OnlineCatalogService(), book -> {}));
            var method = OnlineCatalogPanel.class.getDeclaredMethod("startCatalogLoad", Supplier.class); method.setAccessible(true);
            var statusField = OnlineCatalogPanel.class.getDeclaredField("statusLabel"); statusField.setAccessible(true);
            var progressField = OnlineCatalogPanel.class.getDeclaredField("downloadProgressBar"); progressField.setAccessible(true);
            Supplier<SearchResult> old = () -> {
                started.countDown();
                try { release.await(); } catch (InterruptedException ex) { interrupted.countDown(); Thread.currentThread().interrupt(); }
                throw new IllegalStateException("old failure");
            };
            SwingUtilities.invokeAndWait(() -> invoke(method, panel[0], old));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> invoke(method, panel[0], () -> new SearchResult(List.of(), List.of())));
            await(() -> text(statusField, panel[0]).contains("Книги не найдены"));
            assertTrue(interrupted.await(5, TimeUnit.SECONDS), "Superseded catalog search was not interrupted");
            release.countDown();
            Thread.sleep(200);
            SwingUtilities.invokeAndWait(() -> assertTrue(text(statusField, panel[0]).contains("Книги не найдены")));
            var book = new OnlineBookItem("Available book", "Author", "", "en", "Working");
            SwingUtilities.invokeAndWait(() -> invoke(method, panel[0],
                    () -> new SearchResult(List.of(book), List.of("Offline source"))));
            await(() -> text(statusField, panel[0]).equals("Найдено книг для скачивания: 1 · Не удалось загрузить: Offline source"));
            SwingUtilities.invokeAndWait(() -> assertTrue(MangaSearchRaceTest.Probe.labelExists(panel[0], "Available book")));

            SwingUtilities.invokeAndWait(() -> invoke(method, panel[0], () -> {
                throw new IllegalStateException("offline");
            }));
            await(() -> text(statusField, panel[0]).contains("Не удалось загрузить каталог"));
            SwingUtilities.invokeAndWait(() -> { try { assertFalse(((JProgressBar) progressField.get(panel[0])).isVisible()); } catch (IllegalAccessException ex) { throw new RuntimeException(ex); } });
        } finally {
            release.countDown();
            if (previous == null) System.clearProperty("user.home"); else System.setProperty("user.home", previous);
        }
    }
    private static void invoke(java.lang.reflect.Method method, Object target, Supplier<SearchResult> supplier) {
        try { method.invoke(target, supplier); } catch (ReflectiveOperationException ex) { throw new RuntimeException(ex); }
    }
    private static String text(java.lang.reflect.Field field, Object target) {
        try { return ((JLabel) field.get(target)).getText(); } catch (IllegalAccessException ex) { throw new RuntimeException(ex); }
    }
    private static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            boolean[] success = new boolean[1]; SwingUtilities.invokeAndWait(() -> success[0] = condition.getAsBoolean());
            if (success[0]) return;
            Thread.sleep(20);
        }
        fail("Ожидаемое состояние не появилось");
    }
}
