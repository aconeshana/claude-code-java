package com.claudecode.session.stats;

import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.session.stats.ClaudeCodeStats.ModelUsage;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

/**
 * Selective streaming reader for the transcript fields consumed by usage
 * statistics. Large message bodies, tool inputs/results, progress payloads,
 * and other unrelated JSON values are skipped directly by Jackson's token
 * parser rather than materialized as {@code JsonNode} trees.
 *
 * <ul>
 *   <li>per-entry
 *       extraction of message counts, timestamps, tool uses, token usage, and
 *       speculation time.</li>
 *   <li>malformed-row and
 *       BOM tolerance, plus the last-100-MB cap and first-partial-line skip.</li>
 * </ul>
 */
final class TranscriptStatsScanner {

    static final long MAX_JSONL_READ_BYTES = 100L * 1024 * 1024;
    private static final int INPUT_BUFFER_BYTES = 64 * 1024;
    private static final String SYNTHETIC_MODEL = "<synthetic>";

    private final long maxReadBytes;

    TranscriptStatsScanner() {
        this(MAX_JSONL_READ_BYTES);
    }

    TranscriptStatsScanner(long maxReadBytes) {
        if (maxReadBytes <= 0) throw new IllegalArgumentException("maxReadBytes must be positive");
        this.maxReadBytes = maxReadBytes;
    }

    record ScanResult(
        long speculationMs,
        long mainCount,
        String firstTimestamp,
        String lastTimestamp,
        long toolUseCount,
        Map<String, ModelUsage> usageByModel,
        long totalTokens
    ) {}

