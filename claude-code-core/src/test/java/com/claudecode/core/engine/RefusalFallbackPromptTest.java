package com.claudecode.core.engine;

import com.claudecode.core.message.RefusalFallbackDecision;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The question a refused turn puts to the user, and what happens to it when
 * nobody answers.
 *
 * <ul>
 *   <li>the
 *       {@code refusal_fallback_prompt} dialog payload and its
 *       {@code default: "cancelled"} declaration.</li>
 * </ul>
 */
class RefusalFallbackPromptTest {

    @Test
    void aPromptThatCannotBeAnsweredCancelsRatherThanSwitchingSilently() {
        RefusalFallbackPrompt refused = _ -> null;

        assertEquals(RefusalFallbackDecision.Choice.CANCELLED,
            RefusalFallbackPrompt.askOrCancel(refused, request()),
            "released declares cancelled as the dialog's default result");
    }

    @Test
    void aPromptThatThrowsIsNotAllowedToTakeTheTurnDownWithIt() {
        RefusalFallbackPrompt broken = _ -> {
            throw new IllegalStateException("no terminal");
        };

        assertEquals(RefusalFallbackDecision.Choice.CANCELLED,
            RefusalFallbackPrompt.askOrCancel(broken, request()));
    }

    @Test
    void anAbsentPortIsTheSameAsOneThatDeclines() {
        assertEquals(RefusalFallbackDecision.Choice.CANCELLED,
            RefusalFallbackPrompt.askOrCancel(null, request()));
    }

    @Test
    void anAnsweredPromptIsPassedThroughUntouched() {
        RefusalFallbackPrompt answering = _ -> RefusalFallbackDecision.Choice.EDIT_PROMPT;

        assertEquals(RefusalFallbackDecision.Choice.EDIT_PROMPT,
            RefusalFallbackPrompt.askOrCancel(answering, request()));
    }

    @Test
    void theRequestCarriesEverythingTheDialogHasToRender() {
        RefusalFallbackPrompt.Request request = request();

        assertEquals("claude-fable-5", request.refusedModel());
        assertEquals("claude-opus-4-5", request.fallbackModel());
        assertEquals("cyber", request.category());
        assertNull(request.guidanceText(), "a first-party deployment has nothing to configure");
        assertEquals(List.of("wire-1"), request.retractedMessageUuids());
    }

    private static RefusalFallbackPrompt.Request request() {
        return new RefusalFallbackPrompt.Request(
            "claude-fable-5", "claude-opus-4-5", "cyber", null, List.of("wire-1"));
    }
}
