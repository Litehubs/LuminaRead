package com.luminaread.service.ranobe;

import com.luminaread.service.IMediaCacheService;
import com.luminaread.util.ImageSizeGuard;
import java.awt.image.BufferedImage;
import java.net.URI;
import java.nio.file.Path;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.imageio.ImageIO;

/** Retrieves a cached cover, falling back to a direct download when necessary. */
public final class RanobeCoverLoader {
    private static final String REFERER = "https://ranobelib.me/";
    private static final Logger LOG = Logger.getLogger(RanobeCoverLoader.class.getName());
    private final IMediaCacheService media;

    public RanobeCoverLoader(IMediaCacheService media) {
        this.media = Objects.requireNonNull(media);
    }

    public BufferedImage load(String url) {
        if (url == null || url.isBlank()) return null;
        IMediaCacheService.CachedImageInfo cached = null;
        try {
            cached = media.cacheImageToDisk(url, "ranobe", REFERER, 680);
        } catch (RuntimeException ex) {
            LOG.log(Level.FINE, "Не удалось сохранить обложку в кэш", ex);
        }
        if (cached != null && cached.localUrl.startsWith("file:")) {
            try {
                var file = Path.of(URI.create(cached.localUrl)).toFile();
                if (ImageSizeGuard.inspect(file) != ImageSizeGuard.Result.ACCEPTABLE) throw new java.io.IOException("Cached cover cannot be safely decoded");
                BufferedImage image = ImageIO.read(file);
                if (image != null) return image;
            } catch (Exception ex) {
                LOG.log(Level.FINE, "Не удалось прочитать обложку из кэша", ex);
            }
        }
        try {
            return media.downloadAndDecode(url, REFERER);
        } catch (RuntimeException ex) {
            LOG.log(Level.FINE, "Не удалось загрузить обложку ранобэ", ex);
            return null;
        }
    }
}
