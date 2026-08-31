package com.claudecode.session;

import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.commons.lang3.Strings;

/** Memory-bounded forward transcript reader for resume and SDK history APIs. */
public final class TranscriptReadService {
    public static final int SKIP_PRECOMPACT_THRESHOLD = 5 * 1024 * 1024;
    private static final int READ_CHUNK_SIZE = 1024 * 1024;

    private TranscriptReadService() {}

    public static byte[] readTranscriptForLoad(Path file) {
        try {
            long size = Files.size(file);
            if (size <= SKIP_PRECOMPACT_THRESHOLD
                    || Boolean.parseBoolean(System.getenv("CLAUDE_CODE_DISABLE_PRECOMPACT_SKIP"))) {
                return Files.readAllBytes(file);
            }
            return readLarge(file, size);
        } catch (IOException failure) {
            throw new UncheckedIOException("Failed to read transcript: " + file, failure);
        }
    }

    private static byte[] readLarge(Path file, long size) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream((int) Math.min(size, 8L * 1024 * 1024));
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        byte[] lastAttribution = null;
        byte[] buffer = new byte[READ_CHUNK_SIZE];
        try (BufferedInputStream input = new BufferedInputStream(Files.newInputStream(file),
                READ_CHUNK_SIZE)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                for (int i = 0; i < read; i++) {
                    byte value = buffer[i];
                    line.write(value);
                    if (value != '\n') continue;
                    byte[] bytes = line.toByteArray();
                    line.reset();
                    if (isAttributionSnapshot(bytes)) {
                        lastAttribution = bytes;
                        continue;
                    }
                    if (isResetBoundary(bytes)) {
                        output.reset();
                        lastAttribution = null;
                    }
                    output.write(bytes);
                }
            }
        }
        if (line.size() > 0) {
            byte[] bytes = line.toByteArray();
            if (isAttributionSnapshot(bytes)) lastAttribution = bytes;
            else {
                if (isResetBoundary(bytes)) { output.reset(); lastAttribution = null; }
                output.write(bytes);
            }
        }
        if (lastAttribution != null) {
            if (output.size() > 0 && output.toByteArray()[output.size() - 1] != '\n') output.write('\n');
            output.write(lastAttribution);
        }
        return output.toByteArray();
    }

    private static boolean isAttributionSnapshot(byte[] line) {
        String value = new String(line, StandardCharsets.UTF_8).stripLeading();
        return Strings.CS.startsWith(value, "{\"type\":\"attribution-snapshot\"")
            || Strings.CS.startsWith(value, "{\"type\": \"attribution-snapshot\"");
    }

    private static boolean isResetBoundary(byte[] line) {
        String value = new String(line, StandardCharsets.UTF_8).trim();
        if (!Strings.CS.contains(value, "compact_boundary")) return false;
        try {
            JsonNode node = JsonUtils.getMapper().readTree(value);
            return Strings.CS.equals("system", node.path("type").asText())
                && Strings.CS.equals("compact_boundary", node.path("subtype").asText())
                && !node.path("compactMetadata").has("preservedSegment")
                && !node.path("compactMetadata").has("preservedMessages");
        } catch (Exception _) {
            return false;
        }
    }
}
