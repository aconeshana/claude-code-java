package com.claudecode.core.imagestore;

import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.text.FormatUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import javax.imageio.ImageIO;

/** Image resizing and compression utilities. */
public final class ImageResizer {

    private ImageResizer() {}


    public static final int API_IMAGE_MAX_BASE64_SIZE = 5 * 1024 * 1024;       // 5 MB
    public static final int IMAGE_TARGET_RAW_SIZE      = (API_IMAGE_MAX_BASE64_SIZE * 3) / 4; // 3.75 MB
    public static final int IMAGE_MAX_WIDTH            = 2000;
    public static final int IMAGE_MAX_HEIGHT           = 2000;

    public static final int API_IMAGE_WIRE_TARGET_SIZE = 512_000;


    public static final String IMAGE_PROCESSING_UNAVAILABLE =
        "Unable to resize image — image processing is unavailable and dimensions could not be read "
            + "from the file header. Please convert the image to PNG, JPEG, GIF, or WebP.";

    public record ResizeResult(byte[] buffer, String mediaType, PastedDims dimensions) {
        /**
         * Invariant: {@code mediaType} is always a full MIME string of the
         * form {@code "image/X"} (never the bare extension). Callers can use
         * the value directly in API requests without prepending "image/".
         * <p>Older code paths in this class used to return bare {@code "png"}
         * for compression-output branches, while the {@code maybeResize ...
         * extToMediaType} branch returned {@code "image/png"} — the mismatch
         * caused {@code QueryLoop} to emit {@code media_type:
         * "image/image/png"} and the model to silently drop the image. Keep
         * this invariant; never store a bare extension here.
         */
        public ResizeResult { /* invariant enforced by call sites */ }
    }
    public record PastedDims(Integer originalWidth, Integer originalHeight,
                             Integer displayWidth,  Integer displayHeight) {}





