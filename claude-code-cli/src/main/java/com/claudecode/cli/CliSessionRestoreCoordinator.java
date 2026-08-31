package com.claudecode.cli;

import com.claudecode.cli.CliResumeTargetResolver.Target;
import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.engine.SessionCostState;
import com.claudecode.core.engine.ToolResultBudget;
import com.claudecode.runtime.query.QuerySession;
import com.claudecode.core.message.Message;
import com.claudecode.core.text.FormatUtils;
import com.claudecode.runtime.session.SessionLifecycle;
import com.claudecode.services.cost.CostStatePersistence;
import com.claudecode.services.hooks.HookEngine;
import com.claudecode.runtime.session.MessagesDeserializer;
import com.claudecode.services.session.ResumeStateRestorer;
import com.claudecode.session.SessionInfo;
import com.claudecode.session.SessionManager;
import com.claudecode.session.SessionSearch;
import com.claudecode.session.SessionStorage;
import com.claudecode.session.TranscriptRecorder;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

/**
 * Coordinates startup {@code --resume}/{@code --continue} restoration.
 */
final class CliSessionRestoreCoordinator {

    private CliSessionRestoreCoordinator() {}

    /** Immutable result consumed by the selected execution runner. */
    record Restoration(
            boolean restored,
            String sessionId,
            Runnable deferredRecoveryTranscript,
            boolean pickerRequested,
            String pickerSearchTerm) {
        static final Restoration NONE = new Restoration(false, null, null, false, null);

        /**
         * The interactive picker still has to choose a target.
         *
         * @param searchTerm pre-fills the picker's search box with the value that failed to
         *                   resolve, or {@code null} for a bare {@code -r}
         */
        static Restoration picker(String searchTerm) {
            return new Restoration(false, null, null, true, searchTerm);
        }
    }

    /** Explicit dependencies keep restoration independent of Picocli's mutable root fields. */
    record Request(
            QuerySession engine,
            String cwd,
            String resumeSession,
            boolean continueLastSession,
            boolean forkSession,
            String resumeSessionAt,
            boolean printMode,
            boolean interactiveStartup,
            String initialPrompt,
            String inputFormat,
            TranscriptRecorder transcriptRecorder,
            CliOutput output,
            CliOutput errorOutput) {}

    static Restoration restore(Request request) {

        // never gets a chance to abort a launch that --continue already answered.
        if (request.continueLastSession()) {
            SessionManager manager = new SessionManager(request.cwd());
            String latest = latestSessionId(manager.listSessions());
            if (latest == null) return Restoration.NONE;
            return restoreExisting(request, manager.getSessionFile(latest), latest, true);
        }
        return restore(request, CliResumeTargetResolver.resolve(
            request.resumeSession(), new SessionSearch(request.cwd()),
            request.interactiveStartup()
                ? CliResumeTargetResolver.Mode.INTERACTIVE
                : CliResumeTargetResolver.Mode.PRINT));
    }

    /**
     * Applies the launch mode to an already-resolved target. Interactive startup can fall back on
     * the picker, so an unresolvable <em>title</em> is not an error there — it becomes the picker's
     * initial search query. A headless launch has no such fallback and reports instead.
     */
    static Restoration restore(Request request, Target target) {
        return switch (target) {
            case Target.Absent _ -> Restoration.NONE;
            case Target.Session session ->
                restoreExisting(request, session.transcript(), session.sessionId(), false);
            // Named directly, a transcript needs neither picker nor title search, so both modes
            // simply load it. Only which paths qualify as one differs, and that is settled during
            // resolution.
            case Target.TranscriptFile file ->
                restoreExisting(request, file.transcript(), file.sessionId(), false);
            case Target.Valueless _ -> request.interactiveStartup()
                ? Restoration.picker(null)
                : rejectUnresolvable(request, null);
            case Target.UnknownTitle unknown -> request.interactiveStartup()
                ? Restoration.picker(unknown.query())
                : rejectUnresolvable(request, unknown.query());
            case Target.AmbiguousTitle ambiguous -> request.interactiveStartup()
                ? Restoration.picker(ambiguous.query())
                : rejectAmbiguousTitle(request, ambiguous);
            case Target.MissingSessionId missing ->
                abort(request, "No conversation found with session ID: " + missing.sessionId());
            case Target.UnreadableTranscriptFile file ->
                abortUnreadableTranscript(request, file.rawValue());
        };
    }

/** Reports an unreadable transcript using the argument supplied by the user. */
    @Explanation("Naming the transcript argument keeps print-mode failures actionable")
    private static Restoration abortUnreadableTranscript(Request request, String rawValue) {
        return abort(request, "Unable to load transcript from file: " + rawValue);
    }

