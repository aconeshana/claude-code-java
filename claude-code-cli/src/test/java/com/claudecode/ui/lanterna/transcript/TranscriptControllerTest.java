package com.claudecode.ui.lanterna.transcript;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.claudecode.cli.CliInteractiveSessionAdapter;
import com.claudecode.core.message.AssistantContent;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.ProgressMessage;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.message.SystemMessage;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.UserMessage;
import org.junit.jupiter.api.Assertions;

import java.nio.file.StandardOpenOption;
import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

/** Pure teammate-transcript mapping contract. */
class TranscriptControllerTest {

    @Test
    void mapsRenderableConversationMessagesToSdkEvents() {
        UserMessage user = new UserMessage("u", MessageContent.ofText("hello"));
        AssistantMessage assistant = new AssistantMessage("a",
            AssistantContent.of(List.of(new TextBlock("ok"))));
        SystemMessage system = new SystemMessage("s", "info", "info", "notice");

        assertSame(user, assertInstanceOf(SDKMessage.User.class,
            TranscriptController.toSdkMessage(user)).message());
        assertSame(assistant, assertInstanceOf(SDKMessage.Assistant.class,
            TranscriptController.toSdkMessage(assistant)).message());
        assertSame(system, assertInstanceOf(SDKMessage.System.class,
            TranscriptController.toSdkMessage(system)).message());
    }

    @Test
    void skipsNonConversationProgressMessages() {
        assertNull(TranscriptController.toSdkMessage(new ProgressMessage("p", "working")));
    }

    @Test
    void loadsOrdinaryLocalAgentSidechainForTranscriptView(@TempDir Path dir) throws Exception {
        String agentId = "a1234567890abcdef";
        Path transcript = dir.resolve("agent-" + agentId + ".jsonl");
        Files.writeString(transcript,
            """
            {"type":"user","uuid":"u1","parentUuid":null,"timestamp":"2026-01-01T00:00:00Z","isSidechain":true,"agentId":"a1234567890abcdef","message":{"role":"user","content":"inspect"}}
            {"type":"assistant","uuid":"a1","parentUuid":"u1","timestamp":"2026-01-01T00:00:01Z","isSidechain":true,"agentId":"a1234567890abcdef","message":{"id":"m1","type":"message","role":"assistant","model":"test","content":[{"type":"text","text":"done"}],"stop_reason":"end_turn","usage":{"input_tokens":1,"output_tokens":1}}}
            """);

        List<Message> messages = TranscriptController.loadAgentTranscript(
            transcript, agentId, new CliInteractiveSessionAdapter());

        assertEquals(2, messages.size());
        assertInstanceOf(UserMessage.class, messages.getFirst());
        assertInstanceOf(AssistantMessage.class, messages.get(1));
    }

    @Test
    void localAgentTranscriptStampChangesAsSidechainGrows(@TempDir Path dir) throws Exception {
        Path transcript = dir.resolve("agent-live.jsonl");
        assertEquals(-1L, TranscriptController.transcriptStamp(transcript));
        Files.writeString(transcript, "first\n");
        long first = TranscriptController.transcriptStamp(transcript);
        Files.writeString(transcript, "second\n", StandardOpenOption.APPEND);

        long second = TranscriptController.transcriptStamp(transcript);

        Assertions.assertNotEquals(first, second);
    }
}
