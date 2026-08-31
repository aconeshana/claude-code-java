package com.claudecode.services.compact;

import com.claudecode.core.engine.RequestMessageNormalizer;
import com.claudecode.core.engine.StreamingClient;
import com.claudecode.core.message.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.lang3.Strings;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reproduction test for a user-reported manual-{@code /compact} 400
 * ({@code "message content cannot be empty"}, with repeated
 * {@link MessageConstants#SYNTHETIC_TOOL_RESULT_PLACEHOLDER} occurrences).
 * <p>
 * Exercises {@link DefaultManualCompactStrategy#truncateHeadForPTLRetry} —
 * the head-truncation loop that runs when the summarizer reports
 * {@code PROMPT_TOO_LONG_MARKER} — against a history containing a round with
 * PARALLEL tool calls (one assistant turn, several {@code tool_use} blocks,
 * each with its own separate {@code UserMessage} result — the confirmed real
 * domain shape from {@code ToolExecution}), then feeds the truncated result
 * through the full {@link RequestMessageNormalizer#normalizeForApi} pipeline
 * (the same pipeline the production compact cache-sharing fork request uses,
 * via {@code QueryHelpers.buildRequestMessages}) to check whether truncation
 * can strand a {@code tool_use} without its {@code tool_result} in a way the
 * pairing repair fails to patch, or produce genuinely empty wire content.
 */
class DefaultManualCompactStrategyPtlPairingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DefaultManualCompactStrategy strategy =
            new DefaultManualCompactStrategy(TokenEstimator.getInstance());

    /** One assistant turn, {@code toolCount} parallel tool_use blocks, one separate result UserMessage each. */
    private static List<Message> parallelToolRound(int roundIdx, int toolCount) {
        List<Message> round = new ArrayList<>();
        round.add(new UserMessage("u-" + roundIdx, MessageContent.ofText("Request " + roundIdx)));

        String apiId = "api-" + roundIdx;
        List<ContentBlock> toolUses = new ArrayList<>();
        for (int t = 0; t < toolCount; t++) {
            ObjectNode input = MAPPER.createObjectNode();
            input.put("path", "/tmp/file-" + roundIdx + "-" + t + ".txt");
            toolUses.add(new ToolUseBlock("tu-" + roundIdx + "-" + t, "Read", input));
        }
        AssistantContent aContent = AssistantContent.of(apiId, toolUses);
        round.add(new AssistantMessage("a-" + roundIdx, aContent, false, null, Instant.now()));

        for (int t = 0; t < toolCount; t++) {
            ToolResultBlock toolResult = new ToolResultBlock(
                    "tu-" + roundIdx + "-" + t, List.of(new TextBlock("result " + t)), false);
            MessageContent mc = MessageContent.ofBlocks(List.of(toolResult));
            round.add(new UserMessage("ur-" + roundIdx + "-" + t, mc, false, false,
                    null, MessageOrigin.TOOL_RESULT, null, Instant.now(), null, null));
        }
        return round;
    }

    private static List<Message> textOnlyRound(int roundIdx) {
        List<Message> round = new ArrayList<>();
        round.add(new UserMessage("u-" + roundIdx, MessageContent.ofText("Plain request " + roundIdx)));
        AssistantContent content = AssistantContent.of(
                "api-" + roundIdx, List.of(new TextBlock("Plain reply " + roundIdx)));
        round.add(new AssistantMessage("a-" + roundIdx, content, false, null, Instant.now()));
        return round;
    }

    @SuppressWarnings("unchecked")
    private static void assertNoOrphanedToolUseOrEmptyContent(
            List<StreamingClient.StreamRequest.RequestMessage> wire) {
        for (StreamingClient.StreamRequest.RequestMessage m : wire) {
            Object content = m.content();
            if (content instanceof String s) {
                assertFalse(s.isEmpty(), "wire message content must never be a bare empty string: " + wire);
                continue;
            }
            List<Object> blocks = (List<Object>) content;
            assertFalse(blocks.isEmpty(),
                    "wire message content must never be an empty block list (role=" + m.role() + "): " + wire);
        }

        // Every tool_use id must be followed, somewhere later in the wire list,
        // by either a real or synthesized tool_result with a matching tool_use_id.
        List<String> toolUseIds = new ArrayList<>();
        List<String> toolResultIds = new ArrayList<>();
        for (StreamingClient.StreamRequest.RequestMessage m : wire) {
            if (!(m.content() instanceof List<?> blocks)) continue;
            for (Object b : blocks) {
                if (!(b instanceof Map<?, ?> map)) continue;
                if ("tool_use".equals(map.get("type")) || "server_tool_use".equals(map.get("type"))) {
                    toolUseIds.add((String) map.get("id"));
                } else if ("tool_result".equals(map.get("type"))) {
                    toolResultIds.add((String) map.get("tool_use_id"));
                }
            }
        }
        for (String id : toolUseIds) {
            assertTrue(toolResultIds.contains(id),
                    "tool_use " + id + " has no matching tool_result (real or synthetic) after pairing repair: "
                            + wire);
        }
    }

    @Test
    void truncatingAcrossAParallelToolCallRoundNeverStrandsAPairOrEmptiesContent() {
        List<Message> history = new ArrayList<>(textOnlyRound(0));
        history.addAll(parallelToolRound(1, 3));
        history.addAll(textOnlyRound(2));
        history.addAll(parallelToolRound(3, 4));
        history.addAll(textOnlyRound(4));

        List<Message> current = history;
        int groupCount = MessageGrouping.groupByApiRound(current).size();

        // Drive every possible PTL-retry truncation depth, checking the pairing
        // invariant after each step — this is exactly the sequence
        // streamCompactSummary drives when the summarizer keeps reporting
        // PROMPT_TOO_LONG_MARKER.
        for (int i = 0; i < groupCount - 1; i++) {
            current = strategy.truncateHeadForPTLRetry(current);
            List<StreamingClient.StreamRequest.RequestMessage> wire =
                    RequestMessageNormalizer.normalizeForApi(current, true, true);
            assertNoOrphanedToolUseOrEmptyContent(wire);
        }

        // One group left — further truncation must refuse rather than silently
        // producing a broken request.
        List<Message> finalHistory = current;
        assertThrows(CompactException.class, () -> strategy.truncateHeadForPTLRetry(finalHistory));
    }

    @Test
    void truncatingRightAtAParallelToolCallRoundBoundaryKeepsThatRoundIntactOrDropsItWhole() {
        // Regression-focused variant: the removed head group is *itself* a
        // parallel tool-call round, so if groupByApiRound ever let a tool_result
        // leak into a later group, this is where it would surface.

        // groupByApiRound's first group is always the lone leading user message
        // (there is no assistant id yet to anchor a group boundary before it),
        // so the round-0 tool_use/tool_result group is only removed on the
// *second* truncation call — this matches what streamCompactSummary's
        // retry loop actually drives, one PTL retry at a time.
        List<Message> history = new ArrayList<>(parallelToolRound(0, 5));
        history.addAll(textOnlyRound(1));

        List<Message> afterFirst = strategy.truncateHeadForPTLRetry(history);
        List<Message> truncated = strategy.truncateHeadForPTLRetry(afterFirst);

        // The whole parallel-tool round must be gone — not partially split.
        for (Message m : truncated) {
            if (m instanceof AssistantMessage am && am.message() != null) {
                for (ContentBlock b : am.message().content()) {
                    assertFalse(b instanceof ToolUseBlock tu && tu.id().startsWith("tu-0-"),
                            "round 0's tool_use must not survive head truncation: " + truncated);
                }
            }
            if (m instanceof UserMessage um && um.message() != null && um.message().blocks() != null) {
                for (ContentBlock b : um.message().blocks()) {
                    assertFalse(b instanceof ToolResultBlock tr && tr.toolUseId().startsWith("tu-0-"),
                            "round 0's tool_result must not survive orphaned after head truncation: " + truncated);
                }
            }
        }

        List<StreamingClient.StreamRequest.RequestMessage> wire =
                RequestMessageNormalizer.normalizeForApi(truncated, true, true);
        assertNoOrphanedToolUseOrEmptyContent(wire);
    }

    @Test
    void promptTooLongFallbackDropsTwentyPercentAndPrependsThe197RetryMarker() {
        List<Message> history = new ArrayList<>();
        for (int round = 0; round < 9; round++) {
            history.addAll(textOnlyRound(round));
        }
        List<List<Message>> groups = MessageGrouping.groupByApiRound(history);
        assertEquals(10, groups.size());

        List<Message> truncated = strategy.truncateHeadForPTLRetry(
            history, CompactService.PROMPT_TOO_LONG_MARKER);

        UserMessage marker = assertInstanceOf(UserMessage.class, truncated.getFirst());
        assertTrue(marker.isMeta());
        assertEquals("[earlier conversation truncated for compaction retry]",
            marker.message().text());
        assertSame(groups.get(2).getFirst(), truncated.get(1),
            "the unparseable fallback drops floor(10 * 20%) groups");
    }

    @Test
    void promptTooLongTokenGapDropsEnoughWholeGroupsInOneRetry() {
        List<Message> history = new ArrayList<>();
        for (int round = 0; round < 5; round++) {
            history.addAll(textOnlyRound(round));
        }
        List<List<Message>> groups = MessageGrouping.groupByApiRound(history);
        int firstGroupTokens = Math.toIntExact(
            TokenEstimator.getInstance().estimateTokenCount(groups.getFirst()));
        int gap = firstGroupTokens + 1;
        int limit = 100_000;
        String response = CompactService.PROMPT_TOO_LONG_MARKER + ": "
            + (limit + gap) + " tokens > " + limit + " maximum";

        List<Message> truncated = strategy.truncateHeadForPTLRetry(history, response);

        assertSame(groups.get(2).getFirst(), truncated.get(1),
            "the parsed gap exceeds group 0, so groups 0 and 1 are dropped together");
    }

    @Test
    void repeatedPromptTooLongRetryReplacesRatherThanAccumulatesTheMarker() {
        List<Message> history = new ArrayList<>();
        for (int round = 0; round < 9; round++) {
            history.addAll(textOnlyRound(round));
        }

        List<Message> once = strategy.truncateHeadForPTLRetry(
            history, CompactService.PROMPT_TOO_LONG_MARKER);
        List<Message> twice = strategy.truncateHeadForPTLRetry(
            once, CompactService.PROMPT_TOO_LONG_MARKER);

        assertEquals(1, twice.stream()
            .filter(UserMessage.class::isInstance)
            .map(UserMessage.class::cast)
            .filter(UserMessage::isMeta)
            .filter(message -> Strings.CS.equals(
                "[earlier conversation truncated for compaction retry]",
                message.message().text()))
            .count());
        assertTrue(twice.size() < once.size(), "retry 2 must continue making progress");
    }
}
