package com.claudecode.core.paste;

import com.claudecode.core.imagestore.ImageResizer;
import com.claudecode.core.message.PastedContent;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;

/**
 * Shared command execution and PNG-file decoding for clipboard backends.
 */
final class ClipboardImageProcesses {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private ClipboardImageProcesses() {}

    static ClipboardReadResult readCommandToFile(List<String> command, Path output) {
        try {
            Process process = new ProcessBuilder(command)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .redirectOutput(output.toFile())
                .start();
            try { process.getOutputStream().close(); } catch (IOException _) {}
            if (!process.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                terminate(process);
                return ClipboardReadResult.Unavailable.transientFailure(
                    new IOException("clipboard command timed out"));
            }
            if (process.exitValue() != 0) return new ClipboardReadResult.Empty();
            return readPng(output);
        } catch (IOException failure) {
            return ClipboardReadResult.Unavailable.transientFailure(failure);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return ClipboardReadResult.Unavailable.transientFailure(failure);
        } finally {
            delete(output);
        }
    }

    static ClipboardReadResult runCommandWritingFile(List<String> command, Path output) {
        return runCommandWritingFile(command, output, Map.of());
    }

    static ClipboardReadResult runCommandWritingFile(
        List<String> command,
        Path output,
        Map<String, String> environment
    ) {
        try {
            ProcessBuilder builder = new ProcessBuilder(command)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD);
            builder.environment().putAll(environment);
            Process process = builder.start();
            try { process.getOutputStream().close(); } catch (IOException _) {}
            if (!process.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                terminate(process);
                return ClipboardReadResult.Unavailable.transientFailure(
                    new IOException("clipboard command timed out"));
            }
            if (process.exitValue() != 0 || Files.notExists(output)) {
                return new ClipboardReadResult.Empty();
            }
            return readPng(output);
        } catch (IOException failure) {
            return ClipboardReadResult.Unavailable.transientFailure(failure);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return ClipboardReadResult.Unavailable.transientFailure(failure);
        } finally {
            delete(output);
        }
    }

    private static ClipboardReadResult readPng(Path output) throws IOException {
        byte[] bytes = Files.readAllBytes(output);
        if (bytes.length == 0) return new ClipboardReadResult.Empty();
        if (isBmp(bytes)) bytes = convertBmpToPng(bytes);
        if (!isPng(bytes)) {
            return ClipboardReadResult.Unavailable.transientFailure(
                new IOException("clipboard command returned unsupported image bytes"));
        }
        ImageResizer.ResizeResult resized = ImageResizer.maybeResizeAndDownsample(bytes, "png");
        ImageResizer.PastedDims dims = resized.dimensions();
        PastedContent.ImageDimensions pastedDims = dims == null ? null
            : new PastedContent.ImageDimensions(
                dims.originalWidth(), dims.originalHeight(),
                dims.displayWidth(), dims.displayHeight());
        return new ClipboardReadResult.Image(new ImagePaste.ImageWithDimensions(
            Base64.getEncoder().encodeToString(resized.buffer()),
            resized.mediaType(), pastedDims));
    }

    private static boolean isPng(byte[] bytes) {
        return bytes.length >= 8 && bytes[0] == (byte) 0x89
            && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G';
    }

    private static boolean isBmp(byte[] bytes) {
        return bytes.length >= 2 && bytes[0] == 'B' && bytes[1] == 'M';
    }

    private static byte[] convertBmpToPng(byte[] bytes) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
        if (image == null) throw new IOException("unable to decode clipboard BMP");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", output)) {
            throw new IOException("unable to encode clipboard BMP as PNG");
        }
        return output.toByteArray();
    }

    private static void delete(Path output) {
        try { Files.deleteIfExists(output); } catch (IOException _) {}
    }

    private static void terminate(Process process) throws InterruptedException {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        process.waitFor(1, TimeUnit.SECONDS);
    }
}
