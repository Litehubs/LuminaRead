package com.luminaread.ui;

import com.luminaread.model.Book;
import com.luminaread.service.LibraryStorageService;
import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class LibraryShelfTest {
    @Test void browsingAndSearchingReachTheWholeShelfWithBoundedCards() throws Exception {
        List<Book> books = IntStream.range(0, 3000)
                .mapToObj(i -> new Book("Book " + i, "Author", "book-" + i, "TXT")).toList();
        var library = new LibraryStorageService() {
            @Override public List<Book> getFilteredBooks(String filter, String query, String sort) {
                return books.stream().filter(book -> book.getTitle().contains(query)).toList();
            }
        };
        var opened = new AtomicReference<Book>();
        SwingUtilities.invokeAndWait(() -> {
            var shelf = new LibraryPanel(library, opened::set, () -> {});
            var reads = buttons(shelf, "Читать");
            int perPage = reads.size();
            assertTrue(perPage > 1 && perPage < 50);
            buttons(shelf, "Дальше").get(0).doClick(0);
            buttons(shelf, "Читать").get(0).doClick(0);
            assertSame(books.get(perPage), opened.get());

            Book last = books.get(2999);
            shelf.focusBook(last.getId());
            var lastPage = buttons(shelf, "Читать");
            lastPage.get(lastPage.size() - 1).doClick(0);
            assertSame(last, opened.get());

            var search = children(shelf).filter(JTextField.class::isInstance)
                    .map(JTextField.class::cast).findFirst().orElseThrow();
            search.setText("Book 1755");
            assertEquals(1, buttons(shelf, "Читать").size());
            buttons(shelf, "Читать").get(0).doClick(0);
            assertSame(books.get(1755), opened.get());
            search.setText("absent");
            assertTrue(buttons(shelf, "Читать").isEmpty());
            search.setText("");
            assertEquals(perPage, buttons(shelf, "Читать").size());
            assertFalse(buttons(shelf, "Назад").get(0).isEnabled());
        });
    }

    private static List<JButton> buttons(Container root, String name) {
        return children(root).filter(JButton.class::isInstance).map(JButton.class::cast)
                .filter(button -> name.equals(button.getText())).toList();
    }

    private static Stream<Component> children(Container root) {
        return Stream.of(root.getComponents()).flatMap(child -> child instanceof Container nested
                ? Stream.concat(Stream.of(child), children(nested)) : Stream.of(child));
    }
}
