package com.claudecode.core.engine;

import org.apache.commons.lang3.Strings;

import com.claudecode.core.message.ApiErrorMessages;
import com.claudecode.core.message.AgentListingDeltaAttachment;
import com.claudecode.core.message.AttachmentMessage;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.AssistantContent;
import com.claudecode.core.message.ContentBlock;
import com.claudecode.core.message.DocumentBlock;
import com.claudecode.core.message.FileContentAttachment;
import com.claudecode.core.message.ImageBlock;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.MessageFactory;
import com.claudecode.core.message.MessageOrigin;
import com.claudecode.core.message.PlanModeExitAttachment;
import com.claudecode.core.message.QueuedCommandAttachment;
import com.claudecode.core.message.SystemMessage;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.ToolResultBlock;
import com.claudecode.core.message.ToolReferenceBlock;
import com.claudecode.core.message.ToolUseBlock;
import com.claudecode.core.message.UserMessage;

import com.fasterxml.jackson.databind.node.TextNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;


class RequestMessageNormalizerTest {

    private static final ImageBlock IMG = new ImageBlock(TextNode.valueOf("x"));
    private static final DocumentBlock DOC = new DocumentBlock(TextNode.valueOf("x"));

    private static UserMessage metaUser(String uuid, ContentBlock... blocks) {
        return new UserMessage(uuid, MessageContent.ofBlocks(List.of(blocks)),
            true, false, null, MessageOrigin.USER, null, Instant.now(), null, null);
    }

    private static UserMessage plainUser(String uuid, ContentBlock... blocks) {
        return new UserMessage(uuid, MessageContent.ofBlocks(List.of(blocks)),
            false, false, null, MessageOrigin.USER, null, Instant.now(), null, null);
    }

    /**
     * A preceding assistant {@code tool_use} turn for the given id, so a
     * following bare {@code ToolResultBlock} user message is not treated as an
     * orphaned tool_result by {@link RequestMessageNormalizer#ensureToolResultPairing}
     * (that pass only pairs against an immediately-preceding assistant turn).
     */
    private static AssistantMessage assistantToolUse(String messageId, String toolUseId) {
        return new AssistantMessage(messageId, AssistantContent.of(List.of(
            new ToolUseBlock(toolUseId, "Bash", JsonNodeFactory.instance.objectNode()))));
    }

    private static AssistantMessage tooLargeError(ApiErrorMessages.TooLargeKind kind) {
        return MessageFactory.createAssistantAPIErrorMessage(
            ApiErrorMessages.tooLargeMessage(kind, false));
    }

    @Test
    void imageTooLarge_stripsImageFromMetaUser() {
        UserMessage meta = metaUser("m1", new TextBlock("hi"), IMG);
        var out = RequestMessageNormalizer.stripMetaBlocksForTooLargeErrors(
            List.of(meta, tooLargeError(ApiErrorMessages.TooLargeKind.IMAGE_TOO_LARGE)));

        UserMessage result = findUser(out);
        assertEquals("m1", result.uuid());
        assertEquals(1, result.message().blocks().size());
        assertInstanceOf(TextBlock.class, result.message().blocks().getFirst());
        assertFalse(result.message().blocks().stream().anyMatch(ImageBlock.class::isInstance));
    }

    @Test
    void requestTooLarge_stripsImageAndDocumentFromMetaUser() {
        UserMessage meta = metaUser("m1", new TextBlock("hi"), IMG, DOC);
        var out = RequestMessageNormalizer.stripMetaBlocksForTooLargeErrors(
            List.of(meta, tooLargeError(ApiErrorMessages.TooLargeKind.REQUEST_TOO_LARGE)));

        UserMessage result = findUser(out);
        assertEquals(1, result.message().blocks().size());
        assertInstanceOf(TextBlock.class, result.message().blocks().getFirst());
    }

    @Test
    void promptTooLong_doesNotStripImage_regressionA() {
        // PROMPT_TOO_LONG is intentionally NOT registered in errorToBlockTypes, so

        // strips nothing).
        UserMessage meta = metaUser("m1", new TextBlock("hi"), IMG);
        var out = RequestMessageNormalizer.stripMetaBlocksForTooLargeErrors(
            List.of(meta, MessageFactory.createAssistantAPIErrorMessage("Prompt is too long")));

        UserMessage result = findUser(out);
        assertEquals(2, result.message().blocks().size());
        assertTrue(result.message().blocks().stream().anyMatch(ImageBlock.class::isInstance));
    }