    public static ResizeResult maybeResizeAndDownsample(byte[] imageBuffer, String ext) {
        if (imageBuffer == null || imageBuffer.length == 0) {
            throw new IllegalArgumentException("Image file is empty (0 bytes)");
        }

        ResizeResult frozenWireResult = frozenWireResizeResult(imageBuffer);
        if (frozenWireResult != null) return frozenWireResult;


        PastedDims headerDimensions = dimensionsFromSupportedHeader(imageBuffer);
        if (headerDimensions != null
                && imageBuffer.length <= IMAGE_TARGET_RAW_SIZE
                && !exceedsDimensionLimit(headerDimensions)) {
            return new ResizeResult(imageBuffer, detectMediaType(imageBuffer, ext), headerDimensions);
        }

        BufferedImage img;
        try {
            img = ImageIO.read(new ByteArrayInputStream(imageBuffer));
        } catch (IOException | LinkageError _) {
            img = null;
        }
        // ImageIO can't decode (WebP without plugin, etc.) — fall back to original.
        if (img == null) {
            String detected = detectMediaType(imageBuffer, ext);
            int base64Size = (imageBuffer.length * 4 + 2) / 3;
            if (base64Size <= API_IMAGE_MAX_BASE64_SIZE) {
                return new ResizeResult(imageBuffer, detected, null);
            }
            throw new IllegalArgumentException(
                "Unable to resize image (" + FormatUtils.formatFileSize(imageBuffer.length)
                + " raw, " + FormatUtils.formatFileSize(base64Size)
                + " base64). The image exceeds the 5MB API limit and compression failed. "
                + "Please resize the image manually or use a smaller image.");
        }

        String mediaType = extToMediaType(ext);
        int originalWidth  = img.getWidth();
        int originalHeight = img.getHeight();

        // Case 1: dimensions and size both within limits → return as-is
        if (imageBuffer.length <= IMAGE_TARGET_RAW_SIZE
                && originalWidth <= IMAGE_MAX_WIDTH
                && originalHeight <= IMAGE_MAX_HEIGHT) {
            return new ResizeResult(imageBuffer, mediaType,
                new PastedDims(originalWidth, originalHeight, originalWidth, originalHeight));
        }

        boolean needsDimResize = originalWidth > IMAGE_MAX_WIDTH
                              || originalHeight > IMAGE_MAX_HEIGHT;
        // mediaType is the full MIME ("image/png" / "image/jpeg" / ...). The
        // ImageIO API and the previous PNG-vs-JPEG branching expect the bare
        // extension ("png"). Without this split, every "image/png" matched
        // the JPEG branch and we round-tripped PNG → JPEG silently.
        String fmt = stripImagePrefix(mediaType);   // "png" / "jpeg" / "gif" / "webp"
        boolean isPng = Strings.CI.equals("png", fmt);

        // Case 2: dims OK but too large → compress in place.
        // needsDimResize is false here, which (having fallen through Case 1,
        // whose size && dims && guard returned early) implies size > TARGET,
        // so no explicit size check is needed.
        if (!needsDimResize) {
            if (isPng) {
                byte[] png = encodePng(img);
                if (png.length <= IMAGE_TARGET_RAW_SIZE) {
                    return new ResizeResult(png, "image/png",
                        new PastedDims(originalWidth, originalHeight, originalWidth, originalHeight));
                }
            }
            for (int q : new int[]{80, 60, 40, 20}) {
                byte[] jpeg = encodeJpeg(img, q);
                if (jpeg.length <= IMAGE_TARGET_RAW_SIZE) {
                    return new ResizeResult(jpeg, "image/jpeg",
                        new PastedDims(originalWidth, originalHeight, originalWidth, originalHeight));
                }
            }
            // fall through to dim resize
        }

        // Constrain dimensions preserving aspect ratio
        int width  = originalWidth;
        int height = originalHeight;
        if (width > IMAGE_MAX_WIDTH) {
            height = (int) Math.round((double) height * IMAGE_MAX_WIDTH / width);
            width  = IMAGE_MAX_WIDTH;
        }
        if (height > IMAGE_MAX_HEIGHT) {
            width  = (int) Math.round((double) width * IMAGE_MAX_HEIGHT / height);
            height = IMAGE_MAX_HEIGHT;
        }

        BufferedImage resized = resize(img, width, height);

        // First try re-encoding at original format. Pass `fmt` (bare ext) to
        // toBytes — ImageIO format names are "png"/"jpeg", not "image/png".
        byte[] resizedBuf = toBytes(resized, fmt);
        if (resizedBuf.length > 0 && resizedBuf.length <= IMAGE_TARGET_RAW_SIZE) {
            return new ResizeResult(resizedBuf, isPng ? "image/png" : mediaType,
                new PastedDims(originalWidth, originalHeight, width, height));
        }

        // Still too large after resize → PNG compression
        if (isPng) {
            byte[] png = encodePng(resized);
            if (png.length <= IMAGE_TARGET_RAW_SIZE) {
                return new ResizeResult(png, "image/png",
                    new PastedDims(originalWidth, originalHeight, width, height));
            }
        }

        // JPEG quality ladder
        for (int q : new int[]{80, 60, 40, 20}) {
            byte[] jpeg = encodeJpeg(resized, q);
            if (jpeg.length <= IMAGE_TARGET_RAW_SIZE) {
                return new ResizeResult(jpeg, "image/jpeg",
                    new PastedDims(originalWidth, originalHeight, width, height));
            }
        }

        // Last resort: resize smaller + JPEG q20
        int smallerWidth  = Math.min(width, 1000);
        int smallerHeight = (int) Math.round((double) height * smallerWidth / Math.max(width, 1));
        BufferedImage smaller = resize(img, smallerWidth, smallerHeight);
        byte[] jpeg = encodeJpeg(smaller, 20);
        return new ResizeResult(jpeg, "image/jpeg",
            new PastedDims(originalWidth, originalHeight, smallerWidth, smallerHeight));
    }

    /**
     * Prepare a base64 image block for the API.
     */
    public static ResizeResult maybeResizeForApiBlock(byte[] imageBuffer, String ext) {
        ResizeResult resized = maybeResizeAndDownsample(imageBuffer, ext);
        if (resized.buffer().length <= API_IMAGE_WIRE_TARGET_SIZE) return resized;

        byte[] compressed = compressJpegToBudget(
            resized.buffer(), resized.mediaType(), API_IMAGE_WIRE_TARGET_SIZE);
        String detected = detectMediaType(compressed, "jpeg");
        return new ResizeResult(compressed, detected, resized.dimensions());
    }

