package com.claudecode.runtime.sessionlink;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.core.message.AssistantContent;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.ThinkingBlock;
import com.claudecode.core.message.ToolResultBlock;
import com.claudecode.core.message.ToolUseBlock;
import com.claudecode.core.message.UserMessage;
import com.claudecode.core.message.Usage;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.runtime.turn.TurnOutcome;
import com.claudecode.runtime.turn.UserInput;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.apache.commons.lang3.Strings;

class SessionLinkEventSinkTest {

    @Test
    void projectsAssistantAndToolBlocksWithoutTerminalRendering() {
        List<SessionLinkFrame> frames = new ArrayList<>();
        SessionLinkEventSink sink = new SessionLinkEventSink("session-7", frames::add);
        var input = JsonUtils.getMapper().createObjectNode().put("command", "pwd");

        sink.onTurnStart(UserInput.of("hello", "hello", Map.of(), "default"));
        sink.onMessage(new SDKMessage.StreamRequestStart("claude-opus-4-6", 3));
        sink.onMessage(new SDKMessage.Assistant(new AssistantMessage("a1",
            AssistantContent.of("m1", List.of(
                new ThinkingBlock("checking", null),
                new TextBlock("working"),
                new ToolUseBlock("tool-1", "Bash", input)))), Usage.EMPTY));
        sink.onMessage(new SDKMessage.User(new UserMessage("u1",
            MessageContent.ofBlocks(List.of(new ToolResultBlock("tool-1",
                List.of(new TextBlock("/project")), false))))));

        assertEquals(List.of("turn.started", "turn.model", "output.thinking", "output.text",
            "tool.started", "tool.completed"), frames.stream().map(SessionLinkFrame::name).toList());
        assertEquals("hello", frames.getFirst().payload().path("display_text").asText());
        assertEquals("tui", frames.getFirst().payload().path("origin").asText());
        assertEquals("default", frames.getFirst().payload().path("permission_mode").asText());
        assertEquals("claude-opus-4-6", frames.get(1).payload().path("model").asText());
        assertEquals("Bash", frames.get(4).payload().path("name").asText());
        assertEquals("/project", frames.get(5).payload().path("result").asText());
        assertTrue(frames.get(5).payload().path("success").asBoolean());
    }

    @Test
    void turnStartedCarriesRemoteInputOrigin() {
        List<SessionLinkFrame> frames = new ArrayList<>();
        SessionLinkEventSink sink = new SessionLinkEventSink("session-remote", frames::add);

        sink.onTurnStart(UserInput.of("from Feishu", "from Feishu", Map.of(), "default")
            .withInputOrigin("remote"));

        assertEquals("remote", frames.getFirst().payload().path("origin").asText());
    }

    @Test
    void emitsExactlyOneTerminalEventEvenWhenSdkResultAlreadyArrived() {
        List<SessionLinkFrame> frames = new ArrayList<>();
        SessionLinkEventSink sink = new SessionLinkEventSink("session-8", frames::add);
        sink.onTurnStart(UserInput.of("hello", "hello", Map.of(), "default"));
        sink.onMessage(result("done"));
        sink.onTurnComplete(new TurnOutcome(false, false, 8L, null, null, null, "default"));

        List<SessionLinkFrame> completed = frames.stream()
            .filter(frame -> Strings.CS.equals("turn.completed", frame.name())).toList();
        assertEquals(1, completed.size());
        assertEquals("done", completed.getFirst().payload().path("content").asText());
        assertTrue(completed.getFirst().payload().path("done").asBoolean());
        assertFalse(completed.getFirst().payload().path("metadata").has("fallback"));
    }

    @Test
    void keepsTurnFailureRecoverableAndStillPublishesCompletion() {
        List<SessionLinkFrame> frames = new ArrayList<>();
        SessionLinkEventSink sink = new SessionLinkEventSink("session-error", frames::add);

        sink.onTurnStart(UserInput.of("retry", "retry", Map.of(), "default"));
        sink.onError(new IllegalStateException("network timeout"), false);
        sink.onTurnComplete(new TurnOutcome(false, false, 12L, null, null, null, "default"));

        assertEquals(List.of("turn.started", "session.error", "turn.completed"),
            frames.stream().map(SessionLinkFrame::name).toList());
        assertEquals("network timeout", frames.get(1).payload().path("message").asText());
        assertTrue(frames.get(2).payload().path("done").asBoolean());
        assertTrue(frames.get(2).payload().path("metadata").path("fallback").asBoolean());
    }

    @Test
    void userCancellationCompletesTurnWithoutPublishingSessionError() {
        List<SessionLinkFrame> frames = new ArrayList<>();
        SessionLinkEventSink sink = new SessionLinkEventSink("session-cancel", frames::add);

        sink.onTurnStart(UserInput.of("stop", "stop", Map.of(), "default"));
        sink.onError(new IllegalStateException("Request interrupted"), true);
        sink.onTurnComplete(new TurnOutcome(true, false, 5L, null, null, null, "default"));

        assertEquals(List.of("turn.started", "turn.completed"),
            frames.stream().map(SessionLinkFrame::name).toList());
        assertTrue(frames.getLast().payload().path("metadata").path("user_cancel").asBoolean());
    }

    private static SDKMessage.Result result(String text) {
        return new SDKMessage.Result("success", List.of(), Usage.EMPTY, Map.of(), Map.of(),
            "session-8", 0, List.of(), "off", null, 1, 1, 1, 1, 1, 1,
            "end_turn", "r1", text, false, List.of());
    }
}
