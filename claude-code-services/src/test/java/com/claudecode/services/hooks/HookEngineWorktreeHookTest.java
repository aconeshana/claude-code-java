package com.claudecode.services.hooks;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import com.claudecode.core.serialization.JsonUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;


class HookEngineWorktreeHookTest {

    private static boolean shellAvailable() {
        try {
            return new ProcessBuilder("sh", "-c", "true").start().waitFor() == 0;
        } catch (Exception _) { return false; }
    }

    private static HooksSettings settings(HookEvent event, String command) {
        return new HooksSettings(Map.of(event,
            List.of(new HookMatcher(Optional.empty(), List.of(new BashCommandHook(command))))));
    }

    @Test
    void hasWorktreeCreateHook_trueOnlyWhenConfigured() {
        assertFalse(new HookEngine(HooksSettings.EMPTY, "/tmp").hasWorktreeCreateHook());
        assertTrue(new HookEngine(settings(HookEvent.WORKTREE_CREATE, "echo /x"), "/tmp")
            .hasWorktreeCreateHook());
    }

    @Test
    void dispatchWorktreeCreate_returnsHookStdoutAsPath(@TempDir Path dir) {
        assumeTrue(shellAvailable(), "sh not available");
        // The hook echoes an absolute path — dispatch must surface it as the worktree path.
        String wtPath = dir.resolve("created-wt").toString();
        HookEngine engine = new HookEngine(settings(HookEvent.WORKTREE_CREATE, "echo " + wtPath), dir.toString());

        Optional<String> result = engine.dispatchWorktreeCreate("my-slug");

        assertTrue(result.isPresent());
        assertEquals(wtPath, result.get());
    }

    @Test
    void dispatchWorktreeCreate_emptyWhenNoHook() {
        assertTrue(new HookEngine(HooksSettings.EMPTY, "/tmp").dispatchWorktreeCreate("x").isEmpty());
    }

    @Test
    void dispatchWorktreeCreate_acceptsReleasedStructuredPath() {
        var callback = new CallbackHook("worktree", (_, _) -> {
            var root = JsonUtils.getMapper().createObjectNode();
            var specific = root.putObject("hookSpecificOutput");
            specific.put("hookEventName", "WorktreeCreate");
            specific.put("worktreePath", "/tmp/from-structured-hook");
            return root;
        }, Optional.empty());
        HookEngine engine = new HookEngine(new HooksSettings(Map.of(
            HookEvent.WORKTREE_CREATE,
            List.of(new HookMatcher(Optional.empty(), List.of(callback))))), "/tmp");

        assertEquals("/tmp/from-structured-hook",
            engine.dispatchWorktreeCreate("slug").orElseThrow());
    }

    @Test
    void dispatchWorktreeRemove_trueWhenHookRan(@TempDir Path dir) {
        assumeTrue(shellAvailable(), "sh not available");
        HookEngine engine = new HookEngine(settings(HookEvent.WORKTREE_REMOVE, "true"), dir.toString());
        assertTrue(engine.dispatchWorktreeRemove("/some/worktree"));
    }

    @Test
    void dispatchWorktreeRemove_falseWhenNoHook() {
        assertFalse(new HookEngine(HooksSettings.EMPTY, "/tmp").dispatchWorktreeRemove("/x"));
    }
}
