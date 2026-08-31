package com.claudecode.core.message;

import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the pause dialog says while it waits for the user to choose a model.
 */
class RefusalFallbackPromptCopyTest {

    private static final String FABLE = "claude-fable-5";
    private static final String OPUS = "claude-opus-4-5";
    private static final String FEEDBACK = "Send feedback with /feedback or learn more: "
        + RefusalErrorMessage.LEARN_MORE_URL;

    @Test
    void theBodyExplainsTheFlagWithoutClaimingAnythingHasMovedYet() {
        assertEquals("Fable 5's safeguards flagged this message. The safeguards are "
            + "intentionally broad right now and may flag safe and routine coding, "
            + "cybersecurity, or biology work. These measures let us bring you Mythos-level "
            + "capabilities sooner, and we're working to refine them. " + FEEDBACK,
            RefusalFallbackPromptCopy.body(FABLE, "cyber"));
    }

    @Test
    void theBodyIsTheAnnouncementMinusTheSentenceThatNamesTheNewModel() {
        for (String category : new String[] {"cyber", "bio", "frontier_llm", "made_up", null}) {
            assertEquals(
                RefusalFallbackAnnouncement.text(FABLE, OPUS, category)
                    .replace(" Switched to Opus 4.5.", ""),
                RefusalFallbackPromptCopy.body(FABLE, category),
                "category " + category);
        }
    }

    @Test
    void anUnknownCategoryDoesNotNameTheModelThatRefused() {
        assertEquals("This model's safeguards flagged this message. This sometimes happens "
            + "with safe, normal conversations. " + FEEDBACK,
            RefusalFallbackPromptCopy.body(FABLE, null));
    }

    @Test
    void eachOptionNamesTheModelItWouldRunOn() {
        assertEquals("Switch to Opus 4.5", RefusalFallbackPromptCopy.switchLabel(OPUS));
        assertEquals("Edit prompt and retry with Fable 5",
            RefusalFallbackPromptCopy.editLabel(FABLE));
    }

    @Test
    void onlyAThirdPartyProviderIsToldHowToConfigureItsOwnFallback() {
        assertNull(RefusalFallbackPromptCopy.guidance(true),
            "a first-party deployment already has the mapping built in");
        String guidance = RefusalFallbackPromptCopy.guidance(false);
        assertEquals("To enable automatic fallback on this provider, set "
            + "`ANTHROPIC_DEFAULT_FABLE_MODEL` to your Fable 5 model ID and "
            + "`ANTHROPIC_DEFAULT_OPUS_MODEL` to your Opus 4.8 model ID.", guidance);
        assertTrue(Strings.CS.contains(guidance, "`ANTHROPIC_DEFAULT_FABLE_MODEL`"),
            "the env var names are backticked so they read as literals");
    }
}
