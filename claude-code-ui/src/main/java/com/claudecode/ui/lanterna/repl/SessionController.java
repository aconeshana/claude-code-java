package com.claudecode.ui.lanterna.repl;

import com.claudecode.core.annotation.Explanation;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.commands.CommandContext;
import com.claudecode.commands.session.ResumeRequest;
import com.claudecode.commands.XmlConstants;
import com.claudecode.keybindings.UserKeybindingsStore;
import com.claudecode.keybindings.KeybindingHints;
import com.claudecode.core.engine.CompactProgressEvent;
import com.claudecode.core.engine.FileHistoryManager;
import com.claudecode.core.engine.MessageCompactor;
import com.claudecode.runtime.query.QuerySession;
import com.claudecode.runtime.query.RewindModelUnwind;
import com.claudecode.core.engine.HookDispatcher;
import com.claudecode.core.engine.ThinkingClearLatch;
import com.claudecode.core.git.GitUtils;
import com.claudecode.core.imagestore.ImageStore;
import com.claudecode.core.io.PathUtils;
import com.claudecode.core.message.HumanTurns;
import com.claudecode.core.message.ImageBlock;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageConstants;
import com.claudecode.core.message.MessageFactory;
import com.claudecode.core.message.MessageNormalizer;
import com.claudecode.core.message.PastedContent;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.message.SystemMessage;
import com.claudecode.core.message.TokenEstimator;
import com.claudecode.core.message.Usage;
import com.claudecode.core.message.UserMessage;
import com.claudecode.core.model.ApiProviderScope;
import com.claudecode.core.model.ModelCatalog;
import com.claudecode.core.prompt.SystemPromptSectionResolver;
import com.claudecode.core.state.AgentColorStore;
import com.claudecode.permissions.PermissionGate;
import com.claudecode.runtime.session.ConversationResetPort;
import com.claudecode.runtime.session.PreparedSessionResume;
import com.claudecode.runtime.session.SessionLifecycle;
import com.claudecode.runtime.session.SessionResumeRequest;
import com.claudecode.tools.agent.AgentDefinitionLoader;
import com.claudecode.tools.skills.InvokedSkillRegistry;
import com.claudecode.core.state.CwdState;
import com.claudecode.core.text.DisplayTagUtils;
import com.claudecode.core.text.XmlTagUtils;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.gui2.WindowBasedTextGUI;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.screen.Screen.RefreshType;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import com.claudecode.ui.lanterna.dialog.MessageSelectorDialog;
import com.claudecode.ui.lanterna.dialog.SessionSelectorDialog;
import com.claudecode.ui.lanterna.input.InputPanel;
import com.claudecode.ui.lanterna.theme.LanternaTheme;
import com.claudecode.ui.lanterna.transcript.MessageCollapser;
import com.claudecode.ui.lanterna.transcript.MessageHistory;
import com.claudecode.ui.lanterna.transcript.MessagePanel;
import com.claudecode.ui.lanterna.transcript.TranscriptReplay;

/**
 * Owns the conversation / session lifecycle for the REPL: resuming a past session into the view,
 * replaying its messages, rewinding the current conversation to a picked message, and
 * partial-compact summarization.
 */
public final class SessionController implements ReplCommandUiBridge.Session {

    private final WindowBasedTextGUI gui;
    private final Screen screen;
    private final QuerySession queryEngine;
    private final CommandContext commandContext;
    private final MessagePanel messagePanel;
    private final MessageHistory messageHistory;
    private final MessageCollapser collapser;
    private final InputPanel inputPanel;
    private final MessageSelectorDialog messageSelectorDialog;
    /** Supplier (not a bare ref) because the gate is set on the screen after
     *  construction via setPermissionGate — this always reads the current value. */
    private final Supplier<PermissionGate> permissionGate;
    private final SessionLifecycle sessionLifecycle;
    private final InteractiveSessionPort sessions;
    private final InvokedSkillRegistry invokedSkills;
    private final ConversationResetPort conversationReset;
    private final Runnable resetTopicTitle;
    private final Runnable markExistingSession;
    private final Consumer<String> terminalTitle;
    private final Consumer<String> cancelSessionInteractions;
    private final Runnable resetConversationSurface;
    private final Runnable sessionActivated;
    private final String initialModelPreference;
    private Consumer<String> modelChanged = _ -> {};
    private Runnable rewindStateReset = () -> {};
    private BooleanSupplier rewindInterruptRequired = () -> false;
    private Consumer<Runnable> rewindDeferrer = Runnable::run;
    private Consumer<Supplier<? extends CompletionStage<?>>> asyncRewindDeferrer =
        Supplier::get;
    private final SessionResumeGeneration resumeGeneration = new SessionResumeGeneration();
    private UserKeybindingsStore keybindingsStore;

    void setRewindDeferrer(Consumer<Runnable> deferrer) {
        rewindDeferrer = deferrer != null ? deferrer : Runnable::run;
    }

    void setRewindInterruptRequired(BooleanSupplier required) {
        rewindInterruptRequired = required != null ? required : () -> false;
    }

    void setAsyncRewindDeferrer(
            Consumer<Supplier<? extends CompletionStage<?>>> deferrer) {
        asyncRewindDeferrer = deferrer != null ? deferrer : Supplier::get;
    }

    SessionController(WindowBasedTextGUI gui,
                      Screen screen,
                      QuerySession queryEngine,
                      CommandContext commandContext,
                      MessagePanel messagePanel,
                      MessageHistory messageHistory,
                      MessageCollapser collapser,
                      InputPanel inputPanel,
                      MessageSelectorDialog messageSelectorDialog,
                      Supplier<PermissionGate> permissionGate,
                      SessionLifecycle sessionLifecycle,
                      ConversationResetPort conversationReset,
                      Runnable resetTopicTitle,
                      Runnable markExistingSession,
                      Consumer<String> terminalTitle) {
        this(gui, screen, queryEngine, commandContext, messagePanel, messageHistory,
            collapser, inputPanel, messageSelectorDialog, permissionGate,
            sessionLifecycle, conversationReset, resetTopicTitle,
            markExistingSession, terminalTitle, null, null, null, null, null);
    }

    SessionController(WindowBasedTextGUI gui,
                      Screen screen,
                      QuerySession queryEngine,
                      CommandContext commandContext,
                      MessagePanel messagePanel,
                      MessageHistory messageHistory,
                      MessageCollapser collapser,
                      InputPanel inputPanel,
                      MessageSelectorDialog messageSelectorDialog,
                      Supplier<PermissionGate> permissionGate,
                      SessionLifecycle sessionLifecycle,
                      ConversationResetPort conversationReset,
                      Runnable resetTopicTitle,
                      Runnable markExistingSession,
                      Consumer<String> terminalTitle,
                      Runnable sessionActivated) {
        this(gui, screen, queryEngine, commandContext, messagePanel, messageHistory,
            collapser, inputPanel, messageSelectorDialog, permissionGate,
            sessionLifecycle, conversationReset, resetTopicTitle,
            markExistingSession, terminalTitle, null, null, sessionActivated, null, null);
    }