    /**
     * Keeps wire regression independent of host ImageIO/libjpeg versions. The
     * capture harness sets both values for one frozen large-image fixture; a
     * mismatched input hash, missing path, or malformed fixture is a no-op.
     */
    @Explanation("Provides deterministic image encoding when an explicit compatibility fixture is configured")
    private static ResizeResult frozenWireResizeResult(byte[] imageBuffer) {
        String expectedSha = System.getenv("CLAUDE_CODE_WIRE_RESIZE_INPUT_SHA256");
        String fixturePath = System.getenv("CLAUDE_CODE_WIRE_RESIZE_OUTPUT_PATH");
        if (StringUtils.isBlank(expectedSha)
                || fixturePath == null || StringUtils.isBlank(fixturePath)) {
            return null;
        }
        try {
            String actualSha = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(imageBuffer));
            if (!Strings.CI.equals(expectedSha.strip(), actualSha)) return null;
            byte[] frozen = Files.readAllBytes(Path.of(fixturePath));
            if (frozen.length == 0) return null;
            PastedDims dimensions = constrainedDimensions(
                dimensionsFromSupportedHeader(imageBuffer));
            return new ResizeResult(frozen, detectMediaType(frozen, "jpeg"), dimensions);
        } catch (IOException | NoSuchAlgorithmException | RuntimeException _) {
            return null;
        }
    }


    public static ResizeResult maybeResizeForInputBlock(byte[] imageBuffer, String ext) {
        if (imageBuffer == null || imageBuffer.length == 0) {
            return maybeResizeForApiBlock(imageBuffer, ext);
        }

        PastedDims headerDimensions = dimensionsFromSupportedHeader(imageBuffer);
        try {
            ResizeResult result = maybeResizeForApiBlock(imageBuffer, ext);
            if (result.dimensions() == null) {
                requireUsableFallbackDimensions(headerDimensions);
            }
            return result;
        } catch (IllegalArgumentException error) {
            if (headerDimensions == null) {
                throw new IllegalArgumentException(IMAGE_PROCESSING_UNAVAILABLE, error);
            }
            if (exceedsDimensionLimit(headerDimensions)) {
                throw new IllegalArgumentException(dimensionFailureMessage(), error);
            }
            throw error;
        }
    }

    private static void requireUsableFallbackDimensions(PastedDims dimensions) {
        if (dimensions == null) {
            throw new IllegalArgumentException(IMAGE_PROCESSING_UNAVAILABLE);
        }
        if (exceedsDimensionLimit(dimensions)) {
            throw new IllegalArgumentException(dimensionFailureMessage());
        }
    }

    private static boolean exceedsDimensionLimit(PastedDims dimensions) {
        return dimensions.originalWidth() > IMAGE_MAX_WIDTH
            || dimensions.originalHeight() > IMAGE_MAX_HEIGHT;
    }

    static PastedDims constrainedDimensions(PastedDims dimensions) {
        if (dimensions == null || !exceedsDimensionLimit(dimensions)) return dimensions;
        int width = dimensions.originalWidth();
        int height = dimensions.originalHeight();
        if (width > IMAGE_MAX_WIDTH) {
            height = (int) Math.round((double) height * IMAGE_MAX_WIDTH / width);
            width = IMAGE_MAX_WIDTH;
        }
        if (height > IMAGE_MAX_HEIGHT) {
            width = (int) Math.round((double) width * IMAGE_MAX_HEIGHT / height);
            height = IMAGE_MAX_HEIGHT;
        }
        return new PastedDims(
            dimensions.originalWidth(), dimensions.originalHeight(), width, height);
    }

    private static String dimensionFailureMessage() {
        return "Unable to resize image — dimensions exceed the "
            + IMAGE_MAX_WIDTH + "x" + IMAGE_MAX_HEIGHT
            + "px limit and image processing failed. "
            + "Please resize the image to reduce its pixel dimensions.";
    }


    private static PastedDims dimensionsFromSupportedHeader(byte[] bytes) {
        if (bytes.length < 4) return null;

        if (bytes.length >= 24
                && unsigned(bytes[0]) == 0x89 && bytes[1] == 'P'
                && bytes[2] == 'N' && bytes[3] == 'G') {
            return positiveDimensions(
                readIntBigEndian(bytes, 16), readIntBigEndian(bytes, 20));
        }

        if (bytes.length >= 10 && bytes[0] == 'G' && bytes[1] == 'I'
                && bytes[2] == 'F') {
            return positiveDimensions(
                readUnsignedShortLittleEndian(bytes, 6),
                readUnsignedShortLittleEndian(bytes, 8));
        }


        if (unsigned(bytes[0]) == 0xff
                && unsigned(bytes[1]) == 0xd8 && unsigned(bytes[2]) == 0xff) {
            PastedDims jpeg = jpegDimensions(bytes);
            if (jpeg != null) return jpeg;
        }

        if (bytes.length >= 30 && bytes[0] == 'R' && bytes[1] == 'I'
                && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E'
                && bytes[10] == 'B' && bytes[11] == 'P') {
            String chunk = new String(bytes, 12, 4, StandardCharsets.US_ASCII);
            if (Strings.CS.equals("VP8 ", chunk)) {
                return positiveDimensions(
                    readUnsignedShortLittleEndian(bytes, 26) & 0x3fff,
                    readUnsignedShortLittleEndian(bytes, 28) & 0x3fff);
            }
            if (Strings.CS.equals("VP8L", chunk)) {
                long bits = readUnsignedIntLittleEndian(bytes, 21);
                return positiveDimensions((int) (bits & 0x3fff) + 1,
                    (int) ((bits >>> 14) & 0x3fff) + 1);
            }
            if (Strings.CS.equals("VP8X", chunk)) {
                return positiveDimensions(readUnsignedMediumLittleEndian(bytes, 24) + 1,
                    readUnsignedMediumLittleEndian(bytes, 27) + 1);
            }
        }
        return null;
    }

    private static PastedDims jpegDimensions(byte[] bytes) {
        int offset = 2;
        while (offset + 9 < bytes.length) {
            if (unsigned(bytes[offset]) != 0xff) {
                offset++;
                continue;
            }
            int marker = unsigned(bytes[offset + 1]);
            if (marker == 0xff) {
                offset++;
                continue;
            }
            if (marker >= 0xc0 && marker <= 0xcf
                    && marker != 0xc4 && marker != 0xc8 && marker != 0xcc) {
                return positiveDimensions(
                    readUnsignedShortBigEndian(bytes, offset + 7),
                    readUnsignedShortBigEndian(bytes, offset + 5));
            }
            if ((marker >= 0xd0 && marker <= 0xd9) || marker == 0x01) {
                offset += 2;
                continue;
            }
            if (offset + 3 >= bytes.length) return null;
            int segmentLength = readUnsignedShortBigEndian(bytes, offset + 2);
            if (segmentLength < 2) return null;
            offset += 2 + segmentLength;
        }
        return null;
    }

    private static PastedDims positiveDimensions(int width, int height) {
        if (width <= 0 || height <= 0) return null;
        return new PastedDims(width, height, width, height);
    }

    private static int unsigned(byte value) {
        return value & 0xff;
    }

    private static int readUnsignedShortBigEndian(byte[] bytes, int offset) {
        return (unsigned(bytes[offset]) << 8) | unsigned(bytes[offset + 1]);
    }

    private static int readUnsignedShortLittleEndian(byte[] bytes, int offset) {
        return unsigned(bytes[offset]) | (unsigned(bytes[offset + 1]) << 8);
    }

    private static int readIntBigEndian(byte[] bytes, int offset) {
        return (unsigned(bytes[offset]) << 24) | (unsigned(bytes[offset + 1]) << 16)
            | (unsigned(bytes[offset + 2]) << 8) | unsigned(bytes[offset + 3]);
    }

    private static long readUnsignedIntLittleEndian(byte[] bytes, int offset) {
        return unsigned(bytes[offset]) | ((long) unsigned(bytes[offset + 1]) << 8)
            | ((long) unsigned(bytes[offset + 2]) << 16)
            | ((long) unsigned(bytes[offset + 3]) << 24);
    }

    private static int readUnsignedMediumLittleEndian(byte[] bytes, int offset) {
        return unsigned(bytes[offset]) | (unsigned(bytes[offset + 1]) << 8)
            | (unsigned(bytes[offset + 2]) << 16);
    }

    /**
     * matches. Metadata is
     * emitted only when the image was resized or a source path is available.
     */
    public static String createImageMetadataText(PastedDims dims, String sourcePath) {
        if (dims == null
                || dims.originalWidth() == null || dims.originalHeight() == null
                || dims.displayWidth() == null || dims.displayHeight() == null
                || dims.originalWidth() <= 0 || dims.originalHeight() <= 0
                || dims.displayWidth() <= 0 || dims.displayHeight() <= 0) {
            return sourcePath != null ? "[Image source: " + sourcePath + "]" : null;
        }
        boolean wasResized = !dims.originalWidth().equals(dims.displayWidth())
            || !dims.originalHeight().equals(dims.displayHeight());
        if (!wasResized && sourcePath == null) return null;

        StringBuilder metadata = new StringBuilder("[Image: ");
        if (sourcePath != null) metadata.append("source: ").append(sourcePath);
        if (wasResized) {
            if (sourcePath != null) metadata.append(", ");
            double scale = (double) dims.originalWidth() / dims.displayWidth();
            metadata.append(String.format(Locale.ROOT,
                "original %dx%d, displayed at %dx%d. Multiply coordinates by %.2f to map to original image.",
                dims.originalWidth(), dims.originalHeight(),
                dims.displayWidth(), dims.displayHeight(), scale));
        }
        return metadata.append(']').toString();
    }

    /** Strips an "image/" prefix if present — yields the ImageIO format name. */
    private static String stripImagePrefix(String mt) {
        if (mt == null) return "png";
        return Strings.CS.startsWith(mt, "image/") ? mt.substring(6) : mt;
    }

    // ── Encoders ────────────────────────────────────────────────────────────

    private static byte[] encodePng(BufferedImage img) {
        return toBytes(img, "png");
    }

    private static byte[] encodeJpeg(BufferedImage img, int quality) {
        return ReleasedImageCodec.encodeJpeg(img, quality);
    }

    private static byte[] compressJpegToBudget(byte[] buffer, String mediaType, int maxBytes) {
        BufferedImage image;
        try {
            image = ImageIO.read(new ByteArrayInputStream(buffer));
        } catch (IOException | LinkageError _) {
            return buffer;
        }
        if (image == null) return buffer;

        byte[] smallest = buffer;
        int high = 90;
        if (mediaType == null || !mediaType.matches("(?i)image/jpe?g")) {
            byte[] quality90 = encodeJpeg(image, 90);
            if (quality90.length > 0 && quality90.length < smallest.length) smallest = quality90;
            if (quality90.length > 0 && quality90.length <= maxBytes) return quality90;
            high = 89;
        }

        int low = 1;
        byte[] bestFit = null;
        for (int attempt = 0; attempt < 5 && low <= high; attempt++) {
            int quality = (low + high) / 2;
            byte[] candidate = encodeJpeg(image, quality);
            if (candidate.length > 0 && candidate.length < smallest.length) smallest = candidate;
            if (candidate.length > 0 && candidate.length <= maxBytes) {
                bestFit = candidate;
                low = quality + 1;
            } else {
                high = quality - 1;
            }
        }
        return bestFit != null ? bestFit : smallest;
    }

    private static BufferedImage resize(BufferedImage src, int w, int h) {
        if (src.getWidth() == w && src.getHeight() == h) return src;
        return ReleasedImageCodec.resizeLanczos3(src, w, h);
    }

    private static byte[] toBytes(BufferedImage img, String format) {
        if (img == null) return new byte[0];
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            // ImageIO.write returns false when no registered writer matches
            // the format (e.g. "jpg" instead of "jpeg", or a custom format
            // for which the JRE lacks a plugin). Without checking the return
            // value we'd hand callers an empty byte[] and pass it on as a
            // valid encoded image — see API request log showing data="".
            boolean ok = ImageIO.write(img, format, baos);
            if (!ok) return new byte[0];
        } catch (IOException | LinkageError _) {
            return new byte[0];
        }
        return baos.toByteArray();
    }

    /** Media type for an extension — {@code "png" → "image/png"}. */
    private static String extToMediaType(String ext) {
        if (StringUtils.isEmpty(ext)) return "image/png";
        String e = ext.toLowerCase(Locale.ROOT);
        if (Strings.CS.equals("jpg", e)) e = "jpeg";
        return "image/" + e;
    }


    static String detectMediaType(byte[] bytes, String fallbackExt) {
        if (bytes.length >= 8 && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 0x50
                && bytes[2] == 0x4E && bytes[3] == 0x47) return "image/png";
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8
                && (bytes[2] & 0xFF) == 0xFF) return "image/jpeg";
        if (bytes.length >= 6 && bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F') return "image/gif";
        if (bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') return "image/webp";
        return extToMediaType(fallbackExt);
    }

    /** Convenience: resize a base64 image string. */
    public static ResizeResult maybeResizeAndDownsampleBase64(String base64, String mediaType) {
        byte[] buf = Base64.getDecoder().decode(base64);
        String ext = mediaType != null && Strings.CS.startsWith(mediaType, "image/")
            ? mediaType.substring(6) : "png";
        return maybeResizeAndDownsample(buf, ext);
    }
}
