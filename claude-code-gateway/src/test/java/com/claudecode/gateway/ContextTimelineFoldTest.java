package com.claudecode.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.claudecode.core.message.AssistantContent;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.AttachmentMessage;
import com.claudecode.core.message.CompactMetadata;
import com.claudecode.core.message.ContentBlock;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.MessageOrigin;
import com.claudecode.core.message.PlanModeReminderAttachment;
import com.claudecode.core.message.SystemMessage;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.ToolResultBlock;
import com.claudecode.core.message.ToolUseBlock;
import com.claudecode.core.message.Usage;
import com.claudecode.core.message.UserMessage;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The snapshot-diff fold: categories, settlements, compaction archive, file ops, agents. */
class ContextTimelineFoldTest {

    private static final Instant T0 = Instant.parse("2026-09-16T10:00:00Z");

    @Test
    void foldsUserAssistantAndToolRowsIntoCategoriesAndSettlesOneRequestPerResponse() {
        ContextTimelineFold fold = new ContextTimelineFold(ZoneOffset.UTC);
        fold.envelope(1_000, 500);
        List<Message> rows = new ArrayList<>();
        rows.add(user("Please read the file", T0));
        // One API response, two rows sharing the envelope id and usage.
        Usage usage = usage(8_000, 120, 300, 5_000);
        rows.add(assistant("resp-1", List.of(new TextBlock("Sure, reading.")), usage, "claude-sonnet-5",
            T0.plusSeconds(2)));
        rows.add(assistant("resp-1", List.of(new ToolUseBlock("tu-1", "Read",
            JsonUtils.getMapper().createObjectNode().put("file_path", "/work/a.txt"))),
            usage, "claude-sonnet-5", T0.plusSeconds(2)));
        rows.add(toolResult("tu-1", "line one\nline two\n", false, T0.plusSeconds(4)));

        assertThat(fold.sync(rows)).isTrue();
        ObjectNode head = fold.head(200_000L, null);
        JsonNode current = head.path("current");
        assertThat(current.path("system").asLong()).isEqualTo(1_000);
        assertThat(current.path("tools").asLong()).isEqualTo(500);
        assertThat(current.path("user").asLong()).isPositive();
        assertThat(current.path("assistant").asLong()).isPositive();
        assertThat(current.path("tool").asLong()).isPositive();
        assertThat(current.path("total").asLong()).isEqualTo(
            1_500 + current.path("user").asLong() + current.path("assistant").asLong()
                + current.path("tool").asLong());
        assertThat(head.path("counts").path("steps").asInt()).isEqualTo(1);
        assertThat(head.path("counts").path("turns").asInt()).isEqualTo(1);
        assertThat(head.path("toolCalls").asInt()).isEqualTo(1);
        assertThat(head.path("humanInputs").asInt()).isEqualTo(1);
        assertThat(head.path("lastUser").asText()).isEqualTo("Please read the file");
        assertThat(head.path("last").path("prompt").asLong()).isEqualTo(8_000 + 300 + 5_000);
        assertThat(head.path("model").asText()).isEqualTo("claude-sonnet-5");
        assertThat(head.path("contextWindow").asLong()).isEqualTo(200_000);
        assertThat(head.path("cost").path("").path("claude-sonnet-5").path("peak")
            .path("cacheRead").asLong()).isEqualTo(5_000);

        ObjectNode detail = JsonUtils.getMapper().createObjectNode();
        fold.detailInto(detail);
        assertThat(detail.path("requests")).hasSize(1);
        JsonNode request = detail.path("requests").get(0);
        // The record snapshots the surface AS DISPATCHED: only the user row.
        assertThat(request.path("assistant").asLong()).isZero();
        assertThat(request.path("tool").asLong()).isZero();
        assertThat(request.path("turn").asLong()).isEqualTo(1);
        assertThat(request.path("step").asLong()).isEqualTo(1);
        assertThat(request.path("cacheRead").asLong()).isEqualTo(5_000);
        assertThat(request.path("output").asLong()).isEqualTo(120);
        List<String> cats = new ArrayList<>();
        detail.path("nodes").forEach(node -> cats.add(node.path("cat").asText()));
        assertThat(cats).containsExactly("user", "assistant", "assistant", "tool");
        JsonNode toolNode = detail.path("nodes").get(3);
        assertThat(toolNode.path("tool").asText()).isEqualTo("Read");
        assertThat(toolNode.path("text").asText()).isEqualTo("line one line two");
        assertThat(detail.path("fileOps")).hasSize(1);
        assertThat(detail.path("fileOps").get(0).path("kind").asText()).isEqualTo("read");
        assertThat(detail.path("fileOps").get(0).path("path").asText()).isEqualTo("/work/a.txt");
        // Activity books the billed volume under the settlement's local day.
        JsonNode day = fold.activity().path("days").path("2026-09-16");
        assertThat(day.path("requests").asLong()).isEqualTo(1);
        assertThat(day.path("tokens").asLong()).isEqualTo(8_000 + 300 + 5_000 + 120);
    }

