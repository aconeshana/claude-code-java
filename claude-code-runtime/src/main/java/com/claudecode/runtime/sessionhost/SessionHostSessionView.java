package com.claudecode.runtime.sessionhost;

import com.claudecode.core.annotation.Explanation;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/**
 * Non-owning view of the application-managed active session.
 */
@Explanation("Prevents endpoint adapters from owning session event infrastructure")
public interface SessionHostSessionView {

    SessionHostInfo info();

    CompletionStage<Void> submit(SessionHostSubmission submission);

    SessionHostModelController models();

    SessionHostEffortController efforts();

    SessionHostCompactController compacts();

    static SessionHostSessionView of(SessionHostSession session) {
        Objects.requireNonNull(session, "session");
        return new SessionHostSessionView() {
            @Override public SessionHostInfo info() { return session.info(); }
            @Override public CompletionStage<Void> submit(SessionHostSubmission submission) {
                return session.submit(submission);
            }
            @Override public SessionHostModelController models() { return session.models(); }
            @Override public SessionHostEffortController efforts() { return session.efforts(); }
            @Override public SessionHostCompactController compacts() { return session.compacts(); }
        };
    }
}
