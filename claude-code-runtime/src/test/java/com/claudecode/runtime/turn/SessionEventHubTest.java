package com.claudecode.runtime.turn;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.message.SystemMessage;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.Test;

class SessionEventHubTest {

    @Test
    void resetReplayPreventsPreviousLogicalSessionFromSeedingLateSubscriber() {
        List<String> calls = new ArrayList<>();
        SessionEventHub hub = new SessionEventHub(
            new RecordingSink("primary", new ArrayList<>()), _ -> {});
        hub.onTurnStart(UserInput.of("old", "old", null, "default"));
        hub.onMessage(new SDKMessage.System(
            new SystemMessage("old", "status", "info", "old output")));
        hub.onTurnComplete(new TurnOutcome(
            false, false, 1L, null, null, null, "default"));

        hub.resetReplay();
        hub.subscribe(new RecordingSink("late", calls));

        assertTrue(calls.isEmpty());
        hub.onTurnStart(UserInput.of("new", "new", null, "default"));
        assertEquals(List.of("late:start"), calls);
    }

    @Test
    void fansOutSemanticEventsAfterPrimarySink() throws Exception {
        List<String> calls = new ArrayList<>();
        RecordingSink primary = new RecordingSink("primary", calls);
        RecordingSink remote = new RecordingSink("remote", calls);
        SessionEventHub hub = new SessionEventHub(primary, _ -> calls.add("failure"));

        AutoCloseable subscription = hub.subscribe(remote);
        UserInput input = UserInput.of("hello", "hello", null, "default");
        SDKMessage message = new SDKMessage.System(
            new SystemMessage("m1", "status", "info", "ready"));
        TurnOutcome outcome = new TurnOutcome(
            false, false, 12L, null, null, null, "default");

        hub.onTurnStart(input);
        hub.onMessage(message);
        hub.onTurnComplete(outcome);
        hub.onIdle();
        subscription.close();
        hub.onError(new IllegalStateException("after unsubscribe"), false);

        assertEquals(List.of(
            "primary:start", "remote:start",
            "primary:message", "remote:message",
            "primary:complete", "remote:complete",
            "primary:idle", "remote:idle",
            "primary:error"), calls);
    }

    @Test
    void isolatesObserverFailureWithoutHidingPrimaryFailure() {
        List<String> calls = new ArrayList<>();
        RuntimeException remoteFailure = new RuntimeException("remote down");
        SessionEventHub hub = new SessionEventHub(
            new RecordingSink("primary", calls),
            failure -> calls.add("failure:" + failure.getMessage()));
        hub.subscribe(new ThrowingSink(remoteFailure));
        hub.subscribe(new RecordingSink("second", calls));

        hub.onIdle();

        assertEquals(List.of(
            "primary:idle", "failure:remote down", "second:idle"), calls);
    }

    @Test
    void isolatesPrimaryFailureAndStillNotifiesObservers() {
        List<String> calls = new ArrayList<>();
        RuntimeException primaryFailure = new RuntimeException("render blew up");
        SessionEventHub hub = new SessionEventHub(
            new ThrowingSink(primaryFailure),
            failure -> calls.add("failure:" + failure.getMessage()));
        hub.subscribe(new RecordingSink("remote", calls));

        assertDoesNotThrow(() -> hub.onMessage(new SDKMessage.System(
            new SystemMessage("m1", "status", "info", "ready"))));

        assertEquals(List.of("failure:render blew up", "remote:message"), calls);
    }

    @Test
    void isolatesFailureReporterFailureAndContinuesFanOut() {
        List<String> calls = new ArrayList<>();
        SessionEventHub hub = new SessionEventHub(
            new RecordingSink("primary", calls),
            _ -> { throw new RuntimeException("reporting failed"); });
        hub.subscribe(new ThrowingSink(new RuntimeException("remote down")));
        hub.subscribe(new RecordingSink("second", calls));

        hub.onIdle();

        assertEquals(List.of("primary:idle", "second:idle"), calls);
    }