    @Test
    void secondSyncFoldsOnlyNewRowsAndBumpsRevisionOnlyOnChange() {
        ContextTimelineFold fold = new ContextTimelineFold(ZoneOffset.UTC);
        List<Message> rows = new ArrayList<>();
        rows.add(user("hi", T0));
        fold.sync(rows);
        long rev = fold.detailRev();
        assertThat(fold.sync(rows)).isFalse();
        assertThat(fold.detailRev()).isEqualTo(rev);
        rows.add(assistant("r2", List.of(new TextBlock("hello")), usage(10, 5, 0, 0), "m", T0.plusSeconds(1)));
        assertThat(fold.sync(rows)).isTrue();
        assertThat(fold.detailRev()).isGreaterThan(rev);
    }

    @Test
    void compactionArchivesReplacedRowsUnderTheBoundaryAndKeepsTheSummaryLive() {
        ContextTimelineFold fold = new ContextTimelineFold(ZoneOffset.UTC);
        List<Message> rows = new ArrayList<>();
        UserMessage first = user("first question", T0);
        AssistantMessage answer = assistant("r1", List.of(new TextBlock("first answer")),
            usage(100, 20, 0, 0), "m", T0.plusSeconds(1));
        rows.add(first);
        rows.add(answer);
        fold.sync(rows);
        long freed = fold.head(null, null).path("current").path("user").asLong()
            + fold.head(null, null).path("current").path("assistant").asLong();

        // Compaction rewrites the list: boundary + summary replace the history.
        List<Message> compacted = new ArrayList<>();
        compacted.add(new SystemMessage(UUID.randomUUID().toString(), "compact_boundary", "info",
            "Conversation compacted", null, T0.plusSeconds(10),
            new CompactMetadata("auto", 50_000L), null, null, null, null));
        compacted.add(new UserMessage(UUID.randomUUID().toString(),
            MessageContent.ofText("Summary of the conversation so far"), false, true, null,
            MessageOrigin.COMPACT_SUMMARY, null, T0.plusSeconds(11), null, null));
        compacted.add(user("second question", T0.plusSeconds(20)));
        assertThat(fold.sync(compacted)).isTrue();

        ObjectNode detail = JsonUtils.getMapper().createObjectNode();
        fold.detailInto(detail);
        assertThat(detail.path("archive")).hasSize(2);
        long boundarySeq = detail.path("archive").get(0).path("gone").asLong();
        assertThat(detail.path("archive").get(1).path("gone").asLong()).isEqualTo(boundarySeq);
        List<String> kinds = new ArrayList<>();
        detail.path("events").forEach(event -> kinds.add(event.path("kind").asText()));
        assertThat(kinds).contains("compaction");
        JsonNode compaction = detail.path("events").get(kinds.indexOf("compaction"));
        assertThat(compaction.path("seq").asLong()).isEqualTo(boundarySeq);
        assertThat(compaction.path("tokens").asLong()).isEqualTo(freed);
        assertThat(compaction.path("count").asInt()).isEqualTo(2);
        assertThat(compaction.path("form").asText()).isEqualTo("auto");
        // Live surface: the summary (inject) and the new user row, seqs after the boundary.
        assertThat(detail.path("nodes")).hasSize(2);
        assertThat(detail.path("nodes").get(0).path("cat").asText()).isEqualTo("inject");
        assertThat(detail.path("nodes").get(0).path("form").asText()).isEqualTo("compaction");
        assertThat(detail.path("nodes").get(0).path("seq").asLong()).isGreaterThan(boundarySeq);
        ObjectNode head = fold.head(null, null);
        assertThat(head.path("counts").path("compactions").asInt()).isEqualTo(1);
        assertThat(head.path("current").path("assistant").asLong()).isZero();
        assertThat(head.path("humanInputs").asInt()).isEqualTo(2);
    }

