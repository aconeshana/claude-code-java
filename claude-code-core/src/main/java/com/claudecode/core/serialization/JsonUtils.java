package com.claudecode.core.serialization;

import org.apache.commons.lang3.StringUtils;
import com.claudecode.core.io.FileUtils;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON utility methods for Claude Code, backed by Jackson.
 */
public final class JsonUtils {

    private static final ObjectMapper MAPPER = createMapper();
    private static final ObjectReader STRICT_READER = MAPPER.reader()
        .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final long MAX_JSONL_READ_BYTES = 100L * 1024 * 1024;
    private static final int PARSE_CACHE_MAX_KEY_BYTES = 8 * 1024;
    private static final int PARSE_CACHE_ENTRIES = 50;
    private static final Map<String, JsonNode> SAFE_PARSE_CACHE = Collections.synchronizedMap(
        new LinkedHashMap<>(64, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, JsonNode> eldest) {
                return size() > PARSE_CACHE_ENTRIES;
            }
        });

    private JsonUtils() {}

    private static ObjectMapper createMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new Jdk8Module());
        mapper.registerModule(new JavaTimeModule());
        mapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        return mapper;
    }

    /**
     * Returns the shared pre-configured mapper for object binding and nested
     * custom deserializers. Root-level JSON entry points use {@link #STRICT_READER}
     * so this compatibility escape hatch remains permissive for field parsers.
     */
    public static ObjectMapper getMapper() {
        return MAPPER;
    }

    /** Serializes an object to a JSON string. */
    public static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Serializes an object to an indented JSON string. */
    public static String toPrettyJson(Object value) throws JsonProcessingException {
        return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value);
    }

    /** Deserializes a JSON string to the specified type. */
    public static <T> T fromJson(String json, Class<T> type) {
        try {
            return MAPPER.readerFor(type)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .readValue(json);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to parse JSON", e);
        }
    }

    /** Parses a JSON string into a JsonNode tree. */
    public static JsonNode parseTree(String json) {
        try {
            return STRICT_READER.readTree(json);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to parse JSON tree", e);
        }
    }


    public static String stripBom(String content) {
        return StringUtils.isNotEmpty(content) && content.charAt(0) == '\uFEFF'
            ? content.substring(1) : content;
    }


    public static JsonNode safeParseJson(String json) {
        if (StringUtils.isEmpty(json)) return null;
        if (json.getBytes(StandardCharsets.UTF_8).length <= PARSE_CACHE_MAX_KEY_BYTES) {
            synchronized (SAFE_PARSE_CACHE) {
                if (SAFE_PARSE_CACHE.containsKey(json)) return SAFE_PARSE_CACHE.get(json);
            }
        }
        JsonNode parsed;
        try {
            parsed = STRICT_READER.readTree(stripBom(json));
            // Do not expose Jackson's NullNode through the safe-parse wrapper.
            if (parsed != null && parsed.isNull()) parsed = null;
        } catch (IOException _) {
            parsed = null;
        }
        if (json.getBytes(StandardCharsets.UTF_8).length <= PARSE_CACHE_MAX_KEY_BYTES) {
            synchronized (SAFE_PARSE_CACHE) {
                SAFE_PARSE_CACHE.put(json, parsed);
            }
        }
        return parsed;
    }

    /** Tolerant JSON-with-comments parser, including trailing commas. */
    public static JsonNode safeParseJsonc(String jsonc) {
        if (StringUtils.isBlank(jsonc)) return null;
        return safeParseJson(stripJsoncCommentsAndTrailingCommas(stripBom(jsonc)));
    }

    /**
     * Appends an item to a root JSONC array without discarding existing comments.
     * Invalid/non-array input falls back to a new four-space-indented array.
     */
    public static String addItemToJsoncArray(String content, Object newItem) {
        String clean = stripBom(content == null ? "" : content);
        if (StringUtils.isBlank(clean)) return prettyArrayWith(newItem);
        JsonNode parsed = safeParseJsonc(clean);
        if (parsed == null || !parsed.isArray()) return prettyArrayWith(newItem);
        int close = findRootArrayClose(clean);
        if (close < 0) return prettyArrayWith(newItem);
        String prefix = clean.substring(0, close);
        boolean empty = parsed.isEmpty();
        String indentation = "    ";
        String serialized;
        try {
            serialized = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(newItem);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
        serialized = serialized.replace("\n", "\n" + indentation);
        String separator = empty ? "" : ",";
        String insertion = separator + "\n" + indentation + serialized + "\n";
        return prefix.stripTrailing() + insertion + clean.substring(close);
    }

    private static String prettyArrayWith(Object item) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(List.of(item));
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static int findRootArrayClose(String value) {
        boolean string = false, escaped = false, lineComment = false, blockComment = false;
        int depth = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            char next = i + 1 < value.length() ? value.charAt(i + 1) : 0;
            if (lineComment) { if (c == '\n') lineComment = false; continue; }
            if (blockComment) { if (c == '*' && next == '/') { blockComment = false; i++; } continue; }
            if (string) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') string = false;
                continue;
            }
            if (c == '"') { string = true; continue; }
            if (c == '/' && next == '/') { lineComment = true; i++; continue; }
            if (c == '/' && next == '*') { blockComment = true; i++; continue; }
            if (c == '[') depth++;
            else if (c == ']' && --depth == 0) return i;
        }
        return -1;
    }

    /** Converts JSONC into strict JSON while preserving comment-like text in strings. */
    public static String stripJsoncCommentsAndTrailingCommas(String jsonc) {
        if (StringUtils.isBlank(jsonc)) return "";
        StringBuilder out = new StringBuilder(jsonc.length());
        boolean string = false, escaped = false, lineComment = false, blockComment = false;
        for (int i = 0; i < jsonc.length(); i++) {
            char c = jsonc.charAt(i);
            char next = i + 1 < jsonc.length() ? jsonc.charAt(i + 1) : 0;
            if (lineComment) {
                if (c == '\n') { lineComment = false; out.append(c); }
                continue;
            }
            if (blockComment) {
                if (c == '*' && next == '/') { blockComment = false; i++; }
                else if (c == '\n') out.append('\n');
                continue;
            }
            if (string) {
                out.append(c);
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') string = false;
                continue;
            }
            if (c == '"') { string = true; out.append(c); continue; }
            if (c == '/' && next == '/') { lineComment = true; i++; continue; }
            if (c == '/' && next == '*') { blockComment = true; i++; continue; }
            if (c == ',') {
                int j = i + 1;
                while (j < jsonc.length() && Character.isWhitespace(jsonc.charAt(j))) j++;
                if (j < jsonc.length() && (jsonc.charAt(j) == '}' || jsonc.charAt(j) == ']')) continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    /** Parses newline-delimited JSON, skipping blank and malformed lines. */
    public static List<JsonNode> parseJsonLines(String data) {
        if (StringUtils.isEmpty(data)) return List.of();
        int length = data.length();
        int start = data.charAt(0) == '\uFEFF' ? 1 : 0;
        List<JsonNode> values = new ArrayList<>();
        while (start < length) {
            int end = data.indexOf('\n', start);
            if (end < 0) end = length;
            String line = data.substring(start, end).trim();
            start = end + 1;
            if (line.isEmpty()) continue;
            try {
                values.add(STRICT_READER.readTree(line));
            } catch (IOException _) {

            }
        }
        return values;
    }

    /** Reads and parses a JSONL file, reading at most its last 100 MB. */
    public static List<JsonNode> readJsonLines(Path path) throws IOException {
        return readJsonLines(path, MAX_JSONL_READ_BYTES);
    }

    static List<JsonNode> readJsonLines(Path path, long maxBytes) throws IOException {
        if (maxBytes <= 0 || maxBytes > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("maxBytes must be between 1 and Integer.MAX_VALUE");
        }
        long size = Files.size(path);
        if (size <= maxBytes) {
            return parseJsonLines(Files.readString(path, StandardCharsets.UTF_8));
        }

        byte[] tail = new byte[(int) maxBytes];
        int totalRead = 0;
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            channel.position(size - maxBytes);
            ByteBuffer buffer = ByteBuffer.wrap(tail);
            while (buffer.hasRemaining()) {
                int read = channel.read(buffer);
                if (read < 0) break;
                totalRead += read;
            }
        }

        int start = 0;
        for (int i = 0; i < totalRead; i++) {
            if (tail[i] == '\n') {
                if (i < totalRead - 1) start = i + 1;
                break;
            }
        }
        return parseJsonLines(new String(tail, start, totalRead - start, StandardCharsets.UTF_8));
    }

    /**
     * Reads a JSON file strictly (no JSONC / comment tolerance) into a {@link JsonNode} tree.
     * Uses the same root-level trailing-token check as {@link #parseTree(String)};
     * throws {@link IOException} on failure.
     */
    public static JsonNode readJson(Path path) throws IOException {
        try (var input = Files.newInputStream(path)) {
            return STRICT_READER.readTree(input);
        }
    }

    /** Reads a JSON file strictly into the requested Java type. */
    public static <T> T readJson(Path path, Class<T> type) throws IOException {
        return MAPPER.readerFor(type)
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .readValue(path.toFile());
    }

    /**
     * Writes a {@link JsonNode} to {@code path} as pretty-printed JSON, creating parent directories.
     * Pretty-printing matches {@code writerWithDefaultPrettyPrinter}.
     */
    public static void writeJson(Path path, JsonNode node) throws IOException {
        writeJson(path, (Object) node, true);
    }

    /**
     * Writes a {@link JsonNode} to {@code path}, creating parent directories. When {@code pretty} is
     * true the output is pretty-printed, otherwise compact.
     */
    public static void writeJson(Path path, JsonNode node, boolean pretty) throws IOException {
        writeJson(path, (Object) node, pretty);
    }

    /** Writes an arbitrary value to {@code path} as pretty-printed JSON. */
    public static void writeJson(Path path, Object value) throws IOException {
        writeJson(path, value, true);
    }

    /**
     * Writes an arbitrary value to {@code path}, creating parent directories. When
     * {@code pretty} is true the output is pretty-printed, otherwise compact.
     */
    public static void writeJson(Path path, Object value, boolean pretty) throws IOException {
        String json = pretty
                ? MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value)
                : MAPPER.writeValueAsString(value);
        FileUtils.writeString(path, json);
    }
}