    /**
     * The session {@code --continue} means. {@link SessionManager#listSessions} already returns
     * lastModified-descending, so never re-sort it by transcript creation time here.
     */
    static String latestSessionId(List<SessionInfo> sessions) {
        return sessions == null || sessions.isEmpty() ? null : sessions.getFirst().id();
    }

    /**
     * The picker is a REPL surface, so a non-interactive startup has nowhere to show it.
     */
    private static Restoration rejectUnresolvable(Request request, String rawValue) {
        String message = "Error: --resume requires a valid session ID or session title when used "
            + "with --print. Usage: claude -p --resume <session-id|title>";
        if (StringUtils.isNotBlank(rawValue)) {
            message += ". Provided value \"" + rawValue
                + "\" is not a UUID and does not match any session title.";
        }
        request.errorOutput().println(message);
        throw new CliLaunchAbort(1);
    }

    /** Headless disambiguation listing: the candidate ids are the only way forward. */
    private static Restoration rejectAmbiguousTitle(
            Request request, Target.AmbiguousTitle ambiguous) {
        StringBuilder message = new StringBuilder("Error: --resume \"")
            .append(ambiguous.query()).append("\" matches ").append(ambiguous.matches().size())
            .append(" sessions. Pass one of these session IDs to disambiguate:");
        for (CliResumeTargetResolver.TitleMatch match : ambiguous.matches()) {
            message.append('\n').append("  ").append(match.sessionId())
                .append("  (modified ").append(FormatUtils.formatInstantIso(match.modified()))
                .append(')');
        }
        request.errorOutput().println(message.toString());
        throw new CliLaunchAbort(1);
    }


    private static Restoration abort(Request request, String message) {
        CliOutput channel = request.interactiveStartup()
            ? request.output() : request.errorOutput();
        channel.println(message);
        throw new CliLaunchAbort(1);
    }

    /**
     * @param transcript the log's own path, which for a title match or an explicit argument may live
     * outside the launch cwd's project directory — resolving it from {@code request.cwd} would silently
     * resume nothing @param sessionId {@code null} only for a argument whose file name is not a UUID.
     */
    private static Restoration restoreExisting(
            Request request,
            Path transcript,
            String sessionId,
            boolean continuing) {
        SessionStorage storage = new SessionStorage();
        List<Message> messages = MessagesDeserializer.deserialize(storage.readMessages(transcript));
        if (StringUtils.isNotBlank(request.resumeSessionAt())) {
            int selected = -1;
            for (int i = 0; i < messages.size(); i++) {
                if (Strings.CS.equals(request.resumeSessionAt(), messages.get(i).uuid())) {
                    selected = i;
                    break;
                }
            }
            if (selected < 0) {
                return abort(request, "No message found with UUID: " + request.resumeSessionAt());
            }
            messages = List.copyOf(messages.subList(0, selected + 1));
        }
        List<ToolResultBudget.Replacement> contentReplacements =
            storage.readContentReplacements(transcript);
        ResumeStateRestorer restorer = newResumeRestorer(request.engine(), storage);
        restorer.preSwitch();
        // The session identity must change before loading anything. The engine,
        // shared identity, hook dispatcher, and subsequent transcript writes then
        // target one session from the first restored message onward.
        if (sessionId != null && !request.forkSession()) {
            request.engine().conversation().switchToSession(sessionId);
            // Plan-mode queries can materialize a plan path before their first
            // transcript write, so restore the persisted slug immediately after
            // the identity changes rather than when the random launch id exists.
            if (request.transcriptRecorder() != null) {
                request.transcriptRecorder().restoreSessionSlug(sessionId);
                request.transcriptRecorder().restoreSessionMetadata(sessionId, messages);
            }
        }
        restoreToolResultBudget(
            request.engine(), request.engine().conversation().getSessionId(),
            messages, contentReplacements);
        request.engine().conversation().loadMessages(messages);
        boolean deferRecoveryTranscript = shouldDeferRecoveryTranscript(
            request.printMode(), request.initialPrompt());
        restorer.postSwitch(
            transcript, messages, request.cwd(),
            shouldPersistRecoveryTranscript(request.printMode(), deferRecoveryTranscript));
        List<Message> deferredMessages = messages;
        Runnable deferred = deferRecoveryTranscript
            ? () -> restorer.persistRecoveredMessages(transcript, deferredMessages)
            : null;
        if (sessionId != null) {
            CostStatePersistence.restoreForSession(sessionId, Path.of(request.cwd()));
        }
        String restoredId = sessionId != null
            ? sessionId : request.engine().conversation().getSessionId();
        String label = continuing ? "Continuing last session " : "Resumed session ";
        writeSessionRestoreNotice(
            request.output(), request.printMode(), request.inputFormat(),
            label + restoredId + " — " + messages.size() + " messages loaded.");
        return new Restoration(true, restoredId, deferred, false, null);
    }

