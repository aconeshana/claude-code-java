package com.claudecode.cli;

import com.claudecode.core.engine.StreamingClient;
import com.claudecode.core.engine.SideQuestionContext;
import com.claudecode.core.engine.SessionCostState;
import com.claudecode.core.message.Usage;
import com.claudecode.runtime.query.QuerySession;
import java.lang.reflect.Proxy;
import java.util.Iterator;
import java.util.List;
import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;


class CliInteractiveSessionLauncherTest {

    @BeforeEach
    void resetCostState() {
        SessionCostState.get().reset();
    }

    @Test
    void sideQuestionPreservesCacheKeyFieldsAndOnlyAddsAnEphemeralSuffix() {
        var tool = new StreamingClient.StreamRequest.ToolDef("Read", "read", null);
        var parent = new StreamingClient.StreamRequest(
            "claude-sonnet-4-6", 32_000, "system",
            List.of(new StreamingClient.StreamRequest.RequestMessage("user", "main")),
            true, List.of(tool), null, "high", "fallback", null, null, null, null,
            true, "session-1", null, false, "user", null, 12_000);

        var fork = CliInteractiveSessionLauncher.forkSideQuestionRequest(parent, "wrapped");

        assertEquals(parent.systemPrompt(), fork.systemPrompt());
        assertEquals(parent.tools(), fork.tools());
        assertEquals(parent.model(), fork.model());
        assertEquals(parent.effort(), fork.effort());
        assertEquals(parent.thinkingEnabled(), fork.thinkingEnabled());
        assertEquals(parent.thinkingBudgetTokens(), fork.thinkingBudgetTokens());
        assertEquals(parent.messages(), fork.messages().subList(0, parent.messages().size()));
        assertEquals("wrapped", fork.messages().getLast().content());
        assertEquals("side_question", fork.querySource());
        assertTrue(fork.skipCacheWrite());
        assertNull(fork.jsonSchema());
        assertNotNull(fork.abortController());
    }

    @Test
    void sideQuestionPrependsProcessHistoryAsAlternatingConversationTurns() {
        var parent = new StreamingClient.StreamRequest(
            "claude-sonnet-4-6", 32_000, "system",
            List.of(new StreamingClient.StreamRequest.RequestMessage("user", "main")),
            true);

        var fork = CliInteractiveSessionLauncher.forkSideQuestionRequest(
            parent, "wrapped current", List.of(
                new SideQuestionContext.Exchange("previous question", "previous answer"),
                new SideQuestionContext.Exchange("second question", "second answer")));

        assertEquals(List.of("user", "user", "assistant", "user", "assistant", "user"),
            fork.messages().stream().map(StreamingClient.StreamRequest.RequestMessage::role).toList());
        assertEquals(List.of("main", "previous question", "previous answer",
                "second question", "second answer", "wrapped current"),
            fork.messages().stream().map(StreamingClient.StreamRequest.RequestMessage::content).toList());
    }

    @Test
    void cacheSafeSideQuestionContributesToReleasedCumulativeApiMetrics() {
        Usage finalUsage = new Usage(11, 5, 0, 0);
        StreamingClient client = new StreamingClient() {
            @Override public Iterator<StreamingEvent> createStream(StreamRequest request) {
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                return List.<StreamingEvent>of(
                    new StreamingEvent.MessageStartEvent(
                        "msg-1", "claude-served-side", List.of(),
                        new Usage(11, 0, 0, 0)),
                    new StreamingEvent.ContentBlockDeltaEvent(0, "text_delta", "answer"),
                    new StreamingEvent.MessageDeltaEvent("end_turn", finalUsage),
                    new StreamingEvent.MessageStopEvent()).iterator();
            }

            @Override public String getModel() { return "claude-requested-side"; }
        };
        StreamingClient.StreamRequest parent = new StreamingClient.StreamRequest(
            "claude-requested-side", 1_024, "system",
            List.of(new StreamingClient.StreamRequest.RequestMessage("user", "main")), true);
        QuerySession.Forks forks = (QuerySession.Forks) Proxy.newProxyInstance(
            QuerySession.Forks.class.getClassLoader(), new Class<?>[]{QuerySession.Forks.class},
            (_, method, _) -> Strings.CS.equals(
                method.getName(), "getLastCacheSafeForkRequest")
                ? parent : null);
        QuerySession session = (QuerySession) Proxy.newProxyInstance(
            QuerySession.class.getClassLoader(), new Class<?>[]{QuerySession.class},
            (_, method, _) -> Strings.CS.equals(method.getName(), "forks") ? forks : null);

        String answer = CliInteractiveSessionLauncher.runSideQuestion(
            session, null, client, parent.model(), () -> false,
            null, null, "wrapped");

        assertEquals("answer", answer);
        assertEquals(finalUsage,
            SessionCostState.get().usageByModel().get("claude-served-side"));
        assertTrue(SessionCostState.get().apiDurationMs() >= 1L);
    }
}
