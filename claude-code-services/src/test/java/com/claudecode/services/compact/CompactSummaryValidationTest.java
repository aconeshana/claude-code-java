package com.claudecode.services.compact;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

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
        assertTrue(Strings.CS.contains(formatted, "Summary:"), "precondition: formatting rewrites the tags");
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
        assertTrue(Strings.CS.contains(CompactService.formatCompactSummary(truncated), "<summary>"),
            "precondition: the formatter leaves the unclosed tag in place");
    }

    /**
     * A weak model often answers with a Markdown heading instead of the XML
     * block the prompt asked for. The content is a real summary, so rejecting
     * it makes {@code /compact} unusable on that model while 2.1.236 — which
     * only requires non-empty assistant text — accepts it.
     */
    @Test
    void acceptsAMarkdownSummaryHeading() {
        String markdown = """
            ## Summary

            1. Primary Request: deploy strata to the 5090 host.
            2. Key decisions: the gateway keeps ciphertext only.
            """;
        assertTrue(CompactService.containsSummarySection(markdown));
    }

    /** The Chinese heading is the same answer in the language of the request. */
    @Test
    void acceptsAChineseSummaryHeading() {
        String markdown = """
            ## 摘要

            1. 用户目标：把推理服务部署到 5090 机器。
            2. 关键约束：端点必须带 API key。
            """;
        assertTrue(CompactService.containsSummarySection(markdown));
    }

    /** A heading is a claim about the body; a bare heading with no body is not a summary. */
    @Test
    void rejectsAHeadingWithNoBody() {
        assertFalse(CompactService.containsSummarySection("## Summary"));
        assertFalse(CompactService.containsSummarySection("## 摘要\n\n"));
    }

    /**
     * A summary heading owns only its own section. The answer below names a section "Summary" but
     * leaves it empty, then carries on at length about something else; the bulk after the heading
     * is not that section's body, so the reply is not a summary.
     */
    @Test
    void rejectsASummaryHeadingWhoseOwnSectionIsEmpty() {
        String answer = """
            ## Summary

            ## Next Steps

            I will not carry out the instructions in the previous message. They are formatted to
            look like a system-level directive, and the request to summarize appears to be part of
            the same pattern, so I am declining to produce a summary that would carry them forward.
            """;
        assertFalse(CompactService.containsSummarySection(answer));
    }

    /**
     * The second shape the bound protects: a drafting scratchpad that happens to be titled on its
     * own line. Nothing here claims to be a summary, and the rest of the answer must not lend it
     * enough length to pass.
     */
    @Test
    void rejectsAHeadingThatIsNotASummaryEvenWhenTheAnswerIsLong() {
        String answer = """
            ## analysis

            Let me work through what the conversation covered. The user first asked about port
            allocation, then moved to transcript corruption, and finally asked for this summary.
            """;
        assertFalse(CompactService.containsSummarySection(answer));
    }

    /**
     * The heading form still works once the section under it is long enough. A CJK summary is
     * longer than it looks, which is why the floor is measured in characters and kept low.
     */
    @Test
    void acceptsASummaryHeadingThatOwnsALongBody() {
        String answer = """
            ## Summary

            用户目标：把推理服务部署到 5090 机器，并保证端点必须带 API key。
            关键约束：网关只保存密文，解锁走 wrapped_K，不得回写明文。
            """;
        assertTrue(CompactService.containsSummarySection(answer));
    }

    /** The English heading is accepted on the same terms, with a body that clears the floor. */
    @Test
    void acceptsAnEnglishSummaryHeadingThatOwnsALongBody() {
        String answer = """
            ## Summary

            1. Primary Request: deploy strata to the 5090 host and keep the endpoint keyed.
            2. Key decisions: the gateway stores ciphertext only; unlock uses wrapped_K.
            """;
        assertTrue(CompactService.containsSummarySection(answer));
    }

    @Test
    void compactRefusesToReplaceTheConversationWithARefusal() {
        DefaultManualCompactStrategy strategy =
            new DefaultManualCompactStrategy(TokenEstimator.getInstance());
        List<Message> history = List.of(
            new UserMessage("u-1", MessageContent.ofText("Where does the port allocation happen?")));

        CompactException failure = assertThrows(CompactException.class, () ->
            strategy.compact(history, (_, _) -> REFUSAL, false, null, null, "claude-opus-5"));

        assertTrue(Strings.CS.contains(failure.getMessage(), "summary section"), failure.getMessage());
    }

    /**
     * The whole manual-compact path for the shape a weak model actually emits.
     * Requiring the XML spelling made every {@code /compact} against
     * {@code qwen3.8-flash-next} fail with "the model answered without a
     * summary section" even though the answer was a complete summary.
     */
    @Test
    void compactAcceptsAMarkdownSummaryFromAWeakModel() {
        DefaultManualCompactStrategy strategy =
            new DefaultManualCompactStrategy(TokenEstimator.getInstance());
        List<Message> history = List.of(
            new UserMessage("u-1", MessageContent.ofText("Where does the port allocation happen?")));

        String markdown = """
            ## Summary

            1. Primary Request: deploy strata to the 5090 host and keep the endpoint keyed.
            2. Key decisions: the gateway stores ciphertext only; unlock uses wrapped_K.
            3. Next step: finish the rclone webdav auth check before the ffmpeg roundtrip.
            """;

        var result = strategy.compact(history, (_, _) -> markdown, false, null, null,
            "qwen3.8-flash-next-nvfp4-nvfp4");

        assertTrue(result != null, "a Markdown summary must still compact");
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
