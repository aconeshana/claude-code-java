package com.claudecode.ui.lanterna.repl;

import com.claudecode.commands.CommandContext;
import com.claudecode.commands.session.ResumeRequest;
import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.effort.EffortHelpers;
import com.claudecode.core.model.CustomModelCatalog;
import com.claudecode.core.model.CustomModelConfig;
import com.claudecode.runtime.query.QuerySession;
import com.claudecode.runtime.sessionhost.RemoteAttachmentStore;
import com.claudecode.runtime.sessionhost.RemoteSubmissionPrompt;
import com.claudecode.runtime.sessionhost.SessionCollaborationController;
import com.claudecode.runtime.sessionhost.SessionHostCompactResult;
import com.claudecode.runtime.sessionhost.SessionHostEffortController;
import com.claudecode.runtime.sessionhost.SessionHostEffortState;
import com.claudecode.runtime.sessionhost.SessionHostInfo;
import com.claudecode.runtime.sessionhost.SessionHostModelController;
import com.claudecode.runtime.sessionhost.SessionHostModelOptions;
import com.claudecode.runtime.sessionhost.SessionHostModelState;
import com.claudecode.runtime.sessionhost.SessionHostRegistry;
import com.claudecode.runtime.sessionhost.SessionHostSession;
import com.claudecode.runtime.sessionhost.SessionHostSubmission;
import com.claudecode.runtime.sessionhost.SessionOpenRequest;
import com.claudecode.runtime.turn.SessionEventHub;
import com.claudecode.ui.lanterna.slash.SlashCommandDispatcher;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Publishes the interactive REPL's active logical session to the Session Host registry and
 * serves the remote control surface (submit, model, effort, compact) for that session.
 *
 * <p>Owns the published title/generation fence, the {@link #ready()} readiness future, and
 * the per-session controller adapters handed to {@link SessionHostSession}. Everything that
 * must touch Lanterna components goes back through {@link Feedback}; this class itself has no
 * Lanterna dependency.
 *
 * <p>TS coverage (paths relative to the claude-code repo root):
 * <ul>
 *   <li>No direct 197 counterpart — the Session Host is a Java-side extension. Model switching
 *       follows the SDK {@code set_model} control-request semantics (update only this
 *       QuerySession, never the persisted user setting).</li>
 * </ul>
 */
@Explanation("Session Host is a Java-side extension; there is no 197 equivalent to mirror")
final class SessionHostPublisher {

    private static final Logger log = LoggerFactory.getLogger(SessionHostPublisher.class);

    /** Screen-side effects the publisher cannot own. */
    interface Feedback {
        /** The active session's model changed through the remote surface. */
        void modelChanged(String model);
        /** Post a dim system line into the transcript (any thread). */
        void system(String text);
        /** Show a transient prompt-footer hint (any thread). */
        void transientHint(String text, int millis);
        /** The status line should re-run because effort changed. */
        void statusLineChanged();
    }

    /** Collaborators that only exist once the REPL scene has been built. */
    record Bindings(SessionEventHub events,
                    ReplSubmissionCoordinator submissions,
                    SlashCommandDispatcher slash,
                    SessionController sessions) {}

    private final SessionHostRegistry registry;
    private final QuerySession queryEngine;
    private final CommandContext commandContext;
    private final InteractiveSessionPort interactiveSessions;
    private final CustomModelCatalog customModels;
    private final boolean showBuiltInModelFamilies;
    private final SessionCollaborationController collaboration;
    private final String initialSessionName;
    private final Consumer<Runnable> guiInvoker;
    private final Feedback feedback;

    private volatile Bindings bindings;
    private volatile String publishedTitle;
    private volatile String publishedSessionId;
    /** Latest-session-wins fence for background transcript-title reads. */
    private final AtomicLong titleGeneration = new AtomicLong();
    private final CompletableFuture<Void> ready = new CompletableFuture<>();

    SessionHostPublisher(SessionHostRegistry registry,
                         QuerySession queryEngine,
                         CommandContext commandContext,
                         InteractiveSessionPort interactiveSessions,
                         CustomModelCatalog customModels,
                         boolean showBuiltInModelFamilies,
                         SessionCollaborationController collaboration,
                         String initialSessionName,
                         Consumer<Runnable> guiInvoker,
                         Feedback feedback) {
        this.registry = registry;
        this.queryEngine = Objects.requireNonNull(queryEngine, "queryEngine");
        this.commandContext = Objects.requireNonNull(commandContext, "commandContext");
        this.interactiveSessions = interactiveSessions;
        this.customModels = customModels;
        this.showBuiltInModelFamilies = showBuiltInModelFamilies;
        this.collaboration = collaboration;
        this.initialSessionName = initialSessionName;
        this.guiInvoker = Objects.requireNonNull(guiInvoker, "guiInvoker");
        this.feedback = Objects.requireNonNull(feedback, "feedback");
        this.publishedTitle = StringUtils.defaultString(initialSessionName);
    }

    /** Installs the scene-built collaborators; publication is a no-op until this runs. */
    void bind(Bindings bindings) {
        this.bindings = Objects.requireNonNull(bindings, "bindings");
    }

    /** Completes once the semantic event hub and native submission path are ready. */
    CompletableFuture<Void> ready() { return ready; }

    /** Re-publishes the current session and refreshes its title in the background. */
    void publishActiveSession() {
        publishActiveSession(null, true);
    }

    /** Startup overload consuming the already-scanned immutable transcript metadata. */
    void publishActiveSession(String preparedTitle) {
        publishActiveSession(preparedTitle, false);
    }

    private void publishActiveSession(String preparedTitle, boolean refreshTitleInBackground) {
        Bindings bound = bindings;
        if (registry == null || bound == null) return;
        String sessionId = queryEngine.conversation().getSessionId();
        long generation = titleGeneration.incrementAndGet();
        boolean newBinding = !Objects.equals(publishedSessionId, sessionId);
        if (newBinding) {
            boolean firstPublication = publishedSessionId == null;
            // The hub intentionally survives /new and /resume so all endpoint
            // subscriptions remain attached. Its replay prefix does not: those
            // events belong to the previously active logical session and must
            // never seed the newly bound IM thread.
            bound.events().resetReplay();
            publishedSessionId = sessionId;
            String effectiveTitle = StringUtils.trimToNull(preparedTitle);
            if (effectiveTitle == null && firstPublication) {
                effectiveTitle = initialSessionName;
            }
            publishedTitle = StringUtils.defaultString(effectiveTitle);
        }
        registry.activateLocal(buildHostSession(sessionId));
        ready.complete(null);
        if (newBinding && refreshTitleInBackground) {
            refreshTitle(sessionId, generation);
        }
    }

    /**
     * Applies an externally decided title (AI topic title, SessionStart hook) to the
     * published session. Safe before {@link #bind}: the registry entry is refreshed with the
     * collaborators known so far, matching the pre-extraction behaviour.
     */
    void applyTitle(String title) {
        titleGeneration.incrementAndGet();
        publishedTitle = title;
        if (registry != null) {
            registry.refreshLocal(buildHostSession(queryEngine.conversation().getSessionId()));
        }
    }

    private void refreshTitle(String sessionId, long generation) {
        Thread.ofVirtual().name("session-host-title-" + sessionId).start(() -> {
            String title;
            try {
                title = interactiveSessions.readCustomTitle(
                    commandContext.session().workingDirectory(), sessionId);
            } catch (RuntimeException failure) {
                log.debug("Session Host title refresh failed: {}", failure.toString());
                return;
            }
            if (generation != titleGeneration.get()
                    || !Objects.equals(publishedSessionId, sessionId)) return;
            publishedTitle = StringUtils.defaultString(StringUtils.trimToNull(title));
            registry.refreshLocal(buildHostSession(sessionId));
        });
    }

    /** Snapshot for the CLI-owned registry/list adapter. */
    SessionHostSession currentHostSession() {
        if (bindings == null) {
            throw new IllegalStateException("Session Host is not ready");
        }
        return buildHostSession(queryEngine.conversation().getSessionId());
    }

    /** Native Session Host create/resume command; safe to call from a virtual thread. */
    CompletableFuture<SessionHostSession> activateHostSession(SessionOpenRequest request) {
        if (request == null) return CompletableFuture.failedFuture(
            new IllegalArgumentException("session request is required"));
        Bindings bound = bindings;
        String requested = request.requestedSessionId();
        if (StringUtils.isBlank(requested)) {
            CompletableFuture<SessionHostSession> result = new CompletableFuture<>();
            guiInvoker.accept(() -> {
                try {
                    bound.sessions().clearConversation();
                    result.complete(currentHostSession());
                } catch (RuntimeException failure) {
                    result.completeExceptionally(failure);
                }
            });
            return result;
        }
        if (requested.equals(queryEngine.conversation().getSessionId())) {
            return CompletableFuture.completedFuture(currentHostSession());
        }
        String searchCwd = StringUtils.isBlank(request.workDir())
            ? commandContext.session().workingDirectory() : request.workDir();
        return CompletableFuture.supplyAsync(() -> interactiveSessions
                .findExactSession(searchCwd, requested)
                .orElseThrow(() -> new IllegalArgumentException(
                    "session not found: " + requested)))
            .thenCompose(located -> bound.sessions().resumeAsync(new ResumeRequest(
                located.id(), located.transcriptPath(), located.projectPath(),
                ResumeRequest.Entrypoint.SLASH_COMMAND_SESSION_ID)).toCompletableFuture())
            .thenApply(_ -> currentHostSession());
    }

    private SessionHostSession buildHostSession(String sessionId) {
        Bindings bound = bindings;
        String workDir = commandContext.session().workingDirectory();
        SessionHostInfo info = new SessionHostInfo(sessionId, workDir, publishedTitle,
            queryEngine.conversation().getMessages().size(), Instant.now(), "");
        return new SessionHostSession(
            info, bound == null ? null : bound.events(),
            submission -> submitRemote(sessionId, submission),
            new SessionHostModelController() {
                @Override public SessionHostModelState get() {
                    return currentSessionModelState(sessionId);
                }

                @Override public SessionHostModelState set(String selected) {
                    return setSessionModel(sessionId, selected);
                }
            },
            new SessionHostEffortController() {
                @Override public SessionHostEffortState get() {
                    return currentSessionEffortState(sessionId);
                }

                @Override public SessionHostEffortState set(String selected) {
                    return setSessionEffort(sessionId, selected);
                }
            },
            instructions -> {
                requireActiveHostSession(sessionId);
                return bindings.slash().dispatchSessionHostCompact(instructions)
                    .thenApply(result -> new SessionHostCompactResult(result.output()));
            });
    }

    private SessionHostModelState currentSessionModelState(String expectedSessionId) {
        requireActiveHostSession(expectedSessionId);
        String current = queryEngine.configuration().getConfig().modelPreference();
        List<CustomModelConfig> custom = customModels != null ? customModels.list() : List.of();
        return new SessionHostModelState(current == null ? "default" : current,
            SessionHostModelOptions.build(current, queryEngine.configuration().getConfig()::isModelAllowed,
                custom, showBuiltInModelFamilies));
    }

    private SessionHostModelState setSessionModel(String expectedSessionId, String selected) {
        requireActiveHostSession(expectedSessionId);
        SessionHostModelState available = currentSessionModelState(expectedSessionId);
        if (available.models().stream().noneMatch(option -> selected.equals(option.name()))) {
            throw new IllegalArgumentException("model is not available for this session");
        }
        String preference = Strings.CS.equals("default", selected) ? null : selected;
        queryEngine.configuration().setModel(preference);
        // Session Host model changes match SDK set_model: update only this
        // QuerySession/session. Reusing applyModelSelection() here wrote
        // the persisted setting and made sibling PTY/Feishu sessions drift.
        feedback.modelChanged(queryEngine.configuration().getConfig().model());
        return currentSessionModelState(expectedSessionId);
    }

    private SessionHostEffortState currentSessionEffortState(String expectedSessionId) {
        requireActiveHostSession(expectedSessionId);
        String model = queryEngine.configuration().getConfig().model();
        if (!EffortHelpers.modelSupportsEffort(model)) {
            return new SessionHostEffortState("auto", "", List.of());
        }
        String configured = queryEngine.configuration().getConfig().effortValue();
        String current = StringUtils.isBlank(configured) ? "auto" : configured;
        String effective = EffortHelpers.getDisplayedEffortLevel(model, configured);
        List<String> choices = new ArrayList<>();
        choices.add("auto");
        choices.addAll(EffortHelpers.supportedEffortLevels(model));
        return new SessionHostEffortState(current, effective, choices);
    }

    private SessionHostEffortState setSessionEffort(String expectedSessionId, String selected) {
        requireActiveHostSession(expectedSessionId);
        SessionHostEffortState available = currentSessionEffortState(expectedSessionId);
        if (!available.efforts().contains(selected)) {
            throw new IllegalArgumentException("effort is not available for this session");
        }
        String configured = Strings.CS.equals("auto", selected) ? null : selected;
        queryEngine.configuration().getConfig().setEffortValue(configured);
        SessionHostEffortState updated = currentSessionEffortState(expectedSessionId);
        showRemoteEffortNotification(expectedSessionId, configured, updated);
        return updated;
    }

    private void showRemoteEffortNotification(
            String sessionId, String configured, SessionHostEffortState state) {
        String text = EffortHelpers.getEffortNotificationText(
            configured, queryEngine.configuration().getConfig().model());
        if (text != null) {
            feedback.transientHint(text, 12_000);
        }
        String channel = collaboration == null
            ? "" : collaboration.selection(sessionId).channel();
        feedback.system(RemoteSessionControlFeedback.effortChanged(state, channel));
        feedback.statusLineChanged();
    }

    private void requireActiveHostSession(String expectedSessionId) {
        if (!queryEngine.conversation().getSessionId().equals(expectedSessionId)) {
            throw new IllegalStateException("session is no longer active");
        }
    }

    private CompletableFuture<Void> submitRemote(
            String expectedSessionId, SessionHostSubmission submission) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        guiInvoker.accept(() -> {
            try {
                if (!queryEngine.conversation().getSessionId().equals(expectedSessionId)) {
                    throw new IllegalStateException("session is no longer active");
                }
                // Shared assembly with the headless path: image chips as
                // [Image #N] refs, file attachments persisted then referenced
                // as Attached file: lines.
                RemoteSubmissionPrompt assembled = RemoteSubmissionPrompt.assemble(
                    submission, file -> persistRemoteAttachment(
                        expectedSessionId, submission.messageId(), file).toString());
                bindings.submissions().handleRemoteQuery(
                    assembled.prompt(), assembled.pasted());
                result.complete(null);
            } catch (RuntimeException failure) {
                result.completeExceptionally(failure);
            }
        });
        return result;
    }

    private Path persistRemoteAttachment(
            String sessionId, String messageId, SessionHostSubmission.Attachment attachment) {
        return RemoteAttachmentStore.persist(commandContext.session().workingDirectory(),
            sessionId, messageId, attachment);
    }
}
