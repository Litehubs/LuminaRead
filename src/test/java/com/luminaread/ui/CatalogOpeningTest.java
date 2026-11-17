package com.luminaread.ui;

import com.luminaread.model.manga.MangaSearchResult;
import com.luminaread.model.ranobe.RanobeItem;
import com.luminaread.service.MangaService;
import com.luminaread.service.RanobeService;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CatalogOpeningTest {
    @Test void eachCatalogStartsItsFirstSearchOnlyWhenOpened() throws Exception {
        var mangaQueries = new LinkedBlockingQueue<String>();
        var ranobeQueries = new LinkedBlockingQueue<String>();
        MangaPanel[] manga = new MangaPanel[1];
        RanobePanel[] ranobe = new RanobePanel[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                manga[0] = new MangaPanel(null, new MangaService() {
                    @Override public MangaSearchResult searchMangaDetailed(String query, String source) {
                        mangaQueries.add(query);
                        return new MangaSearchResult(List.of(), List.of());
                    }
                }, false);
                ranobe[0] = new RanobePanel(null, new RanobeService() {
                    @Override public List<RanobeItem> searchRanobe(String query) {
                        ranobeQueries.add(query);
                        return List.of();
                    }
                }, false);
            });
            assertNull(mangaQueries.poll(200, TimeUnit.MILLISECONDS));
            assertNull(ranobeQueries.poll(200, TimeUnit.MILLISECONDS));
            SwingUtilities.invokeAndWait(manga[0]::openCatalog);
            assertEquals("", mangaQueries.poll(5, TimeUnit.SECONDS));
            assertNull(ranobeQueries.poll(200, TimeUnit.MILLISECONDS));
            SwingUtilities.invokeAndWait(() -> {
                manga[0].openCatalog();
                ranobe[0].openCatalog();
                ranobe[0].openCatalog();
            });
            assertEquals("", ranobeQueries.poll(5, TimeUnit.SECONDS));
            assertNull(mangaQueries.poll(200, TimeUnit.MILLISECONDS));
            assertNull(ranobeQueries.poll(200, TimeUnit.MILLISECONDS));
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                if (manga[0] != null) manga[0].disposeResources();
                if (ranobe[0] != null) ranobe[0].disposeResources();
            });
        }
    }
}