    ScanResult scan(Path path, boolean subagent) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            long size = channel.size();
            long startOffset = Math.max(0, size - maxReadBytes);
            long parseOffset = tailParseOffset(channel, startOffset, size);
            channel.position(parseOffset);
            try (InputStream input = new BufferedInputStream(
                    Channels.newInputStream(channel), INPUT_BUFFER_BYTES)) {
                Accumulator accumulator = new Accumulator(subagent);
                scanLines(input, accumulator);
                return accumulator.result();
            }
        }
    }

    private static long tailParseOffset(FileChannel channel, long startOffset, long size)
            throws IOException {
        if (startOffset == 0) return 0;
        channel.position(startOffset);
        ByteBuffer buffer = ByteBuffer.allocate(INPUT_BUFFER_BYTES);
        long absolute = startOffset;
        while (channel.read(buffer) >= 0) {
            int length = buffer.position();
            buffer.flip();
            for (int i = 0; i < length; i++) {
                if (buffer.get() == '\n') {
                    long afterNewline = absolute + i + 1;
                    return afterNewline < size ? afterNewline : startOffset;
                }
            }
            if (length == 0) break;
            absolute += length;
            buffer.clear();
        }
        return startOffset;
    }

    private static void scanLines(InputStream input, Accumulator accumulator) throws IOException {
        JsonLineInput lineInput = new JsonLineInput(
            new PushbackInputStream(input, INPUT_BUFFER_BYTES));
        while (!lineInput.sourceExhausted()) {
            lineInput.beginLine();
            try (JsonParser parser = JsonUtils.getMapper().getFactory().createParser(lineInput)) {
                if (parser.nextToken() == JsonToken.START_OBJECT) {
                    RootFields root = parseRoot(parser);
                    if (parser.nextToken() == null) accumulator.accept(root);
                }
            } catch (IOException _) {

            } finally {
                lineInput.drainLine();
            }
        }
    }

    private static RootFields parseRoot(JsonParser parser) throws IOException {
        String type = null;
        String timestamp = null;
        boolean sidechain = false;
        long timeSavedMs = 0;
        MessageFields message = null;

        while (parser.nextToken() != JsonToken.END_OBJECT) {
            String field = parser.currentName();
            JsonToken value = parser.nextToken();
            if (field == null || value == null) break;
            switch (field) {
                case "type" -> type = textValue(parser, value);
                case "timestamp" -> timestamp = textValue(parser, value);
                case "isSidechain" -> sidechain = value == JsonToken.VALUE_TRUE;
                case "timeSavedMs" -> timeSavedMs = longValue(parser, value);
                case "message" -> {
                    if (value == JsonToken.START_OBJECT) message = parseMessage(parser);
                    else parser.skipChildren();
                }
                default -> parser.skipChildren();
            }
        }
        return new RootFields(type, timestamp, sidechain, timeSavedMs, message);
    }

    private static MessageFields parseMessage(JsonParser parser) throws IOException {
        String model = "unknown";
        long toolUses = 0;
        UsageFields usage = null;
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            String field = parser.currentName();
            JsonToken value = parser.nextToken();
            if (field == null || value == null) break;
            switch (field) {
                case "model" -> {
                    String parsed = textValue(parser, value);
                    model = StringUtils.isEmpty(parsed) ? "unknown" : parsed;
                }
                case "content" -> {
                    if (value == JsonToken.START_ARRAY) toolUses = parseContent(parser);
                    else parser.skipChildren();
                }
                case "usage" -> {
                    if (value == JsonToken.START_OBJECT) usage = parseUsage(parser);
                    else parser.skipChildren();
                }
                default -> parser.skipChildren();
            }
        }
        return new MessageFields(model, toolUses, usage);
    }

    private static long parseContent(JsonParser parser) throws IOException {
        long toolUses = 0;
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            if (parser.currentToken() != JsonToken.START_OBJECT) {
                parser.skipChildren();
                continue;
            }
            String blockType = null;
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String field = parser.currentName();
                JsonToken value = parser.nextToken();
                if (Strings.CS.equals("type", field)) blockType = textValue(parser, value);
                else parser.skipChildren();
            }
            if (Strings.CS.equals("tool_use", blockType)) toolUses++;
        }
        return toolUses;
    }

    private static UsageFields parseUsage(JsonParser parser) throws IOException {
        long input = 0;
        long output = 0;
        long cacheRead = 0;
        long cacheCreation = 0;
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            String field = parser.currentName();
            JsonToken value = parser.nextToken();
            if (field == null || value == null) break;
            switch (field) {
                case "input_tokens" -> input = longValue(parser, value);
                case "output_tokens" -> output = longValue(parser, value);
                case "cache_read_input_tokens" -> cacheRead = longValue(parser, value);
                case "cache_creation_input_tokens" -> cacheCreation = longValue(parser, value);
                default -> parser.skipChildren();
            }
        }
        return new UsageFields(input, output, cacheRead, cacheCreation);
    }

    private static String textValue(JsonParser parser, JsonToken token) throws IOException {
        return token == JsonToken.VALUE_STRING ? parser.getText() : null;
    }

    private static long longValue(JsonParser parser, JsonToken token) throws IOException {
        return token.isNumeric() || token == JsonToken.VALUE_STRING ? parser.getValueAsLong(0) : 0;
    }

    private record RootFields(
        String type, String timestamp, boolean sidechain, long timeSavedMs, MessageFields message) {}

    private record MessageFields(String model, long toolUses, UsageFields usage) {}

    private record UsageFields(long input, long output, long cacheRead, long cacheCreation) {}

    private static final class Accumulator {
        private final boolean subagent;
        private final Map<String, ModelUsage> usageByModel = new LinkedHashMap<>();
        private long speculationMs;
        private long mainCount;
        private String firstTimestamp;
        private String lastTimestamp;
        private long toolUseCount;
        private long totalTokens;

        private Accumulator(boolean subagent) {
            this.subagent = subagent;
        }

        private void accept(RootFields root) {
            if (Strings.CS.equals("speculation-accept", root.type())) {
                speculationMs += root.timeSavedMs();
                return;
            }
            if (!isTranscriptType(root.type())) return;
            if (!subagent && root.sidechain()) return;

            mainCount++;
            if (mainCount == 1) firstTimestamp = root.timestamp();
            lastTimestamp = root.timestamp();
            if (!Strings.CS.equals("assistant", root.type()) || root.message() == null) return;

            MessageFields message = root.message();
            toolUseCount += message.toolUses();
            if (message.usage() == null || SYNTHETIC_MODEL.equals(message.model())) return;
            UsageFields usage = message.usage();
            ModelUsage delta = new ModelUsage(
                usage.input(), usage.output(), usage.cacheRead(), usage.cacheCreation(),
                0, 0, 0, 0);
            usageByModel.merge(message.model(), delta, ModelUsage::plus);
            totalTokens += usage.input() + usage.output();
        }

        private ScanResult result() {
            return new ScanResult(speculationMs, mainCount, firstTimestamp, lastTimestamp,
                toolUseCount,
                Collections.unmodifiableMap(new LinkedHashMap<>(usageByModel)), totalTokens);
        }

        private static boolean isTranscriptType(String type) {
            return switch (type) {
                case "user", "assistant", "attachment", "system" -> true;
                default -> false;
            };
        }
    }

    /** Presents one JSONL row as an independent stream without buffering the row. */
    private static final class JsonLineInput extends InputStream {
        private final PushbackInputStream source;
        private boolean lineEnded = true;
        private boolean sourceExhausted;

        private JsonLineInput(PushbackInputStream source) {
            this.source = source;
        }

        private void beginLine() {
            lineEnded = false;
        }

        private boolean sourceExhausted() {
            return sourceExhausted;
        }

        @Override
        public int read() throws IOException {
            if (lineEnded || sourceExhausted) return -1;
            int value = source.read();
            if (value < 0) {
                sourceExhausted = true;
                lineEnded = true;
                return -1;
            }
            if (value == '\n') {
                lineEnded = true;
                return -1;
            }
            return value;
        }

        @Override
        public int read(byte[] target, int offset, int length) throws IOException {
            if (lineEnded || sourceExhausted) return -1;
            int read = source.read(target, offset, Math.min(length, INPUT_BUFFER_BYTES));
            if (read < 0) {
                sourceExhausted = true;
                lineEnded = true;
                return -1;
            }
            for (int i = 0; i < read; i++) {
                if (target[offset + i] != '\n') continue;
                int afterNewline = read - i - 1;
                if (afterNewline > 0) {
                    source.unread(target, offset + i + 1, afterNewline);
                }
                lineEnded = true;
                return i == 0 ? -1 : i;
            }
            return read;
        }

        private void drainLine() throws IOException {
            while (!lineEnded && !sourceExhausted) {
                if (read() < 0) break;
            }
        }

        /** Jackson closes each per-row parser; the shared transcript stream remains open. */
        @Override public void close() {}
    }
}
