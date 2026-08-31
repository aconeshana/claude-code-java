package com.claudecode.tools.worktree;

import org.apache.commons.lang3.Strings;
import com.claudecode.runtime.query.DefaultQuerySession;
import com.claudecode.runtime.query.QuerySessionSpec;
import com.claudecode.core.engine.StreamingClient;
import com.claudecode.core.prompt.SystemPromptRuntime;
import com.claudecode.core.prompt.SystemPromptSectionResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Proves the {@code env_info_simple} system-prompt section reads {@code
 * WorktreeService.getCurrentWorktreeSession} and {@code System.getProperty("user.dir")}
 * LIVE on every {@code assembleSystemPrompt} call, instead of a value frozen at
 * {@code QuerySessionSpec} construction — a mid-session worktree switch (via
 * {@code EnterWorktreeTool}/{@code ExitWorktreeTool}) must show up in the very next
 * turn's prompt without rebuilding the {@link DefaultQuerySession}.
 */
class WorktreeSystemPromptTest {

    private static final StreamingClient NOOP_CLIENT = new StreamingClient() {
        @Override public Iterator<StreamingEvent> createStream(StreamRequest request) {
            return Collections.emptyIterator();
        }
        @Override public String getModel() { return "test-model"; }
    };

    private String savedUserDir;

    @BeforeEach
    void setUp() {
        savedUserDir = System.getProperty("user.dir");
        SystemPromptSectionResolver.clearAll();
    }

    @AfterEach
    void tearDown() {
        WorktreeService.clearCurrentSessionForTests();
        if (savedUserDir != null) System.setProperty("user.dir", savedUserDir);
        SystemPromptSectionResolver.clearAll();
    }

    @Test
    void envInfo_reflectsLiveWorktreeSwitchWithoutRebuildingEngine(@TempDir Path original, @TempDir Path worktree) {
        System.setProperty("user.dir", original.toString());
        DefaultQuerySession engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(NOOP_CLIENT)
            .workingDirectory(original.toString())
            .promptRuntimeSupplier(() -> new SystemPromptRuntime(
                null, false, null, false, List.of(), List.of(),
                WorktreeService.getCurrentWorktreeSession() != null))
            .build());

        String before = engine.assembleSystemPrompt(null);
        assertTrue(Strings.CS.contains(before, "Primary working directory: " + original), before);
        assertFalse(Strings.CS.contains(before, "This is a git worktree"), before);

        // Simulate what EnterWorktreeTool does: populate WorktreeService, switch
        // user.dir, and invalidate the cached section — same engine instance, no
        // QuerySessionSpec rebuild.
        WorktreeSession session = new WorktreeSession(
            original.toString(), worktree.toString(), "feature-x", "worktree-feature-x",
            "main", "abc123", "sess-1", null, false, 0L, false);
        WorktreeService.restoreWorktreeSession(session);
        System.setProperty("user.dir", worktree.toString());
        SystemPromptSectionResolver.clearAll();

        String after = engine.assembleSystemPrompt(null);
        assertTrue(Strings.CS.contains(after, "Primary working directory: " + worktree), after);
        assertTrue(Strings.CS.contains(after, "This is a git worktree"), after);
    }
}