    @Test
    void replaysLatestTurnInOrderToLateObserver() {
        List<String> calls = new ArrayList<>();
        SessionEventHub hub = new SessionEventHub(
            new RecordingSink("primary", calls), _ -> calls.add("failure"));
        UserInput input = UserInput.of("hello", "hello", null, "default");
        SDKMessage message = new SDKMessage.System(
            new SystemMessage("m1", "status", "info", "ready"));
        TurnOutcome outcome = new TurnOutcome(
            false, false, 12L, null, null, null, "default");

        hub.onTurnStart(input);
        hub.onMessage(message);
        hub.onTurnComplete(outcome);
        hub.subscribe(new RecordingSink("late", calls));

        assertEquals(List.of(
            "primary:start", "primary:message", "primary:complete",
            "late:start", "late:message", "late:complete"), calls);
    }

    @Test
    void nextTurnReplacesLateObserverReplayBuffer() {
        List<String> calls = new ArrayList<>();
        SessionEventHub hub = new SessionEventHub(
            new RecordingSink("primary", calls), _ -> calls.add("failure"));

        hub.onTurnStart(UserInput.of("first", "first", null, "default"));
        hub.onMessage(new SDKMessage.System(
            new SystemMessage("m1", "status", "info", "first")));
        hub.onTurnStart(UserInput.of("second", "second", null, "default"));
        hub.subscribe(new RecordingSink("late", calls));

        assertEquals(List.of(
            "primary:start", "primary:message", "primary:start", "late:start"), calls);
    }

    @Test
    void subscribeWithoutReplaySkipsRecordedPrefix() {
        List<String> calls = new ArrayList<>();
        SessionEventHub hub = new SessionEventHub(
            new RecordingSink("primary", calls), _ -> calls.add("failure"));
        hub.onTurnStart(UserInput.of("old", "old", null, "default"));
        hub.onMessage(new SDKMessage.System(
            new SystemMessage("m1", "status", "info", "old")));
        hub.onTurnComplete(new TurnOutcome(
            false, false, 12L, null, null, null, "default"));

        AutoCloseable subscription = hub.subscribe(new RecordingSink("scoped", calls), false);
        assertTrue(calls.stream().noneMatch(call -> Strings.CS.startsWith(call, "scoped:")));

        hub.onIdle();
        assertEquals(List.of("scoped:idle"), calls.stream()
            .filter(call -> Strings.CS.startsWith(call, "scoped:")).toList());
        assertDoesNotThrow(subscription::close);
    }

    @Test
    void subscribeWithoutReplayStillReceivesLiveEventsInOrder() {
        List<String> calls = new ArrayList<>();
        SessionEventHub hub = new SessionEventHub(
            new RecordingSink("primary", calls), _ -> {});
        AutoCloseable subscription = hub.subscribe(new RecordingSink("scoped", calls), false);

        hub.onTurnStart(UserInput.of("live", "live", null, "default"));
        hub.onMessage(new SDKMessage.System(
            new SystemMessage("m1", "status", "info", "live")));
        hub.onTurnComplete(new TurnOutcome(
            false, false, 1L, null, null, null, "default"));

        assertEquals(List.of(
            "scoped:start", "scoped:message", "scoped:complete"), calls.stream()
            .filter(call -> Strings.CS.startsWith(call, "scoped:")).toList());
        assertDoesNotThrow(subscription::close);
    }

    private static class RecordingSink implements SessionSink {
        private final String name;
        private final List<String> calls;

        private RecordingSink(String name, List<String> calls) {
            this.name = name;
            this.calls = calls;
        }

        @Override public void onTurnStart(UserInput input) { calls.add(name + ":start"); }
        @Override public void onMessage(SDKMessage msg) { calls.add(name + ":message"); }
        @Override public void onError(Throwable error, boolean userCancel) { calls.add(name + ":error"); }
        @Override public void onTurnComplete(TurnOutcome outcome) { calls.add(name + ":complete"); }
        @Override public void onIdle() { calls.add(name + ":idle"); }
    }

    private static final class ThrowingSink implements SessionSink {
        private final RuntimeException failure;

        private ThrowingSink(RuntimeException failure) {
            this.failure = failure;
        }

        @Override public void onTurnStart(UserInput input) { throw failure; }
        @Override public void onMessage(SDKMessage msg) { throw failure; }
        @Override public void onError(Throwable error, boolean userCancel) { throw failure; }
        @Override public void onTurnComplete(TurnOutcome outcome) { throw failure; }
        @Override public void onIdle() { throw failure; }
    }
}
