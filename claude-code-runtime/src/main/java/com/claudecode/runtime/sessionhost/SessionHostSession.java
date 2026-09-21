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
        SessionHostPermissionController permissions) {

    public SessionHostSession(
            SessionHostInfo info,
            SessionEventHub events,
            Function<SessionHostSubmission, CompletionStage<Void>> submitter) {
        this(info, events, submitter, SessionHostModelController.unsupported(),
            SessionHostEffortController.unsupported(), SessionHostCompactController.unsupported(),
            SessionHostPermissionController.unsupported());
    }

    public SessionHostSession(
            SessionHostInfo info,
            SessionEventHub events,
            Function<SessionHostSubmission, CompletionStage<Void>> submitter,
            SessionHostModelController models) {
        this(info, events, submitter, models, SessionHostEffortController.unsupported(),
            SessionHostCompactController.unsupported(), SessionHostPermissionController.unsupported());
    }

    public SessionHostSession(
            SessionHostInfo info,
            SessionEventHub events,
            Function<SessionHostSubmission, CompletionStage<Void>> submitter,
            SessionHostModelController models,
            SessionHostEffortController efforts) {
        this(info, events, submitter, models, efforts, SessionHostCompactController.unsupported(),
            SessionHostPermissionController.unsupported());
    }

    public SessionHostSession(
            SessionHostInfo info,
            SessionEventHub events,
            Function<SessionHostSubmission, CompletionStage<Void>> submitter,
            SessionHostModelController models,
            SessionHostEffortController efforts,
            SessionHostCompactController compacts) {
        this(info, events, submitter, models, efforts, compacts,
            SessionHostPermissionController.unsupported());
    }

    public SessionHostSession {
        Objects.requireNonNull(info, "info");
        Objects.requireNonNull(events, "events");
        Objects.requireNonNull(submitter, "submitter");
        Objects.requireNonNull(models, "models");
        Objects.requireNonNull(efforts, "efforts");
        Objects.requireNonNull(compacts, "compacts");
        Objects.requireNonNull(permissions, "permissions");
    }

    public CompletionStage<Void> submit(SessionHostSubmission submission) {
        return submitter.apply(Objects.requireNonNull(submission, "submission"));
    }
}