    @Test
    void boundaryObservedBeforeTheRewriteStillOwnsTheArchivedRows() {
        ContextTimelineFold fold = new ContextTimelineFold(ZoneOffset.UTC);
        List<Message> rows = new ArrayList<>();
        rows.add(user("first question", T0));
        rows.add(assistant("r1", List.of(new TextBlock("first answer")),
            usage(100, 20, 0, 0), "m", T0.plusSeconds(1)));
        fold.sync(rows);

        // Snapshot 1: the compact_boundary row is appended while the history
        // is still in place (the hub delivered the row before the rewrite).
        SystemMessage boundary = new SystemMessage(UUID.randomUUID().toString(), "compact_boundary",
            "info", "Conversation compacted", null, T0.plusSeconds(10),
            new CompactMetadata("auto", 50_000L), null, null, null, null);
        rows.add(boundary);
        fold.sync(rows);
        // Snapshot 2: the rewrite lands.
        List<Message> compacted = new ArrayList<>();
        compacted.add(boundary);
        compacted.add(new UserMessage(UUID.randomUUID().toString(),
            MessageContent.ofText("Summary"), false, true, null,
            MessageOrigin.COMPACT_SUMMARY, null, T0.plusSeconds(11), null, null));
        fold.sync(compacted);

        ObjectNode head = fold.head(null, null);
        assertThat(head.path("counts").path("compactions").asInt()).isEqualTo(1);
        assertThat(head.path("counts").path("prunes").asInt()).isZero();
        ObjectNode detail = JsonUtils.getMapper().createObjectNode();
        fold.detailInto(detail);
        assertThat(detail.path("archive")).hasSize(2);
        JsonNode compaction = detail.path("events").get(0);
        assertThat(compaction.path("kind").asText()).isEqualTo("compaction");
        assertThat(compaction.path("count").asInt()).isEqualTo(2);
    }

    @Test
    void onlyTheNewestSurfaceNodesKeepTheirContent() {
        ContextTimelineFold fold = new ContextTimelineFold(ZoneOffset.UTC);
        List<Message> rows = new ArrayList<>();
        for (int i = 0; i < ContextTimelineFold.MAX_CONTENT_NODES + 5; i++) {
            rows.add(user("row " + i, T0.plusSeconds(i)));
        }
        fold.sync(rows);
        ObjectNode detail = JsonUtils.getMapper().createObjectNode();
        fold.detailInto(detail);
        long oldest = detail.path("nodes").get(0).path("seq").asLong();
        long newest = detail.path("nodes").get(detail.path("nodes").size() - 1).path("seq").asLong();
        assertThat(fold.nodeAt(oldest).content).isNull();
        assertThat(fold.nodeAt(newest).content).isEqualTo("row " + (ContextTimelineFold.MAX_CONTENT_NODES + 4));
        // The token ledger still counts every live row.
        assertThat(fold.head(null, null).path("humanInputs").asInt())
            .isEqualTo(ContextTimelineFold.MAX_CONTENT_NODES + 5);
    }

    @Test
    void rowsVanishingWithoutABoundaryBecomeAPruneEvent() {
        ContextTimelineFold fold = new ContextTimelineFold(ZoneOffset.UTC);
        List<Message> rows = new ArrayList<>();
        rows.add(user("keep", T0));
        rows.add(user("drop me", T0.plusSeconds(1)));
        fold.sync(rows);
        rows.remove(1);
        fold.sync(rows);
        ObjectNode detail = JsonUtils.getMapper().createObjectNode();
        fold.detailInto(detail);
        assertThat(detail.path("events")).hasSize(1);
        assertThat(detail.path("events").get(0).path("kind").asText()).isEqualTo("prune");
        assertThat(detail.path("archive")).hasSize(1);
        assertThat(fold.head(null, null).path("counts").path("prunes").asInt()).isEqualTo(1);
    }

