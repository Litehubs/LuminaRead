package com.luminaread.ui;

import com.luminaread.model.ranobe.RanobeChapter;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RanobeChapterListTest {
    private static RanobeChapter chapter(String number, String title) {
        return new RanobeChapter(number, "1", number, title, "", "");
    }

    @Test void filtersSortsAndFindsSavedProgress() {
        RanobeChapter first = chapter("1", "Начало"), second = chapter("2", "Продолжение"), third = chapter("3", "Финал");
        RanobeChapterList chapters = new RanobeChapterList();
        chapters.setChapters(List.of(first, second, third));
        assertEquals(1, chapters.lastReadIndex("Гл. 2"));
        chapters.toggleOrder();
        assertEquals(List.of(third, second, first), chapters.visible());
        chapters.filter("ПРОДОЛЖ");
        assertEquals(List.of(second), chapters.visible());
        assertEquals(0, chapters.lastReadIndex("Гл. 2"));
        chapters.filter("");
        assertEquals(List.of(third, second, first), chapters.visible());
        chapters.clear();
        assertTrue(chapters.visible().isEmpty());
        chapters.setChapters(List.of(first, third));
        assertEquals(List.of(third, first), chapters.visible());
    }
}
