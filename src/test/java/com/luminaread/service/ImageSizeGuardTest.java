package com.luminaread.service;

import com.luminaread.util.ImageSizeGuard;
import org.junit.jupiter.api.Test;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.zip.CRC32;
import static org.junit.jupiter.api.Assertions.*;

class ImageSizeGuardTest {
    @Test void rejectsHugePngBeforeDecodingButKeepsNormalImages() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 3, BufferedImage.TYPE_INT_RGB), "png", out);
        byte[] normal = out.toByteArray();
        assertEquals(ImageSizeGuard.Result.ACCEPTABLE, ImageSizeGuard.inspect(normal));
        assertEquals(2, new MediaCacheService().decodeBytes(normal).getWidth());

        byte[] huge = normal.clone();
        ByteBuffer.wrap(huge).putInt(16, 6000).putInt(20, 6000);
        CRC32 crc = new CRC32(); crc.update(huge, 12, 17);
        ByteBuffer.wrap(huge).putInt(29, (int) crc.getValue());
        assertEquals(ImageSizeGuard.Result.TOO_LARGE, ImageSizeGuard.inspect(huge));
        assertNull(new MediaCacheService().decodeBytes(huge));
        assertNull(MediaCacheService.tryDecodeWithSips(huge));
    }

    @Test void cacheEvictsByTotalPixelsAndClearsAccounting() throws Exception {
        var image = new BufferedImage(3000, 3000, BufferedImage.TYPE_BYTE_GRAY);
        var cache = new MediaCacheService() {
            @Override public BufferedImage downloadAndDecode(String url, String referer) { return image; }
        };
        for (int i = 0; i < 5; i++) cache.loadImageCached("https://example.com/" + i, java.util.List.of());
        var field = MediaCacheService.class.getDeclaredField("cachedPixels");
        field.setAccessible(true);
        assertEquals(27_000_000L, field.getLong(cache));
        cache.clearMemoryCache();
        assertEquals(0, field.getLong(cache));
    }

    @Test void identifiesFormatsWithoutAnInstalledReaderAsUnverified() throws Exception {
        byte[] unknown = {1, 2, 3, 4};
        assertEquals(ImageSizeGuard.Result.UNKNOWN, ImageSizeGuard.inspect(unknown));
        assertNull(new MediaCacheService().decodeBytes(unknown));
        assertNull(MediaCacheService.tryDecodeWithSips(unknown));
    }
}
