package com.claudecode.core.paste;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/** macOS FFM clipboard smoke test; it never mutates the system clipboard. */
@EnabledOnOs(OS.MAC)
class MacNativeClipboardImageReaderTest {

    @Test
    void nativePasteboardBackendLoadsWithoutFallingBack() {
        ClipboardReadResult result = MacNativeClipboardImageReader.read();

        assertFalse(result instanceof ClipboardReadResult.Unavailable,
            () -> "native pasteboard backend unavailable: "
                + failureChain(((ClipboardReadResult.Unavailable) result).cause()));
    }

    @Test
    void coreGraphicsResizeProducesCappedPngWithoutVerticalFlip() throws Exception {
        BufferedImage source = new BufferedImage(2100, 100, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < source.getHeight(); y++) {
            int color = y < source.getHeight() / 2 ? 0xffff_0000 : 0xff00_00ff;
            for (int x = 0; x < source.getWidth(); x++) source.setRGB(x, y, color);
        }
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(source, "png", encoded));

        MacNativeClipboardImageReader.NativeClipboardData resized =
            MacNativeClipboardImageReader.resizePng(encoded.toByteArray(), 2000, 2000);
        BufferedImage output = ImageIO.read(new ByteArrayInputStream(resized.png()));

        assertEquals(2000, resized.displayWidth());
        assertEquals(95, resized.displayHeight());
        assertEquals(2100, resized.originalWidth());
        assertEquals(100, resized.originalHeight());
        assertTrue(((output.getRGB(20, 5) >>> 16) & 0xff) > 200,
            "top row must remain red after native resize");
        assertTrue((output.getRGB(20, output.getHeight() - 5) & 0xff) > 200,
            "bottom row must remain blue after native resize");
    }

    private static String failureChain(Throwable failure) {
        StringBuilder chain = new StringBuilder();
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (!chain.isEmpty()) chain.append(" -> ");
            chain.append(current);
        }
        return chain.toString();
    }
}
