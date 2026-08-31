package com.claudecode.runtime.session;

import org.apache.commons.lang3.Strings;

import com.claudecode.core.message.AssistantContent;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.ContentBlock;
import com.claudecode.core.message.CompactMetadata;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.MessageOrigin;
import com.claudecode.core.message.PreservedSegment;
import com.claudecode.core.message.SystemMessage;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.ThinkingBlock;
import com.claudecode.core.message.ToolResultBlock;
import com.claudecode.core.message.ToolUseBlock;
import com.claudecode.core.message.Usage;
import com.claudecode.core.message.UserMessage;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MessagesDeserializerTest {

    // ── Stage 0: retracted messages ───────────────────────────────────────

    @Test
    void refusalRetractedMessagesNeverMakeItBackIntoTheConversation() {
        String logical = "f47ac10b-58cc-4372-a567-0e02b2c3d479";
        String wire = "f47ac10b-58cc-4372-a567-000000000001";
        List<Message> out = MessagesDeserializer.deserialize(List.of(
            user("9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d", "what is the weather"),
            assistant(logical, new TextBlock("a reply the model took back")),
            refusalFallback("ann-1", List.of(wire)),
            user("11111111-2222-3333-4444-555555555555", "still here")));

        assertTrue(out.stream().noneMatch(m -> Strings.CS.equals(logical, m.uuid())),
            "the retracted assistant message must not be restored: " + out);
        assertTrue(out.stream().anyMatch(SystemMessage.class::isInstance),
            "the announcement itself stays in the transcript");
    }

    @Test
    void aChainWithoutRetractionsIsUntouchedByTheNewStage() {
        List<Message> in = List.of(user("u1", "hello"), assistant("a1", new TextBlock("hi")));
        assertEquals(2, MessagesDeserializer.deserialize(in).size());
    }

    // ── Stage 1: strip invalid permissionMode ─────────────────────────────

    @Test
    void invalidPermissionModeIsStripped() {
        UserMessage stale = withPermissionMode(user("u1", "hello"), "legacyMode");
        List<Message> out = MessagesDeserializer.deserialize(List.of(stale));
        UserMessage repaired = (UserMessage) out.getFirst();
        assertNull(repaired.permissionMode(), "unknown permissionMode should be nulled");
    }

    @Test
    void validPermissionModeIsPreserved() {
        UserMessage keep = withPermissionMode(user("u1", "hello"), "bypassPermissions");
        List<Message> out = MessagesDeserializer.deserialize(List.of(keep));
        UserMessage repaired = (UserMessage) out.getFirst();
        assertEquals("bypassPermissions", repaired.permissionMode());
    }

    // ── Stage 2: unresolved tool_use ──────────────────────────────────────

    @Test
    void allUnresolvedToolUsesDropTheAssistant() {
        AssistantMessage badAssistant = assistant("a1", toolUse("tu-1", "Read"));
        UserMessage userTail = user("u1", "next question");
        // Note: no tool_result for tu-1 anywhere.
        List<Message> out = MessagesDeserializer.deserialize(List.of(badAssistant, userTail));
        // Sentinel injection also runs since the last non-system message is user.
        assertTrue(out.stream().noneMatch(m -> m == badAssistant),
            "assistant with only-unresolved tool_use must be dropped");
    }

    @Test
    void mixedResolvedAndUnresolvedKeepsAssistant() {
        AssistantMessage am = assistant("a1", toolUse("tu-1", "Read"), toolUse("tu-2", "Read"));
        UserMessage tr = userWithBlocks("u1", toolResult("tu-1", "content read"));
        List<Message> out = MessagesDeserializer.deserialize(List.of(am, tr));
        assertTrue(out.contains(am), "mixed resolved/unresolved must keep the assistant");
    }

    @Test
    void toolUseWithMatchingResultSurvives() {
        AssistantMessage am = assistant("a1", toolUse("tu-1", "Read"));
        UserMessage tr = userWithBlocks("u1", toolResult("tu-1", "content"));
        List<Message> out = MessagesDeserializer.deserialize(List.of(am, tr));
        assertTrue(out.contains(am));
    }

    // ── Stage 3: orphaned thinking ────────────────────────────────────────

    @Test
    void thinkingOnlyWithoutSiblingIsDropped() {
        AssistantMessage orphan = assistant(
            "a1", /* messageId */ "shared", new ThinkingBlock("just musing"));
        List<Message> out = MessagesDeserializer.deserialize(List.of(orphan));
        assertFalse(out.contains(orphan), "orphaned thinking-only must drop");
    }

    @Test
    void thinkingOnlyKeptIfSiblingHasContent() {
        AssistantMessage think = assistant("a1", "shared", new ThinkingBlock("musing"));
        AssistantMessage body  = assistant("a2", "shared", new TextBlock("done"));
        List<Message> out = MessagesDeserializer.deserialize(List.of(think, body));
        assertTrue(out.contains(think), "sibling shares message.id → keep for later merge");
    }

    @Test
    void thinkingWithMixedContentKept() {
        AssistantMessage am = assistant(
            "a1", "id-1",
            new ThinkingBlock("musing"), new TextBlock("actual answer"));
        List<Message> out = MessagesDeserializer.deserialize(List.of(am));
        assertTrue(out.contains(am), "message has text besides thinking — keep");
    }

    // ── Stage 4: whitespace-only assistant + user merge ───────────────────

    @Test
    void whitespaceOnlyAssistantDroppedAndUsersMerged() {
        UserMessage u1 = user("u1", "hello");
        AssistantMessage empty = assistant("a1", new TextBlock("   \n\n  "));
        UserMessage u2 = user("u2", "world");
        List<Message> out = MessagesDeserializer.deserialize(List.of(u1, empty, u2));
        assertFalse(out.contains(empty));
        // The two users should have been merged into one before sentinel injection.
        long userCount = out.stream().filter(UserMessage.class::isInstance).count();
        assertEquals(1, userCount, "adjacent users after assistant drop must be merged into one");
        UserMessage merged = (UserMessage) out.stream()
            .filter(UserMessage.class::isInstance).findFirst().orElseThrow();
        // joinTextAtSeam inserts a "\n" between two text blocks so "hello" + "world"

        List<ContentBlock> blocks = merged.message().blocks();
        assertEquals(List.of(new TextBlock("hello\n"), new TextBlock("world")), blocks);
    }

    @Test
    void whitespaceOnlyAssistantDropPreservesToolResultsAcrossThreeAdjacentUsers() {
        // Reproduces the real /resume corruption: an assistant turn with 3 tool_use
        // blocks interleaved with whitespace-only streaming text deltas, each
        // followed by its own single-tool_result UserMessage persisted as a
        // separate JSONL row. Folding the whitespace-only assistants together must
        // NOT discard the three real tool_result blocks (previously it did, via a
        // text-only merge, yielding one UserMessage with empty text — the exact
        // "message content cannot be empty" wire shape).
        AssistantMessage toolUseA = assistant("a1", toolUse("tu-1", "Read"));
        AssistantMessage blank1 = assistant("a2", new TextBlock("\n"));
        AssistantMessage toolUseB = assistant("a3", toolUse("tu-2", "Bash"));
        AssistantMessage toolUseC = assistant("a4", toolUse("tu-3", "Grep"));
        AssistantMessage blank2 = assistant("a5", new TextBlock("\n"));
        UserMessage resultA = userWithBlocks("u1", toolResult("tu-1", "content A"));
        UserMessage resultB = userWithBlocks("u2", toolResult("tu-2", "content B"));
        UserMessage resultC = userWithBlocks("u3", toolResult("tu-3", "content C"));

        List<Message> out = MessagesDeserializer.deserialize(List.of(
            toolUseA, blank1, toolUseB, toolUseC, blank2, resultA, resultB, resultC));

        assertFalse(out.contains(blank1));
        assertFalse(out.contains(blank2));
        List<UserMessage> toolResultUsers = out.stream()
            .filter(UserMessage.class::isInstance)
            .map(UserMessage.class::cast)
            .filter(um -> um.message().blocks() != null
                && um.message().blocks().stream().anyMatch(ToolResultBlock.class::isInstance))
            .toList();
        assertEquals(1, toolResultUsers.size(), "the three tool-result users must merge into one");
        UserMessage merged = toolResultUsers.getFirst();
        List<String> resolvedIds = merged.message().blocks().stream()
            .filter(ToolResultBlock.class::isInstance)
            .map(b -> ((ToolResultBlock) b).toolUseId())
            .toList();
        assertEquals(List.of("tu-1", "tu-2", "tu-3"), resolvedIds,
            "all three real tool_result blocks must survive the merge, not collapse to empty text");
    }

    @Test
    void nonEmptyAssistantIsKept() {
        AssistantMessage am = assistant("a1", new TextBlock("real answer"));
        List<Message> out = MessagesDeserializer.deserialize(List.of(am));
        assertTrue(out.contains(am));
    }

    // ── Stage 5: turn interruption ────────────────────────────────────────

    @Test
    void completedTurnIsNone() {
        UserMessage u1 = user("u1", "question");
        AssistantMessage am = assistant("a1", new TextBlock("answer"));
        var result = MessagesDeserializer.deserializeWithInterrupt(List.of(u1, am));
        assertInstanceOf(MessagesDeserializer.None.class, result.state());
        // No sentinel added since last non-system is assistant, not user.
        assertEquals(2, result.messages().size());
    }

    @Test
    void trailingUserTextIsInterruptedPrompt() {
        UserMessage u1 = user("u1", "question");
        var result = MessagesDeserializer.deserializeWithInterrupt(List.of(u1));
        assertInstanceOf(MessagesDeserializer.InterruptedPrompt.class, result.state());
        // Sentinel got injected after u1, so messages.size == 2.
        assertEquals(2, result.messages().size());
        assertInstanceOf(AssistantMessage.class, result.messages().get(1));
    }

    @Test
    void trailingToolResultIsInterruptedTurn_synthesizesContinuation() {
        AssistantMessage am = assistant("a1", toolUse("tu-1", "Read"));
        UserMessage trailingResult = userWithBlocks("u1", toolResult("tu-1", "content"));
        var result = MessagesDeserializer.deserializeWithInterrupt(List.of(am, trailingResult));
        // interrupted_turn is converted to interrupted_prompt after synth continuation append
        assertInstanceOf(MessagesDeserializer.InterruptedPrompt.class, result.state());
        Message injected = result.messages().get(2);   // am, trailingResult, continuation, sentinel
        assertInstanceOf(UserMessage.class, injected);
        assertEquals(MessagesDeserializer.CONTINUE_FROM_WHERE_YOU_LEFT_OFF,
            ((TextBlock) ((UserMessage) injected).message().blocks().getFirst()).text());
        assertTrue(((UserMessage) injected).isMeta(), "continuation must be isMeta=true");
    }

    @Test
    void metaUserIsNotFlaggedAsInterrupted() {
        AssistantMessage am = assistant("a1", new TextBlock("done"));
        UserMessage meta = new UserMessage("u1", MessageContent.ofText("post-hook note"),
            /*isMeta*/ true, false, null, MessageOrigin.USER,
            null, Instant.now(), null, null, null);
        var result = MessagesDeserializer.deserializeWithInterrupt(List.of(am, meta));
        assertInstanceOf(MessagesDeserializer.None.class, result.state());
    }

    // ── Stage 6: sentinel injection ───────────────────────────────────────

    @Test
    void sentinelAppendedAfterTrailingUser() {
        UserMessage u = user("u1", "hi");
        List<Message> out = MessagesDeserializer.deserialize(List.of(u));
        assertEquals(2, out.size());
        assertInstanceOf(AssistantMessage.class, out.get(1));
        AssistantMessage sentinel = (AssistantMessage) out.get(1);
        List<ContentBlock> blocks = sentinel.message().content();
        assertEquals(1, blocks.size());
        assertInstanceOf(TextBlock.class, blocks.getFirst());
        assertEquals(MessagesDeserializer.NO_RESPONSE_REQUESTED,
            ((TextBlock) blocks.getFirst()).text());
    }

    @Test
    void systemMessagesDontBlockSentinelInsertion() {
        UserMessage u = user("u1", "hi");
        SystemMessage sys = new SystemMessage("s1", "info", "info", "…");
// Sentinel should be inserted BETWEEN u and sys (immediately after the last non-system
// relevant message).
        List<Message> out = MessagesDeserializer.deserialize(List.of(u, sys));
        assertEquals(3, out.size());
        assertInstanceOf(AssistantMessage.class, out.get(1));
        assertSame(sys, out.get(2), "system message stays at the tail");
    }

    // ── Stage: prune pre compact_boundary ─────────────────────────────────

    @Test
    void preBoundaryMessagesArePruned() {
        UserMessage pre1 = user("pre1", "old");
        UserMessage pre2 = user("pre2", "stale");
        SystemMessage boundary = boundary("bnd");
        UserMessage post = user("post1", "kept");
        List<Message> out = MessagesDeserializer.deserialize(List.of(pre1, pre2, boundary, post));
        List<String> uuids = out.stream().map(Message::uuid).toList();
        assertFalse(uuids.contains("pre1"), "pre-boundary message dropped");
        assertFalse(uuids.contains("pre2"), "pre-boundary message dropped");
        assertInstanceOf(SystemMessage.class, out.getFirst(), "boundary leads the reconstructed view");
        assertEquals("bnd", out.getFirst().uuid());
        assertTrue(uuids.contains("post1"), "post-boundary message is preserved");
    }

    @Test
    void preservedSegmentIsRelinkedAfterCompactBoundaryOnResume() {
        UserMessage stale = user("old", "summarized history");
        AssistantMessage terminal = new AssistantMessage(
            "terminal", AssistantContent.of("msg-terminal", List.of(new TextBlock("OK")),
                new Usage(1, 1, 3, 5)),
            false, "old", Instant.parse("2026-07-29T18:00:00Z"));
        SystemMessage boundary = new SystemMessage(
            "boundary", "compact_boundary", "info", "compacted",
            null, Instant.parse("2026-07-29T18:00:01Z"),
            new CompactMetadata(new PreservedSegment("terminal", "summary", "terminal")));
        UserMessage summary = new UserMessage(
            "summary", MessageContent.ofText("compacted summary"), false, true,
            null, MessageOrigin.COMPACT_SUMMARY, "boundary",
            Instant.parse("2026-07-29T18:00:02Z"), null, null, null);
        UserMessage post = new UserMessage(
            "post", MessageContent.ofText("next prompt"), false, false,
            null, MessageOrigin.USER, "summary",
            Instant.parse("2026-07-29T18:00:03Z"), null, null, null);

        List<Message> out = MessagesDeserializer.deserialize(
            List.of(stale, terminal, boundary, summary, post));

        assertEquals(List.of("boundary", "summary", "terminal", "post"),
            out.stream().limit(4).map(Message::uuid).toList(),
            "TS applyPreservedSegmentRelinks inserts the preserved terminal assistant after the summary anchor");
        assertFalse(out.stream().anyMatch(m -> Strings.CS.equals("old", m.uuid())),
            "summarized pre-boundary history must still be pruned");
        AssistantMessage restored = assertInstanceOf(AssistantMessage.class, out.get(2));
        assertEquals("OK", ((TextBlock) restored.message().content().getFirst()).text());
        assertEquals(Usage.EMPTY, restored.message().usage(),
            "released applyPreservedSegmentRelinks clears stale usage without dropping content");
    }

    @Test
    void pruningKeepsLastBoundaryWhenSeveral() {
// matches Java writing a fresh boundary on top of an already-compacted
        // transcript: only the trailing boundary's view survives.
        UserMessage old = user("o1", "ancient");
        SystemMessage firstBoundary = boundary("b1");
        UserMessage mid = user("m1", "middle");
        SystemMessage secondBoundary = boundary("b2");
        UserMessage recent = user("r1", "recent");
        List<Message> out = MessagesDeserializer.deserialize(
            List.of(old, firstBoundary, mid, secondBoundary, recent));
        List<String> uuids = out.stream().map(Message::uuid).toList();
        assertFalse(uuids.contains("o1"), "pre-first-boundary message dropped");
        assertFalse(uuids.contains("m1"), "pre-second-boundary message dropped");
        assertEquals("b2", out.getFirst().uuid());
        assertTrue(uuids.contains("r1"), "post-boundary message preserved");
    }

    @Test
    void pruningIsNoOpWithoutBoundary() {
        UserMessage u1 = user("u1", "a");
        UserMessage u2 = user("u2", "b");
        List<Message> out = MessagesDeserializer.deserialize(List.of(u1, u2));
        List<String> uuids = out.stream().map(Message::uuid).toList();
        assertTrue(uuids.contains("u1"));
        assertTrue(uuids.contains("u2"));
    }

    @Test
    void boundaryPruningIsIdempotent() {
        UserMessage pre = user("pre1", "old");
        SystemMessage boundary = boundary("bnd");
        UserMessage post = user("post1", "kept");
        List<Message> pass1 = MessagesDeserializer.deserialize(List.of(pre, boundary, post));
        List<Message> pass2 = MessagesDeserializer.deserialize(pass1);
        assertEquals(pass1.size(), pass2.size(),
            "prunePreBoundary must be idempotent across deserialize passes");
        assertEquals("bnd", pass2.getFirst().uuid());
    }


    // ── Idempotence ───────────────────────────────────────────────────────

    @Test
    void pipelineIsIdempotent() {
        AssistantMessage bad = assistant("a1", toolUse("tu-x", "Read"));
        UserMessage tail = user("u1", "hi");
        List<Message> pass1 = MessagesDeserializer.deserialize(List.of(bad, tail));
        List<Message> pass2 = MessagesDeserializer.deserialize(pass1);
        assertEquals(pass1.size(), pass2.size(),
            "running deserialize twice must produce the same message count");
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private static UserMessage user(String uuid, String text) {
        return new UserMessage(uuid, MessageContent.ofText(text));
    }

    private static UserMessage userWithBlocks(String uuid, ContentBlock... blocks) {
        return new UserMessage(uuid, MessageContent.ofBlocks(List.of(blocks)));
    }

    private static AssistantMessage assistant(String uuid, ContentBlock... blocks) {
        return new AssistantMessage(uuid, AssistantContent.of(List.of(blocks)));
    }

    private static AssistantMessage assistant(String uuid, String messageId, ContentBlock... blocks) {
        return new AssistantMessage(uuid, AssistantContent.of(messageId, List.of(blocks)));
    }

    private static ToolUseBlock toolUse(String id, String name) {
        return new ToolUseBlock(id, name, JsonNodeFactory.instance.objectNode());
    }

    private static ToolResultBlock toolResult(String toolUseId, String text) {
        return new ToolResultBlock(toolUseId,
            List.of(new TextBlock(text)),
            /* isError */ false);
    }

    private static SystemMessage boundary(String uuid) {
        return new SystemMessage(uuid, "compact_boundary", "compact", "…");
    }

    private static SystemMessage refusalFallback(String uuid, List<String> retracted) {
        return new SystemMessage(uuid, "model_refusal_fallback", "warning",
            "Retrying with a fallback model",
            null, Instant.EPOCH, null, null, null, retracted);
    }

    private static UserMessage withPermissionMode(UserMessage um, String mode) {
        return new UserMessage(
            um.uuid(), um.message(),
            um.isMeta(), um.isCompactSummary(),
            um.toolUseResult(), um.origin(),
            um.parentUuidValue(), um.timestampValue(),
            um.imagePasteIds(), mode, um.sessionIdValue());
    }
}
