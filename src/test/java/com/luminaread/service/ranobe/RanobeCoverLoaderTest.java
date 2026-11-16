package com.luminaread.service.ranobe;

import com.luminaread.service.IMediaCacheService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RanobeCoverLoaderTest {
    @TempDir Path dir;

    @Test void usesCachedImageAndPreservesRequestParameters() throws Exception {
        Path file = dir.resolve("cover.png");
        ImageIO.write(new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB), "png", file.toFile());
        FakeMedia media = new FakeMedia(file.toUri().toString());
        BufferedImage image = new RanobeCoverLoader(media).load("https://example.test/cover");
        assertNotNull(image);
        assertEquals(3, image.getWidth());
        assertEquals(0, media.downloads);
        assertEquals("ranobe", media.folder);
        assertEquals("https://ranobelib.me/", media.referer);
        assertEquals(680, media.width);
    }

    @Test void unreadableCacheFallsBackToDownload() {
        FakeMedia media = new FakeMedia(dir.resolve("missing.png").toUri().toString());
        assertNotNull(new RanobeCoverLoader(media).load("https://example.test/cover"));
        assertEquals(1, media.downloads);
        assertNull(new RanobeCoverLoader(media).load(""));
        assertEquals(1, media.downloads);
    }

    @Test void cacheFailureStillAttemptsDownload() {
        FakeMedia media = new FakeMedia("");
        media.failCache = true;
        assertNotNull(new RanobeCoverLoader(media).load("https://example.test/cover"));
        assertEquals(1, media.downloads);
    }

    private static final class FakeMedia implements IMediaCacheService {
        private final String cached;
        String folder, referer;
        int width, downloads;
        boolean failCache;
        FakeMedia(String cached) { this.cached = cached; }
        @Override public BufferedImage loadImageCached(String url, List<String> servers) { return null; }
        @Override public CachedImageInfo cacheImageToDisk(String url, String subDir, String referer, int maxWidth) {
            this.folder = subDir; this.referer = referer; this.width = maxWidth;
            if (failCache) throw new IllegalStateException("cache unavailable");
            return new CachedImageInfo(cached, 0, 0);
        }
        @Override public BufferedImage downloadAndDecode(String url, String referer) {
            assertEquals("https://ranobelib.me/", referer); downloads++;
            return new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        }
        @Override public void clearMemoryCache() {}
    }
}
