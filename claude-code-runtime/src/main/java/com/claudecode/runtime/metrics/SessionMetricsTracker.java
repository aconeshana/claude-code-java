package com.claudecode.runtime.metrics;

import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.engine.TranscriptSink;
import com.claudecode.core.message.Usage;
import com.claudecode.core.metrics.SessionMetricsEvent;
import com.claudecode.core.metrics.SessionMetricsSnapshot;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Thread-safe durable fold for session metrics. */
@Explanation("Projects persisted session metrics for the built-in HUD")
public final class SessionMetricsTracker {
    private final Supplier<TranscriptSink> sinkSupplier;
    private final LongSupplier clock;

    private String sessionId;
    private long nextSeq;
    private boolean complete;
    private boolean started;
    private long currentTurn;
    private String currentTurnId;
    private long currentStep;
    private OpenStep openStep;
    private final Map<String, Long> openTools = new HashMap<>();
    private final Set<Long> countedTurns = new HashSet<>();

    private long steps;
    private long llmMs;
    private long toolMs;
    private long ttftMs;
    private long ttftSteps;
    private long decodeMs;
    private long decodeTokens;
    private long uncachedInputTokens;
    private long outputTokens;
    private long cacheWriteTokens;
    private long cacheReadTokens;

    public SessionMetricsTracker(String sessionId, Supplier<TranscriptSink> sinkSupplier) {
        this(sessionId, sinkSupplier, System::currentTimeMillis);
    }

    SessionMetricsTracker(String sessionId, Supplier<TranscriptSink> sinkSupplier,
                          LongSupplier clock) {
        this.sessionId = sessionId;
        this.sinkSupplier = sinkSupplier;
        this.clock = clock;
        this.complete = true;
    }

    public synchronized void startFresh(String newSessionId) {
        clear(newSessionId, true);
        emit(SessionMetricsEvent.Kind.SESSION_START, null, 0, 0, Usage.EMPTY, false);
    }

    public synchronized void ensureStarted() {
        if (!started) startFresh(sessionId);
    }

    public synchronized void restore(String restoredSessionId,
                                     List<SessionMetricsEvent> events,
                                     List<String> transcriptTurnIds) {
        clear(restoredSessionId, false);
        boolean transcriptHasTurns = transcriptTurnIds != null && !transcriptTurnIds.isEmpty();
        if (events == null || events.isEmpty()) {
            complete = !transcriptHasTurns;
            if (complete) {
                emit(SessionMetricsEvent.Kind.SESSION_START, null, 0, 0,
                    Usage.EMPTY, false);
            }
            return;
        }
        long expected = 0;
        for (SessionMetricsEvent event : events) {
            if (event.schemaVersion() != SessionMetricsEvent.SCHEMA_VERSION
                    || !restoredSessionId.equals(event.sessionId())
                    || event.seq() != expected++) {
                complete = false;
                return;
            }
            apply(event);
            nextSeq = expected;
        }
        complete = started;
        if (complete && transcriptHasTurns) {
            Set<String> measuredTurnIds = new HashSet<>();
            for (SessionMetricsEvent event : events) {
                if (event.kind() == SessionMetricsEvent.Kind.TURN_START
                        && event.turnId() != null) measuredTurnIds.add(event.turnId());
            }
            complete = measuredTurnIds.containsAll(transcriptTurnIds);
        }
        if (!complete) return;
        if (openStep != null) {
            endStep(true);
        }
        if (currentTurnId != null) endTurn(true);
    }

    public synchronized void beginTurn(String turnId) {
        ensureStarted();
        if (currentTurnId != null) endTurn(false);
        currentTurn += 1;
        currentStep = 0;
        currentTurnId = turnId;
        emit(SessionMetricsEvent.Kind.TURN_START, null, currentTurn, 0,
            Usage.EMPTY, false);
    }

    /** Idempotent while a provider retry keeps the same logical step open. */
    public synchronized void beginStep() {
        if (currentTurnId == null) return;
        if (openStep != null) return;
        currentStep += 1;
        emit(SessionMetricsEvent.Kind.STEP_START, null, currentTurn, currentStep,
            Usage.EMPTY, false);
    }

    public synchronized void firstToken() {
        if (openStep == null || openStep.firstTokenTime != null) return;
        emit(SessionMetricsEvent.Kind.ASSISTANT_FIRST_TOKEN, null,
            currentTurn, currentStep, Usage.EMPTY, false);
    }

    public synchronized void usage(Usage usage) {
        if (openStep == null || usage == null) return;
        SessionMetricsEvent event = SessionMetricsEvent.usage(nextSeq, clock.getAsLong(),
            sessionId, currentTurnId, currentTurn, currentStep, usage);
        appendAndApply(event);
    }

    public synchronized void assistantMessage() {
        if (openStep == null || openStep.messageTime != null) return;
        emit(SessionMetricsEvent.Kind.ASSISTANT_MESSAGE, null,
            currentTurn, currentStep, Usage.EMPTY, false);
    }

    public synchronized void toolCall(String callId) {
        if (openStep == null || callId == null || openTools.containsKey(callId)) return;
        emit(SessionMetricsEvent.Kind.TOOL_CALL, callId,
            currentTurn, currentStep, Usage.EMPTY, false);
    }

    public synchronized void toolResult(String callId) {
        if (openStep == null || callId == null || !openTools.containsKey(callId)) return;
        emit(SessionMetricsEvent.Kind.TOOL_RESULT, callId,
            currentTurn, currentStep, Usage.EMPTY, false);
    }

