package com.claudecode.runtime.query;

import com.claudecode.runtime.turn.SessionSink;
import com.claudecode.runtime.turn.TurnOutcome;
import com.claudecode.runtime.turn.UserInput;

import com.claudecode.core.message.SDKMessage;
import java.util.ArrayList;
import java.util.List;

/**
 * Test double that records the {@link SessionSink} event stream. It imports no
 * Lanterna type — that it compiles and satisfies the contract is the proof that
 * {@code SessionSink} is front-end-agnostic (the "second consumer" the plan asks
 * for, standing in for a future WebUI/API adapter).
 */
final class RecordingSink implements SessionSink {

    /** Ordered event names: "start" | "message" | "error" | "complete" | "idle". */
    final List<String> events = new ArrayList<>();
    final List<UserInput> starts = new ArrayList<>();
    final List<SDKMessage> messages = new ArrayList<>();
    final List<TurnOutcome> completions = new ArrayList<>();
    Throwable lastError;
    boolean lastUserCancel;
    int idleCount;

    @Override public void onTurnStart(UserInput input) { events.add("start"); starts.add(input); }
    @Override public void onMessage(SDKMessage msg)     { events.add("message"); messages.add(msg); }
    @Override public void onError(Throwable e, boolean userCancel) {
        events.add("error"); lastError = e; lastUserCancel = userCancel;
    }
    @Override public void onTurnComplete(TurnOutcome outcome) { events.add("complete"); completions.add(outcome); }
    @Override public void onIdle() { events.add("idle"); idleCount++; }

    TurnOutcome lastCompletion() { return completions.getLast(); }
}
