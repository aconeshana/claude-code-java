package com.claudecode.core.imagestore;

import com.claudecode.core.paste.ImagePaste;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.NodeList;




class ImageResizerTest {

    @TempDir
    Path tempDir;

    @Test
    void compliantSupportedImageUsesHeaderMetadataWithoutReencoding() throws Exception {
        byte[] input = noisyPng(32, 24);

        ImageResizer.ResizeResult result =
            ImageResizer.maybeResizeAndDownsample(input, "png");

        assertSame(input, result.buffer());
        assertEquals("image/png", result.mediaType());
        assertEquals(new ImageResizer.PastedDims(32, 24, 32, 24), result.dimensions());
    }

    @Test
    void frozenResizeMetadataUsesTheSameDimensionConstraintAsTheReleasedProcessor() {
        assertEquals(
            new ImageResizer.PastedDims(3000, 3000, 2000, 2000),
            ImageResizer.constrainedDimensions(
                new ImageResizer.PastedDims(3000, 3000, 3000, 3000)));
        assertEquals(
            new ImageResizer.PastedDims(1300, 1300, 1300, 1300),
            ImageResizer.constrainedDimensions(
                new ImageResizer.PastedDims(1300, 1300, 1300, 1300)));
    }

    @Test
    void apiBlockPreparationEnforcesThe197PerImageByteBudget() throws Exception {
        byte[] input = noisyPng(2100, 2100);

        ImageResizer.ResizeResult result =
            ImageResizer.maybeResizeForApiBlock(input, "png");

        assertTrue(result.buffer().length <= ImageResizer.API_IMAGE_WIRE_TARGET_SIZE);
        assertEquals("image/jpeg", result.mediaType());
        assertNotNull(result.dimensions());
        assertEquals(2100, result.dimensions().originalWidth());
        assertEquals(2000, result.dimensions().displayWidth());
    }

    @Test
    void pastedImagePathIsResizedBeforeItIsStoredAsPastedContent() throws Exception {
        Path image = tempDir.resolve("oversized.png");
        Files.write(image, noisyPng(2100, 2100));

        ImagePaste.ImageWithDimensions result =
            ImagePaste.tryReadImageFromPath(image.toString());

        assertNotNull(result);
        assertNotNull(result.dimensions());
        assertEquals(2100, result.dimensions().originalWidth());
        assertEquals(2100, result.dimensions().originalHeight());
        assertEquals(2000, result.dimensions().displayWidth());
        assertEquals(2000, result.dimensions().displayHeight());
        assertTrue(Base64.getDecoder().decode(result.base64()).length < Files.size(image));
    }

    @Test
    void jpegConversionDropsAlphaButPreservesUnderlyingRgbLikeReleasedNativeCodec() throws Exception {
        byte[] input = noisyPngWithTransparentColoredCorner(1200, 1200);

        ImageResizer.ResizeResult result =
            ImageResizer.maybeResizeForApiBlock(input, "png");
        BufferedImage jpeg = ImageIO.read(new ByteArrayInputStream(result.buffer()));

        assertEquals("image/jpeg", result.mediaType());
        assertNotNull(jpeg);
        int corner = jpeg.getRGB(0, 0);
        assertTrue(((corner >>> 16) & 0xff) > 180,
            "released native codec retains the hidden red channel when dropping alpha");
        assertTrue(((corner >>> 8) & 0xff) > 40);
        assertTrue((corner & 0xff) < 60);
    }

    @Test
    void apiJpegUsesThe444SamplingWrittenByReleased197() throws Exception {
        ImageResizer.ResizeResult result =
            ImageResizer.maybeResizeForApiBlock(noisyPng(2100, 2100), "png");

        try (ImageInputStream input = ImageIO.createImageInputStream(
                new ByteArrayInputStream(result.buffer()))) {
            ImageReader reader = ImageIO.getImageReadersByFormatName("jpeg").next();
            reader.setInput(input);
            IIOMetadata metadata = reader.getImageMetadata(0);
            IIOMetadataNode root = (IIOMetadataNode) metadata.getAsTree(
                "javax_imageio_jpeg_image_1.0");
            NodeList components = root.getElementsByTagName("componentSpec");
            assertEquals(3, components.getLength());
            for (int index = 0; index < components.getLength(); index++) {
                IIOMetadataNode component = (IIOMetadataNode) components.item(index);
                assertEquals("1", component.getAttribute("HsamplingFactor"));
                assertEquals("1", component.getAttribute("VsamplingFactor"));
            }
            reader.dispose();
        }
    }

