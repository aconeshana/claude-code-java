package com.claudecode.ui.lanterna.repl;

import com.claudecode.core.engine.TranscriptSink;
import com.claudecode.runtime.query.QuerySession;
import java.util.Objects;

/**
 * {@link ReplInterruptActions.TurnAbortTarget} over the live {@link QuerySession}: Esc / Ctrl+C
 * abort the in-flight turn through the session's submission port and salvage against its
 * transcript sink.
 *
 * <p>TS coverage (paths relative to the claude-code repo root):
 * <ul>
 *   <li>{@code utils/abortController.ts} — turn-level abort signalling that the interrupt
 *       gestures trigger.</li>
 * </ul>
 */
record QuerySessionAbortTarget(QuerySession queryEngine) implements ReplInterruptActions.TurnAbortTarget {

    QuerySessionAbortTarget {
        Objects.requireNonNull(queryEngine, "queryEngine");
    }

    @Override public void interrupt() { queryEngine.submission().interrupt(); }
    @Override public void softInterrupt() { queryEngine.submission().softInterrupt(); }
    @Override public String sessionId() { return queryEngine.conversation().getSessionId(); }
    @Override public TranscriptSink transcriptSink() { return queryEngine.execution().getTranscriptSink(); }
}