    @Test
    void noSyntheticError_keepsImage() {
        UserMessage meta = metaUser("m1", new TextBlock("hi"), IMG);
        var out = RequestMessageNormalizer.stripMetaBlocksForTooLargeErrors(List.of(meta));

        UserMessage result = findUser(out);
        assertTrue(result.message().blocks().stream().anyMatch(ImageBlock.class::isInstance));
    }

    @Test
    void nonMetaUser_notStripped() {
        UserMessage plain = plainUser("p1", new TextBlock("hi"), IMG);
        var out = RequestMessageNormalizer.stripMetaBlocksForTooLargeErrors(
            List.of(plain, tooLargeError(ApiErrorMessages.TooLargeKind.IMAGE_TOO_LARGE)));

        UserMessage result = findUser(out);
        assertTrue(result.message().blocks().stream().anyMatch(ImageBlock.class::isInstance));
    }

    @Test
    void allStrippedMetaUser_droppedFromList() {
        UserMessage meta = metaUser("m1", IMG); // only an image block
        var out = RequestMessageNormalizer.stripMetaBlocksForTooLargeErrors(
            List.of(meta, tooLargeError(ApiErrorMessages.TooLargeKind.IMAGE_TOO_LARGE)));

        // Only the synthetic error remains; the emptied meta message is dropped.
        assertEquals(1, out.size());
        assertInstanceOf(AssistantMessage.class, out.getFirst());
    }

    // ---- end-to-end via normalizeForApi (wire) ----

    @SuppressWarnings("unchecked")
    @Test
    void normalizeForApi_stripsMetaImageAndExcludesSyntheticError() {
        UserMessage meta = metaUser("m1", new TextBlock("hi"), IMG);
        var wire = RequestMessageNormalizer.normalizeForApi(
            List.of(meta, tooLargeError(ApiErrorMessages.TooLargeKind.IMAGE_TOO_LARGE)),
            false, false);

        assertEquals(1, wire.size());
        assertEquals("user", wire.getFirst().role());
        // image stripped → only text remains, serialized as a plain string
        assertEquals("hi", wire.getFirst().content());
    }

    @SuppressWarnings("unchecked")
    @Test
    void normalizeForApi_promptTooLongKeepsImageInWire() {
        UserMessage meta = metaUser("m1", new TextBlock("hi"), IMG);
        var wire = RequestMessageNormalizer.normalizeForApi(
            List.of(meta, MessageFactory.createAssistantAPIErrorMessage("Prompt is too long")),
            false, false);

        assertEquals(1, wire.size());
        assertInstanceOf(List.class, wire.getFirst().content());
        List<Map<String, Object>> blocks = (List<Map<String, Object>>) wire.getFirst().content();
        assertTrue(blocks.stream().anyMatch(b -> b.get("type") instanceof String type
                && Strings.CS.equals("image", type)));
    }

