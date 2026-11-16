package com.luminaread.service;

import com.luminaread.service.manga.MangaLibClient;
import com.luminaread.service.manga.RemangaClient;
import com.luminaread.service.manga.MangaDexClient;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class MangaSearchFailureTest {
    @Test void partialFailureKeepsOtherResultsAndReportsFailedSource() {
        var item = new MangaService.MangaItem("id", "Book", "", "", "", "Remanga", "", "");
        MangaService service = new MangaService(
                new MangaLibClient() { @Override public List<MangaService.MangaItem> search(String q) { throw new IllegalStateException("offline"); } },
                new RemangaClient() { @Override public List<MangaService.MangaItem> search(String q) { return List.of(item); } },
                new MangaDexClient() { @Override public List<MangaService.MangaItem> search(String q) { return List.of(); } }, null, null);
        var result = service.searchMangaDetailed("query", "Все источники");
        assertEquals(List.of(item), result.items());
        assertEquals(List.of("MangaLib"), result.failedSources());
        assertThrows(IllegalStateException.class, () -> service.searchManga("query", "MangaLib"));
        assertTrue(service.searchManga("query", "MangaDex").isEmpty());
    }
}
