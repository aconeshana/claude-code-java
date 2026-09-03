package com.claudecode.ui.lanterna.transcript;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.core.constants.Figures;
import com.claudecode.core.message.AssistantContent;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.ContentBlock;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.MessageOrigin;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.ThinkingBlock;
import com.claudecode.core.message.UserMessage;
import com.googlecode.lanterna.SGR;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.Test;

/**
 * The collapsed group's thinking projection, ported from the 2.1.236 bundle: an accumulated
 * duration in the header and a flattened thinking preview on the {@code ⎿} row.
 */
class MessageCollapserThinkingTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private final long[] now = {10_000};
    private final MessagePanel panel = new MessagePanel();
    private final MessageCollapser collapser =
        new MessageCollapser(new MessageCollapserTest.CapturingDispatcher(), false);

    MessageCollapserThinkingTest() {
        collapser.setClock(() -> now[0]);
    }

    private void dispatch(SDKMessage message) {
        collapser.dispatch(message, panel);
    }

    private static SDKMessage prompt(Instant at) {
        return new SDKMessage.User(new UserMessage("user-1", MessageContent.ofText("go"),
            false, false, null, MessageOrigin.USER, null, at, null, null));
    }

    private static SDKMessage thinking(String text, Instant at) {
        return new SDKMessage.Assistant(new AssistantMessage("assistant-" + at,
            AssistantContent.of(List.<ContentBlock>of(new ThinkingBlock(text))),
            false, null, at), null);
    }

    private static SDKMessage say(String text, Instant at) {
        return new SDKMessage.Assistant(new AssistantMessage("assistant-" + at,
            AssistantContent.of(List.<ContentBlock>of(new TextBlock(text))),
            false, null, at), null);
    }

    private static SDKMessage grep(String pattern, String id) {
        return new SDKMessage.StreamEvent("tool_call_start",
            "Grep|" + id + "|{\"pattern\":\"" + pattern + "\"}");
    }

    /** The rendered header row, with the leading bullet/indent stripped. */
    private String header() {
        String line = panel.snapshotStyledLines().stream()
            .map(MessagePanel.StyledLine::text)
            .filter(text -> Strings.CS.contains(text, "hought") || Strings.CS.contains(text, "hinking"))
            .findFirst().orElseThrow(() -> new AssertionError(
                "no thinking header rendered: " + panel.snapshotStyledLines().stream()
                    .map(MessagePanel.StyledLine::text).toList()));
        return line.substring(line.indexOf('T')).strip();
    }

    /** The rendered {@code ⎿} detail row body, if one exists. */
    private Optional<String> detailRow() {
        return panel.snapshotStyledLines().stream()
            .filter(line -> Strings.CS.contains(line.text(), Figures.RESULT_BRANCH))
            .map(line -> line.text().substring(
                line.text().indexOf(Figures.RESULT_BRANCH) + Figures.RESULT_BRANCH.length()))
            // RESULT_PREFIX ends in a non-breaking space, which Java's strip() leaves alone.
            .map(body -> body.replace('\u00a0', ' ').strip())
            .findFirst();
    }

    @Test
    void thinkingAloneRendersAGroupWithNoToolCallsAtAll() {
        dispatch(prompt(T0));
        dispatch(thinking("Line one.\n\nLine  two.", T0.plusSeconds(24)));

        assertEquals("Thinking for 24s… (ctrl+o to expand)", header());
        // The "summary" is the whole body, whitespace-collapsed — 236's pm(us(x)).
        assertEquals(Optional.of("Line one. Line two."), detailRow());
    }

    @Test
    void theDurationIsBoldAndTheRestOfTheHeaderIsNot() {
        dispatch(prompt(T0));
        dispatch(thinking("hm", T0.plusSeconds(24)));

        var bold = panel.snapshotStyledLines().stream()
            .filter(line -> Strings.CS.contains(line.text(), "Thinking for"))
            .findFirst().orElseThrow()
            .segments().stream()
            .filter(segment -> segment.modifiers().contains(SGR.BOLD))
            .map(MessagePanel.Segment::text)
            .toList();

        assertEquals(List.of("24s"), bold);
    }

    @Test
    void countPartsFollowTheThinkingSegmentAsLowercaseContinuations() {
        dispatch(prompt(T0));
        dispatch(thinking("hm", T0.plusSeconds(24)));
        dispatch(grep("TODO", "grep-1"));
        dispatch(grep("FIXME", "grep-2"));
        dispatch(grep("XXX", "grep-3"));

        assertEquals("Thinking for 24s, searching for 3 patterns… (ctrl+o to expand)", header());
    }

    @Test
    void aToolCallResetsTheSummaryButTheRowHoldsItForThreeSeconds() {
        dispatch(prompt(T0));
        dispatch(thinking("Deciding where to look.", T0.plusSeconds(24)));
        dispatch(grep("TODO", "grep-1"));

        assertEquals(Optional.of("Deciding where to look."), detailRow(),
            "236's G0h keeps the reset summary visible for egw=3000ms");

        now[0] += 3_001;
        dispatch(grep("FIXME", "grep-2"));

        assertEquals(Optional.of("\"FIXME\""), detailRow(),
            "once the hold lapses the row falls back to the tool display hint");
    }

    @Test
    void endingTheGroupFreezesTheHeaderAndDropsTheDetailRow() {
        dispatch(prompt(T0));
        dispatch(thinking("Deciding where to look.", T0.plusSeconds(24)));
        dispatch(new SDKMessage.StreamEvent("content_block_delta", "Here is the answer"));

        assertEquals("Thought for 24s (ctrl+o to expand)", header());
        assertTrue(detailRow().isEmpty(), detailRow().toString());
    }

    @Test
    void aGapLongerThanTenMinutesIsClampedToTheSingleSpanCeiling() {
        dispatch(prompt(T0));
        dispatch(thinking("hm", T0.plusSeconds(1800)));

        assertEquals("Thinking for 10m 0s… (ctrl+o to expand)", header());
    }

    @Test
    void separateThinkingBlocksAccumulate() {
        dispatch(prompt(T0));
        dispatch(thinking("first", T0.plusSeconds(10)));
        dispatch(thinking("second", T0.plusSeconds(25)));

        assertEquals("Thinking for 25s… (ctrl+o to expand)", header());
        assertEquals(Optional.of("second"), detailRow(), "the latest block wins the row");
    }

    @Test
    void theLiveClockAdvancesWithoutANewThinkingBlock() {
        dispatch(prompt(T0));
        dispatch(thinking("hm", T0.plusSeconds(24)));

        now[0] += 5_000;
        dispatch(grep("TODO", "grep-1"));

        assertEquals("Thinking for 29s, searching for 1 pattern… (ctrl+o to expand)", header());
    }

    @Test
    void anAssistantWithoutALeadingThinkingBlockOpensNoGroup() {
        dispatch(prompt(T0));
        dispatch(say("plain answer", T0.plusSeconds(24)));

        assertFalse(panel.snapshotStyledLines().stream()
            .anyMatch(line -> Strings.CS.contains(line.text(), "hinking for")),
            "236's XxS only recognises thinking at content[0]");
    }

    @Test
    void aBlankThinkingBlockIsIgnored() {
        dispatch(prompt(T0));
        dispatch(thinking("   \n\t ", T0.plusSeconds(24)));

        assertTrue(panel.snapshotStyledLines().isEmpty(),
            panel.snapshotStyledLines().stream().map(MessagePanel.StyledLine::text).toList()
                .toString());
    }
}