    @Test
    void metaRowsAndAttachmentsAreInjectionsWithEventsAndPlanModeToggles() {
        ContextTimelineFold fold = new ContextTimelineFold(ZoneOffset.UTC);
        List<Message> rows = new ArrayList<>();
        rows.add(new UserMessage(UUID.randomUUID().toString(),
            MessageContent.ofText("<system-reminder>\nPlan mode is active.\n</system-reminder>"),
            true, false, null, MessageOrigin.SYSTEM, null, T0, null, null));
        rows.add(new AttachmentMessage(UUID.randomUUID().toString(),
            new PlanModeReminderAttachment(false, "/work/.claude/plan.md", false)));
        fold.sync(rows);
        ObjectNode detail = JsonUtils.getMapper().createObjectNode();
        fold.detailInto(detail);
        assertThat(detail.path("nodes")).hasSize(2);
        assertThat(detail.path("nodes").get(0).path("cat").asText()).isEqualTo("inject");
        assertThat(detail.path("nodes").get(0).path("name").asText()).isEqualTo("plan-mode");
        assertThat(detail.path("nodes").get(1).path("cat").asText()).isEqualTo("inject");
        assertThat(detail.path("nodes").get(1).path("name").asText()).isEqualTo("plan_mode");
        List<String> kinds = new ArrayList<>();
        detail.path("events").forEach(event -> kinds.add(event.path("kind").asText()));
        assertThat(kinds).containsExactly("inject", "inject", "mode");
        assertThat(fold.head(null, null).path("counts").path("injects").asInt()).isEqualTo(2);
        assertThat(fold.head(null, null).path("humanInputs").asInt()).isZero();
    }

    @Test
    void agentCallsOpenRunningRecordsAndSettleFromTheToolUseResult() {
        ContextTimelineFold fold = new ContextTimelineFold(ZoneOffset.UTC);
        List<Message> rows = new ArrayList<>();
        rows.add(user("delegate", T0));
        ObjectNode input = JsonUtils.getMapper().createObjectNode();
        input.put("subagent_type", "explore");
        input.put("description", "Find usages");
        input.put("prompt", "Find every caller of foo");
        rows.add(assistant("r1", List.of(new ToolUseBlock("agent-1", "Task", input)),
            usage(10, 5, 0, 0), "m", T0.plusSeconds(1)));
        fold.sync(rows);
        ObjectNode detail = JsonUtils.getMapper().createObjectNode();
        fold.detailInto(detail);
        assertThat(detail.path("agents")).hasSize(1);
        assertThat(detail.path("agents").get(0).path("status").asText()).isEqualTo("running");
        assertThat(detail.path("agents").get(0).path("type").asText()).isEqualTo("explore");

        Map<String, Object> result = Map.of(
            "status", "completed", "agentId", "a1b2c3", "agentType", "explore",
            "resolvedModel", "claude-haiku-5", "totalDurationMs", 4_200, "totalTokens", 12_345,
            "totalToolUseCount", 7, "usage", usage(100, 50, 10, 2_000));
        rows.add(new UserMessage(UUID.randomUUID().toString(),
            MessageContent.ofBlocks(List.of(new ToolResultBlock("agent-1",
                List.of(new TextBlock("done")), false))),
            false, false, result, MessageOrigin.TOOL_RESULT, null, T0.plusSeconds(6), null, null));
        fold.sync(rows);
        detail = JsonUtils.getMapper().createObjectNode();
        fold.detailInto(detail);
        JsonNode agent = detail.path("agents").get(0);
        assertThat(agent.path("status").asText()).isEqualTo("done");
        assertThat(agent.path("agentId").asText()).isEqualTo("a1b2c3");
        assertThat(agent.path("tokens").asLong()).isEqualTo(12_345);
        assertThat(agent.path("durationMs").asLong()).isEqualTo(4_200);
        assertThat(agent.path("toolUses").asLong()).isEqualTo(7);
        assertThat(agent.path("model").asText()).isEqualTo("claude-haiku-5");
        assertThat(agent.path("usage").path("cacheReadTokens").asLong()).isEqualTo(2_000);
        assertThat(agent.path("endSeq").asLong()).isGreaterThan(agent.path("seq").asLong());
        // The paired result names its tool and books timing.
        JsonNode toolNode = detail.path("nodes").get(2);
        assertThat(toolNode.path("tool").asText()).isEqualTo("Task");
        ObjectNode timing = fold.timing(1_000, 200, 100, 1);
        assertThat(timing.path("toolCalls").asLong()).isEqualTo(1);
        assertThat(timing.path("toolsMs").asLong()).isEqualTo(5_000);
        assertThat(timing.path("tools").path("Task").path("calls").asLong()).isEqualTo(1);
    }

