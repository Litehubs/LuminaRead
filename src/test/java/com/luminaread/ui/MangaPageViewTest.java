package com.luminaread.ui;

import org.junit.jupiter.api.Test;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class MangaPageViewTest {
    @Test void errorLabelRetriesAndLoadedPageNavigates() throws Exception {
        AtomicInteger retry = new AtomicInteger(), left = new AtomicInteger(), right = new AtomicInteger();
        SwingUtilities.invokeAndWait(() -> {
            MangaPageView page = new MangaPageView(1, 120, MangaPanel.ALIGN_CENTER, 0, 8, true,
                    left::incrementAndGet, right::incrementAndGet, ignored -> retry.incrementAndGet());
            page.setSize(120, 200);
            page.setError("Ошибка");
            JLabel message = (JLabel) page.getComponent(0);
            click(message, 30);
            assertEquals(1, retry.get(), "Clicking the visible error text must trigger retry");
            page.setImage(new BufferedImage(10, 20, BufferedImage.TYPE_INT_RGB), 120, MangaPanel.ALIGN_CENTER, 0, 8);
            assertFalse(message.isVisible());
            assertEquals(248, page.getPreferredSize().height);
            click(page, 10);
            click(page, 100);
            assertEquals(1, left.get());
            assertEquals(1, right.get());
        });
    }

    private static void click(java.awt.Component component, int x) {
        component.dispatchEvent(new MouseEvent(component, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(),
                0, x, 5, 1, false, MouseEvent.BUTTON1));
    }
}
