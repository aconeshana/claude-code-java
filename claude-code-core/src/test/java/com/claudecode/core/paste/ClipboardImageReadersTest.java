package com.claudecode.core.paste;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.core.imagestore.ImageResizer;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Clipboard backend orchestration tests.
 */
class ClipboardImageReadersTest {

    @Test
    void imageIsAuthoritativeAndSkipsFallback() {
        ImagePaste.ImageWithDimensions image = image();
        AtomicInteger fallbackCalls = new AtomicInteger();
        ClipboardImageService service = service(
            backend("native", () -> new ClipboardReadResult.Image(image)),
            backend("fallback", () -> {
                fallbackCalls.incrementAndGet();
                return new ClipboardReadResult.Empty();
            }));

        ClipboardReadResult result = service.read();

        assertSame(image, ((ClipboardReadResult.Image) result).image());
        assertEquals(0, fallbackCalls.get());
    }

    @Test
    void emptyIsAuthoritativeAndSkipsFallback() {
        AtomicInteger fallbackCalls = new AtomicInteger();
        ClipboardImageService service = service(
            backend("native", ClipboardReadResult.Empty::new),
            backend("fallback", () -> {
                fallbackCalls.incrementAndGet();
                return new ClipboardReadResult.Empty();
            }));

        ClipboardReadResult result = service.read();

        assertEquals(ClipboardReadResult.Empty.class, result.getClass());
        assertEquals(0, fallbackCalls.get());
    }

    @Test
    void unavailableBackendFallsBack() {
        ImagePaste.ImageWithDimensions image = image();
        ClipboardImageService service = service(
            backend("native", () -> ClipboardReadResult.Unavailable.transientFailure(
                new IllegalStateException("native unavailable"))),
            backend("fallback", () -> new ClipboardReadResult.Image(image)));

        assertSame(image, ((ClipboardReadResult.Image) service.read()).image());
    }

    @Test
    void thrownBackendFailureFallsBackAndRetriesNextTime() {
        AtomicInteger primaryCalls = new AtomicInteger();
        ClipboardImageService service = service(
            backend("native", () -> {
                primaryCalls.incrementAndGet();
                throw new IllegalStateException("native failed");
            }),
            backend("fallback", ClipboardReadResult.Empty::new));

        service.read();
        service.read();

        assertEquals(2, primaryCalls.get());
    }

    @Test
    void permanentCapabilityFailureIsStickyAndBypassedLater() {
        AtomicInteger primaryCalls = new AtomicInteger();
        AtomicInteger fallbackCalls = new AtomicInteger();
        ClipboardImageService service = service(
            backend("native", () -> {
                primaryCalls.incrementAndGet();
                return ClipboardReadResult.Unavailable.permanent(
                    new UnsupportedOperationException("backend absent"));
            }),
            backend("fallback", () -> {
                fallbackCalls.incrementAndGet();
                return new ClipboardReadResult.Empty();
            }));

        service.read();
        service.read();

        assertEquals(1, primaryCalls.get());
        assertEquals(2, fallbackCalls.get());
    }

    @Test
    void backendIoDoesNotHoldAServiceWideLock() throws Exception {
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        CountDownLatch bothStarted = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        ClipboardImageService service = service(backend("concurrent", () -> {
            int current = active.incrementAndGet();
            maximum.accumulateAndGet(current, Math::max);
            bothStarted.countDown();
            try {
                release.await(1, TimeUnit.SECONDS);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            } finally {
                active.decrementAndGet();
            }
            return new ClipboardReadResult.Empty();
        }));

        Thread first = Thread.ofVirtual().start(service::read);
        Thread second = Thread.ofVirtual().start(service::read);
        assertTrue(bothStarted.await(1, TimeUnit.SECONDS));
        release.countDown();
        first.join();
        second.join();

        assertEquals(2, maximum.get());
    }

    @Test
    void nativePngWithinRawBudgetPreservesNativeOriginalAndDisplayDimensions() throws Exception {
        byte[] png = png(32, 24, false);
        MacNativeClipboardImageReader.NativeClipboardData nativeImage =
            new MacNativeClipboardImageReader.NativeClipboardData(
                png, 6000, 4000, 2000, 1333);

        ImagePaste.ImageWithDimensions result =
            MacNativeClipboardImageReader.prepareNativeImage(nativeImage);

        assertEquals("image/png", result.mediaType());
        assertArrayEquals(png, Base64.getDecoder().decode(result.base64()));
        assertEquals(6000, result.dimensions().originalWidth());
        assertEquals(4000, result.dimensions().originalHeight());
        assertEquals(2000, result.dimensions().displayWidth());
        assertEquals(1333, result.dimensions().displayHeight());
    }

    @Test
    void oversizedNativePngKeepsNativeOriginalDimensionsAfterJavaSizeCompression() throws Exception {
        byte[] png = png(2000, 1333, true);
        assertTrue(png.length > ImageResizer.IMAGE_TARGET_RAW_SIZE);
        MacNativeClipboardImageReader.NativeClipboardData nativeImage =
            new MacNativeClipboardImageReader.NativeClipboardData(
                png, 6000, 4000, 2000, 1333);

        ImagePaste.ImageWithDimensions result =
            MacNativeClipboardImageReader.prepareNativeImage(nativeImage);

        assertEquals(6000, result.dimensions().originalWidth());
        assertEquals(4000, result.dimensions().originalHeight());
        assertEquals(2000, result.dimensions().displayWidth());
        assertEquals(1333, result.dimensions().displayHeight());
        assertTrue(Base64.getDecoder().decode(result.base64()).length <=
            ImageResizer.IMAGE_TARGET_RAW_SIZE);
    }

    private static ClipboardImageService service(ClipboardImageBackend... backends) {
        return new ClipboardImageService(List.of(backends));
    }

    private static ClipboardImageBackend backend(String name, ClipboardImageReader reader) {
        return new ClipboardImageBackend() {
            @Override public String name() { return name; }
            @Override public ClipboardReadResult read() { return reader.read(); }
        };
    }

    private static ImagePaste.ImageWithDimensions image() {
        return new ImagePaste.ImageWithDimensions("cG5n", "image/png", null);
    }

    private static byte[] png(int width, int height, boolean noisy) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        int state = 0x197197;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (noisy) {
                    state ^= state << 13;
                    state ^= state >>> 17;
                    state ^= state << 5;
                }
                image.setRGB(x, y, noisy ? state & 0x00ff_ffff : 0x0042_84c6);
            }
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "png", output));
        return output.toByteArray();
    }
}
