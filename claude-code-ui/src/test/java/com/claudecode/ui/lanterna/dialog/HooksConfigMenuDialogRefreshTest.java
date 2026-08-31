package com.claudecode.ui.lanterna.dialog;

import com.claudecode.runtime.hooks.HookConfigurationSnapshot;
import com.claudecode.runtime.hooks.HookConfigurationSnapshot.HookEntry;
import com.claudecode.runtime.hooks.HookConfigurationSnapshot.HookEvent;
import com.claudecode.runtime.hooks.HookConfigurationSnapshot.HookEventMetadata;
import com.claudecode.runtime.hooks.HookConfigurationSnapshot.HookKind;
import com.claudecode.keybindings.UserKeybindingsStore;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies {@link HooksConfigMenuDialog#refresh} preserves navigation state whenever the new
 * on-disk snapshot still makes sense, and unwinds one level at a time when it doesn't.
 */
class HooksConfigMenuDialogRefreshTest {

    private static final HookEvent EV_PRE  = HookEvent.PRE_TOOL_USE;
    private static final HookEvent EV_POST = HookEvent.POST_TOOL_USE;

    /** Matcher-supporting metadata used by both events in the fixture. */
    private static final Map<HookEvent, HookEventMetadata> METADATA_WITH_MATCHERS =
        buildMetadata();

    private static Map<HookEvent, HookEventMetadata> buildMetadata() {
        Map<HookEvent, HookEventMetadata> m = new LinkedHashMap<>();
        var matcher = new HookEventMetadata.MatcherMetadata("bash|read", "tool_name");
        m.put(EV_PRE,  new HookEventMetadata("Before tool", "desc", matcher));
        m.put(EV_POST, new HookEventMetadata("After tool",  "desc", matcher));
        return m;
    }

    private static HookEntry bashHook(HookEvent event, String matcher, String cmd) {
        return new HookEntry(event, HookKind.COMMAND, matcher,
            "User Settings", "User", "User settings (~/.claude/settings.json)", cmd, cmd);
    }

    private static HookConfigurationSnapshot snapshot(List<HookEntry> hooks) {
        return new HookConfigurationSnapshot(hooks, METADATA_WITH_MATCHERS);
    }

    private static HookConfigurationSnapshot snapshot(
        List<HookEntry> hooks, Map<HookEvent, HookEventMetadata> metadata) {
        return new HookConfigurationSnapshot(hooks, metadata);
    }

    // ── Refresh preservation ─────────────────────────────────────────────────

    @Test
    void refresh_sameSnapshot_preservesSelectMatcherState() {
        HooksConfigMenuDialog d = new HooksConfigMenuDialog();
        List<HookEntry> hooks = List.of(
            bashHook(EV_PRE, "Bash", "echo pre"),
            bashHook(EV_PRE, "Read", "echo read"));
        d.show(snapshot(hooks), false, false, () -> {});
        d.enterMatcherStateForTest(EV_PRE);

        assertEquals(HooksConfigMenuDialog.NavState.SELECT_MATCHER, d.navStateForTest());
        // Cursor math: EV_PRE is the first event, "Bash" the first sorted matcher.
        assertEquals(0, d.eventIdxForTest(), "EV_PRE is the first event in events");
        assertEquals(0, d.matcherIdxForTest(), "Bash sorts before Read");
        assertTrue(d.eventsForTest().containsAll(List.of(EV_PRE, EV_POST)),
            "events reflect the metadata keyset");

        // Refresh with the exact same snapshot — nothing should move.
        d.refresh(snapshot(hooks), false, false);

        assertEquals(HooksConfigMenuDialog.NavState.SELECT_MATCHER, d.navStateForTest(),
            "identical refresh must not bounce user back to SELECT_EVENT");
        assertEquals(EV_PRE, d.selectedEventForTest());
        assertEquals(0, d.eventIdxForTest(), "identical refresh must preserve cursor");
        assertEquals(0, d.matcherIdxForTest(), "identical refresh must preserve cursor");
    }

    @Test
    void refresh_selectedEventDeleted_fallsBackToSelectEvent() {
        HooksConfigMenuDialog d = new HooksConfigMenuDialog();
        List<HookEntry> hooks = List.of(
            bashHook(EV_PRE,  "Bash", "echo pre"),
            bashHook(EV_POST, "Read", "echo post"));
        d.show(snapshot(hooks), false, false, () -> {});
        d.enterMatcherStateForTest(EV_PRE);

        // Snapshot 2: PRE_TOOL_USE is no longer in metadata (event dropped
        // from the map by the on-disk config).
        Map<HookEvent, HookEventMetadata> newMeta = new LinkedHashMap<>();
        newMeta.put(EV_POST, METADATA_WITH_MATCHERS.get(EV_POST));

        d.refresh(snapshot(List.of(bashHook(EV_POST, "Read", "echo post")), newMeta),
            false, false);

        assertEquals(HooksConfigMenuDialog.NavState.SELECT_EVENT, d.navStateForTest(),
            "when the selected event disappears from metadata, unwind to SELECT_EVENT");
    }

    @Test
    void refresh_selectedMatcherDeleted_fallsBackFromSelectHook() {
        HooksConfigMenuDialog d = new HooksConfigMenuDialog();
        List<HookEntry> hooks = List.of(
            bashHook(EV_PRE, "Bash", "echo pre-bash"),
            bashHook(EV_PRE, "Read", "echo pre-read"));
        d.show(snapshot(hooks), false, false, () -> {});
        d.enterHookStateForTest(EV_PRE, "Bash");
        assertEquals(HooksConfigMenuDialog.NavState.SELECT_HOOK, d.navStateForTest());
        // Cursor math: event idx 0, Bash is first sorted matcher, only Bash hook present.
        assertEquals(0, d.eventIdxForTest(), "EV_PRE is the first event");
        assertEquals(0, d.matcherIdxForTest(), "Bash sorts before Read");
        assertEquals(0, d.hookIdxForTest(), "only the Bash matcher's hook is in the list");

        // Snapshot 2: the Bash matcher is gone; only Read remains.
        List<HookEntry> newHooks = List.of(bashHook(EV_PRE, "Read", "echo pre-read"));
        d.refresh(snapshot(newHooks), false, false);

        // The matcher we drilled into no longer exists — hookList becomes empty
        // and refresh should pop back to SELECT_MATCHER (matchers list is non-empty).
        assertEquals(HooksConfigMenuDialog.NavState.SELECT_MATCHER, d.navStateForTest(),
            "when the selected matcher's hooks vanish, unwind one level to SELECT_MATCHER");
    }

    @Test
    void refresh_selectedHookDeleted_fallsBackFromViewHook() {
        HooksConfigMenuDialog d = new HooksConfigMenuDialog();
        HookEntry h1 = bashHook(EV_PRE, "Bash", "echo one");
        HookEntry h2 = bashHook(EV_PRE, "Bash", "echo two");
        d.show(snapshot(List.of(h1, h2)), false, false, () -> {});
        d.enterViewStateForTest(EV_PRE, "Bash", h1);
        assertEquals(HooksConfigMenuDialog.NavState.VIEW_HOOK, d.navStateForTest());
        // Cursor math: view state must pin the exact hook AND its indices.
        assertSame(h1, d.selectedHookForTest(), "viewed hook must be the one passed in");
        assertEquals(0, d.eventIdxForTest(), "EV_PRE is the first event");
        assertEquals(0, d.matcherIdxForTest(), "Bash sorts before Read");
        assertEquals(0, d.hookIdxForTest(), "h1 is the first hook under the Bash matcher");

        // Snapshot 2: h1 is deleted; h2 still there.
        d.refresh(snapshot(List.of(h2)), false, false);

        assertEquals(HooksConfigMenuDialog.NavState.SELECT_HOOK, d.navStateForTest(),
            "when the currently-viewed hook disappears, pop back to SELECT_HOOK");
    }

    @Test
    void refresh_disabledFlipsOn_forcesDisabledScreen() {
        HooksConfigMenuDialog d = new HooksConfigMenuDialog();
        List<HookEntry> hooks = List.of(bashHook(EV_PRE, "Bash", "echo pre"));
        d.show(snapshot(hooks), false, false, () -> {});
        d.enterHookStateForTest(EV_PRE, "Bash");


        d.refresh(snapshot(hooks), true, false);

        assertTrue(d.isDisabledForTest(),
            "refresh must propagate disabled=true so the disabled screen shows");
        assertEquals(HooksConfigMenuDialog.NavState.SELECT_EVENT, d.navStateForTest(),
            "disabled screen renders at the top level, so nav must reset");
    }

    @Test
    void refresh_whileHidden_isNoOp() {
        HooksConfigMenuDialog d = new HooksConfigMenuDialog();
        d.show(snapshot(List.of()), false, false, () -> {});
        d.hide();
        assertFalse(d.isActive());

        // Should not throw and should not reactivate.
        d.refresh(snapshot(List.of(bashHook(EV_PRE, "Bash", "cmd"))), false, false);
        assertFalse(d.isActive(), "refresh on a hidden dialog must not re-activate it");
    }

    @Test
    void refresh_flipsRestrictedByPolicy() {
        HooksConfigMenuDialog d = new HooksConfigMenuDialog();
        d.show(snapshot(List.of(bashHook(EV_PRE, "Bash", "cmd"))),
            /* disabled */ false, /* disabledByPolicy */ false,
            /* restrictedByPolicy */ false, () -> {});
        assertFalse(d.isRestrictedByPolicyForTest());

        // Policy admin flips allowManagedHooksOnly=true on disk.
        d.refresh(snapshot(List.of(bashHook(EV_PRE, "Bash", "cmd"))),
            false, false, /* restrictedByPolicy */ true);

        assertTrue(d.isRestrictedByPolicyForTest(),
            "refresh must propagate restrictedByPolicy to the dialog");
        assertEquals(HooksConfigMenuDialog.NavState.SELECT_EVENT, d.navStateForTest(),
            "restrictedByPolicy alone (without disabled) doesn't force a nav reset — "
                + "the banner just adds to the SELECT_EVENT header");
    }

    @Test
    void selectNavigationAndCancellationUseRuntimeBindings(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("keybindings.json");
        Files.writeString(file, """
            [
              {"context":"Select","bindings":{
                "x":"select:next","z":"select:accept",
                "down":null,"enter":null,"escape":null
              }},
              {"context":"Confirmation","bindings":{
                "q":"confirm:no","escape":null
              }}
            ]
            """);
        UserKeybindingsStore store = createStore(file);
        try {
            HooksConfigMenuDialog d = new HooksConfigMenuDialog();
            d.setKeybindingsStore(store);
            d.show(snapshot(List.of(bashHook(EV_PRE, "Bash", "echo pre"))),
                false, false, () -> {});

            d.handleKey(new KeyStroke(KeyType.ARROW_DOWN), new AtomicBoolean(true));
            assertEquals(0, d.eventIdxForTest(), "unbound Down must not move the Select");
            d.handleKey(new KeyStroke('x', false, false), new AtomicBoolean(true));
            assertEquals(1, d.eventIdxForTest());
            d.handleKey(new KeyStroke('z', false, false), new AtomicBoolean(true));
            assertEquals(HooksConfigMenuDialog.NavState.SELECT_MATCHER, d.navStateForTest());

            d.handleKey(new KeyStroke(KeyType.ESCAPE), new AtomicBoolean(true));
            assertEquals(HooksConfigMenuDialog.NavState.SELECT_MATCHER, d.navStateForTest(),
                "unbound Escape must not navigate back");
            d.handleKey(new KeyStroke('q', false, false), new AtomicBoolean(true));
            assertEquals(HooksConfigMenuDialog.NavState.SELECT_EVENT, d.navStateForTest());
        } finally {
            store.dispose();
        }
    }

    private static UserKeybindingsStore createStore(Path file) throws Exception {
        Method create = UserKeybindingsStore.class
            .getDeclaredMethod("create", Path.class, boolean.class);
        create.setAccessible(true);
        return (UserKeybindingsStore) create.invoke(null, file, true);
    }
}
