package com.claudecode.services.compact;

import org.apache.commons.lang3.Strings;

import com.claudecode.core.engine.ThinkingClearLatch;
import com.claudecode.core.message.*;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class PartialCompactTest {

    @Test
    void partialCompactFromDirection() {
        CompactSummarizer summarizer = (_, _) -> "Summary of compacted messages";
        CompactService service = new CompactService(
                TokenEstimator.getInstance(), summarizer, true);

        List<Message> messages = createTestMessages(6);

        PartialCompactResult result = service.partialCompactConversation(
                messages, 3, "from", null);

        assertNotNull(result);
        assertEquals("from", result.direction());
        assertEquals(3, result.pivotIndex());
        // Kept messages are the first 3
        assertEquals(3, result.keptMessages().size());
        assertTrue(result.hasCompaction());
    }

    @Test
    void partialCompactUpToDirection() {
        CompactSummarizer summarizer = (_, _) -> "Summary of compacted messages";
        CompactService service = new CompactService(
                TokenEstimator.getInstance(), summarizer, true);

        List<Message> messages = createTestMessages(6);

        PartialCompactResult result = service.partialCompactConversation(
                messages, 3, "up_to", null);

        assertNotNull(result);
        assertEquals("up_to", result.direction());
        // Kept messages are from index 3 onwards
        assertEquals(3, result.keptMessages().size());
        assertTrue(result.hasCompaction());
    }

    @Test
    void partialCompactFromPreservesEarlierBoundaryAndSummary() {
        CompactSummarizer summarizer = (_, _) -> "Summary of compacted messages";
        CompactService service = new CompactService(
                TokenEstimator.getInstance(), summarizer, true);
        SystemMessage oldBoundary = new SystemMessage(
            "old-boundary", "compact_boundary", "info", "boundary");
        UserMessage oldSummary = new UserMessage(
            "old-summary", MessageContent.ofText("Earlier summary"),
            false, true, null, MessageOrigin.COMPACT_SUMMARY,
            null, null, null, null);
        UserMessage kept = new UserMessage("kept", MessageContent.ofText("kept"));
        SystemMessage progress = new SystemMessage("progress", "progress", "info", "working");
        UserMessage pivot = new UserMessage("pivot", MessageContent.ofText("summarize from here"));

        PartialCompactResult result = service.partialCompactConversation(
            List.of(oldBoundary, oldSummary, kept, progress, pivot), 4, "from", null);

        assertEquals(List.of(oldBoundary, oldSummary, kept), result.keptMessages(),
            "from keeps the prior compact chain and drops only progress rows");
    }

    @Test
    void partialCompactUpToRemovesStaleBoundaryAndSummaryFromKeptTail() {
        CompactSummarizer summarizer = (_, _) -> "Summary of compacted messages";
        CompactService service = new CompactService(
                TokenEstimator.getInstance(), summarizer, true);
        UserMessage compacted = new UserMessage("compacted", MessageContent.ofText("old"));
        SystemMessage staleBoundary = new SystemMessage(
            "stale-boundary", "compact_boundary", "info", "boundary");
        UserMessage staleSummary = new UserMessage(
            "stale-summary", MessageContent.ofText("Stale summary"),
            false, true, null, MessageOrigin.COMPACT_SUMMARY,
            null, null, null, null);
        UserMessage kept = new UserMessage("kept", MessageContent.ofText("kept"));

        PartialCompactResult result = service.partialCompactConversation(
            List.of(compacted, staleBoundary, staleSummary, kept), 1, "up_to", null);

        assertEquals(List.of(kept), result.keptMessages());
    }

    @Test
    void partialCompactUsesThe197CacheSharingMessageRange() {
        AtomicReference<List<String>> summarized = new AtomicReference<>();
        CompactSummarizer summarizer = (messages, _) -> {
            summarized.set(messages.stream().map(Message::uuid).toList());
            return "Summary";
        };
        CompactService service = new CompactService(
            TokenEstimator.getInstance(), summarizer, true);
        List<Message> messages = createTestMessages(6);

        service.partialCompactConversation(messages, 3, "from", null);
        assertEquals(List.of("u0", "u1", "u2", "u3", "u4", "u5"), summarized.get(),
            "from sends the full cache prefix while the prompt limits the summary to the recent tail");

        service.partialCompactConversation(messages, 3, "up_to", null);
        assertEquals(List.of("u0", "u1", "u2"), summarized.get(),
            "up_to sends only the prefix being summarized");
    }

    @Test
    void partialCompactWritesThe197BoundaryMetadataAndLogicalParent() {
        CompactSummarizer summarizer = (_, _) -> "Summary";
        CompactService service = new CompactService(
            TokenEstimator.getInstance(), summarizer, true);
        List<Message> messages = createTestMessages(6);

        PartialCompactResult from = service.partialCompactConversation(
            messages, 3, "from", "focus on the failure");
        SystemMessage fromBoundary = from.compactionResult().boundaryMarker();
        assertEquals("u2", fromBoundary.parentUuid().orElse(null));
        assertEquals("manual", fromBoundary.compactMetadata().trigger());
        assertEquals("focus on the failure", fromBoundary.compactMetadata().userContext());
        assertEquals(3, fromBoundary.compactMetadata().messagesSummarized());
        assertEquals(from.compactionResult().preCompactTokenCount(),
            fromBoundary.compactMetadata().preTokens());

        PartialCompactResult upTo = service.partialCompactConversation(
            messages, 3, "up_to", "keep the resolution");
        SystemMessage upToBoundary = upTo.compactionResult().boundaryMarker();
        assertEquals("u2", upToBoundary.parentUuid().orElse(null),
            "up_to chains the boundary to the final summarized message, not the kept suffix");
        assertEquals("keep the resolution", upToBoundary.compactMetadata().userContext());
        assertEquals(3, upToBoundary.compactMetadata().messagesSummarized());
        assertEquals(upTo.compactionResult().preCompactTokenCount(),
            upToBoundary.compactMetadata().preTokens());
    }

    @Test
    void partialCompactBoundaryRetainsPreCompactDiscoveredTools() {
        CompactService service = new CompactService(
            TokenEstimator.getInstance(), (_, _) -> "Summary", true);
        UserMessage discovery = new UserMessage(
            "discovery", MessageContent.ofToolResult(
                "search", List.of(new ToolReferenceBlock("mcp__wire__lookup")), false));
        UserMessage pivot = new UserMessage("pivot", MessageContent.ofText("continue"));

        PartialCompactResult result = service.partialCompactConversation(
            List.of(discovery, pivot), 1, "from", null);

        assertEquals(List.of("mcp__wire__lookup"), result.compactionResult()
            .boundaryMarker().compactMetadata().preCompactDiscoveredTools());
    }

    @Test
    void partialCompactSummaryCarriesThe197UiMetadata() {
        CompactSummarizer summarizer = (_, _) -> "Summary";
        CompactService service = new CompactService(
            TokenEstimator.getInstance(), summarizer, true);
        List<Message> messages = createTestMessages(6);

        PartialCompactResult from = service.partialCompactConversation(
            messages, 3, "from", "focus on the failure");
        UserMessage fromSummary = (UserMessage) from.compactionResult().summaryMessages().getFirst();
        assertEquals(new SummarizeMetadata(3, "focus on the failure", "from"),
            fromSummary.summarizeMetadata());
        assertNull(fromSummary.isVisibleInTranscriptOnly());

        PartialCompactResult upTo = service.partialCompactConversation(
            messages, 3, "up_to", null);
        UserMessage upToSummary = (UserMessage) upTo.compactionResult().summaryMessages().getFirst();
        assertEquals(new SummarizeMetadata(3, null, "up_to"),
            upToSummary.summarizeMetadata());
        assertNull(upToSummary.isVisibleInTranscriptOnly());

        PartialCompactResult noKeptPrefix = service.partialCompactConversation(
            messages, 0, "from", null);
        UserMessage transcriptOnly = (UserMessage) noKeptPrefix.compactionResult()
            .summaryMessages().getFirst();
        assertNull(transcriptOnly.summarizeMetadata());
        assertEquals(Boolean.TRUE, transcriptOnly.isVisibleInTranscriptOnly());
    }

    @Test
    void partialCompactAnnotatesBoundaryWithPreservedSegment() {
        CompactSummarizer summarizer = (_, _) -> "Summary of compacted messages";
        CompactService service = new CompactService(
                TokenEstimator.getInstance(), summarizer, true);
        List<Message> messages = createTestMessages(6);

        // 'from': kept = messages[0..2], anchor = the boundary marker itself.
        PartialCompactResult from = service.partialCompactConversation(
                messages, 3, "from", null);
        SystemMessage fromBoundary = from.compactionResult().boundaryMarker();
        assertNotNull(fromBoundary.compactMetadata());
        PreservedSegment fromSeg = fromBoundary.compactMetadata().preservedSegment();
        assertNotNull(fromSeg);
        assertEquals(from.keptMessages().getFirst().uuid(), fromSeg.headUuid());
        assertEquals(from.keptMessages().getLast().uuid(), fromSeg.tailUuid());
        assertEquals(fromBoundary.uuid(), fromSeg.anchorUuid());

        // 'up_to': kept = messages[3..5], anchor = last summary message uuid.
        PartialCompactResult upTo = service.partialCompactConversation(
                messages, 3, "up_to", null);
        SystemMessage upToBoundary = upTo.compactionResult().boundaryMarker();
        assertNotNull(upToBoundary.compactMetadata());
        PreservedSegment upToSeg = upToBoundary.compactMetadata().preservedSegment();
        assertNotNull(upToSeg);
        assertEquals(upTo.keptMessages().getFirst().uuid(), upToSeg.headUuid());
        assertEquals(upTo.keptMessages().getLast().uuid(), upToSeg.tailUuid());
        List<Message> summaries = upTo.compactionResult().summaryMessages();
        String lastSummaryUuid = summaries.getLast().uuid();
        assertEquals(lastSummaryUuid, upToSeg.anchorUuid());
    }

    @Test
    void partialCompactInvalidDirection() {
        CompactService service = new CompactService(
                TokenEstimator.getInstance(), null, true);

        List<Message> messages = createTestMessages(4);

        assertThrows(CompactException.class, () ->
                service.partialCompactConversation(messages, 2, "invalid", null));
    }

    @Test
    void partialCompactEmptyMessages() {
        CompactService service = new CompactService(
                TokenEstimator.getInstance(), null, true);

        assertThrows(CompactException.class, () ->
                service.partialCompactConversation(List.of(), 0, "from", null));
    }

    @Test
    void partialCompactOutOfBoundsPivot() {
        CompactService service = new CompactService(
                TokenEstimator.getInstance(), null, true);

        List<Message> messages = createTestMessages(3);

        assertThrows(CompactException.class, () ->
                service.partialCompactConversation(messages, 10, "from", null));
    }

    @Test
    void partialCompactUpToFirstMessageUsesThe197NothingToSummarizeError() {
        CompactService service = new CompactService(
            TokenEstimator.getInstance(), (_, _) -> "Summary", true);

        CompactException failure = assertThrows(CompactException.class, () ->
            service.partialCompactConversation(createTestMessages(3), 0, "up_to", null));

        assertEquals("Nothing to summarize before the selected message.",
            failure.getMessage());
    }

    @Test
    void partialCompactBlankSummaryUsesThe197InvalidTextError() {
        CompactService service = new CompactService(
            TokenEstimator.getInstance(), (_, _) -> "", true);

        CompactException failure = assertThrows(CompactException.class, () ->
            service.partialCompactConversation(createTestMessages(3), 1, "from", null));

        assertEquals(
            "Failed to generate conversation summary - response did not contain valid text content",
            failure.getMessage());
    }

    @Test
    void partialCompactPromptTooLongUsesThe197RetryGuidance() {
        CompactService service = new CompactService(
            TokenEstimator.getInstance(),
            (_, _) -> CompactService.PROMPT_TOO_LONG_MARKER, true);

        CompactException failure = assertThrows(CompactException.class, () ->
            service.partialCompactConversation(createTestMessages(1), 0, "from", null));

        assertEquals(
            "Conversation too long. Press esc twice to go up a few messages and try again.",
            failure.getMessage());
    }

    @Test
    void successfulPartialCompactResetsPostCompactionState() {
        CompactService service = new CompactService(
            TokenEstimator.getInstance(), (_, _) -> "Summary", true);
        ThinkingClearLatch.trip();
        service.suppressCompactWarning();

        try {
            service.partialCompactConversation(createTestMessages(3), 1, "from", null);

            assertFalse(ThinkingClearLatch.isLatched());
            assertFalse(service.isCompactWarningSuppressed());
        } finally {
            ThinkingClearLatch.reset();
        }
    }

    @Test
    void filterKeptMessagesRemovesProgressAndBoundary() {
        CompactService service = new CompactService();

        List<Message> messages = new ArrayList<>();
        messages.add(new UserMessage("u1", MessageContent.ofText("Hello")));
        messages.add(new SystemMessage("s1", "progress", "info", "Working..."));
        messages.add(new SystemMessage("s2", "compact_boundary", "info", "boundary"));
        messages.add(new UserMessage("u2", MessageContent.ofText("World")));

        List<Message> filtered = service.filterKeptMessages(messages);

        assertEquals(2, filtered.size());
        assertTrue(filtered.stream().allMatch(UserMessage.class::isInstance));
    }

    @Test
    void filterKeptMessagesRemovesCompactSummary() {
        CompactService service = new CompactService();

        List<Message> messages = new ArrayList<>();
        messages.add(new UserMessage("u1", MessageContent.ofText("Hello")));
        messages.add(new UserMessage("u2", MessageContent.ofText("Summary"),
                false, true, null, MessageOrigin.COMPACT_SUMMARY, null, null, null, null));
        messages.add(new UserMessage("u3", MessageContent.ofText("World")));

        List<Message> filtered = service.filterKeptMessages(messages);

        assertEquals(2, filtered.size());
    }

    @Test
    void isFilterableMessageDetectsProgressSubtype() {
        SystemMessage progress = new SystemMessage("s1", "progress", "info", "Working...");
        assertTrue(CompactService.isFilterableMessage(progress));
    }

    @Test
    void isFilterableMessageDetectsCompactBoundary() {
        SystemMessage boundary = new SystemMessage("s1", "compact_boundary", "info", "boundary");
        assertTrue(CompactService.isFilterableMessage(boundary));
    }

    @Test
    void isFilterableMessageAllowsNormalMessages() {
        UserMessage normal = new UserMessage("u1", MessageContent.ofText("Hello"));
        assertFalse(CompactService.isFilterableMessage(normal));
    }

    private List<Message> createTestMessages(int count) {
        List<Message> messages = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            messages.add(new UserMessage("u" + i, MessageContent.ofText("Message " + i)));
        }
        return messages;
    }

    // ── partialCompactAndAssemble: interface-level convenience wrapper ─────

    @Test
    void partialCompactAndAssemble_fromDirection_appendsSummaryAfterKept() {
        CompactSummarizer summarizer = (_, _) -> "SUMMARY";
        CompactService service = new CompactService(
                TokenEstimator.getInstance(), summarizer, true);

        List<Message> messages = createTestMessages(6);

        List<Message> post = service.partialCompactAndAssemble(messages, 3, "from", null);


        assertNotNull(post);
        assertFalse(post.isEmpty());
        // First message is the boundary marker (SystemMessage subtype compact_boundary)
        assertTrue(post.getFirst() instanceof SystemMessage sm && Strings.CS.equals("compact_boundary", sm.subtype()));
        // Kept portion (u0..u2) sits immediately after the boundary
        assertEquals("u0", post.get(1).uuid());
        assertEquals("u2", post.get(3).uuid());
    }

    @Test
    void partialCompactAndAssemble_upToDirection_prependsSummaryBeforeKept() {
        CompactSummarizer summarizer = (_, _) -> "SUMMARY";
        CompactService service = new CompactService(
                TokenEstimator.getInstance(), summarizer, true);

        List<Message> messages = createTestMessages(6);

        List<Message> post = service.partialCompactAndAssemble(messages, 3, "up_to", null);

        // Expected: [boundaryMarker] + summaryMessages + kept(u3,u4,u5) + attachments + hookResults
        assertNotNull(post);
        // Last kept message uuid should be u5 (from the tail slice starting at pivotIndex=3)
        // Find it — kept sits after boundary + summary, so scan backward from end skipping
        // any attachments/hookResults (createTestMessages produces UserMessages so no attachments).
        boolean sawU5 = post.stream().anyMatch(m -> Strings.CS.equals("u5", m.uuid()));
        boolean sawU3 = post.stream().anyMatch(m -> Strings.CS.equals("u3", m.uuid()));
        assertTrue(sawU5 && sawU3, "kept messages must survive");
        // u0/u1/u2 should be gone (they were compacted away)
        assertFalse(post.stream().anyMatch(m -> Strings.CS.equals("u0", m.uuid())),
            "pre-pivot messages must be removed in up_to mode");
    }

    @Test
    void partialCompactAndAssemble_noSummarizer_returnsFilteredKeptOnly() {
// Summarizer=null → hasCompaction=false → wrapper returns just filtered kept
        CompactService service = new CompactService(
                TokenEstimator.getInstance(), null, true);

        List<Message> messages = createTestMessages(4);
        List<Message> post = service.partialCompactAndAssemble(messages, 2, "from", null);

        assertNotNull(post);
        assertEquals(2, post.size(), "with no summarizer we get only the filtered kept slice");
        assertEquals("u0", post.getFirst().uuid());
        assertEquals("u1", post.get(1).uuid());
    }
}