    SessionController(WindowBasedTextGUI gui,
                      Screen screen,
                      QuerySession queryEngine,
                      CommandContext commandContext,
                      MessagePanel messagePanel,
                      MessageHistory messageHistory,
                      MessageCollapser collapser,
                      InputPanel inputPanel,
                      MessageSelectorDialog messageSelectorDialog,
                      Supplier<PermissionGate> permissionGate,
                      SessionLifecycle sessionLifecycle,
                      ConversationResetPort conversationReset,
                      Runnable resetTopicTitle,
                      Runnable markExistingSession,
                      Consumer<String> terminalTitle,
                      Consumer<String> cancelSessionInteractions,
                      Runnable sessionActivated) {
        this(gui, screen, queryEngine, commandContext, messagePanel, messageHistory,
            collapser, inputPanel, messageSelectorDialog, permissionGate,
            sessionLifecycle, conversationReset, resetTopicTitle,
            markExistingSession, terminalTitle, cancelSessionInteractions,
            null, sessionActivated, null, null);
    }

    SessionController(WindowBasedTextGUI gui,
                      Screen screen,
                      QuerySession queryEngine,
                      CommandContext commandContext,
                      MessagePanel messagePanel,
                      MessageHistory messageHistory,
                      MessageCollapser collapser,
                      InputPanel inputPanel,
                      MessageSelectorDialog messageSelectorDialog,
                      Supplier<PermissionGate> permissionGate,
                      SessionLifecycle sessionLifecycle,
                      ConversationResetPort conversationReset,
                      Runnable resetTopicTitle,
                      Runnable markExistingSession,
                      Consumer<String> terminalTitle,
                      Consumer<String> cancelSessionInteractions,
                      Runnable renderFreshConversationWelcome,
                      Runnable sessionActivated,
                      InteractiveSessionPort sessions,
                      InvokedSkillRegistry invokedSkills) {
        this.gui = gui;
        this.screen = screen;
        this.queryEngine = queryEngine;
        this.commandContext = commandContext;
        this.messagePanel = messagePanel;
        this.messageHistory = messageHistory;
        this.collapser = collapser;
        this.inputPanel = inputPanel;
        this.messageSelectorDialog = messageSelectorDialog;
        this.permissionGate = permissionGate;
        this.sessionLifecycle = sessionLifecycle;
        this.sessions = sessions;
        this.invokedSkills = invokedSkills;
        this.conversationReset = conversationReset != null
            ? conversationReset : ConversationResetPort.noop();
        this.resetTopicTitle = resetTopicTitle != null ? resetTopicTitle : () -> {};
        this.markExistingSession = markExistingSession != null ? markExistingSession : () -> {};
        this.terminalTitle = terminalTitle != null ? terminalTitle : _ -> {};
        this.cancelSessionInteractions = cancelSessionInteractions != null
            ? cancelSessionInteractions : _ -> {};
        this.resetConversationSurface = () -> resetFreshConversationSurface(
            messagePanel, renderFreshConversationWelcome);
        this.sessionActivated = sessionActivated != null ? sessionActivated : () -> {};
        this.initialModelPreference = queryEngine != null
            ? queryEngine.configuration().getConfig().modelPreference() : null;
        if (messageSelectorDialog != null && gui != null) {
            messageSelectorDialog.setGuiInvoker(this::laterOnGuiThread);
        }
    }

    static void resetFreshConversationSurface(
            MessagePanel messagePanel, Runnable renderWelcome) {
        messagePanel.clear();
        if (renderWelcome != null) renderWelcome.run();
    }

    private void appendLine(String text, TextColor color) {
        messagePanel.appendLine(text, color);
    }

    private void laterOnGuiThread(Runnable uiWork) {
        if (gui == null) {
            uiWork.run();
        } else {
            gui.getGUIThread().invokeLater(uiWork);
        }
    }

    void setKeybindingsStore(UserKeybindingsStore store) {
        this.keybindingsStore = store;
    }

    void setModelChanged(Consumer<String> callback) {
        this.modelChanged = callback != null ? callback : _ -> {};
    }

    void setRewindStateReset(Runnable callback) {
        rewindStateReset = callback != null ? callback : () -> {};
    }

    /** Starts a fresh conversation while preserving terminal scrollback and background tasks. */
    @Override
    public void clearConversation() {
        resumeGeneration.invalidate();
        HookDispatcher hooks = queryEngine.execution().getHookDispatcher();
        if (hooks != null) {
            try {
                hooks.dispatchSessionEnd("clear");
            } catch (Exception _) {
                // Hooks are best-effort during reset.
            }
        }

        String oldSessionId = queryEngine.conversation().getSessionId();
        cancelSessionInteractions.accept(oldSessionId);
        if (sessionLifecycle != null) sessionLifecycle.saveCost(oldSessionId);
        releaseTranscriptState(oldSessionId);
        String newSessionId = queryEngine.conversation().startNewSession();
        resetTopicTitle.run();
        terminalTitle.accept("Claude Code");

        try {
            if (sessions != null) {
                sessions.appendParentSession(System.getProperty("user.dir"),
                    newSessionId, oldSessionId, "clear");
            }
        } catch (Exception _) {
            // Session lineage is best-effort metadata.
        }

        AgentColorStore.resetAll();
        onGuiThread(() -> {
            inputPanel.setAgentName(null);
            inputPanel.setSessionColor(null);
        });
        AgentDefinitionLoader.clearCache();

        if (invokedSkills != null) invokedSkills.clearForNewSession(invokedSkills.agentIds());
        ImageStore.clearStoredImagePaths();
        conversationReset.reset();
        SystemPromptSectionResolver.clearAll();
        ThinkingClearLatch.reset();

        Path originalCwd = CwdState.getOriginalCwd();
        if (originalCwd != null && Files.isDirectory(originalCwd)) {
            System.setProperty("user.dir", originalCwd.toString());
        }

        messageHistory.clear();
        onGuiThread(() -> {
            resetConversationSurface.run();
            inputPanel.setText("");
        });

        if (hooks != null) {
            try {
                queryEngine.conversation().injectSystemReminder(
                    hooks.dispatchSessionStartWithOutcome("clear").additionalContext());
            } catch (Exception _) {
                // Hooks are best-effort during reset.
            }
        }
        onGuiThread(this::refreshComplete);
        sessionActivated.run();
    }

