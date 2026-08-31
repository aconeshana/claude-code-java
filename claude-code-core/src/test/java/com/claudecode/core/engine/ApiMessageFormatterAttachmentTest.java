package com.claudecode.core.engine;

import org.apache.commons.lang3.Strings;

import com.claudecode.core.message.AttachmentMessage;
import com.claudecode.core.message.CompactFileReferenceAttachment;
import com.claudecode.core.message.FileContentAttachment;
import com.claudecode.core.message.InvokedSkillsAttachment;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageFactory;
import com.claudecode.core.message.PlanFileReferenceAttachment;
import com.claudecode.core.message.PlanModeReminderAttachment;
import com.claudecode.core.message.TaskStatusAttachment;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Assembly-protocol test for the post-compact re-attachment pipeline's most
 * critical hop: {@link ApiMessageFormatter#toRequestMessages} must not
 * silently drop {@link AttachmentMessage}s the way its {@code default -> {}}
 * branch drops every other unhandled {@link Message} type. Unit tests
 * elsewhere already verify the two halves in isolation —
 * {@code DefaultManualCompactStrategyAttachmentsTest} (producers build the
 * right {@code AttachmentPayload}) and {@code AttachmentRendererTest}
 * (a payload renders to the right verbatim text) — but neither exercises
 * {@link ApiMessageFormatter} itself. This is the one place a regression
 * (e.g. reverting the {@code case AttachmentMessage am ->} branch back to
 * falling through to {@code default}) would go undetected: attachments
 * would vanish from the wire request while every other test kept passing.
 * Expected strings are the same verbatim text already verified in
 * {@code AttachmentRendererTest}, re-asserted here one layer downstream at
 * the wire-shaped {@link StreamingClient.StreamRequest.RequestMessage} level.
 */
class ApiMessageFormatterAttachmentTest {

    private static List<StreamingClient.StreamRequest.RequestMessage> format(Message... messages) {
        return ApiMessageFormatter.toRequestMessages(List.of(messages));
    }

    @Test
    void compactFileReferenceReachesWireAsOneSystemTurn() {
        var out = format(new AttachmentMessage("u1", new CompactFileReferenceAttachment("/tmp/big.txt")));

        assertEquals(1, out.size());
        assertEquals("system", out.getFirst().role());
        assertEquals(
            "Note: /tmp/big.txt was read before the last conversation was summarized, but the "
                + "contents are too large to include. Use Read tool if you need to access it.",
            out.getFirst().content());
    }

    @Test
    void fileContentReachesWireAsOneSystemTurn() {
        var out = format(new AttachmentMessage("u1", new FileContentAttachment("/tmp/a.txt", "line1\nline2")));

        assertEquals(1, out.size());
        assertEquals("system", out.getFirst().role());
        assertEquals(
            """
            Called the Read tool with the following input: \
            {"file_path":"/tmp/a.txt"}
            Result of calling the Read tool:
            1\tline1
            2\tline2""",
            out.getFirst().content());
    }

    @Test
    void planFileReferenceReachesWireAsOneUserTurn() {
        var out = format(new AttachmentMessage(
            "u1", new PlanFileReferenceAttachment("/plans/s1.md", "# Plan\nstep 1")));

        assertEquals(1, out.size());
        assertEquals("system", out.getFirst().role());
        assertEquals(
            """
            A plan file exists from plan mode at: /plans/s1.md

            Plan contents:

            # Plan
            step 1

            If this plan is relevant to the current work and not already complete, continue \
            working on it.""",
            out.getFirst().content());
    }

    @Test
    void planModeReminderReachesWireAsOneUserTurn() {
        var out = format(new AttachmentMessage(
            "u1", new PlanModeReminderAttachment(false, "/plans/s1.md", true)));

        assertEquals(1, out.size());
        assertEquals("system", out.getFirst().role());
        String text = (String) out.getFirst().content();
        assertTrue(Strings.CS.contains(text, "A plan file already exists at /plans/s1.md"));
        assertTrue(Strings.CS.contains(text, "ExitPlanMode"));
    }

    @Test
    void invokedSkillsWithNoEntriesProducesNoWireTurnAtAll() {
        var out = format(new AttachmentMessage("u1", new InvokedSkillsAttachment(List.of())));
        assertEquals(List.of(), out);
    }

    @Test
    void invokedSkillsReachesWireAsOneJoinedSystemTurn() {
        var payload = new InvokedSkillsAttachment(List.of(
            new InvokedSkillsAttachment.InvokedSkillEntry("deploy", "/skills/deploy.md", "steps...")));
        var out = format(new AttachmentMessage("u1", payload));

        assertEquals(1, out.size());
        assertEquals("system", out.getFirst().role());
        String invoked = (String) out.getFirst().content();
        assertTrue(Strings.CS.startsWith(invoked, "The following skills were invoked EARLIER in this session"));
        assertTrue(Strings.CS.contains(invoked, "IMPORTANT: Do NOT re-execute these skills"));
        assertTrue(Strings.CS.contains(invoked, "### Skill: deploy\nPath: /skills/deploy.md\n\nsteps..."));
    }

    @Test
    void taskStatusReachesWireAsOneUserTurn() {
        var out = format(new AttachmentMessage(
            "u1", new TaskStatusAttachment("t1", "local_agent", "running", "Refactor auth", "half done", null)));

        assertEquals(1, out.size());
        assertEquals("system", out.getFirst().role());
        assertEquals(
            "Background agent \"Refactor auth\" (t1) is still running. Progress: half done "
                + "Do NOT spawn a duplicate. You will be notified when it completes. You can check its "
                + "progress with the TaskOutput tool or send it a message with SendMessage.",
            out.getFirst().content());
    }

    /**
     * End-to-end shape check: a compact summary turn followed by several
     * {@link AttachmentMessage}s (as {@code DefaultManualCompactStrategy}
     * actually appends them after a compaction) all survive formatting, in
     * order, none silently dropped — the exact sequence the model would see
     * on the wire immediately after a {@code /compact}.
     */
    @Test
    void summaryFollowedByMultipleAttachmentsAllSurviveInOrder() {
        Message summary = MessageFactory.createUserMessage("Conversation summary: ...", false);
        var out = format(
            summary,
            new AttachmentMessage("u1", new CompactFileReferenceAttachment("/tmp/big.txt")),
            new AttachmentMessage("u2", new PlanFileReferenceAttachment("/plans/s1.md", "# Plan")),
            new AttachmentMessage("u3", new TaskStatusAttachment(
                "t1", "local_agent", "completed", "Run tests", "3 files changed", "/out/t1.log")));

        assertEquals(2, out.size());
        assertEquals("Conversation summary: ...", out.getFirst().content());
        assertEquals("system", out.get(1).role());
        String restored = (String) out.get(1).content();
        assertTrue(Strings.CS.contains(restored, "/tmp/big.txt was read before"));
        assertTrue(Strings.CS.contains(restored, "A plan file exists from plan mode at: /plans/s1.md"));
        assertTrue(Strings.CS.contains(restored, "Delta: 3 files changed"));
        assertFalse(Strings.CS.contains(restored, "<system-reminder>"),
            "post-compact attachments are one raw system turn in 2.1.197");
    }
}
