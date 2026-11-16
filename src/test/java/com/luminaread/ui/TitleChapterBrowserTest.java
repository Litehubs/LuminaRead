package com.luminaread.ui;

import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.awt.Color;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TitleChapterBrowserTest {
    @Test
    void doubleClickOpensOnlyTheChapterUnderThePointer() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JList<String> list = new JList<>(new String[]{"Первая", "Вторая"});
            list.setSize(200, 200);
            List<Integer> opened = new ArrayList<>();
            new TitleChapterBrowser(list, new JLabel(), null, Color.BLUE, opened::add);

            doubleClick(list, 150, MouseEvent.BUTTON1);
            doubleClick(list, 48, MouseEvent.BUTTON3);
            doubleClick(list, 48, MouseEvent.BUTTON1);

            assertEquals(List.of(1), opened);
        });
    }

    private static void doubleClick(JList<?> list, int y, int button) {
        list.dispatchEvent(new MouseEvent(list, MouseEvent.MOUSE_CLICKED,
                0, 0, 10, y, 2, false, button));
    }
}
