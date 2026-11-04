package com.claudecode.runtime.sessionhost;

import com.claudecode.core.annotation.Explanation;
import com.claudecode.runtime.turn.SessionEventHub;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

/**
 * Live host session plus its semantic event stream and application commands.
 */
@Explanation("Application-owned session exposed to multiple endpoint adapters")
public record SessionHostSession(
        SessionHostInfo info,
        SessionEventHub events,
        Function<SessionHostSubmission, CompletionStage<Void>> submitter,
        SessionHostModelController models,
        SessionHostEffortController efforts,
        SessionHostCompactController compacts,
        SessionHostPermissionController permissions,
        SessionHostCancelController cancels) {

    public SessionHostSession(
            SessionHostInfo info,
            SessionEventHub events,
            Function<SessionHostSubmission, CompletionStage<Void>> submitter) {
        this(info, events, submitter, SessionHostModelController.unsupported(),
            SessionHostEffortController.unsupported(), SessionHostCompactController.unsupported(),
            SessionHostPermissionController.unsupported(), SessionHostCancelController.unsupported());
    }

    public SessionHostSession(
            SessionHostInfo info,
            SessionEventHub events,
            Function<SessionHostSubmission, CompletionStage<Void>> submitter,
            SessionHostModelController models) {
        this(info, events, submitter, models, SessionHostEffortController.unsupported(),
            SessionHostCompactController.unsupported(), SessionHostPermissionController.unsupported(),
            SessionHostCancelController.unsupported());
    }

    public SessionHostSession(
            SessionHostInfo info,
            SessionEventHub events,
            Function<SessionHostSubmission, CompletionStage<Void>> submitter,
            SessionHostModelController models,
            SessionHostEffortController efforts) {
        this(info, events, submitter, models, efforts, SessionHostCompactController.unsupported(),
            SessionHostPermissionController.unsupported(), SessionHostCancelController.unsupported());
    }

    public SessionHostSession(
            SessionHostInfo info,
            SessionEventHub events,
            Function<SessionHostSubmission, CompletionStage<Void>> submitter,
            SessionHostModelController models,
            SessionHostEffortController efforts,
            SessionHostCompactController compacts) {
        this(info, events, submitter, models, efforts, compacts,
            SessionHostPermissionController.unsupported(), SessionHostCancelController.unsupported());
    }

    public SessionHostSession {
        Objects.requireNonNull(info, "info");
        Objects.requireNonNull(events, "events");
        Objects.requireNonNull(submitter, "submitter");
        Objects.requireNonNull(models, "models");
        Objects.requireNonNull(efforts, "efforts");
        Objects.requireNonNull(compacts, "compacts");
        Objects.requireNonNull(permissions, "permissions");
        Objects.requireNonNull(cancels, "cancels");
    }

    public CompletionStage<Void> submit(SessionHostSubmission submission) {
        return submitter.apply(Objects.requireNonNull(submission, "submission"));
    }

    /**
     * Stops this session's running turn — the same user-cancel interrupt the
     * TUI's Ctrl+C drives. Delegates to the session kind's controller; a kind
     * that exposes none throws, which the gateway's stop endpoint maps to an
     * unknown target rather than a failed cancellation.
     */
    public void cancel() {
        cancels.cancel();
    }
}