    static boolean shouldDeferRecoveryTranscript(boolean printMode, String initialPrompt) {
        return printMode && initialPrompt != null;
    }

    static boolean shouldPersistRecoveryTranscript(
            boolean printMode, boolean deferRecoveryTranscript) {
        return printMode && !deferRecoveryTranscript;
    }

    /**
     * Human-only notice for an interactive startup. SDK/json stdout remains
     * protocol-only and must never receive a prose restoration message.
     */
    static void writeSessionRestoreNotice(
            CliOutput output,
            boolean printMode,
            String inputFormat,
            String message) {
        if (!printMode && !Strings.CS.equals("stream-json", inputFormat)) {
            output.println(message);
        }
    }

    /** Creates the runtime-owned interactive session-switch bridge. */
    static SessionLifecycle newSessionLifecycle(QuerySession engine) {
        SessionStorage storage = new SessionStorage();
        ResumeStateRestorer restorer = newResumeRestorer(engine, storage);
        AtomicReference<SessionCostState.Snapshot> capturedTargetCost =
            new AtomicReference<>();
        return new SessionLifecycle(
            engine,
            path -> new SessionLifecycle.TranscriptSnapshot(
                MessagesDeserializer.deserialize(storage.readMessages(path)),
                storage.readContentReplacements(path),
                storage.readSessionMetrics(path),
                storage.readMetricTurnIds(path)),
            new SessionLifecycle.Ports() {
                @Override
                public void captureCost(String sessionId) {
                    capturedTargetCost.set(CostStatePersistence.readForSession(
                        sessionId, Path.of(engine.configuration().getConfig().workingDirectory())));
                }

                @Override
                public void saveCost(String sessionId) {
                    CostStatePersistence.saveForSession(
                        sessionId, Path.of(engine.configuration().getConfig().workingDirectory()));
                }

                @Override
                public void beforeSwitch() {
                    restorer.preSwitch();
                }

                @Override
                public void restoreCost(String sessionId) {
                    CostStatePersistence.restoreCaptured(capturedTargetCost.getAndSet(null));
                }

                @Override
                public void restoreToolResultBudget(
                        String sessionId,
                        List<Message> messages,
                        List<ToolResultBudget.Replacement> replacements) {
                    CliSessionRestoreCoordinator.restoreToolResultBudget(
                        engine, sessionId, messages, replacements);
                }

                @Override
                public void loadEngineMessages(List<Message> messages) {
                    engine.conversation().loadMessages(messages);
                }

                @Override
                public void afterSwitch(Path sessionFile, List<Message> messages, String cwd) {
                    restorer.postSwitch(sessionFile, messages, cwd);
                }
            });
    }


    static void restoreToolResultBudget(
            QuerySession engine,
            String sessionId,
            List<Message> messages,
            List<ToolResultBudget.Replacement> replacements) {
        var config = engine.configuration().getConfig();
        config.toolExecutor().restoreToolResultBudget(
            messages,
            replacements,
            sessionId,
            config.workingDirectory(),
            config.agentId());
    }

    private static ResumeStateRestorer newResumeRestorer(
            QuerySession engine,
            SessionStorage storage) {
        var hookDispatcher = engine.execution().getHookDispatcher();
        return new ResumeStateRestorer(
            engine,
            storage,
            hookDispatcher instanceof HookEngine hookEngine
                ? hookEngine : null,
            null);
    }
}
