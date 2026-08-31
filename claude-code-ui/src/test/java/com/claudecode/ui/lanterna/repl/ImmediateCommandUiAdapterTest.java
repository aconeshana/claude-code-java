package com.claudecode.ui.lanterna.repl;

import com.claudecode.commands.Command;
import com.claudecode.commands.CommandContext;
import com.claudecode.commands.metadata.CommandMetadata;
import com.claudecode.commands.CommandResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Unit tests for {@link ImmediateCommandUiAdapter#shouldTreatAsImmediate}.
 *
 * <p>These tests verify the dispatch-decision logic independently of the
 * Lanterna UI components (InputPanel / MessagePanel), which require a
 * terminal environment and cannot be instantiated in a headless test.
 * The static helper method is package-private for exactly this reason.
 */
class ImmediateCommandUiAdapterTest {

    // ── Test doubles ──────────────────────────────────────────────────────

/** Command stub with {@code isImmediate = true}. */
    private static final Command IMMEDIATE_CMD = new Command() {
        @Override public CommandMetadata metadata() {
            return new CommandMetadata("btw", "BTW — immediate stub");
        }
        @Override public boolean isImmediate() { return true; }
        @Override public CommandResult execute(CommandContext ctx, String args) {
            return CommandResult.of("ok");
        }
    };

/** Command stub with {@code isImmediate = false} (the default). */
    private static final Command NORMAL_CMD = new Command() {
        @Override public CommandMetadata metadata() {
            return new CommandMetadata("help", "help — normal stub");
        }
// isImmediate uses the default → false
        @Override public CommandResult execute(CommandContext ctx, String args) {
            return CommandResult.of("help text");
        }
    };

    // ── shouldTreatAsImmediate decision matrix ────────────────────────────

    @Test
    void immediateCmd_queryActive_noKeybinding_dispatches() {
        assertTrue(ImmediateCommandUiAdapter.shouldTreatAsImmediate(
                IMMEDIATE_CMD, false, true),
            "immediate command while query is active must return true");
    }

    @Test
    void normalCmd_queryActive_noKeybinding_doesNotDispatch() {
        assertFalse(ImmediateCommandUiAdapter.shouldTreatAsImmediate(
                NORMAL_CMD, false, true),
            "non-immediate command while query is active must return false");
    }

    @Test
    void normalCmd_queryActive_fromKeybinding_dispatches() {
        // Even a non-immediate command is treated as immediate when triggered

        assertTrue(ImmediateCommandUiAdapter.shouldTreatAsImmediate(
                NORMAL_CMD, true, true),
            "keybinding-triggered command while query is active must return true");
    }

    @Test
    void immediateCmd_queryIdle_noKeybinding_doesNotDispatch() {
        // Guard only fires when a query IS in flight (queryActive == true).
        // When idle, immediate commands still go through the normal path.
        assertFalse(ImmediateCommandUiAdapter.shouldTreatAsImmediate(
                IMMEDIATE_CMD, false, false),
            "immediate command when query is idle must return false (no bypass needed)");
    }

    @Test
    void immediateCmd_queryIdle_fromKeybinding_doesNotDispatch() {
        // fromKeybinding alone cannot override the queryActive guard.
        assertFalse(ImmediateCommandUiAdapter.shouldTreatAsImmediate(
                IMMEDIATE_CMD, true, false),
            "keybinding + immediate command when query is idle must return false");
    }

    @Test
    void normalCmd_queryIdle_fromKeybinding_doesNotDispatch() {
        assertFalse(ImmediateCommandUiAdapter.shouldTreatAsImmediate(
                NORMAL_CMD, true, false),
            "keybinding + non-immediate command when query is idle must return false");
    }

    // ── GUI-thread affinity ───────────────────────────────────────────────
    // executeAndNotify runs on the immediate-cmd virtual thread. A Lanterna
    // component mutated from there locks itself and then walks the parent
// chain for its theme, while a concurrent updateScreen holds those
    // parents and descends — a silent, intermittent TUI freeze. MessagePanel
    // is exempt by design (private ReentrantReadWriteLock, never the component
    // monitor), but every InputPanel touch must be marshalled.

    @Test
    void backgroundCommandResultTouchesInputPanelOnlyOnTheGuiThread() throws Exception {
        String body = methodBody(Files.readString(Path.of(
            "src/main/java/com/claudecode/ui/lanterna/repl/ImmediateCommandUiAdapter.java")),
            "private void executeAndNotify(");
        List<int[]> marshalled = marshalledRegions(body);

        for (int at = body.indexOf("inputPanel."); at >= 0;
                at = body.indexOf("inputPanel.", at + 1)) {
            int position = at;
            assertTrue(
                marshalled.stream().anyMatch(r -> position > r[0] && position < r[1]),
                "executeAndNotify touches InputPanel off the GUI thread");
        }
    }

    /** {@code onGuiThread.accept(...)} argument spans, located by paren balance. */
    private static List<int[]> marshalledRegions(String body) {
        List<int[]> regions = new ArrayList<>();
        String marker = "onGuiThread.accept(";
        for (int at = body.indexOf(marker); at >= 0; at = body.indexOf(marker, at + 1)) {
            int open = at + marker.length() - 1;
            regions.add(new int[] {open, matchingClose(body, open, '(', ')')});
        }
        assertFalse(regions.isEmpty(), "expected the UI half to be marshalled");
        return regions;
    }

    private static String methodBody(String source, String signature) {
        int at = source.indexOf(signature);
        assertTrue(at >= 0, () -> "method not found: " + signature);
        int open = source.indexOf('{', at);
        return source.substring(open, matchingClose(source, open, '{', '}') + 1);
    }

    private static int matchingClose(String source, int open, char opener, char closer) {
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == opener) depth++;
            else if (c == closer && --depth == 0) return i;
        }
        throw new AssertionError("unbalanced " + opener + " at " + open);
    }
}
