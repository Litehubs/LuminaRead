package com.luminaread.ui;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;

/** Validates embedded cover dimensions before allocating the image raster. */
final class BookCoverDecoder {
    private static final int MAX_SIDE = 12000;
    private static final long MAX_PIXELS = 25_000_000L;

    private BookCoverDecoder() {}

    static BufferedImage decode(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0) return null;
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (input == null) return null;
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) return null;
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || width > MAX_SIDE || height > MAX_SIDE
                        || (long) width * height > MAX_PIXELS) return null;
                return reader.read(0);
            } finally {
                reader.dispose();
            }
        }
    }
}
