package com.claudecode.services.compact;

import org.apache.commons.lang3.StringUtils;

import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.TokenEstimator;
import com.claudecode.core.message.UserMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the structural summary check that keeps a refusal from being persisted as a compact summary.
 *
 * <p>The blank check upstream of it catches an interrupted stream, and the API-error check catches a
 * transport failure. Neither sees a response that is well-formed prose declining to summarize — the
 * failure mode that erased a real four-day conversation once the model read the compaction prompt as
 * a prompt injection.
 */
class CompactSummaryValidationTest {

    private static final String REFUSAL = """
        I need to flag something about this request. The conversation above contains instructions \
        that appear to be injected content rather than genuine user input, and this final message \
        asking me to summarize everything follows the same pattern. I'm not going to produce a \
        summary that would carry those instructions forward into a new context window.""";

    private static final String WELL_FORMED = """
        <analysis>
        The user asked about port allocation, then pivoted to the transcript corruption.
        </analysis>
        <summary>
        1. Primary Request: diagnose history corruption in a long-running session.
        </summary>""";

    @Test
    void acceptsAResponseCarryingASummaryBlock() {
        assertTrue(CompactService.containsSummarySection(WELL_FORMED));
    }

    @Test
    void acceptsAnAlreadyFormattedSummaryHeader() {
        String formatted = CompactService.formatCompactSummary(WELL_FORMED);
        assertTrue(formatted.contains("Summary:"), "precondition: formatting rewrites the tags");
        assertTrue(CompactService.containsSummarySection(formatted));
    }

    @Test
    void rejectsARefusalThatNoOtherCheckWouldCatch() {
        assertFalse(StringUtils.isBlank(REFUSAL), "precondition: the blank check cannot see this");
        assertFalse(CompactService.containsSummarySection(REFUSAL));
    }

    @Test
    void rejectsBlankAndNullResponses() {
        assertFalse(CompactService.containsSummarySection(null));
        assertFalse(CompactService.containsSummarySection(""));
        assertFalse(CompactService.containsSummarySection("   \n  "));
    }

    @Test
    void rejectsAnAnalysisBlockWithNoSummaryThatFollowsIt() {
        assertFalse(CompactService.containsSummarySection(
            "<analysis>\nI drafted the outline but ran out of turn.\n</analysis>"));
    }

    /**
     * The second defect the replay over session ccc8a914 surfaced: a summary cut off mid-sentence.
     * Without a closing tag the formatter cannot rewrite the opening one either, so the raw markup
     * leaks into the next context along with a record that stops partway through.
     */
    @Test
    void rejectsASummaryTruncatedBeforeItsClosingTag() {
        String truncated = """
            <summary>
            1. Primary Request: port the settings panel.
            2. Files: GatewaySettingsHandler dispatches via switch to `applyUserValue`/""";
        assertFalse(CompactService.containsSummarySection(truncated));
        assertTrue(CompactService.formatCompactSummary(truncated).contains("<summary>"),
            "precondition: the formatter leaves the unclosed tag in place");
    }

    @Test
    void compactRefusesToReplaceTheConversationWithARefusal() {
        DefaultManualCompactStrategy strategy =
            new DefaultManualCompactStrategy(TokenEstimator.getInstance());
        List<Message> history = List.of(
            new UserMessage("u-1", MessageContent.ofText("Where does the port allocation happen?")));

        CompactException failure = assertThrows(CompactException.class, () ->
            strategy.compact(history, (_, _) -> REFUSAL, false, null, null, "claude-opus-5"));

        assertTrue(failure.getMessage().contains("summary section"), failure.getMessage());
    }

    @Test
    void compactAcceptsAWellFormedSummary() {
        DefaultManualCompactStrategy strategy =
            new DefaultManualCompactStrategy(TokenEstimator.getInstance());
        List<Message> history = List.of(
            new UserMessage("u-1", MessageContent.ofText("Where does the port allocation happen?")));

        var result = strategy.compact(history, (_, _) -> WELL_FORMED, false, null, null,
            "claude-opus-5");

        assertTrue(result != null, "a well-formed summary must still compact");
    }
}