    @Test
    void releasedLanczosAndJpegCodecRemainByteExact() throws Exception {
        BufferedImage source = patternedArgb(13, 11);

        BufferedImage resized = ReleasedImageCodec.resizeLanczos3(source, 9, 7);
        byte[] jpeg = ReleasedImageCodec.encodeJpeg(resized, 80);

        assertEquals("e7c5cb18953bd36a28cbe0428ef068d549737571958e9307921063f7390ddb44",
            argbSha256(resized));
        assertEquals(727, jpeg.length);
        assertEquals("2708e987ab10dd5fa7cb7f675e2a010fc3e77ba6c31592e1ee799778ba47fa7c",
            sha256(jpeg));
    }

    @Test
    void releasedLanczosHandlesExtremeAspectRatiosAndSinglePixelTargets() throws Exception {
        assertEquals("5bc2a631fae235fe0a522aa498d1fcbd5f82407a75f56a3b72ef55eeda1231aa",
            argbSha256(ReleasedImageCodec.resizeLanczos3(patternedArgb(257, 3), 1, 193)));
        assertEquals("5b160e15d3d4d4d761898a4d62867106056e537559979da84126292478626fea",
            argbSha256(ReleasedImageCodec.resizeLanczos3(patternedArgb(3, 257), 193, 1)));
        assertEquals("365475d471c68745e3881c3743e1669911103607f18d88d9db7292e29177c0bf",
            argbSha256(ReleasedImageCodec.resizeLanczos3(patternedArgb(257, 193), 1, 1)));
    }

    @Test
    void lanczosScratchEstimateExcludesFullFrameIntermediateAndPixelCopies() {
        long estimate = ReleasedImageCodec.estimatedResizeScratchBytes(4000, 3000, 2000, 1500);
        long oldFullFrameBuffers = 4L * 4000 * 3000
            + 4L * 4000 * 1500 * Float.BYTES
            + 4L * 2000 * 1500;

        assertTrue(estimate <= 500_000,
            () -> "streamed resize scratch should stay row/kernel bounded, got " + estimate);
        assertTrue(estimate * 100 < oldFullFrameBuffers,
            () -> "scratch estimate should exclude old full-frame arrays, got " + estimate);
    }

    @Test
    void lanczosResizeDoesNotUseParallelStreamsOrCommonPool() throws Exception {
        Path sourceFile = Path.of("src/main/java/com/claudecode/core/imagestore/ReleasedImageCodec.java");
        String source = Files.readString(sourceFile);

        assertFalse(source.contains("IntStream"));
        assertFalse(source.contains(".parallel()"));
        assertFalse(source.contains("ForkJoinPool.commonPool"));
    }

    private static BufferedImage patternedArgb(int width, int height) {
        BufferedImage source = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int red = (x * 31 + y * 7) & 0xff;
                int green = (x * 11 + y * 29) & 0xff;
                int blue = (x * 17 + y * 13) & 0xff;
                int alpha = (x * 19 + y * 23) & 0xff;
                source.setRGB(x, y, (alpha << 24) | (red << 16) | (green << 8) | blue);
            }
        }
        return source;
    }

    private static String argbSha256(BufferedImage image) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int argb = image.getRGB(x, y);
                digest.update((byte) (argb >>> 24));
                digest.update((byte) (argb >>> 16));
                digest.update((byte) (argb >>> 8));
                digest.update((byte) argb);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static byte[] noisyPng(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        int state = 0x197197;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                state ^= state << 13;
                state ^= state >>> 17;
                state ^= state << 5;
                image.setRGB(x, y, state & 0x00ff_ffff);
            }
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "png", output));
        return output.toByteArray();
    }

    private static byte[] noisyPngWithTransparentColoredCorner(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int state = 0x2197197;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                state ^= state << 13;
                state ^= state >>> 17;
                state ^= state << 5;
                if (x < 80 && y < 80) {
                    image.setRGB(x, y, 0x00f0_5014);
                } else {
                    image.setRGB(x, y, 0xff00_0000 | (state & 0x00ff_ffff));
                }
            }
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "png", output));
        return output.toByteArray();
    }
}
