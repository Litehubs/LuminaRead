package com.luminaread.ui;

import com.luminaread.util.ImageSizeGuard;
import javax.swing.text.Element;
import javax.swing.text.html.ImageView;
import java.net.URL;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Checks known local image formats before Swing creates their raster. */
final class GuardedImageView extends ImageView {
    private final Path imageCache;

    GuardedImageView(Element element, Path imageCache) {
        super(element);
        this.imageCache = imageCache;
    }

    @Override public URL getImageURL() {
        URL url = super.getImageURL();
        if (url == null) return null;
        boolean local = "file".equalsIgnoreCase(url.getProtocol()) && (url.getHost() == null || url.getHost().isEmpty());
        if (!local) return null;
        try {
            Path requested = Path.of(url.toURI()).normalize();
            if (!requested.startsWith(imageCache.toAbsolutePath().normalize())) return null;
            Path realFile = requested.toRealPath();
            if (!realFile.startsWith(imageCache.toRealPath()) || !java.nio.file.Files.isRegularFile(realFile)) return null;
            return ImageSizeGuard.inspect(realFile.toFile()) == ImageSizeGuard.Result.ACCEPTABLE
                    ? realFile.toUri().toURL() : null;
        } catch (Exception ex) {
            Logger.getLogger(GuardedImageView.class.getName()).log(Level.FINE, "Не удалось проверить изображение", ex);
            return null;
        }
    }
}
