package com.claudecode.services.claudemd;

import com.claudecode.core.attachment.AttachmentContext;
import com.claudecode.core.attachment.AttachmentProvider;
import com.claudecode.core.engine.FileStateCache;
import com.claudecode.core.engine.HookDispatcher;
import com.claudecode.core.message.AttachmentPayload;
import com.claudecode.core.message.NestedMemoryAttachment;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Auto-injects CLAUDE.md memory files that are "in scope" for a file the model just read or {@code
 * @}-mentioned.
 */
public final class NestedMemoryAttachmentProvider implements AttachmentProvider {

    private final MemoryFileScanner scanner;
    private final Supplier<HookDispatcher> hookDispatcher;

    public NestedMemoryAttachmentProvider(MemoryFileScanner scanner, Supplier<HookDispatcher> hookDispatcher) {
        this.scanner = scanner;
        this.hookDispatcher = hookDispatcher;
    }

    @Override
    public String name() {
        return "nested_memory";
    }

    @Override
    public List<AttachmentPayload> collect(AttachmentContext ctx) {
        Set<String> triggers = ctx.nestedMemoryAttachmentTriggers();
        if (triggers == null || triggers.isEmpty()) {
            return List.of();
        }
        Path cwd = Path.of(ctx.workingDirectory()).toAbsolutePath().normalize();
        List<AttachmentPayload> out = new ArrayList<>();
        Set<Path> processed = new HashSet<>();
        for (String filePath : triggers) {
            Path target = Path.of(filePath).toAbsolutePath().normalize();
            out.addAll(getForFile(target, cwd, ctx, processed));
        }
        return out;
    }

    private List<AttachmentPayload> getForFile(Path target, Path cwd, AttachmentContext ctx, Set<Path> processed) {
        List<AttachmentPayload> out = new ArrayList<>();
        Path targetDir = target.getParent();

        // Phase 1: User conditional rules matching the target path.
        for (MemoryFileInfo f : scanner.scanDirectory(cwd, Set.of(MemoryType.USER))) {
            if (MemoryFileScanner.matchGlobs(f.globs(), target)) {
                add(f, out, ctx, processed);
            }
        }
        // Phase 2: nested dirs (cwd → target): CLAUDE.md + rules, unconditional + conditional.
        for (Path dir : nestedDirs(cwd, targetDir)) {
            for (MemoryFileInfo f : scanner.scanDirectory(dir, Set.of(MemoryType.PROJECT, MemoryType.LOCAL))) {
                add(f, out, ctx, processed);
            }
        }
        // Phase 3: cwd-level dirs (root → cwd): conditional rules only.
        for (Path dir : cwdLevelDirs(cwd)) {
            for (MemoryFileInfo f : scanner.scanDirectory(dir, Set.of(MemoryType.PROJECT, MemoryType.LOCAL))) {
                if (MemoryFileScanner.matchGlobs(f.globs(), target)) {
                    add(f, out, ctx, processed);
                }
            }
        }
        return out;
    }

    private void add(MemoryFileInfo f, List<AttachmentPayload> out, AttachmentContext ctx, Set<Path> processed) {
        if (!processed.add(f.path())) {
            return;
        }
        if (ctx.loadedNestedMemoryPaths().contains(f.path().toString())) {
            return;
        }


        if (ctx.fileStateCache().get(f.path().toString()) != null) {
            return;
        }
        ctx.loadedNestedMemoryPaths().add(f.path().toString());

// Mark readFileState (cross-turn dedup via the LRU.has check).

// wall-clock time while real Reads.
        try {
            long now = System.currentTimeMillis();
            ctx.fileStateCache().set(f.path().toString(), new FileStateCache.FileState(
                f.contentDiffersFromDisk() && f.rawContent() != null ? f.rawContent() : f.content(),
                now, null, null, f.contentDiffersFromDisk()));
        } catch (Exception _) {
            // Best-effort; skip the readFileState stamp if the file vanished.
        }

        out.add(new NestedMemoryAttachment(
            f.path().toString(), f.content(), scopeDescription(f.type())));

        HookDispatcher hd = hookDispatcher.get();
        if (hd != null) {
            String reason = f.globs() != null ? "path_glob_match"
                : f.parent() != null ? "include" : "nested_traversal";
            try {
                hd.dispatchInstructionsLoaded(
                    f.path().toString(), tsLabel(f.type()), reason, f.globs());
            } catch (Throwable _) {
                // Audit event — non-fatal.
            }
        }
    }

    /** dirs between CWD and targetDir (CWD → target order), only those under CWD. */
    private static List<Path> nestedDirs(Path cwd, Path targetDir) {
        List<Path> dirs = new ArrayList<>();
        for (Path dir = targetDir; dir != null && !dir.equals(cwd); dir = dir.getParent()) {
            if (dir.startsWith(cwd)) {
                dirs.add(dir);
            }
        }
        Collections.reverse(dirs); // target→cwd becomes cwd→target
        return dirs;
    }

    /** dirs from filesystem root to CWD (root → cwd order). */
    private static List<Path> cwdLevelDirs(Path cwd) {
        List<Path> dirs = new ArrayList<>();
        for (Path dir = cwd; dir != null; dir = dir.getParent()) {
            dirs.add(dir);
        }
        Collections.reverse(dirs); // target→root becomes root→cwd
        return dirs;
    }

    private static String scopeDescription(MemoryType type) {
        return switch (type) {
            case PROJECT -> "(project instructions, checked into the codebase)";
            case LOCAL -> "(user's private project instructions, not checked in)";
            default -> "(user's private global instructions for all projects)";
        };
    }

    private static String tsLabel(MemoryType t) {
        return switch (t) {
            case PROJECT -> "Project";
            case LOCAL -> "Local";
            default -> "User";
        };
    }
}