    public synchronized void endStep() {
        endStep(false);
    }

    private void endStep(boolean synthetic) {
        if (openStep == null) return;
        emit(SessionMetricsEvent.Kind.STEP_END, null,
            currentTurn, currentStep, Usage.EMPTY, synthetic);
    }

    public synchronized void endTurn() {
        endTurn(false);
    }

    private void endTurn(boolean synthetic) {
        if (currentTurnId == null) return;
        if (openStep != null) endStep(synthetic);
        emit(SessionMetricsEvent.Kind.TURN_END, null,
            currentTurn, currentStep, Usage.EMPTY, synthetic);
    }

    public synchronized SessionMetricsSnapshot snapshot() {
        if (!complete) return SessionMetricsSnapshot.INCOMPLETE;
        return new SessionMetricsSnapshot(true, countedTurns.size(), steps,
            llmMs, toolMs, ttftMs, ttftSteps, decodeMs, decodeTokens,
            uncachedInputTokens, outputTokens, cacheWriteTokens, cacheReadTokens);
    }

    private void emit(SessionMetricsEvent.Kind kind, String callId,
                      long turn, long step, Usage usage,
                      boolean synthetic) {
        long now = clock.getAsLong();
        SessionMetricsEvent event = new SessionMetricsEvent(
            SessionMetricsEvent.SCHEMA_VERSION, nextSeq, now, sessionId, kind,
            currentTurnId, turn, step, callId,
            Math.max(0, usage.inputTokens()), Math.max(0, usage.outputTokens()),
            Math.max(0, usage.cacheCreationInputTokens()),
            Math.max(0, usage.cacheReadInputTokens()), synthetic);
        appendAndApply(event);
    }

    private void appendAndApply(SessionMetricsEvent event) {
        apply(event);
        nextSeq = event.seq() + 1;
        TranscriptSink sink = sinkSupplier.get();
        if (sink != null) sink.recordSessionMetrics(sessionId, event);
    }

    private void apply(SessionMetricsEvent event) {
        switch (event.kind()) {
            case SESSION_START -> started = true;
            case TURN_START -> {
                currentTurn = event.turn();
                currentTurnId = event.turnId();
                currentStep = 0;
            }
            case STEP_START -> {
                currentTurn = event.turn();
                currentStep = event.step();
                openStep = new OpenStep(event.time());
            }
            case ASSISTANT_FIRST_TOKEN -> {
                if (openStep != null && openStep.firstTokenTime == null) {
                    openStep.firstTokenTime = event.time();
                }
            }
            case ASSISTANT_USAGE -> {
                if (openStep != null) openStep.usage = event;
            }
            case ASSISTANT_MESSAGE -> {
                if (openStep != null && openStep.messageTime == null) {
                    openStep.messageTime = event.time();
                }
            }
            case TOOL_CALL -> openTools.putIfAbsent(event.callId(), event.time());
            case TOOL_RESULT -> {
                Long start = openTools.remove(event.callId());
                if (start != null) toolMs = add(toolMs, elapsed(start, event.time()));
            }
            case STEP_END -> closeStep(event);
            case TURN_END -> {
                openTools.clear();
                currentTurnId = null;
                openStep = null;
            }
        }
    }

    private void closeStep(SessionMetricsEvent event) {
        OpenStep step = openStep;
        if (step == null) return;
        steps = add(steps, 1);
        countedTurns.add(event.turn());
        if (step.messageTime != null) {
            llmMs = add(llmMs, elapsed(step.startTime, step.messageTime));
        }
        if (step.firstTokenTime != null) {
            ttftMs = add(ttftMs, elapsed(step.startTime, step.firstTokenTime));
            ttftSteps = add(ttftSteps, 1);
        }
        if (step.usage != null) {
            uncachedInputTokens = add(uncachedInputTokens, step.usage.uncachedInputTokens());
            outputTokens = add(outputTokens, step.usage.outputTokens());
            cacheWriteTokens = add(cacheWriteTokens, step.usage.cacheWriteTokens());
            cacheReadTokens = add(cacheReadTokens, step.usage.cacheReadTokens());
            if (step.firstTokenTime != null && step.messageTime != null) {
                decodeMs = add(decodeMs, elapsed(step.firstTokenTime, step.messageTime));
                decodeTokens = add(decodeTokens, step.usage.outputTokens());
            }
        }
        openTools.clear();
        openStep = null;
    }

    private void clear(String newSessionId, boolean newComplete) {
        sessionId = newSessionId;
        nextSeq = 0;
        complete = newComplete;
        started = false;
        currentTurn = 0;
        currentTurnId = null;
        currentStep = 0;
        openStep = null;
        openTools.clear();
        countedTurns.clear();
        steps = llmMs = toolMs = ttftMs = ttftSteps = decodeMs = decodeTokens = 0;
        uncachedInputTokens = outputTokens = cacheWriteTokens = cacheReadTokens = 0;
    }

    private static long elapsed(long start, long end) {
        return Math.max(0, end - start);
    }

    private static long add(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException _) {
            return Long.MAX_VALUE;
        }
    }

    private static final class OpenStep {
        private final long startTime;
        private Long firstTokenTime;
        private Long messageTime;
        private SessionMetricsEvent usage;

        private OpenStep(long startTime) {
            this.startTime = startTime;
        }
    }
}
