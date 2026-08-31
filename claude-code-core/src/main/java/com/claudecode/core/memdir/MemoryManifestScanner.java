package com.claudecode.core.memdir;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.core.io.FileUtils;
import com.claudecode.core.util.FrontmatterParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Scans an auto-memory directory for individual memory files and formats them into a manifest —
 * pre-injected into the extraction agent's prompt so it doesn't spend a turn on {@code ls}.
 */
public final class MemoryManifestScanner {

    private static final int MAX_MEMORY_FILES = 200;

    private MemoryManifestScanner() {}

/**
     * One memory file's header info.
     */
    public record MemoryHeader(String filename, Path filePath, long mtimeMs, String description, String type) {}

    /**
     * Scans {@code memoryDir} for {@code .md} files (excluding {@code MEMORY.md}
     * itself), recursively. Returns headers sorted newest-first, capped at
     * {@link #MAX_MEMORY_FILES}. Returns an empty list if the directory
     * doesn't exist or can't be read — this is a best-effort manifest, not a
     * correctness-critical read.
     */
    public static List<MemoryHeader> scan(Path memoryDir) {
        if (memoryDir == null || !Files.isDirectory(memoryDir)) {
            return List.of();
        }
        List<MemoryHeader> headers = new ArrayList<>();
        FrontmatterParser parser = FrontmatterParser.shared();
        try (Stream<Path> walk = Files.walk(memoryDir)) {
            List<Path> mdFiles = walk
                .filter(Files::isRegularFile)
                .filter(p -> Strings.CS.endsWith(p.getFileName().toString(), ".md"))
                .filter(p -> !p.getFileName().toString().equals(AutoMemoryPrompt.ENTRYPOINT_NAME))
                .toList();
            for (Path file : mdFiles) {
                try {
                    String content = Files.readString(file);
                    long mtimeMs = FileUtils.modificationTimeMillis(file);
                    FrontmatterParser.ParseResult result = parser.parse(content);
                    String type = FrontmatterParser.getString(result.metadata(), "type");
                    Object nestedMetadata = result.metadata().get("metadata");
                    if (type == null && nestedMetadata instanceof Map<?, ?> nested) {
                        Object nestedType = nested.get("type");
                        if (nestedType instanceof String value) type = value;
                    }
                    headers.add(new MemoryHeader(
                        memoryDir.relativize(file).toString(),
                        file,
                        mtimeMs,
                        result.description(),
                        type));
                } catch (IOException _) {
                    // Best-effort: skip unreadable files rather than failing the whole scan.
                }
            }
        } catch (IOException _) {
            return List.of();
        }
        return headers.stream()
            .sorted(Comparator.comparingLong(MemoryHeader::mtimeMs).reversed())
            .limit(MAX_MEMORY_FILES)
            .toList();
    }

    /**
     * Formats headers as a text manifest: one line per file with {@code [type] filename (timestamp):
     * description}.
     */
    public static String formatManifest(List<MemoryHeader> headers) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < headers.size(); i++) {
            MemoryHeader m = headers.get(i);
            if (i > 0) sb.append('\n');
            String tag = (StringUtils.isNotBlank(m.type())) ? "[" + m.type() + "] " : "";
            String ts = Instant.ofEpochMilli(m.mtimeMs()).toString();
            sb.append("- ").append(tag).append(m.filename()).append(" (").append(ts).append(")");
            if (StringUtils.isNotBlank(m.description())) {
                sb.append(": ").append(m.description());
            }
        }
        return sb.toString();
    }
}
