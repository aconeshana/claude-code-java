package com.claudecode.cli;

import com.claudecode.core.engine.SubmitOptions;
import com.claudecode.core.engine.TranscriptSink;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageFactory;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.runtime.query.QuerySession;
import com.claudecode.runtime.sessionhost.RemoteSubmissionPrompt;
import com.claudecode.runtime.sessionhost.SessionHostSubmission;
import com.claudecode.runtime.turn.SessionEventHub;
import com.claudecode.runtime.turn.TurnOutcome;
import com.claudecode.runtime.turn.UserInput;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.apache.commons.lang3.StringUtils;

/**
 * Bridges gateway submissions onto one headless {@code QuerySession}.
 *
 * <p>{@code onTurnStart} fires synchronously on the submitting thread (the
 * gateway subscribes its turn-scoped sink before submitting, and the opening
 * event must not race the subscription). The engine's message iterator is
 * then drained on a virtual thread — an undrained iterator would stall the
 * turn — re-emitting every message through the session hub so protocol
 * projections and future mirrors observe the whole turn.
 *
 * <p>Inline images and file attachments ride the submission as
 * {@link RemoteSubmissionPrompt} chips and {@code Attached file:} lines —
 * the same assembly the TUI's remote path uses — so the engine treats a
 * web-composed turn exactly like a TUI turn with pasted pictures.
 */
final class HeadlessTurnDriver {

    private static final long ERROR_FLUSH_TIMEOUT_MS = 2_000L;

    private final QuerySession engine;
    private final SessionEventHub events;
    private final RemoteSubmissionPrompt.FilePersister persister;
    private final Supplier<String> permissionModeSupplier;
    private final TranscriptSink transcriptSink;
    private final String sessionId;

    HeadlessTurnDriver(QuerySession engine, SessionEventHub events,
            RemoteSubmissionPrompt.FilePersister persister) {
        this(engine, events, persister, () -> "default", null, null);
    }

    HeadlessTurnDriver(QuerySession engine, SessionEventHub events,
            RemoteSubmissionPrompt.FilePersister persister,
            Supplier<String> permissionModeSupplier) {
        this(engine, events, persister, permissionModeSupplier, null, null);
    }

    HeadlessTurnDriver(QuerySession engine, SessionEventHub events,
            RemoteSubmissionPrompt.FilePersister persister,
            Supplier<String> permissionModeSupplier,
            TranscriptSink transcriptSink,
            String sessionId) {
        this.engine = engine;
        this.events = events;
        this.persister = persister;
        this.permissionModeSupplier = permissionModeSupplier != null
            ? permissionModeSupplier : () -> "default";
        this.transcriptSink = transcriptSink;
        this.sessionId = sessionId;
    }

    /** Runs one turn; the returned stage completes when the turn ends. */
    CompletionStage<Void> submit(SessionHostSubmission submission) {
        RemoteSubmissionPrompt assembled =
            RemoteSubmissionPrompt.assemble(submission, persister);
        String prompt = assembled.prompt();
        CompletableFuture<Void> done = new CompletableFuture<>();
        long startedNanos = System.nanoTime();
        String mode = StringUtils.defaultIfBlank(permissionModeSupplier.get(), "default");
        // Synchronous on the caller thread: the gateway's turn-scoped
        // subscription must observe onTurnStart before any message.
        events.onTurnStart(UserInput.of(prompt, prompt,
            assembled.pasted().isEmpty() ? null : assembled.pasted(), mode));
        Thread.ofVirtual().name("headless-turn", 0).start(() -> {
            boolean userCancel;
            boolean recordedApiError = false;
            try {
                Iterator<SDKMessage> messages = engine.submission()
                    .submitMessage(prompt, SubmitOptions.withPastedContents(
                        "user", assembled.pasted()));
                while (messages.hasNext()) {
                    SDKMessage message = messages.next();
                    if (message instanceof SDKMessage.Assistant assistant
                            && assistant.message() != null
                            && assistant.message().isApiErrorMessage()) {
                        recordedApiError = true;
                    }
                    if (message instanceof SDKMessage.Error(Exception error)) {
                        userCancel = engine.execution().getAbortController() != null
                            && engine.execution().getAbortController().isAborted();
                        if (!userCancel && !recordedApiError) {
                            persistTurnError(error);
                        }
                        events.onError(error, userCancel);
                        done.complete(null);
                        return;
                    }
                    events.onMessage(message);
                }
                events.onTurnComplete(turnOutcome(
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos)));
                done.complete(null);
            } catch (RuntimeException failure) {
                if (!recordedApiError) {
                    persistTurnError(failure);
                }
                events.onError(failure, false);
                done.complete(null);
            }
        });
        return done;
    }

    /**
     * Records a synthetic API error row into the session transcript when a
     * headless or scheduled-task turn fails before producing an assistant reply,
     * so inspecting the session later explains why the run ended instead of
     * showing only the user prompt.
     */
    private void persistTurnError(Throwable error) {
        if (transcriptSink == null || StringUtils.isBlank(sessionId) || error == null) {
            return;
        }
        String detail = describeError(error);
        AssistantMessage apiError = MessageFactory.createAssistantAPIErrorMessage(
            "API Error: " + detail, null, "api_error");
        transcriptSink.record(sessionId, apiError);
        transcriptSink.awaitPendingWrites(sessionId, ERROR_FLUSH_TIMEOUT_MS);
    }

    private static String describeError(Throwable error) {
        String message = error.getMessage();
        if (StringUtils.isNotBlank(message)) {
            return message.trim();
        }
        Throwable cause = error.getCause();
        if (cause != null && StringUtils.isNotBlank(cause.getMessage())) {
            return cause.getMessage().trim();
        }
        return error.getClass().getSimpleName();
    }

    /** A turn that ran to completion; its wall time feeds the webui turn tail's time pill. */
    private static TurnOutcome turnOutcome(long elapsedMs) {
        return new TurnOutcome(false, false, false, false, false, elapsedMs, null, null, null, null);
    }

    /** Message-level prior conversation for a resumed session; may be empty. */
    record PriorConversation(List<Message> messages) {
        PriorConversation {
            messages = List.copyOf(messages);
        }
    }
}
