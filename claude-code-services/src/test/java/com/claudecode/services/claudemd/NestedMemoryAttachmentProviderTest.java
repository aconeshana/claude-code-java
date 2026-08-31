package com.claudecode.services.claudemd;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.claudecode.core.attachment.AttachmentContext;
import com.claudecode.core.engine.FileStateCache;
import com.claudecode.core.engine.HookDispatcher;
import com.claudecode.core.message.AttachmentPayload;
import com.claudecode.core.message.NestedMemoryAttachment;
import com.fasterxml.jackson.databind.JsonNode;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Coverage for {@link NestedMemoryAttachmentProvider} — the auto-injection of CLAUDE.md files "in
 * scope" for a file the model just read.
 */
class NestedMemoryAttachmentProviderTest {

    @Test
    void noTriggersYieldsNoAttachments(@TempDir Path root) throws Exception {
        Path home = freshHome(root);
        var provider = new NestedMemoryAttachmentProvider(new MemoryFileScanner(home, List.of(), null), () -> null);
        AttachmentContext ctx = context(root.resolve("cwd"), ConcurrentHashMap.newKeySet(), ConcurrentHashMap.newKeySet());
        assertTrue(provider.collect(ctx).isEmpty());
    }

    @Test
    void attachesProjectClaudeMdInNestedDir(@TempDir Path root) throws Exception {
        Path cwd = root.resolve("cwd");
        Files.createDirectories(cwd.resolve("sub"));
        Path target = cwd.resolve("sub").resolve("foo.txt");
        Files.writeString(target, "x");
        Path nested = cwd.resolve("sub").resolve("CLAUDE.md");
        Files.writeString(nested, "nested rules");

        Path home = freshHome(root);
        var provider = new NestedMemoryAttachmentProvider(new MemoryFileScanner(home, List.of(), null), () -> null);

        Set<String> triggers = ConcurrentHashMap.newKeySet();
        triggers.add(target.toString());
        AttachmentContext ctx = context(cwd, triggers, ConcurrentHashMap.newKeySet());

        List<AttachmentPayload> out = provider.collect(ctx);
        assertEquals(1, out.size());
        NestedMemoryAttachment a = (NestedMemoryAttachment) out.getFirst();
        assertEquals(nested.toString(), a.path());
        assertEquals("nested rules", a.content());
        assertEquals("(project instructions, checked into the codebase)", a.scopeDescription());
    }

    @Test
    void loadedSetDeduplicatesAcrossCollectCalls(@TempDir Path root) throws Exception {
        Path cwd = root.resolve("cwd");
        Files.createDirectories(cwd.resolve("sub"));
        Path target = cwd.resolve("sub").resolve("foo.txt");
        Files.writeString(target, "x");
        Files.writeString(cwd.resolve("sub").resolve("CLAUDE.md"), "rules");

        Path home = freshHome(root);
        var provider = new NestedMemoryAttachmentProvider(new MemoryFileScanner(home, List.of(), null), () -> null);

        Set<String> triggers = ConcurrentHashMap.newKeySet();
        triggers.add(target.toString());
        Set<String> loaded = ConcurrentHashMap.newKeySet();
        AttachmentContext ctx = context(cwd, triggers, loaded);

        assertEquals(1, provider.collect(ctx).size());
        // Second call with same loaded set — nothing new to attach.
        assertEquals(0, provider.collect(ctx).size());
        assertFalse(loaded.isEmpty());
    }

    @Test
    void alreadyReadMemoryFileIsNotReInjected(@TempDir Path root) throws Exception {
        Path cwd = root.resolve("cwd");
        Files.createDirectories(cwd.resolve("sub"));
        Path target = cwd.resolve("sub").resolve("foo.txt");
        Files.writeString(target, "x");
        Path nested = cwd.resolve("sub").resolve("CLAUDE.md");
        Files.writeString(nested, "rules");

        Path home = freshHome(root);
        var provider = new NestedMemoryAttachmentProvider(new MemoryFileScanner(home, List.of(), null), () -> null);

        Set<String> triggers = ConcurrentHashMap.newKeySet();
        triggers.add(target.toString());

// guards against re-injection via readFileState.has.
        FileStateCache cache = new FileStateCache();
        cache.set(nested.toString(),
            new FileStateCache.FileState("rules", System.currentTimeMillis(), null, null, false));
        AttachmentContext ctx = contextWithCache(cwd, triggers, ConcurrentHashMap.newKeySet(), cache);

        assertTrue(provider.collect(ctx).isEmpty());
    }

    @Test
    void firesInstructionsLoadedHookOnAttach(@TempDir Path root) throws Exception {
        Path cwd = root.resolve("cwd");
        Files.createDirectories(cwd.resolve("sub"));
        Path target = cwd.resolve("sub").resolve("foo.txt");
        Files.writeString(target, "x");
        Path nested = cwd.resolve("sub").resolve("CLAUDE.md");
        Files.writeString(nested, "rules");

        Path home = freshHome(root);
        RecordingHookDispatcher hook = new RecordingHookDispatcher();
        var provider = new NestedMemoryAttachmentProvider(new MemoryFileScanner(home, List.of(), null), () -> hook);

        Set<String> triggers = ConcurrentHashMap.newKeySet();
        triggers.add(target.toString());
        provider.collect(context(cwd, triggers, ConcurrentHashMap.newKeySet()));

        assertEquals(1, hook.loadedPaths.size());
        assertEquals(nested.toString(), hook.loadedPaths.getFirst());
        assertEquals("Project", hook.loadedTypes.getFirst());
        assertEquals("nested_traversal", hook.loadedReasons.getFirst());
    }

    private static Path freshHome(Path root) throws Exception {
        Path home = root.resolve("home-" + Thread.currentThread().getId());
        Files.createDirectories(home);
        return home;
    }

    private static AttachmentContext context(Path cwd, Set<String> triggers, Set<String> loaded) {
        return AttachmentContext.builder(cwd.toString())
            .fileStateCache(new FileStateCache())
            .loadedNestedMemoryPaths(loaded)
            .nestedMemoryAttachmentTriggers(triggers)
            .build();
    }

    private static AttachmentContext contextWithCache(Path cwd, Set<String> triggers,
                                                       Set<String> loaded, FileStateCache cache) {
        return AttachmentContext.builder(cwd.toString())
            .fileStateCache(cache)
            .loadedNestedMemoryPaths(loaded)
            .nestedMemoryAttachmentTriggers(triggers)
            .build();
    }

    /** Minimal HookDispatcher double capturing INSTRUCTIONS_LOADED events. */
    private static final class RecordingHookDispatcher implements HookDispatcher {
        final List<String> loadedPaths = new ArrayList<>();
        final List<String> loadedTypes = new ArrayList<>();
        final List<String> loadedReasons = new ArrayList<>();

        @Override
        public boolean dispatchPreToolUse(String toolName, JsonNode input, String toolUseId) {
            return true;
        }

        @Override
        public void dispatchPostToolUse(String toolName, JsonNode input, JsonNode output, String toolUseId) {
        }

        @Override
        public void dispatchUserPromptSubmit(String prompt) {
        }

        @Override
        public void dispatchSessionStart(String trigger) {
        }

        @Override
        public void dispatchStop(String reason) {
        }

        @Override
        public void dispatchInstructionsLoaded(String filePath, String memoryType,
                                               String loadReason, List<String> globs) {
            loadedPaths.add(filePath);
            loadedTypes.add(memoryType);
            loadedReasons.add(loadReason);
        }
    }
}
