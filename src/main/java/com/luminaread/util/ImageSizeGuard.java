package com.luminaread.util;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;

/** Inspects dimensions without decoding the full image raster. */
public final class ImageSizeGuard {
    private static final int MAX_SIDE = 12_000;
    private static final long MAX_PIXELS = 25_000_000L;

    public enum Result { ACCEPTABLE, TOO_LARGE, UNKNOWN }

    private ImageSizeGuard() {}

    public static Result inspect(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0) return Result.UNKNOWN;
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            return inspect(input);
        }
    }

    public static Result inspect(File file) throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(file)) {
            return inspect(input);
        }
    }

    private static Result inspect(ImageInputStream input) throws IOException {
        if (input == null) return Result.UNKNOWN;
        var readers = ImageIO.getImageReaders(input);
        if (!readers.hasNext()) return Result.UNKNOWN;
        ImageReader reader = readers.next();
        try {
            reader.setInput(input, true, true);
            int width = reader.getWidth(0), height = reader.getHeight(0);
            return width <= 0 || height <= 0 || width > MAX_SIDE || height > MAX_SIDE
                    || (long) width * height > MAX_PIXELS ? Result.TOO_LARGE : Result.ACCEPTABLE;
        } finally { reader.dispose(); }
    }
}
