package com.luminaread.ui;

import org.junit.jupiter.api.Test;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.zip.CRC32;
import static org.junit.jupiter.api.Assertions.*;

class BookCoverDecoderTest {
    @Test void normalCoverDecodes() throws Exception {
        var source = new BufferedImage(3, 2, BufferedImage.TYPE_INT_ARGB);
        var output = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(source, "png", output));
        var decoded = BookCoverDecoder.decode(output.toByteArray());
        assertNotNull(decoded);
        assertEquals(3, decoded.getWidth());
        assertEquals(2, decoded.getHeight());
    }

    @Test void rejectsOversizedCoverFromItsHeaderBeforeDecoding() throws Exception {
        var output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", output);
        byte[] png = output.toByteArray();
        ByteBuffer.wrap(png).putInt(16, 10000).putInt(20, 10000);
        CRC32 crc = new CRC32(); crc.update(png, 12, 17);
        ByteBuffer.wrap(png).putInt(29, (int) crc.getValue());
        assertNull(BookCoverDecoder.decode(png));
        ByteBuffer.wrap(png).putInt(16, 6000).putInt(20, 6000);
        crc.reset(); crc.update(png, 12, 17);
        ByteBuffer.wrap(png).putInt(29, (int) crc.getValue());
        assertNull(BookCoverDecoder.decode(png), "Pixel count must be bounded even when each side is within its limit");
    }
}