    @SuppressWarnings("unchecked")
    @Test
    void normalizeForApi_mergesSupplementalPdfDocumentAfterToolResult() {
        AssistantMessage toolUse = assistantToolUse("a1", "toolu_pdf");
        UserMessage toolResult = plainUser("tool-result",
            new ToolResultBlock("toolu_pdf", List.of(new TextBlock("PDF file read")), false));
        var source = JsonNodeFactory.instance.objectNode();
        source.put("type", "base64");
        source.put("media_type", "application/pdf");
        source.put("data", "JVBERg==");
        UserMessage document = metaUser("pdf-document", new DocumentBlock(source));

        var wire = RequestMessageNormalizer.normalizeForApi(
            List.of(toolUse, toolResult, document), false, false, "claude-sonnet-4-6");

        assertEquals(2, wire.size());
        assertEquals("user", wire.getLast().role());
        List<Map<String, Object>> blocks =
            assertInstanceOf(List.class, wire.getLast().content());
        assertEquals(List.of("tool_result", "document"),
            blocks.stream().map(block -> block.get("type")).toList());
        assertEquals(source, blocks.getLast().get("source"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void normalizeForApi_bubblesPostCompactAttachmentsPastOrdinaryUsersUntilAssistant() {
        UserMessage summary = MessageFactory.createUserMessage("summary", false);
        AssistantMessage preservedAssistant = new AssistantMessage(
            "a1", AssistantContent.of(List.of(new TextBlock("OK"))));
        UserMessage command = MessageFactory.createUserMessage(
            "<command-name>/compact</command-name>", false);
        AttachmentMessage restoredFile = new AttachmentMessage(
            "att1", new FileContentAttachment("/tmp/a.txt", "1\talpha"));
        UserMessage nextPrompt = MessageFactory.createUserMessage("continue", false);

        var wire = RequestMessageNormalizer.normalizeForApi(
            List.of(summary, preservedAssistant, command, restoredFile, nextPrompt),
            false, false);

        assertEquals(List.of("user", "assistant", "user", "system"),
            wire.stream().map(StreamingClient.StreamRequest.RequestMessage::role).toList());
        assertEquals(List.of(Map.of("type", "text", "text", "OK")), wire.get(1).content(),
            "released 2.1.197 preserves even text-only assistant content as a block array");
        List<Map<String, Object>> mergedUser =
            (List<Map<String, Object>>) wire.get(2).content();
        assertEquals("<command-name>/compact</command-name>\n", mergedUser.getFirst().get("text"));
        assertEquals("continue", mergedUser.get(1).get("text"));
        assertTrue(Strings.CS.contains(wire.get(3).content().toString(), "/tmp/a.txt"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void normalizeForApi_sonnet46PrependsTrailingPostCompactReminderToCurrentPrompt() {
        UserMessage summary = MessageFactory.createUserMessage("summary", false);
        AssistantMessage preservedAssistant = new AssistantMessage(
            "a1", AssistantContent.of(List.of(new TextBlock("OK"))));
        UserMessage currentPrompt = MessageFactory.createUserMessage("continue", false);
        AttachmentMessage listing = new AttachmentMessage(
            "att1", new FileContentAttachment("/tmp/a.txt", "1\talpha"));

        var wire = RequestMessageNormalizer.normalizeForApi(
            List.of(summary, preservedAssistant, currentPrompt, listing),
            false, false, "claude-sonnet-4-6");

        assertEquals(List.of("user", "assistant", "user"),
            wire.stream().map(StreamingClient.StreamRequest.RequestMessage::role).toList());
        List<Map<String, Object>> currentTurn =
            (List<Map<String, Object>>) wire.get(2).content();
        assertTrue(Strings.CS.contains(currentTurn.getFirst().get("text").toString(), "/tmp/a.txt"));
        assertEquals("continue", currentTurn.getLast().get("text"),
            "2.1.197 renders post-compact reminders before the preserved current prompt");
    }

    @SuppressWarnings("unchecked")
    @Test
    void normalizeForApi_smooshesPlanExitReminderIntoToolResult() {
        AssistantMessage toolUse = assistantToolUse("a1", "toolu_exit");
        UserMessage toolResult = plainUser("u1", new ToolResultBlock(
            "toolu_exit", List.of(new TextBlock(
                "User has approved exiting plan mode. You can now proceed.")), false));
        AttachmentMessage exitReminder = new AttachmentMessage(
            "att1", new PlanModeExitAttachment("/tmp/plan.md", false));

        var wire = RequestMessageNormalizer.normalizeForApi(
            List.of(toolUse, toolResult, exitReminder), false, false, "claude-sonnet-4-6");

        assertEquals(2, wire.size());
        List<Map<String, Object>> blocks =
            (List<Map<String, Object>>) wire.getLast().content();
        assertEquals(1, blocks.size(),
            "released 2.1.197 folds system-reminder siblings into the tool_result");
        assertEquals("tool_result", blocks.getFirst().get("type"));
        assertEquals(
            """
            User has approved exiting plan mode. You can now proceed.

            <system-reminder>
            ## Exited Plan Mode

            You have exited plan mode. You can now make edits, run tools, and take actions.
            </system-reminder>""",
            blocks.getFirst().get("content"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void normalizeForApi_doesNotSmooshRealUserTextIntoToolResult() {
        AssistantMessage toolUse = assistantToolUse("a1", "toolu_1");
        UserMessage toolResult = plainUser("u1", new ToolResultBlock(
            "toolu_1", List.of(new TextBlock("done")), false));
        UserMessage realPrompt = MessageFactory.createUserMessage("continue", false);

        var wire = RequestMessageNormalizer.normalizeForApi(
            List.of(toolUse, toolResult, realPrompt), false, false, "claude-sonnet-4-6");

        List<Map<String, Object>> blocks =
            (List<Map<String, Object>>) wire.getLast().content();
        assertEquals(2, blocks.size());
        assertEquals("done", blocks.getFirst().get("content"));
        assertEquals("continue", blocks.getLast().get("text"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void normalizeForApi_keepsPeerAndInventoryRemindersBesideToolResult() {
        AssistantMessage toolUse = assistantToolUse("a1", "toolu_send");
        UserMessage toolResult = plainUser("u1", new ToolResultBlock(
            "toolu_send", List.of(new TextBlock("queued")), false));
        AttachmentMessage listing = new AttachmentMessage("att-list", new AgentListingDeltaAttachment(
            List.of("general-purpose"), List.of("- general-purpose: General agent (Tools: *)"),
            List.of(), true, true));
        AttachmentMessage peer = new AttachmentMessage("att-peer", new QueuedCommandAttachment(
            "start on task 1", "agent-message", "researcher", true));

        var wire = RequestMessageNormalizer.normalizeForApi(
            List.of(toolUse, toolResult, listing, peer), false, false, "claude-sonnet-4-6");

        assertEquals(2, wire.size());
        List<Map<String, Object>> blocks =
            (List<Map<String, Object>>) wire.getLast().content();
        assertEquals(List.of("tool_result", "text", "text"), blocks.stream()
            .map(block -> block.get("type").toString()).toList());
        assertTrue(Strings.CS.contains(
            blocks.get(1).get("text").toString(), "Available agent types"));
        assertTrue(Strings.CS.contains(blocks.get(2).get("text").toString(),
            "Another Claude session sent a message"));
        assertTrue(Strings.CS.endsWith(
            blocks.get(1).get("text").toString(), "</system-reminder>"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void mergeConsecutiveRequestMessages_keepsNewlineBetweenOrdinaryReminders() {
        var first = new StreamingClient.StreamRequest.RequestMessage("user", List.of(
            Map.of("type", "text", "text", "<system-reminder>\nfirst\n</system-reminder>")));
        var second = new StreamingClient.StreamRequest.RequestMessage("user", List.of(
            Map.of("type", "text", "text", "<system-reminder>\nsecond\n</system-reminder>")));

        var merged = RequestMessageNormalizer.mergeConsecutiveRequestMessages(
            List.of(first, second));
        var blocks = (List<Map<String, Object>>) merged.getFirst().content();

        assertEquals("<system-reminder>\nfirst\n</system-reminder>\n",
            blocks.getFirst().get("text"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void normalizeForApi_mergesRepeatedAssistantResponseIdAcrossRejectedToolTurn() {
        AssistantMessage firstToolUse = new AssistantMessage(
            "assistant-1", AssistantContent.of("msg-retried-response", List.of(
                new ToolUseBlock("toolu-1", "Bash",
                    JsonNodeFactory.instance.objectNode().put("command", "touch /tmp/marker")))));
        UserMessage rejected = plainUser("rejected", new ToolResultBlock(
            "toolu-1", List.of(new TextBlock("rejected")), true));
        UserMessage interrupted = MessageFactory.createUserMessage(
            "[Request interrupted by user for tool use]", false);
        UserMessage retryPrompt = MessageFactory.createUserMessage("HOT_RELOAD_AFTER", false);
        AssistantMessage secondToolUse = new AssistantMessage(
            "assistant-2", AssistantContent.of("msg-retried-response", List.of(
                new ToolUseBlock("toolu-2", "Bash",
                    JsonNodeFactory.instance.objectNode().put("command", "touch /tmp/marker")))));
        UserMessage completed = plainUser("completed", new ToolResultBlock(
            "toolu-2", List.of(new TextBlock("done")), false));

        var wire = RequestMessageNormalizer.normalizeForApi(
            List.of(MessageFactory.createUserMessage("HOT_RELOAD_BEFORE", false),
                firstToolUse, rejected, interrupted, retryPrompt, secondToolUse, completed),
            false, false, "claude-sonnet-4-6");

        assertEquals(List.of("user", "assistant", "user"),
            wire.stream().map(StreamingClient.StreamRequest.RequestMessage::role).toList(),
            "2.1.197 merges assistant fragments sharing one API message.id, then the now-adjacent user turns");
        List<Map<String, Object>> assistantBlocks =
            (List<Map<String, Object>>) wire.get(1).content();
        assertEquals(List.of("toolu-1", "toolu-2"),
            assistantBlocks.stream().map(block -> block.get("id")).toList());
        List<Map<String, Object>> userBlocks =
            (List<Map<String, Object>>) wire.get(2).content();
        assertEquals(List.of("toolu-1", "toolu-2"), userBlocks.stream()
            .filter(block -> Strings.CS.equals("tool_result",
                Objects.toString(block.get("type"), null)))
            .map(block -> block.get("tool_use_id"))
            .toList());
        assertEquals(List.of("[Request interrupted by user for tool use]\n", "HOT_RELOAD_AFTER"),
            userBlocks.stream()
                .filter(block -> Strings.CS.equals("text",
                    Objects.toString(block.get("type"), null)))
                .map(block -> block.get("text"))
                .toList());
    }

    @SuppressWarnings("unchecked")
    @Test
    void normalizeForApi_keepsReminderSiblingForToolReferenceResult() {
        AssistantMessage toolUse = assistantToolUse("a1", "toolu_1");
        UserMessage toolResult = plainUser("u1", new ToolResultBlock(
            "toolu_1", List.of(new ToolReferenceBlock("Read")), false));
        AttachmentMessage exitReminder = new AttachmentMessage(
            "att1", new PlanModeExitAttachment("/tmp/plan.md", false));

        var wire = RequestMessageNormalizer.normalizeForApi(
            List.of(toolUse, toolResult, exitReminder), false, true, "claude-sonnet-4-6");

        List<Map<String, Object>> blocks =
            (List<Map<String, Object>>) wire.getLast().content();
        assertEquals(2, blocks.size(),
            "tool_reference cannot be mixed with reminder content inside tool_result");
        assertEquals("tool_result", blocks.getFirst().get("type"));
        assertEquals("text", blocks.getLast().get("type"));
    }

    private static UserMessage findUser(List<Message> messages) {
        return messages.stream()
            .filter(UserMessage.class::isInstance)
            .map(UserMessage.class::cast)
            .findFirst()
            .orElseThrow();
    }


    @Test
    void normalizeForApi_dropsVirtualMessages() {
        UserMessage virtualUser = new UserMessage("uv", MessageContent.ofText("display only"),
            false, false, null, MessageOrigin.USER, null, Instant.now(), null, null,
            null, null, null, true, null, null);
        AssistantMessage virtualAssistant = new AssistantMessage("av",
            AssistantContent.of(List.of(new TextBlock("display only"))),
            false, null, Instant.now(), null, null, null, null, null, null,
            true, null, null, null);
        UserMessage realUser = plainUser("ur", new TextBlock("real"));

        var wire = RequestMessageNormalizer.normalizeForApi(
            List.of(virtualUser, virtualAssistant, realUser), false, true, "claude-sonnet-4-6");

        assertEquals(1, wire.size(), "both virtual envelopes must be dropped");
        assertEquals("user", wire.getFirst().role());
    }


    @Test
    void normalizeForApi_convertsStandaloneLocalCommandToUserTurn() {
        SystemMessage localCmd = MessageFactory.createCommandInputMessage(
            "<local-command-stdout>ok</local-command-stdout>");

        var wire = RequestMessageNormalizer.normalizeForApi(List.of(localCmd), false, false);

        assertEquals(1, wire.size());
        assertEquals("user", wire.getFirst().role());
        assertEquals("<local-command-stdout>ok</local-command-stdout>", wire.getFirst().content());
    }

    


    @SuppressWarnings("unchecked")
    @Test
    void normalizeForApi_mergesLocalCommandIntoPrecedingUserTurn() {
        UserMessage echo = plainUser("u1", new TextBlock("/cost"));
        SystemMessage localCmd = MessageFactory.createCommandInputMessage(
            "<local-command-stdout>Total cost: $0.01</local-command-stdout>");

        var wire = RequestMessageNormalizer.normalizeForApi(List.of(echo, localCmd), false, false);

        assertEquals(1, wire.size(), "echo + local_command stdout must merge into one user turn");
        assertEquals("user", wire.getFirst().role());
        assertInstanceOf(List.class, wire.getFirst().content());
        List<Map<String, Object>> blocks = (List<Map<String, Object>>) wire.getFirst().content();
        String joined = blocks.stream()
            .filter(b -> Strings.CS.equals("text", (String) b.get("type")))
            .map(b -> String.valueOf(b.get("text")))
            .reduce("", (a, b) -> a + b);
        assertTrue(Strings.CS.contains(joined, "/cost"));
        assertTrue(Strings.CS.contains(joined, "Total cost: $0.01"));
    }

    /**
     * Regression guard for the {@code default} branch: non-local_command
     * system messages (e.g. a plain info/warning notice) must still be
     * dropped entirely, exactly like before this change.
     */
    @Test
    void normalizeForApi_dropsNonLocalCommandSystemMessage() {
        SystemMessage notice = new SystemMessage(
            "s1", "warning", "warning", "some notice", null, Instant.now(), null, null, null);
        UserMessage realUser = plainUser("ur", new TextBlock("real"));

        var wire = RequestMessageNormalizer.normalizeForApi(List.of(notice, realUser), false, false);

        assertEquals(1, wire.size());
        assertEquals("user", wire.getFirst().role());
        assertEquals("real", wire.getFirst().content());
    }
}
