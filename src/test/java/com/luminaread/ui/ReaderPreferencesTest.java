package com.luminaread.ui;

import com.luminaread.model.manga.MangaItem;

import com.luminaread.model.ranobe.RanobeItemFactory;
import com.luminaread.service.MangaService;
import com.luminaread.service.RanobeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.swing.SwingUtilities;
import java.io.File;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class ReaderPreferencesTest {
    @Test void badPreferenceDoesNotHideLaterValues(@TempDir Path home) throws Exception {
        String main = Path.of(MangaPanel.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        String tests = Path.of(getClass().getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Djava.awt.headless=true", "-Duser.home=" + home, "-cp", main + File.pathSeparator + tests,
                Probe.class.getName()).redirectErrorStream(true).start();
        try { assertTrue(process.waitFor(20, TimeUnit.SECONDS)); assertEquals(0, process.exitValue(), new String(process.getInputStream().readAllBytes())); }
        finally { process.destroyForcibly(); }
    }

    public static class Probe {
        public static void main(String[] args) throws Exception {
            MangaPanel[] manga = new MangaPanel[1]; RanobePanel[] ranobe = new RanobePanel[1];
            try {
                SwingUtilities.invokeAndWait(() -> {
                    manga[0] = new MangaPanel(null, new MangaService() {
                        @Override public synchronized void loadMangaState(Map<String, MangaItem> favorites, Map<String, MangaItem> history,
                                Map<String, String> last, Map<String, String> prefs) {
                            prefs.put("zoomWidth", "invalid"); prefs.put("autoScrollSpeed", "91");
                        }
                    }, false);
                    ranobe[0] = new RanobePanel(null, new RanobeService() {
                        @Override public synchronized <T extends com.luminaread.model.ranobe.RanobeItem> void loadRanobeState(
                                Map<String, T> favorites, Map<String, T> history, Map<String, String> last,
                                Map<String, String> prefs, RanobeItemFactory<T> factory) {
                            prefs.put("fontSize", "bad"); prefs.put("lineHeight", "NaN"); prefs.put("autoScrollSpeed", "92");
                        }
                    }, false);
                    try {
                        assertField(manga[0], "zoomWidth", 860); assertField(manga[0], "autoScrollSpeed", 91);
                        assertField(ranobe[0], "fontSize", 19); assertField(ranobe[0], "lineHeight", 1.6);
                        assertField(ranobe[0], "autoScrollSpeed", 92);
                    } catch (Exception e) { throw new RuntimeException(e); }
                });
            } finally {
                SwingUtilities.invokeAndWait(() -> { if (manga[0] != null) manga[0].disposeResources(); if (ranobe[0] != null) ranobe[0].disposeResources(); });
            }
        }
        private static void assertField(Object target, String name, Object expected) throws Exception {
            var field = target.getClass().getDeclaredField(name); field.setAccessible(true);
            if (!expected.equals(field.get(target))) throw new AssertionError(name + " was not restored correctly");
        }
    }
}
