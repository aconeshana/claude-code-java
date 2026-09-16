package com.claudecode.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.message.UserMessage;
import com.claudecode.core.metrics.SessionMetricsSnapshot;
import com.claudecode.runtime.sessionhost.SessionHostInfo;
import com.claudecode.runtime.sessionhost.SessionHostRegistry;
import com.claudecode.runtime.sessionhost.SessionHostSession;
import com.claudecode.runtime.sessionhost.SessionOpenRequest;
import com.claudecode.runtime.turn.SessionEventHub;
import com.claudecode.runtime.turn.SessionSink;
import com.claudecode.runtime.turn.TurnOutcome;
import com.claudecode.runtime.turn.UserInput;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/** Ledger lifecycle around the port and registry, without the HTTP layer. */
class ContextTimelineLedgerTest {

    private final SessionHostRegistry registry = new SessionHostRegistry(
        new SessionHostRegistry.Activator() {
            @Override public CompletableFuture<SessionHostSession> activate(SessionOpenRequest request) {
                throw new UnsupportedOperationException("not used by these tests");
            }
            @Override public List<SessionHostInfo> list() {
                return List.of();
            }
        });

    @Test
    void activatingAnotherTuiSessionForgetsThePreviousLedgerButKeepsHeadlessOnes() {
        MultiPort port = new MultiPort();
        port.rows.put("tui-a", List.of(user("a")));
        port.rows.put("tui-b", List.of(user("b")));
        port.rows.put("headless", List.of(user("h")));
        ContextTimelineLedger ledger = new ContextTimelineLedger(registry, port);

        ledger.attach(session("headless"));
        SessionHostSession a = session("tui-a");
        registry.activateLocal(a);
        ledger.activated(a);
        assertThat(ledger.timeline("tui-a")).isPresent();

        SessionHostSession b = session("tui-b");
        registry.activateLocal(b);
        ledger.activated(b);
        // tui-a is no longer live anywhere: its ledger is gone, not resurrected.
        port.rows.remove("tui-a");
        assertThat(ledger.timeline("tui-a")).isEmpty();
        assertThat(ledger.timeline("tui-b")).isPresent();
        assertThat(ledger.timeline("headless")).isPresent();
        ledger.close();
    }

    @Test
    void anEmptySnapshotOfAFoldedSessionIsSkippedAsACompactionMidpoint() {
        MultiPort port = new MultiPort();
        List<Message> rows = new ArrayList<>(List.of(user("one"), user("two")));
        port.rows.put("tui-a", rows);
        ContextTimelineLedger ledger = new ContextTimelineLedger(registry, port);
        SessionHostSession a = session("tui-a");
        registry.activateLocal(a);
        ledger.activated(a);
        assertThat(ledger.timeline("tui-a").orElseThrow().path("humanInputs").asInt()).isEqualTo(2);

        port.rows.put("tui-a", List.of());
        assertThat(ledger.timeline("tui-a").orElseThrow().path("humanInputs").asInt()).isEqualTo(2);
        assertThat(ledger.timeline("tui-a").orElseThrow().path("counts").path("prunes").asInt()).isZero();

        port.rows.put("tui-a", rows);
        assertThat(ledger.timeline("tui-a").orElseThrow().path("humanInputs").asInt()).isEqualTo(2);
        ledger.close();
    }

    private static UserMessage user(String text) {
        return new UserMessage(UUID.randomUUID().toString(), MessageContent.ofText(text), false,
            false, null, null, null, Instant.now(), null, null);
    }

    private static SessionHostSession session(String id) {
        SessionEventHub hub = new SessionEventHub(new SessionSink() {
            @Override public void onTurnStart(UserInput input) {}
            @Override public void onMessage(SDKMessage message) {}
            @Override public void onError(Throwable error, boolean userCancel) {}
            @Override public void onTurnComplete(TurnOutcome outcome) {}
            @Override public void onIdle() {}
        }, _ -> {});
        return new SessionHostSession(new SessionHostInfo(id, "/work", id, 1, Instant.now(), "main"),
            hub, _ -> CompletableFuture.completedFuture(null));
    }

    /** A port double whose live set is exactly the ids it holds rows for. */
    private static final class MultiPort implements GatewaySessionContextPort {
        final java.util.Map<String, List<Message>> rows = new java.util.HashMap<>();

        @Override public Optional<List<Message>> messages(String sessionId) {
            return Optional.ofNullable(rows.get(sessionId));
        }

        @Override public Optional<SessionMetricsSnapshot> metrics(String sessionId) {
            return Optional.empty();
        }

        @Override public List<LiveSession> liveSessions() {
            return rows.keySet().stream()
                .map(id -> new LiveSession(id, id, "/work", Instant.now())).toList();
        }
    }
}
