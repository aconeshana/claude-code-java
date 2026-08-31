package com.claudecode.services.claudemd;

import org.apache.commons.lang3.StringUtils;
import com.claudecode.core.engine.FileStateCache;
import com.claudecode.core.memdir.AutoMemoryPrompt;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Turns a {@link MemoryFileScanner} scan into the trailing <em>CLAUDE.md instructions</em> block
 * that gets appended to the system prompt.
 */
public final class MemoryPromptBuilder {

    private final MemoryFileScanner scanner;
    /** cwd → auto-memory dir. Injectable so tests keep writes inside a temp
     *  base instead of the real {@code ~/.claude/projects}. */
    private final Function<Path, Path> autoMemDirResolver;

    public MemoryPromptBuilder(MemoryFileScanner scanner) {
        this(scanner, AutoMemoryPrompt::ensureAutoMemDir);
    }

    MemoryPromptBuilder(MemoryFileScanner scanner,
                        Function<Path, Path> autoMemDirResolver) {
        this.scanner = scanner;
        this.autoMemDirResolver = autoMemDirResolver;
    }

    /**
     * Scan {@code cwd} for memory files, apply glob-filter, join into a single
     * markdown block separated by {@code \n\n---\n\n}. Returns empty string
     * when no memory applies.
     */
    public String build(Path cwd) {
        return build(cwd, List.of(),
            Set.of(MemoryType.USER, MemoryType.PROJECT, MemoryType.LOCAL));
    }

    public String build(Path cwd, List<Path> additionalDirs) {
        return build(cwd, additionalDirs,
            Set.of(MemoryType.USER, MemoryType.PROJECT, MemoryType.LOCAL));
    }

    /**
     * @param additionalDirs extra project roots (typically from
     *        {@code --add-dir} / {@code /add-dir}) passed straight through
     *        to {@link MemoryFileScanner#scan(Path, List, Set)}. Env-gated in
     *        the scanner — no-op unless
     *        {@code CLAUDE_CODE_ADDITIONAL_DIRECTORIES_CLAUDE_MD} is truthy.
     * @param enabledScopes memory scopes to load (from {@code --settings}
     *        CLI flag / SDK setting-sources). Empty = no memory at all.
     */
    public String build(Path cwd, List<Path> additionalDirs, Set<MemoryType> enabledScopes) {
        return build(cwd, additionalDirs, enabledScopes, null);
    }

    /**
     * Builds the eager claudeMd block and records every actually-injected instruction file in the
     * session read-state cache.
     */
    public String build(Path cwd, List<Path> additionalDirs, Set<MemoryType> enabledScopes,
                        FileStateCache fileStateCache) {
        List<MemoryFileInfo> scanned = scanner.scan(cwd, additionalDirs, enabledScopes);
        Path cwdAbs = cwd.toAbsolutePath().normalize();
        List<MemoryFileInfo> included = scanned.stream()
            .filter(f -> MemoryFileScanner.matchGlobs(f.globs(), cwdAbs))
            .filter(f -> !StringUtils.isBlank(f.content()))
            .toList();
        if (fileStateCache != null) {
// The eager discovery pass is one observation event.
            long observedAtMs = System.currentTimeMillis();
            for (MemoryFileInfo file : included) {
                String absolutePath = file.path().toAbsolutePath().toString();
                if (fileStateCache.get(absolutePath) != null) continue;
                String cachedContent = file.contentDiffersFromDisk()
                    ? (file.rawContent() != null ? file.rawContent() : file.content())
                    : file.content();
                fileStateCache.set(absolutePath,
                    new FileStateCache.FileState(cachedContent, observedAtMs,
                        null, null, file.contentDiffersFromDisk()));
            }
        }
        List<String> blocks = included.stream()
            .map(MemoryPromptBuilder::renderFile)
            .collect(Collectors.toCollection(ArrayList::new));

        String memoryIndex = renderAutoMemoryIndex(cwd);
        if (memoryIndex != null) blocks.add(memoryIndex);
        return String.join("\n\n", blocks);
    }

    /**
     * Renders {@code <autoMemDir>/MEMORY.md} as a claudeMd block, or null when
     * absent/blank/unreadable. Dir resolution is memoized+created by
     * {@link com.claudecode.core.memdir.AutoMemoryPrompt#ensureAutoMemDir}
     * (same instance the {@code # Memory} system-prompt section names, so the
     * write side and this read side always agree on the path).
     */
    private String renderAutoMemoryIndex(Path cwd) {
        try {
            Path memDir = autoMemDirResolver.apply(cwd);
            if (memDir == null) return null;
            Path entrypoint = memDir.resolve(AutoMemoryPrompt.ENTRYPOINT_NAME);
            if (!Files.isReadable(entrypoint)) return null;
            String content = Files.readString(entrypoint);
            if (StringUtils.isBlank(content)) return null;
            return "Contents of " + entrypoint.toAbsolutePath()
                + " (user's auto-memory, persists across conversations):\n\n"
                + AutoMemoryPrompt.truncateEntrypoint(content.strip());
        } catch (Exception _) {
            return null;
        }
    }

    /**
     * One file → {@code "Contents of <abs path> (<scope description>):\n\n<content>"}.
     */
    private static String renderFile(MemoryFileInfo f) {
        String desc = switch (f.type()) {
            case PROJECT -> "(project instructions, checked into the codebase)";
            case LOCAL   -> "(user's private project instructions, not checked in)";
            default      -> "(user's private global instructions for all projects)";
        };
        return "Contents of " + f.path().toAbsolutePath() + " " + desc + ":\n\n"
            + f.content().strip();
    }
}