    /** Switches the active persistence identity after /resume or /branch loaded its messages. */
    @Override
    public void switchActiveSession(String newSessionId) {
        String outgoingSessionId = queryEngine.conversation().getSessionId();
        if (!Strings.CS.equals(outgoingSessionId, newSessionId)) {
            cancelSessionInteractions.accept(outgoingSessionId);
        }
        if (sessionLifecycle != null) sessionLifecycle.switchIdentity(newSessionId);
        else queryEngine.conversation().switchToSession(newSessionId);
        if (!Strings.CS.equals(outgoingSessionId, newSessionId)) {
            releaseTranscriptState(outgoingSessionId);
        }
        markExistingSession.run();
        restoreSessionColor(newSessionId);
        sessionActivated.run();
    }

    private void releaseTranscriptState(String sessionId) {
        if (sessions != null) sessions.releaseTranscriptState(
            queryEngine.execution().getTranscriptSink(), sessionId, 2_000);
    }

    /** A branch starts a new cost ledger rather than inheriting restored transcript usage. */
    @Override
    public void resetSessionCost() {
        queryEngine.execution().setTotalUsage(Usage.EMPTY);
    }

    private void refreshComplete() {
        try {
            screen.refresh(RefreshType.COMPLETE);
        } catch (Exception _) {
            // Refresh failure is non-fatal.
        }
    }

    





















    private void onGuiThread(Runnable uiWork) {
        if (gui == null) {
            uiWork.run();
            return;
        }
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        try {
            gui.getGUIThread().invokeAndWait(() -> {
                try {
                    uiWork.run();
                } catch (RuntimeException e) {
                    failure.set(e);
                }
            });
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return;
        }
        RuntimeException uiFailure = failure.get();
        if (uiFailure != null) throw uiFailure;
    }

/** Loads the five most recent sessions for the welcome card. */
    @Explanation("Lanterna uses the Pokémon welcome card without a recent-activity feed")
    List<InteractiveSessionPort.SessionEntry> recentSessions() {
        try {
            return sessions.recentSessions(commandContext.session().workingDirectory(), 5);
        } catch (Exception _) {
            return List.of();
        }
    }

    /** Detect current git branch via {@code git rev-parse --abbrev-ref HEAD}. */
    private String detectGitBranch() {
        String branch = GitUtils.currentBranch(
            Path.of(commandContext.session().workingDirectory()));
        return Strings.CS.equals("HEAD", branch) ? null : branch;
    }

    /**
     * Shows the SessionSelector dialog for {@code /resume} without args.
     */
    void showSessionPicker() {
        showSessionPicker(null, () -> { });
    }

    /**
     * Picker variant used by a startup {@code -r}, where an argv prompt may be waiting behind the
     * selection.
     */
    void showSessionPicker(String initialSearchQuery, Runnable onSettled) {
        if (commandContext.session().loadMessages() == null) {
            appendLine("This REPL does not support in-place resume.",
                LanternaTheme.welcomeDim());
            onSettled.run();
            return;
        }
        Thread.ofVirtual().name("session-picker")
            .start(() -> runSessionPicker(initialSearchQuery, onSettled));
    }

    /** Body of {@link #showSessionPicker(String, Runnable)} — always off the GUI thread. */
    private void runSessionPicker(String initialSearchQuery, Runnable onSettled) {
        if (sessions == null) {
            appendLine("This REPL does not provide session persistence.", LanternaTheme.welcomeDim());
            onSettled.run();
            return;
        }
        String workingDirectory = commandContext.session().workingDirectory();
        int termRows = 40;
        try { termRows = screen.getTerminalSize().getRows(); } catch (Exception _) {}
        int visibleCount = Math.max(3, (termRows - 10) / 3);
        int initialLoad = Math.max(50, visibleCount * 3);
        String currentSessionId = queryEngine.conversation().getSessionId();
        InteractiveSessionPort.SessionListing sameRepository = excludingSession(
            sessions.sameRepositorySessionListing(workingDirectory), currentSessionId);
        List<InteractiveSessionPort.SessionEntry> initialSessions =
            sameRepository.loadMore(initialLoad);
        if (initialSessions.isEmpty()) {
            appendLine("No conversations found to resume.", LanternaTheme.welcomeDim());
            onSettled.run();
            return;
        }
        String branch = detectGitBranch();
        SessionSelectorDialog dialog = new SessionSelectorDialog(
            initialSessions, sessions, null, branch,
            commandContext.session().workingDirectory(), termRows);
        dialog.setKeybindingsStore(keybindingsStore);
        dialog.setProgressiveListings(sameRepository,
            () -> excludingSession(sessions.allProjectSessionListing(workingDirectory),
                currentSessionId), initialLoad);
        dialog.setGuiInvoker(r -> gui.getGUIThread().invokeLater(r));
        dialog.setHasMultipleWorktrees(detectMultipleWorktrees());
        dialog.setDeleteSessionCallback(info -> sessions.deleteSession(info, workingDirectory));
        // Seeded last, so the search filter runs against the fully configured dialog.
        dialog.setInitialSearchQuery(initialSearchQuery);
        InteractiveSessionPort.SessionEntry selected = dialog.showAndGet(gui);
        if (selected == null) {  // cancelled
            onSettled.run();
            return;
        }


        // "Show all projects" lists sessions the current project does not own, and
        // selecting one carries its own project through as the resume target: the runtime
        // moves the whole app there first, so the new messages land in that project's
        // transcript dir rather than this one's.
        resume(new ResumeRequest(
            selected.id(), selected.transcriptPath(), selected.projectPath(),
            ResumeRequest.Entrypoint.SLASH_COMMAND_PICKER), onSettled);
    }

    private static InteractiveSessionPort.SessionListing excludingSession(
            InteractiveSessionPort.SessionListing source, String excludedId) {
        if (source == null || StringUtils.isBlank(excludedId)) return source;
        return new InteractiveSessionPort.SessionListing() {
            @Override public synchronized List<InteractiveSessionPort.SessionEntry> loadMore(int count) {
                List<InteractiveSessionPort.SessionEntry> result = new ArrayList<>(Math.max(0, count));
                while (result.size() < count && source.hasMore()) {
                    int needed = count - result.size();
                    source.loadMore(needed).stream()
                        .filter(session -> !session.id().equals(excludedId))
                        .forEach(result::add);
                }
                return List.copyOf(result);
            }

            @Override public synchronized boolean hasMore() {
                return source.hasMore();
            }
        };
    }

    /**
     * Shared host-owned resume entry used by both picker and {@code /resume <arg>}.
     * Full transcript I/O stays off the Lanterna GUI thread; all engine/panel
     * mutations are marshalled back in one ordered callback.
     */
    void resume(ResumeRequest request) {
        resume(request, () -> { });
    }

    /**
     * @param onSettled runs after the resume pipeline finishes, including the failure and
     *                  supersede paths, so a caller waiting on the new conversation is never
     *                  left hanging when restoration does not complete
     */
    void resume(ResumeRequest request, Runnable onSettled) {
        resumeAsync(request).exceptionally(failure -> {
            Throwable cause = failure instanceof CompletionException && failure.getCause() != null
                ? failure.getCause() : failure;
            if (!(cause instanceof CancellationException)) {
                appendLine("Failed to resume " + (request == null ? "session" : request.sessionId()) + ": "
                    + cause.getMessage(), LanternaTheme.welcomeDim());
            }
            return null;
        }).thenRun(onSettled);
    }

