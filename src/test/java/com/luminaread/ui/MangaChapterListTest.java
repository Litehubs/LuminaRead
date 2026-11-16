package com.luminaread.ui;

import com.luminaread.model.manga.MangaChapter;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class MangaChapterListTest {
    @Test void selectionOrderAndTitleSwitchRemainConsistent() {
        MangaChapter ru1 = new MangaChapter("1", "1", "", "ru", "test");
        MangaChapter ru2 = new MangaChapter("2", "2", "", "ru", "test");
        MangaChapter en = new MangaChapter("3", "3", "", "en", "test");
        MangaChapterList list = new MangaChapterList();
        list.setChapters(List.of(ru1, en, ru2));
        assertEquals(List.of("ru", "en"), list.languages());
        assertEquals(List.of(ru1, ru2), list.visible());
        list.toggleOrder();
        assertEquals(List.of(ru2, ru1), list.visible());
        list.selectLanguage("en");
        assertEquals(List.of(en), list.visible());
        list.clear();
        assertTrue(list.visible().isEmpty());
        list.setChapters(List.of(ru1, ru2));
        assertEquals(List.of(ru2, ru1), list.visible());
        assertThrows(UnsupportedOperationException.class, () -> list.visible().clear());
    }

    @Test void unknownLanguagesRemainVisibleWhenNoSupportedLanguageExists() {
        MangaChapter other = new MangaChapter("4", "4", "", "ja", "test");
        MangaChapterList list = new MangaChapterList();
        list.setChapters(List.of(other));
        assertTrue(list.languages().isEmpty());
        assertEquals(List.of(other), list.visible());
    }
}
