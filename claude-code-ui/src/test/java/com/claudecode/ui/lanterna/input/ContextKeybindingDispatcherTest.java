package com.claudecode.ui.lanterna.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * Characterization tests for the prompt-facing chord dispatcher.
 */
class ContextKeybindingDispatcherTest {

    private static final KeyStroke CTRL_X = new KeyStroke('x', true, false);
    private static final KeyStroke CTRL_K = new KeyStroke('k', true, false);
    private static final KeyStroke ESCAPE = new KeyStroke(KeyType.ESCAPE);

    @Test
    void prefixIsConsumedBeforeThePromptCanHandleIt() {
        ContextKeybindingDispatcher dispatcher = dispatcherAt(0);

        assertFalse(dispatcher.isCustomizationEnabled());
        ContextKeybindingDispatcher.Result result = dispatcher.resolve("Chat", CTRL_X);

        assertInstanceOf(ContextKeybindingDispatcher.Result.Consumed.class, result);
    }

    @Test
    void completedDefaultPromptChordDispatchesItsActionExactlyOnce() {
        ContextKeybindingDispatcher dispatcher = dispatcherAt(0);

        assertFalse(dispatcher.hasPendingChord());
        assertInstanceOf(ContextKeybindingDispatcher.Result.Consumed.class,
            dispatcher.resolve("Chat", CTRL_X));
        assertTrue(dispatcher.hasPendingChord());
        ContextKeybindingDispatcher.Result.Action action = assertInstanceOf(
            ContextKeybindingDispatcher.Result.Action.class,
            dispatcher.resolve("Chat", CTRL_K));

        assertEquals("chat:killAgents", action.value());
        assertFalse(dispatcher.hasPendingChord());
        assertInstanceOf(ContextKeybindingDispatcher.Result.None.class,
            dispatcher.resolve("Chat", CTRL_K),
            "completion must clear pending state so the continuation cannot fire twice");
    }

    @Test
    void escapeCancelsPendingChordAndLeavesItsFormerContinuationAsAFreshKey() {
        ContextKeybindingDispatcher dispatcher = dispatcherAt(0);

        assertInstanceOf(ContextKeybindingDispatcher.Result.Consumed.class,
            dispatcher.resolve("Chat", CTRL_X));
        assertInstanceOf(ContextKeybindingDispatcher.Result.Consumed.class,
            dispatcher.resolve("Chat", ESCAPE));

        assertInstanceOf(ContextKeybindingDispatcher.Result.None.class,
            dispatcher.resolve("Chat", CTRL_K));
    }

    @Test
    void invalidContinuationIsConsumedAndCancelsPendingChord() {
        ContextKeybindingDispatcher dispatcher = dispatcherAt(0);

        assertInstanceOf(ContextKeybindingDispatcher.Result.Consumed.class,
            dispatcher.resolve("Chat", CTRL_X));
        assertInstanceOf(ContextKeybindingDispatcher.Result.Consumed.class,
            dispatcher.resolve("Chat", new KeyStroke('q', false, false)));

        assertInstanceOf(ContextKeybindingDispatcher.Result.None.class,
            dispatcher.resolve("Chat", CTRL_K));
    }

    @Test
    void expiredPrefixDoesNotCompleteTheChordAndAFollowingPrefixStartsFresh() {
        AtomicLong now = new AtomicLong(10_000);
        ContextKeybindingDispatcher dispatcher = new ContextKeybindingDispatcher(now::get);

        assertInstanceOf(ContextKeybindingDispatcher.Result.Consumed.class,
            dispatcher.resolve("Chat", CTRL_X));
        now.addAndGet(1_001);

        assertInstanceOf(ContextKeybindingDispatcher.Result.None.class,
            dispatcher.resolve("Chat", CTRL_K),
            "the continuation after the one-second timeout is a fresh key");
        assertInstanceOf(ContextKeybindingDispatcher.Result.Consumed.class,
            dispatcher.resolve("Chat", CTRL_X));
        ContextKeybindingDispatcher.Result.Action action = assertInstanceOf(
            ContextKeybindingDispatcher.Result.Action.class,
            dispatcher.resolve("Chat", CTRL_K));
        assertEquals("chat:killAgents", action.value());
    }

    @Test
    void mergedDefaultBindingsSelectAutocompleteEscapeWhenBothContextsAreActive() {
        ContextKeybindingDispatcher dispatcher = dispatcherAt(0);

        // KeybindingResolver uses a context set and positional last-wins bindings;
        // this pins the existing DEFAULT_BINDINGS merge order, not a new contexts-list priority.
        ContextKeybindingDispatcher.Result.Action action = assertInstanceOf(
            ContextKeybindingDispatcher.Result.Action.class,
            dispatcher.resolve(List.of("Chat", "Autocomplete"), ESCAPE));

        assertEquals("autocomplete:dismiss", action.value());
    }

    private static ContextKeybindingDispatcher dispatcherAt(long now) {
        return new ContextKeybindingDispatcher(() -> now);
    }
}
