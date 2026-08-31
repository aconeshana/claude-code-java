package com.claudecode.mcp;

import org.apache.commons.lang3.StringUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Shared persistence primitives for binary MCP output.
 */
public final class McpOutputStorage {

    private static final long SIX_BASE36_DIGITS = 2_176_782_336L;

    private McpOutputStorage() {}

    /** Result of persisting one binary MCP payload. */
    public record PersistResult(Path filepath, int size, String extension, String error) {
        public static PersistResult success(Path filepath, int size, String extension) {
            return new PersistResult(filepath, size, extension, null);
        }

        public static PersistResult failure(String error) {
            return new PersistResult(null, 0, null, error);
        }

        public boolean succeeded() {
            return filepath != null;
        }
    }

    /** Writes raw bytes using a MIME-derived extension. */
    public static PersistResult persistBinaryContent(Path directory, byte[] bytes,
                                                     String mimeType, String persistId) {
        if (directory == null) {
            return PersistResult.failure("Tool-results directory is unavailable");
        }
        try {
            Files.createDirectories(directory);
            String extension = extensionForMimeType(mimeType);
            Path filepath = directory.resolve(persistId + "." + extension);
            Files.write(filepath, bytes);
            return PersistResult.success(filepath, bytes.length, extension);
        } catch (IOException | RuntimeException error) {
            String detail = error.getMessage();
            return PersistResult.failure(StringUtils.isBlank(detail)
                ? error.getClass().getSimpleName() : detail);
        }
    }


    public static String extensionForMimeType(String mimeType) {
        if (StringUtils.isBlank(mimeType)) return "bin";
        String normalized = mimeType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "application/pdf" -> "pdf";
            case "application/json" -> "json";
            case "text/csv" -> "csv";
            case "text/plain" -> "txt";
            case "text/html" -> "html";
            case "text/markdown" -> "md";
            case "application/zip" -> "zip";
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "docx";
            case "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> "xlsx";
            case "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> "pptx";
            case "application/msword" -> "doc";
            case "application/vnd.ms-excel" -> "xls";
            case "audio/mpeg" -> "mp3";
            case "audio/wav" -> "wav";
            case "audio/ogg" -> "ogg";
            case "video/mp4" -> "mp4";
            case "video/webm" -> "webm";
            case "image/png" -> "png";
            case "image/jpeg" -> "jpg";
            case "image/gif" -> "gif";
            case "image/webp" -> "webp";
            case "image/svg+xml" -> "svg";
            default -> "bin";
        };
    }

    /** Returns the six-character base36 suffix used in MCP persistence IDs. */
    public static String randomBase36Suffix() {
        String value = Long.toString(
            ThreadLocalRandom.current().nextLong(SIX_BASE36_DIGITS), 36);
        return "0".repeat(6 - value.length()) + value;
    }
}
