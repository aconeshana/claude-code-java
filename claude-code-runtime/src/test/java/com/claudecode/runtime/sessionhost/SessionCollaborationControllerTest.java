package com.claudecode.runtime.sessionhost;

import org.apache.commons.lang3.Strings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.runtime.turn.SessionEventHub;
import com.claudecode.runtime.turn.SessionSink;
import com.claudecode.runtime.turn.TurnOutcome;
import com.claudecode.runtime.turn.UserInput;
import org.junit.jupiter.api.Assertions;
import com.claudecode.core.message.SDKMessage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class SessionCollaborationControllerTest {

    @Test
    void defaultsOffAndKeepsExactlyOneChannelPerSession() throws Exception {
        SessionHostSession first = session("session-1");
        SessionHostSession second = session("session-2");
        SessionHostRegistry registry = registry(first, second);
        registry.activateLocal(first);
        SessionCollaborationController controller =
            new SessionCollaborationController(registry);
        List<SessionCollaborationController.Change> changes = new ArrayList<>();
        try (AutoCloseable ignored = controller.subscribe(changes::add)) {
            controller.replaceAvailableChannels(List.of("slack", "feishu", "feishu"));

            assertEquals(List.of("feishu", "slack"), controller.availableChannels());
            assertFalse(controller.current().enabled());
            assertEquals("Off", controller.current().displayValue());

            controller.selectCurrent("feishu");
            assertEquals("feishu", controller.current().channel());
            controller.selectCurrent("slack");
            assertEquals("slack", controller.current().channel());

            registry.activateLocal(second);
            assertFalse(controller.current().enabled(), "a new session must default to off");
            registry.activateLocal(first);
            assertEquals("slack", controller.current().channel(),
                "returning to a session restores its own collaboration selection");

            controller.disableCurrent();
            assertFalse(controller.current().enabled());
        }

        assertTrue(changes.stream().anyMatch(change -> change.enabled()
            && Strings.CS.equals("feishu", change.channel())));
        assertTrue(changes.stream().anyMatch(change -> !change.enabled()
            && Strings.CS.equals("session-1", change.sessionId())));
    }

    @Test
    void rejectsChannelsNotReportedByCcConnect() {
        SessionHostSession session = session("session-1");
        SessionHostRegistry registry = registry(session);
        registry.activateLocal(session);
        SessionCollaborationController controller =
            new SessionCollaborationController(registry);
        controller.replaceAvailableChannels(List.of("feishu"));

        Assertions.assertThrows(IllegalArgumentException.class,
            () -> controller.selectCurrent("slack"));
    }

    private static SessionHostRegistry registry(SessionHostSession... sessions) {
        return new SessionHostRegistry(new SessionHostRegistry.Activator() {
            @Override public CompletableFuture<SessionHostSession> activate(SessionOpenRequest request) {
                for (SessionHostSession session : sessions) {
                    if (session.info().id().equals(request.requestedSessionId())) {
                        return CompletableFuture.completedFuture(session);
                    }
                }
                return CompletableFuture.failedFuture(new IllegalArgumentException("missing session"));
            }

            @Override public List<SessionHostInfo> list() {
                return Arrays.stream(sessions).map(SessionHostSession::info).toList();
            }
        });
    }

    private static SessionHostSession session(String id) {
        SessionSink sink = new SessionSink() {
            @Override public void onTurnStart(UserInput input) {}
            @Override public void onMessage(SDKMessage message) {}
            @Override public void onError(Throwable error, boolean userCancel) {}
            @Override public void onTurnComplete(TurnOutcome outcome) {}
            @Override public void onIdle() {}
        };
        return new SessionHostSession(new SessionHostInfo(id, "/project", "", 0, null, ""),
            new SessionEventHub(sink, _ -> {}),
            _ -> CompletableFuture.completedFuture(null));
    }
}