    CompletionStage<Void> resumeAsync(ResumeRequest request) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        if (request == null || request.sessionFile() == null || sessionLifecycle == null) {
            return CompletableFuture.failedFuture(
                new IllegalArgumentException("session resume request is unavailable"));
        }
        long generation = resumeGeneration.begin();
        Thread.ofVirtual().name("session-resume-" + request.sessionId()).start(() -> {
            try {
                PreparedSessionResume prepared = sessionLifecycle.prepare(
                    new SessionResumeRequest(
                        request.sessionId(), request.sessionFile(), request.projectPath()),
                    commandContext.session().workingDirectory());
                RestoredSessionBadge badge = restoredSessionBadge(
                    request.sessionFile(), sessions);
                if (!resumeGeneration.isCurrent(generation)) {
                    result.completeExceptionally(new CancellationException(
                        "session resume was superseded by a newer request"));
                    return;
                }
                gui.getGUIThread().invokeLater(() -> {
                    try {
                        if (!resumeGeneration.isCurrent(generation)) {
                            result.completeExceptionally(new CancellationException(
                                "session resume was superseded by a newer request"));
                            return;
                        }
                        finishResume(prepared, badge);
                        result.complete(null);
                    } catch (RuntimeException failure) {
                        result.completeExceptionally(failure);
                    }
                });
            } catch (Exception e) {
                result.completeExceptionally(e);
            }
        });
        return result;
    }

    /** Post-selection resume pipeline — GUI thread (state switch + replay + repaint). */
    private void finishResume(PreparedSessionResume prepared, RestoredSessionBadge badge) {
        SessionResumeRequest request = prepared.request();
        try {
            sessionLifecycle.activate(prepared, msgs -> {
                messageHistory.clear();
                messagePanel.clear();
                String header = String.format(
                    "  [Resumed session %s — %d message%s loaded%s]",
                    request.sessionId(), msgs.size(), msgs.size() == 1 ? "" : "s",
                    prepared.crossProject()
                        ? " · switched to " + PathUtils.abbreviateTilde(prepared.restoredCwd())
                        : "");
                messagePanel.appendLine(header, LanternaTheme.welcomeDim());
// Restore prompt-bar agentName / customTitle / agentColor from the resumed JSONL.
                applyPreparedSessionColor(
                    request.sessionId(), badge, request.projectPath());
                replayLoadedMessages(msgs, request.projectPath(), request.sessionId());
            });
            if (!Strings.CS.equals(prepared.outgoingSessionId(), request.sessionId())) {
                releaseTranscriptState(prepared.outgoingSessionId());
            }

            messagePanel.scrollToBottom();
            try { screen.refresh(RefreshType.COMPLETE); }
            catch (Exception _) {}
            sessionActivated.run();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to resume " + request.sessionId(), e);
        }
    }


    void replayLoadedMessages(List<Message> msgs) {
        String sessionId = queryEngine == null || queryEngine.conversation() == null
            ? null : queryEngine.conversation().getSessionId();
        replayLoadedMessages(msgs, System.getProperty("user.dir"), sessionId);
    }

    @Explanation("Rehydrates Ctrl+O Agent detail from sidechain JSONL because progress rows "
        + "do not participate in the persisted main transcript chain")
    void replayLoadedMessages(List<Message> msgs, String cwd, String sessionId) {
        TranscriptReplay.replay(msgs, collapser, messagePanel, messageHistory::record,
            agentId -> {
                if (sessions == null || StringUtils.isBlank(sessionId)) return List.of();
                Path transcript = sessions.agentTranscriptPath(cwd, sessionId, agentId);
                if (transcript == null) return List.of();
                return sessions.readAgentSidechainMessages(transcript, agentId);
            });
    }

    /**
     * Restores the prompt-bar {@code agentName} / {@code customTitle} / {@code agentColor} for {@code
     * sessionId} by scanning the tail of the session JSONL.
     */
    void restoreSessionColor(String sessionId) {
        if (StringUtils.isBlank(sessionId)) return;
        String projectPath = System.getProperty("user.dir");
        Thread.ofVirtual().name("session-badge-load-" + sessionId).start(() -> {
            Path sessionFile = sessions != null
                ? sessions.sessionFile(projectPath, sessionId) : null;
            RestoredSessionBadge badge = restoredSessionBadge(sessionFile, sessions);
            applyPreparedSessionColor(sessionId, badge, projectPath);
        });
    }

    /**
     * Applies an already-scanned transcript badge without performing file I/O
     * on the GUI thread. Metadata durability is queued to a virtual thread.
     */
    void applyPreparedSessionColor(
            String sessionId, RestoredSessionBadge badge, String projectPath) {
        if (StringUtils.isBlank(sessionId) || inputPanel == null) return;
        RestoredSessionBadge prepared = badge != null
            ? badge : new RestoredSessionBadge(null, null);
        onGuiThread(() -> {
            if (!Strings.CS.equals(
                    queryEngine.conversation().getSessionId(), sessionId)) return;
            inputPanel.setAgentName(prepared.name());
            inputPanel.setSessionColor(prepared.color());
            if (prepared.name() != null) terminalTitle.accept(prepared.name());
        });


        // collapse into one Java call because we don't buffer metadata before
        // file creation. Fresh session → tail empty → no-op. Resumed session →
        // metadata refreshed to EOF so a subsequent SIGKILL doesn't lose the
        // badge even after 64KB+ of new content pushes the original entries
        // out of the tail-scan window.
        String metadataProject = StringUtils.isBlank(projectPath)
            ? System.getProperty("user.dir") : projectPath;
        if (sessions == null) return;
        Thread.ofVirtual().name("session-metadata-refresh-" + sessionId).start(() -> {
            try {
                sessions.reAppendSessionMetadata(metadataProject, sessionId);
            } catch (Exception _) { /* best-effort */ }
        });
    }

    /** Pure transcript-to-badge projection used by resume and regression tests. */
    static RestoredSessionBadge restoredSessionBadge(
            Path sessionFile, InteractiveSessionPort sessions) {
        InteractiveSessionPort.MetadataSnapshot metadata = sessionFile == null || sessions == null
            ? InteractiveSessionPort.MetadataSnapshot.empty() : sessions.scanMetadata(sessionFile);
        return restoredSessionBadge(metadata);
    }

    static RestoredSessionBadge restoredSessionBadge(
            InteractiveSessionPort.MetadataSnapshot metadata) {
        InteractiveSessionPort.MetadataSnapshot snapshot = metadata != null
            ? metadata : InteractiveSessionPort.MetadataSnapshot.empty();
        String name = StringUtils.isNotBlank(snapshot.agentName())
            ? snapshot.agentName() : StringUtils.defaultIfBlank(snapshot.customTitle(), null);
        String color = StringUtils.isNotBlank(snapshot.agentColor())
            && !Strings.CS.equals("default", snapshot.agentColor()) ? snapshot.agentColor() : null;
        return new RestoredSessionBadge(name, color);
    }

    record RestoredSessionBadge(String name, String color) {}


    void openMessageSelector() {
        if (gui == null) return;
        gui.getGUIThread().invokeLater(this::showMessageSelector);
    }

    /**
     * Shows the MessageSelector overlay — triggered by double-Esc on empty input when idle OR by {@code
     * /rewind}.
     */
    void showMessageSelector() {
        String parentSessionId = parentSessionId();

        messageSelectorDialog.show(queryEngine.conversation()::getMessagesForRewind,
            queryEngine.conversation().getFileHistoryManager(),
            (message, action, onSuccess, onFailure) ->
                deferAsyncRewind(() -> executeRestoreSelectionAsync(
                    message, action, onSuccess, onFailure)),
            this::scheduleSummarize,
            selection -> {
                if (selection == null || selection.message() == null) return;
                // RESTORE_CONVERSATION / RESTORE_CODE / RESTORE_CODE_AND_CONVERSATION can
                // surface here — Summarize actions are fully resolved inside the dialog via
                // the SummarizeExecutor (the dialog stays open with a spinner/inline error,
                // onSummarize instead of closing immediately).
                handleRestoreSelection(selection);
            }, parentSessionId,
            parentSessionId == null ? null
                : () -> deferRewind(() -> resumePreviousSession(parentSessionId)),
            this::interruptForRewindIfRequired);
    }

    private void deferRewind(Runnable operation) {
        rewindDeferrer.accept(operation);
    }

    private void deferAsyncRewind(Supplier<? extends CompletionStage<?>> operation) {
        asyncRewindDeferrer.accept(operation);
    }

    private void interruptForRewindIfRequired() {
        if (rewindInterruptRequired.getAsBoolean()) {
            queryEngine.submission().interrupt();
        }
    }

    private String parentSessionId() {
        if (sessions == null) return null;
        String cwd = System.getProperty("user.dir");
        String current = queryEngine.conversation().getSessionId();
        String parent = sessions.parentSessionId(cwd, current);
        return StringUtils.isBlank(parent) || Strings.CS.equals(parent, current) ? null : parent;
    }

    private void resumePreviousSession(String parentSessionId) {
        if (sessions == null || StringUtils.isBlank(parentSessionId)) return;
        String cwd = System.getProperty("user.dir");
        Path transcript = sessions.sessionFile(cwd, parentSessionId);
        if (transcript == null) return;
        String projectPath = sessions.findExactSession(cwd, parentSessionId)
            .map(InteractiveSessionPort.SessionEntry::projectPath)
            .filter(StringUtils::isNotBlank)
            .orElse(cwd);
        resume(new ResumeRequest(parentSessionId, transcript, projectPath,
            ResumeRequest.Entrypoint.REWIND_PREVIOUS_SESSION));
    }

    /**
     * Message Actions "edit" entry point.
     */
    void editMessageFromActions(String renderUuid) {
        List<Message> msgs = List.copyOf(queryEngine.conversation().getMessagesForRewind());
        MessageRewindPolicy.Match match = MessageRewindPolicy
            .findSelectableUser(msgs, renderUuid)
            .orElse(null);
        if (match == null) return;

        UserMessage selected = match.message();
        FileHistoryManager fileHistory = queryEngine.conversation().getFileHistoryManager();
        boolean onlySynthetic = MessageRewindPolicy
            .messagesAfterAreOnlySynthetic(msgs, match.index());

        if (fileHistory == null) {
            finishEditMessageFromActions(msgs, selected, null, true, onlySynthetic);
            return;
        }

        Thread.ofVirtual().name("rewind-message-action-check").start(() -> {
            boolean noFileChanges = !fileHistory.hasAnyChanges(selected.uuid());
            laterOnGuiThread(() -> finishEditMessageFromActions(
                msgs, selected, fileHistory, noFileChanges, onlySynthetic));
        });
    }

    private void finishEditMessageFromActions(
            List<Message> msgs, UserMessage selected, FileHistoryManager fileHistory,
            boolean noFileChanges, boolean onlySynthetic) {
        if (noFileChanges && onlySynthetic) {
            interruptForRewindIfRequired();
            deferRewind(() -> restoreConversationTo(selected));
            return;
        }

        messageSelectorDialog.showPreselected(
            queryEngine.conversation()::getMessagesForRewind,
            fileHistory,
            selected,
            this::interruptForRewindIfRequired,
            (message, action, onSuccess, onFailure) ->
                deferAsyncRewind(() -> executeRestoreSelectionAsync(
                    message, action, onSuccess, onFailure)),
            this::scheduleSummarize,
            selection -> {
                if (selection != null && selection.message() != null) {
                    handleRestoreSelection(selection);
                }
            });
    }


    private void handleRestoreSelection(MessageSelectorDialog.Selection selection) {
        executeRestoreSelection(selection.message(), selection.action(), () -> {},
            error -> appendLine("  ⎿  " + error, LanternaTheme.toolError()));
    }

    private void executeRestoreSelection(
            UserMessage selected,
            MessageSelectorDialog.RestoreAction action,
            Runnable onSuccess,
            Consumer<String> onFailure) {
        Exception codeError = null;
        Exception conversationError = null;

        if (action == MessageSelectorDialog.RestoreAction.RESTORE_CODE
                || action == MessageSelectorDialog.RestoreAction.RESTORE_CODE_AND_CONVERSATION) {
            try {
                restoreCodeTo(selected);
            } catch (Exception e) {
                codeError = e;
            }
        }
        if (action == MessageSelectorDialog.RestoreAction.RESTORE_CONVERSATION
                || action == MessageSelectorDialog.RestoreAction.RESTORE_CODE_AND_CONVERSATION) {
            try {
                restoreConversationTo(selected);
            } catch (Exception e) {
                conversationError = e;
            }
        }

        String errorMessage = combinedErrorMessage(conversationError, codeError);
        if (errorMessage != null) {
            onFailure.accept(errorMessage);
        } else {
            onSuccess.run();
        }
    }

    private CompletionStage<Void> executeRestoreSelectionAsync(
            UserMessage selected,
            MessageSelectorDialog.RestoreAction action,
            Runnable onSuccess,
            Consumer<String> onFailure) {
        CompletableFuture<Void> completion = new CompletableFuture<>();
        Thread.ofVirtual().name("rewind-restore").start(() -> {
            Exception codeError = null;
            if (action == MessageSelectorDialog.RestoreAction.RESTORE_CODE
                    || action == MessageSelectorDialog.RestoreAction.RESTORE_CODE_AND_CONVERSATION) {
                try {
                    restoreCodeTo(selected);
                } catch (Exception e) {
                    codeError = e;
                }
            }
            Exception finalCodeError = codeError;
            try {
                laterOnGuiThread(() -> {
                    try {
                        Exception conversationError = null;
                        if (action == MessageSelectorDialog.RestoreAction.RESTORE_CONVERSATION
                                || action == MessageSelectorDialog.RestoreAction.RESTORE_CODE_AND_CONVERSATION) {
                            try {
                                restoreConversationTo(selected);
                            } catch (Exception e) {
                                conversationError = e;
                            }
                        }
                        String errorMessage = combinedErrorMessage(
                            conversationError, finalCodeError);
                        if (errorMessage != null) onFailure.accept(errorMessage);
                        else onSuccess.run();
                        completion.complete(null);
                    } catch (RuntimeException e) {
                        completion.completeExceptionally(e);
                    }
                });
            } catch (RuntimeException e) {
                completion.completeExceptionally(e);
            }
        });
        return completion;
    }


    static String combinedErrorMessage(Exception conversationError, Exception codeError) {
        if (conversationError != null && codeError != null) {
            return "Failed to restore the conversation and code:\n"
                + exceptionDetail(codeError);
        }
        if (conversationError != null) {
            return "Failed to restore the conversation:\n" + exceptionDetail(conversationError);
        }
        if (codeError != null) {
            return "Failed to restore the code:\n" + exceptionDetail(codeError);
        }
        return null;
    }

    private static String exceptionDetail(Exception error) {
        if (StringUtils.isNotBlank(error.getMessage())) return "Error: " + error.getMessage();
        String type = error.getClass().getSimpleName();
        return "Error: " + (StringUtils.isNotBlank(type) ? type : error.toString());
    }

    /**
     * Restores every tracked file to its version as of {@code selected}'s checkpoint.
     */
    private void restoreCodeTo(UserMessage selected) {
        FileHistoryManager fileHistoryManager = queryEngine.conversation().getFileHistoryManager();
        if (fileHistoryManager == null) return;
        fileHistoryManager.rewind(selected.uuid());
    }

    /**
     * Rewinds conversation to just before {@code selected}, restores permissionMode, and populates the
     * input with the picked message's text + image chips.
     */
    private void restoreConversationTo(UserMessage selected) {
        List<Message> liveMessages = List.copyOf(
            queryEngine.conversation().getMessagesForRewind());
        int idx = lastIdentityIndexOf(liveMessages, selected);
        if (idx >= 0) {
            List<Message> retainedMessages = new ArrayList<>(liveMessages.subList(0, idx));
            List<Message> slicedMessages = new ArrayList<>(
                liveMessages.subList(idx, liveMessages.size()));
            RewindModelUnwind.Result modelUnwind = rewindModelUnwind(
                retainedMessages, slicedMessages);
            rebaseTranscriptAfterRewind(retainedMessages);
            queryEngine.conversation().loadMessages(retainedMessages);
            rewindStateReset.run();
            applyRewindModelUnwind(modelUnwind);

            // Restore the permission mode that was active when the selected user message was
            // originally submitted. A stale selector object leaves all conversation state alone.
            if (selected.permissionMode() != null) {
                final String pm = selected.permissionMode();
                laterOnGuiThread(() -> {
                    inputPanel.setPermissionMode(pm);
                    PermissionGate gate = permissionGate.get();
                    if (gate != null) gate.setMode(pm);
                });
            }

            queryEngine.forks().clearNestedMemoryAttachmentTriggers();
            truncateConversationSurfaceFrom(selected);
        }

        RestoredInput restored = restoredInput(selected);
        if (inputPanel != null && (restored.text() != null || restored.replaceImageChips())) {
            laterOnGuiThread(() -> {
                if (restored.text() != null) {
                    inputPanel.setRestoredText(restored.text());
                }
                // Image restoration is independent of text. Image-only prompts

                if (restored.replaceImageChips()) {
                    inputPanel.replaceImageChips(restored.imageChips());
                }
            });
        }
    }

    private static int identityIndexOf(List<? extends Message> messages, Message selected) {
        for (int index = 0; index < messages.size(); index++) {
            if (messages.get(index) == selected) return index;
        }
        return -1;
    }

    private static int lastIdentityIndexOf(List<? extends Message> messages, Message selected) {
        for (int index = messages.size() - 1; index >= 0; index--) {
            if (messages.get(index) == selected) return index;
        }
        return -1;
    }

    private RewindModelUnwind.Result rewindModelUnwind(
            List<Message> retainedMessages, List<Message> slicedMessages) {
        var config = queryEngine.configuration().getConfig();
        boolean firstParty = config.llmClient() != null
            && ApiProviderScope.usesFirstPartyModelIds(config.llmClient().provider());
        return RewindModelUnwind.evaluate(
            retainedMessages,
            slicedMessages,
            config.mainLoopModelOverride(),
            firstParty,
            initialModelPreference,
            model -> config.isModelAllowed(model)
                && (ModelCatalog.isBuiltInSelection(model)
                    || config.isCustomModel(model)
                    || ModelCatalog.sameModel(model, initialModelPreference)));
    }

    private void applyRewindModelUnwind(RewindModelUnwind.Result result) {
        if (result == null || !(result.model() instanceof RewindModelUnwind.Restore restore)) {
            return;
        }
        var config = queryEngine.configuration().getConfig();
        config.setMainLoopModelOverride(restore.value());
        modelChanged.accept(config.model());
    }

    /** Pure prompt-state derivation used by the rewind UI and unit tests. */
    static RestoredInput restoredInput(UserMessage selected) {
        Map<Integer, PastedContent> imageChips = PastedContent.imagesFromMessage(selected);
        var content = selected == null ? null : selected.message();
        String rawText = content == null ? null
            : content.text() != null ? content.text()
            : MessageNormalizer.getContentText(content);
        String text = rawText == null ? null : textForResubmit(rawText);
        boolean replaceImageChips = content != null && content.blocks() != null
            && content.blocks().stream().anyMatch(ImageBlock.class::isInstance);
        return new RestoredInput(text, imageChips, replaceImageChips);
    }

    record RestoredInput(String text, Map<Integer, PastedContent> imageChips,
                         boolean replaceImageChips) {}


    static String textForResubmit(String rawText) {
        String bash = XmlTagUtils.extractTag(rawText, XmlConstants.BASH_INPUT_TAG).orElse(null);
        if (bash != null) return "!" + bash;
        String cmd = XmlTagUtils.extractTag(rawText, XmlConstants.COMMAND_NAME_TAG).orElse(null);
        if (cmd != null) {
            String args = XmlTagUtils.extractTag(rawText, XmlConstants.COMMAND_ARGS_TAG).orElse("");
            return cmd + " " + args;
        }
        return DisplayTagUtils.stripIdeContextTags(rawText);
    }

    /**
     * Partial-compact around the picked message and swap the engine's message history to the resulting
     * slice.
     */
    CompletionStage<Void> runSummarize(
            UserMessage selected,
            MessageSelectorDialog.RestoreAction action, String feedback,
            Runnable onSuccess, Consumer<String> onFailure) {
        return runSummarize(capturePartialCompactSelection(selected), selected,
            action, feedback, onSuccess, onFailure);
    }

    private void scheduleSummarize(
            UserMessage selected,
            MessageSelectorDialog.RestoreAction action, String feedback,
            Runnable onSuccess, Consumer<String> onFailure) {
        PartialCompactSelection selection = capturePartialCompactSelection(selected);
        if (selection.pivotIndex() < 0) {
            runSummarize(selection, selected, action, feedback, onSuccess, onFailure);
            return;
        }
        deferAsyncRewind(() -> runSummarize(
            selection, selected, action, feedback, onSuccess, onFailure));
    }

    private PartialCompactSelection capturePartialCompactSelection(UserMessage selected) {
        List<Message> liveMessages = List.copyOf(
            queryEngine.conversation().getMessagesForRewind());
        List<Message> activeMessages = List.copyOf(
            MessageConstants.getMessagesAfterCompactBoundary(liveMessages));
        return new PartialCompactSelection(
            activeMessages, identityIndexOf(activeMessages, selected));
    }

    private CompletionStage<Void> runSummarize(
            PartialCompactSelection selection, UserMessage selected,
            MessageSelectorDialog.RestoreAction action, String feedback,
            Runnable onSuccess, Consumer<String> onFailure) {
        CompletableFuture<Void> completion = new CompletableFuture<>();
        String direction = action == MessageSelectorDialog.RestoreAction.SUMMARIZE_UP_TO ? "up_to" : "from";
        if (selection.pivotIndex() < 0) {
            final String msg =
                "That message is no longer in the active context. Choose a more recent message.";
            SystemMessage warning = new SystemMessage(
                UUID.randomUUID().toString(), "warning", "warning", msg);
            queryEngine.conversation().appendTranscriptMessage(warning);
            completeSummarizeOnGuiThread(completion, () -> {
                SDKMessage.System event = new SDKMessage.System(warning);
                messageHistory.record(event);
                collapser.dispatch(event, messagePanel);
                onSuccess.run();
            });
            return completion;
        }
        MessageCompactor cs = queryEngine.execution().getCompactService();
        if (cs == null) {
            final String msg = "compaction service is not available in this session.";
            completeSummarizeOnGuiThread(completion, () -> {
                onFailure.accept(msg);
            });
            return completion;
        }
        // Snapshot the message list — partial compact must run against a stable
        // slice; the engine's live list may mutate while we're summarizing.
        final List<Message> snapshot = selection.messages();
        final int pivotIndex = selection.pivotIndex();

        Thread.ofVirtual().name("rewind-summarize").start(() -> {
            Consumer<CompactProgressEvent> notify =
                queryEngine.execution().getOnCompactProgress();
            try {
                // The selector is commonly opened by interrupting an active turn. Official
                // partial compact uses a fresh abort controller; clear the Java fork's sticky
                // cancellation before starting the equivalent user-initiated compact request.
                cs.prepareManualCompact();
                HookDispatcher hooks = queryEngine.execution().getHookDispatcher();
                long preCompactTokens = cs.contextTokenCount(
                    snapshot, queryEngine.configuration().getConfig().model());
                if (notify != null) {
                    notify.accept(new CompactProgressEvent.HooksStart("pre_compact"));
                }
                HookDispatcher.HookOutcome preOutcome = hooks != null
                    ? hooks.dispatchPreCompactWithOutcome(
                        "manual", null, preCompactTokens)
                    : HookDispatcher.HookOutcome.PROCEED;
                String compactInstructions = partialCompactInstructions(
                    preOutcome.additionalContext(), feedback);
                if (notify != null) notify.accept(new CompactProgressEvent.CompactStart());

                MessageCompactor.PartialCompactOutput compacted = cs.partialCompact(
                    snapshot, pivotIndex, direction, feedback, compactInstructions);
                List<Message> post = new ArrayList<>(compacted.messages());

                if (notify != null) {
                    notify.accept(new CompactProgressEvent.HooksStart("session_start"));
                }
                HookDispatcher.HookOutcome sessionStartOutcome = hooks != null
                    ? hooks.dispatchSessionStartWithOutcome("compact")
                    : HookDispatcher.HookOutcome.PROCEED;
                if (sessionStartOutcome.hasAdditionalContext()) {
                    post.add(MessageFactory.createUserMessage(
                        MessageConstants.wrapInSystemReminder(
                            sessionStartOutcome.additionalContext()), true));
                }

                try {
                    String sid = queryEngine.conversation().getSessionId();
                    if (sessions != null && StringUtils.isNotBlank(sid)) {
                        sessions.reAppendSessionMetadata(System.getProperty("user.dir"), sid);
                    }
                } catch (Exception _) { /* best-effort */ }

                if (notify != null) {
                    notify.accept(new CompactProgressEvent.HooksStart("post_compact"));
                }
                if (hooks != null) {
                    hooks.dispatchPostCompactWithOutcome(
                        "manual", compacted.summaryText(),
                        postCompactTokenCount(compacted, post, cs));
                }

                List<Message> retainedRewindMessages =
                    action == MessageSelectorDialog.RestoreAction.SUMMARIZE_FROM
                        ? livePrefixBefore(selected.uuid()) : List.of();
                queryEngine.conversation().loadCompactedMessages(post, retainedRewindMessages);
                queryEngine.forks().getLoadedNestedMemoryPaths().clear();
                queryEngine.forks().clearNestedMemoryAttachmentTriggers();
                // The compact payload already carries the delta reconstructed against the kept
                // slice. Snapshot the current pool so the next prompt does not announce it twice.
                queryEngine.forks().setPreviousTurnTools(
                    queryEngine.configuration().getConfig().tools());

                // Persist the compacted view to the transcript (boundary +
                // summary + attachments + hooks) so a later --resume reloads the
                // summary instead of the full pre-compact history. The kept
                // messages (snapshot prefix) are already on disk from when they
                // streamed, so skip them to avoid duplicating uuids. Java records
                // streaming messages immediately, so no separate flush-before-

                var sink = queryEngine.execution().getTranscriptSink();
                if (sink != null) {
                    String sid = queryEngine.conversation().getSessionId();
                    for (Message m : post) {
                        try { sink.record(sid, m); }
                        catch (Throwable _) { /* best-effort */ }
                    }
                }

                Runnable postCompactCleanup = queryEngine.execution().getPostCompactCallback();
                if (postCompactCleanup != null) {
                    try { postCompactCleanup.run(); }
                    catch (Exception _) { /* best-effort, matching full compact */ }
                }
                SystemPromptSectionResolver.clearAll();

                String restoredText = action == MessageSelectorDialog.RestoreAction.SUMMARIZE_FROM
                    ? restoredInput(selected).text() : null;
                if (notify != null) notify.accept(new CompactProgressEvent.CompactEnd());
                completeSummarizeOnGuiThread(completion, () -> {
                    rebuildPartialCompactSurface(retainedRewindMessages, post);
                    if (restoredText != null && inputPanel != null) {
                        inputPanel.setRestoredText(restoredText);
                    }
                    if (inputPanel != null) {
                        String shortcut = KeybindingHints.shortcut(keybindingsStore,
                            "app:toggleTranscript", "Global", "Ctrl+O");
                        inputPanel.showTransientHint(
                            "Conversation summarized (" + shortcut + " for history)", 8_000);
                    }
                    onSuccess.run();
                });
            } catch (Exception e) {
                final String msg = exceptionDetail(e);
                if (notify != null) notify.accept(new CompactProgressEvent.CompactEnd());
                completeSummarizeOnGuiThread(completion, () -> {
                    onFailure.accept(msg);
                });
            }
        });
        return completion;
    }

    private record PartialCompactSelection(List<Message> messages, int pivotIndex) {}

    private List<Message> livePrefixBefore(String selectedUuid) {
        List<Message> liveMessages = queryEngine.conversation().getMessagesForRewind();
        for (int index = 0; index < liveMessages.size(); index++) {
            if (Strings.CS.equals(liveMessages.get(index).uuid(), selectedUuid)) {
                return List.copyOf(liveMessages.subList(0, index));
            }
        }
        return List.of();
    }

    private static String partialCompactInstructions(String hookInstructions, String feedback) {
        boolean hasHook = StringUtils.isNotBlank(hookInstructions);
        boolean hasFeedback = StringUtils.isNotBlank(feedback);
        if (hasHook && hasFeedback) {
            return hookInstructions + "\nUser context: " + feedback;
        }
        if (hasHook) return hookInstructions;
        return hasFeedback ? "User context: " + feedback : null;
    }

    private long postCompactTokenCount(
            MessageCompactor.PartialCompactOutput compacted,
            List<Message> post, MessageCompactor compactor) {
        Usage usage = compacted.compactionUsage();
        if (usage.inputTokens() > 0 || usage.outputTokens() > 0
                || usage.cacheCreationInputTokens() > 0
                || usage.cacheReadInputTokens() > 0) {
            return TokenEstimator.contextTokens(
                usage, queryEngine.configuration().getConfig().model());
        }
        return compactor.estimatePostCompactTokenCount(post);
    }

    private void completeSummarizeOnGuiThread(
            CompletableFuture<Void> completion, Runnable uiWork) {
        try {
            laterOnGuiThread(() -> {
                try {
                    uiWork.run();
                    completion.complete(null);
                } catch (RuntimeException e) {
                    completion.completeExceptionally(e);
                    throw e;
                }
            });
        } catch (RuntimeException e) {
            completion.completeExceptionally(e);
        }
    }

    private void rebuildPartialCompactSurface(
            List<Message> retainedRewindMessages, List<Message> postCompactMessages) {
        List<Message> visible = new ArrayList<>();
        Set<String> visibleUuids = new HashSet<>();
        for (Message message : retainedRewindMessages) {
            if (MessageConstants.isCompactBoundaryMessage(message)) continue;
            visible.add(message);
            if (message.uuid() != null) visibleUuids.add(message.uuid());
        }
        for (Message message : postCompactMessages) {
            if (MessageConstants.isCompactBoundaryMessage(message)) continue;
            if (message.uuid() != null && !visibleUuids.add(message.uuid())) continue;
            visible.add(message);
        }

        messageHistory.clear();
        messagePanel.clear();
        replayLoadedMessages(visible);
    }

    /**
     * Removes the last selectable human {@link UserMessage} and everything after it, then returns
     * the removed message so callers can restore its image chips.
     */
    UserMessage rewindToBeforeLastRealUserMessage() {
        List<Message> messages = new ArrayList<>(queryEngine.conversation().getMessages());
        int selectedIndex = HumanTurns.lastTypedTurnIndex(messages);
        if (selectedIndex < 0) return null;

        UserMessage selected = (UserMessage) messages.get(selectedIndex);
        List<Message> retainedMessages = new ArrayList<>(messages.subList(0, selectedIndex));
        List<Message> slicedMessages = new ArrayList<>(messages.subList(selectedIndex, messages.size()));
        RewindModelUnwind.Result modelUnwind = rewindModelUnwind(
            retainedMessages, slicedMessages);
        rebaseTranscriptAfterRewind(retainedMessages);

        // Use the same conversation-state restore as the manual picker: model overrides and
        // nested-memory attachment triggers must unwind together with the message list. Waiting
        // keeps the model view, transcript surface, and later turn-complete row atomic.
        onGuiThread(() -> {
            queryEngine.conversation().loadMessages(retainedMessages);
            rewindStateReset.run();
            applyRewindModelUnwind(modelUnwind);
            queryEngine.forks().clearNestedMemoryAttachmentTriggers();
            truncateConversationSurfaceFrom(selected);
        });
        return selected;
    }

    private void rebaseTranscriptAfterRewind(List<Message> retainedMessages) {
        var sink = queryEngine.execution().getTranscriptSink();
        if (sink == null) return;
        try {
            sink.rewindConversation(
                queryEngine.conversation().getSessionId(), retainedMessages);
        } catch (RuntimeException _) {
            // Transcript persistence is best-effort; the in-memory rewind must still succeed.
        }
    }

    private void truncateConversationSurfaceFrom(UserMessage selected) {
        messagePanel.truncateFromSourceUuid(selected.uuid());
        messageHistory.truncateFromUserUuid(selected.uuid());
        collapser.resetTurn();
    }

    /**
     * Pure list logic behind {@link #rewindToBeforeLastRealUserMessage}: drops the
     * last selectable human {@link UserMessage} and everything after it,
     * mutating {@code msgs} in place and returning the removed message (or null).
     * Package-private + static so it is unit-testable without a QuerySession.
     */
    static UserMessage rewindTo(List<Message> msgs) {
        int selectedIndex = HumanTurns.lastTypedTurnIndex(msgs);
        if (selectedIndex < 0) return null;
        UserMessage selected = (UserMessage) msgs.get(selectedIndex);
        msgs.subList(selectedIndex, msgs.size()).clear();
        return selected;
    }

    private boolean detectMultipleWorktrees() {
        try {
            Process p = new ProcessBuilder("git", "worktree", "list", "--porcelain")
                .directory(new File(commandContext.session().workingDirectory()))
                .redirectErrorStream(true).start();
            try { p.getOutputStream().close(); } catch (IOException _) {}
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (p.waitFor() != 0) return false;
            long count = out.lines().filter(l -> Strings.CS.startsWith(l, "worktree ")).count();
            return count > 1;
        } catch (Exception _) {
            return false;
        }
    }
}