    @Test
    void fileOpsDeriveKindPathAndLineDeltasFromCallArguments() {
        ObjectNode edit = JsonUtils.getMapper().createObjectNode();
        edit.put("file_path", "/w/x.java");
        edit.put("old_string", "a\nb");
        edit.put("new_string", "a\nb\nc\n");
        ObjectNode grep = JsonUtils.getMapper().createObjectNode();
        grep.put("pattern", "TODO");
        ObjectNode scopedGrep = JsonUtils.getMapper().createObjectNode();
        scopedGrep.put("pattern", "TODO");
        scopedGrep.put("path", "src");
        ObjectNode bash = JsonUtils.getMapper().createObjectNode();
        bash.put("command", "ls");

        List<ContextTimelineFold.FileOp> editOps = ContextTimelineFold.fileOpsOf(
            5, 1L, "Edit", edit, false);
        assertThat(editOps).hasSize(1);
        assertThat(editOps.getFirst().kind()).isEqualTo("write");
        assertThat(editOps.getFirst().added()).isEqualTo(3);
        assertThat(editOps.getFirst().removed()).isEqualTo(2);
        List<ContextTimelineFold.FileOp> grepOps = ContextTimelineFold.fileOpsOf(
            6, 1L, "Grep", grep, true);
        assertThat(grepOps.getFirst().pattern()).isTrue();
        assertThat(grepOps.getFirst().path()).isEqualTo("TODO");
        assertThat(grepOps.getFirst().err()).isTrue();
        List<ContextTimelineFold.FileOp> scopedOps = ContextTimelineFold.fileOpsOf(
            7, 1L, "Grep", scopedGrep, false);
        assertThat(scopedOps.getFirst().path()).isEqualTo("src");
        assertThat(scopedOps.getFirst().detail()).isEqualTo("TODO");
        assertThat(scopedOps.getFirst().pattern()).isFalse();
        assertThat(ContextTimelineFold.fileOpsOf(8, 1L, "Bash", bash, false)).isEmpty();
        assertThat(ContextTimelineFold.lines("")).isZero();
        assertThat(ContextTimelineFold.lines("x\n")).isEqualTo(1);
        assertThat(ContextTimelineFold.lines("x\ny")).isEqualTo(2);
    }

    @Test
    void retentionKeepsTheNewestTurnsAndRecordsTheArchiveFloor() {
        ContextTimelineFold fold = new ContextTimelineFold(ZoneOffset.UTC);
        List<Message> rows = new ArrayList<>();
        for (int turn = 0; turn < ContextTimelineFold.MAX_KEPT_TURNS + 5; turn++) {
            rows.add(user("q" + turn, T0.plusSeconds(turn * 2L)));
            rows.add(assistant("r" + turn, List.of(new TextBlock("a" + turn)),
                usage(1, 1, 0, 0), "m", T0.plusSeconds(turn * 2L + 1)));
        }
        fold.sync(rows);
        ObjectNode head = fold.head(null, null);
        assertThat(head.path("counts").path("turns").asInt())
            .isEqualTo(ContextTimelineFold.MAX_KEPT_TURNS);
        assertThat(head.path("counts").path("steps").asInt())
            .isEqualTo(ContextTimelineFold.MAX_KEPT_TURNS);
        // Every row vanishes without a boundary: one prune archives them all,
        // then the archive cap trims and stamps the floor.
        fold.sync(List.of());
        ObjectNode detail = JsonUtils.getMapper().createObjectNode();
        fold.detailInto(detail);
        assertThat(detail.path("archive").size()).isLessThanOrEqualTo(ContextTimelineFold.MAX_ARCHIVE_NODES);
        assertThat(detail.path("nodes")).isEmpty();
    }

    // ------------------------------------------------------------- helpers

    private static UserMessage user(String text, Instant time) {
        return new UserMessage(UUID.randomUUID().toString(), MessageContent.ofText(text),
            false, false, null, MessageOrigin.USER, null, time, null, null);
    }

    private static AssistantMessage assistant(String responseId, List<ContentBlock> blocks,
                                              Usage usage, String model, Instant time) {
        return new AssistantMessage(UUID.randomUUID().toString(),
            new AssistantContent(responseId, blocks, usage, model, "end_turn", null),
            false, null, time);
    }

    private static UserMessage toolResult(String toolUseId, String text, boolean error, Instant time) {
        return new UserMessage(UUID.randomUUID().toString(),
            MessageContent.ofBlocks(List.of(new ToolResultBlock(toolUseId,
                List.of(new TextBlock(text)), error))),
            false, false, null, MessageOrigin.TOOL_RESULT, null, time, null, null);
    }

    private static Usage usage(long input, long output, long cacheWrite, long cacheRead) {
        return new Usage(input, output, cacheWrite, cacheRead, null, "standard", null, "",
            List.of(), "standard", null);
    }
}
