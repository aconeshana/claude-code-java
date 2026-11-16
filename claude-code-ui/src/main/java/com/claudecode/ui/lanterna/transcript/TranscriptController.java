package com.claudecode.ui.lanterna.transcript;

import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.message.SystemMessage;
import com.claudecode.core.message.Usage;
import com.claudecode.core.message.UserMessage;
import com.claudecode.core.message.MessageConstants;
import com.claudecode.core.message.MessageOrigin;
import com.claudecode.core.io.FileUtils;
import com.claudecode.keybindings.UserKeybindingsStore;
import com.claudecode.tools.tasks.InProcessTeammateTask;
import com.claudecode.tools.tasks.LocalAgentTask;
import com.claudecode.tools.tasks.TaskRegistry;
import com.claudecode.tools.tasks.TaskState;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import com.claudecode.core.message.MessageContent;
import com.googlecode.lanterna.gui2.MultiWindowTextGUI;
import com.googlecode.lanterna.screen.Screen;
import com.claudecode.ui.lanterna.components.SpinnerComponent;
import com.claudecode.ui.lanterna.input.InputPanel;
import com.claudecode.ui.lanterna.input.PromptHistory;
import com.claudecode.ui.lanterna.repl.InteractiveSessionPort;
import org.apache.commons.lang3.Strings;
import com.claudecode.ui.lanterna.theme.LanternaTheme;

/**
 * Owns transcript-mode and teammate-transcript presentation state.
 */
public final class TranscriptController {

    private final MultiWindowTextGUI gui;
    private final Screen screen;
    private final MessagePanel messagePanel;
    private final SpinnerComponent spinner;
    private final InputPanel input;
    private final MessageHistory history;
    private final MessageCollapser collapser;
    private final TaskRegistry taskRegistry;
    private final InteractiveSessionPort sessions;

    private String activeTeammateTaskId;
    private Function<String, Path> agentTranscriptResolver;
    private final Map<String, List<UserMessage>> localAgentPendingInput = new ConcurrentHashMap<>();
    /** In-memory projection consumed by Up; never reread an agent JSONL from the input thread. */
    private volatile String visibleHistoryTaskId;
    private volatile List<PromptHistory.Entry> visiblePromptHistory = List.of();
    private boolean transcriptMode;
    private boolean showAll;
    private TranscriptWindow window;
    private UserKeybindingsStore keybindingsStore;
    private final ScheduledExecutorService localAgentWatcher =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "agent-transcript-watch");
            thread.setDaemon(true);
            return thread;
        });
    private ScheduledFuture<?> localAgentWatchFuture;
    private Path activeLocalAgentTranscript;
    private long activeLocalAgentStamp = -1L;
    /** Which viewed agent still needs its one disk read; see {@link AgentTranscriptLatch}. */
    private final AgentTranscriptLatch transcriptLatch = new AgentTranscriptLatch();
    /** Task whose {@link LocalAgentTask} currently holds this controller's rebuild listener. */
    private String activeLocalAgentTaskId;

    public TranscriptController(MultiWindowTextGUI gui, Screen screen,
                         MessagePanel messagePanel, SpinnerComponent spinner,
                         InputPanel input, MessageHistory history,
                         MessageCollapser collapser, TaskRegistry taskRegistry,
                         InteractiveSessionPort sessions) {
        this.gui = gui;
        this.screen = screen;
        this.messagePanel = messagePanel;
        this.spinner = spinner;
        this.input = input;
        this.history = history;
        this.collapser = collapser;
        this.taskRegistry = taskRegistry;
        this.sessions = sessions;
    }

    public void toggle() {
        if (transcriptMode) {
            if (window != null) window.close();
            return;
        }
        transcriptMode = true;
        spinner.setVerbose(true);
        input.setTranscriptMode(true);
        window = new TranscriptWindow(history, this::onWindowClosed);
        window.setKeybindingsStore(keybindingsStore);
        gui.addWindow(window);
    }

    public void setKeybindingsStore(UserKeybindingsStore store) {
        this.keybindingsStore = store;
        if (window != null) window.setKeybindingsStore(store);
    }

    public void setAgentTranscriptResolver(Function<String, Path> resolver) {
        this.agentTranscriptResolver = resolver;
    }

    private void onWindowClosed() {
        transcriptMode = false;
        showAll = false;
        spinner.setVerbose(false);
        input.setTranscriptMode(false);
        input.setArgumentHint(null);
        window = null;
        refreshComplete();
    }

    public void toggleShowAll() {
        if (!transcriptMode) return;
        showAll = !showAll;
        collapser.setShowAll(showAll);
        if (collapser.isIdle() && !history.isEmpty()) replayLeaderHistory();
        messagePanel.appendLine(
            showAll ? "  [Show all enabled · ctrl+e to collapse]"
                    : "  [Show all disabled]",
            LanternaTheme.welcomeDim());
        refreshComplete();
    }

    public void teammateViewChanged() {
        syncTeammateListener();
        rebuildVisibleTranscript();
    }


    public void appendLocalAgentUserMessage(String taskId, String text) {
        localAgentPendingInput.compute(taskId, (_, messages) -> {
            List<UserMessage> next = new ArrayList<>(messages == null ? List.of() : messages);
            next.add(new UserMessage(UUID.randomUUID().toString(), MessageContent.ofText(text)));
            return List.copyOf(next);
        });
        if (taskId.equals(ViewedTeammateHolder.instance().viewingTaskId())) requestRebuild();
    }


    public List<PromptHistory.Entry> viewedPromptHistory() {
        String taskId = ViewedTeammateHolder.instance().viewingTaskId();
        if (taskId == null) return null;
        return Objects.equals(taskId, visibleHistoryTaskId)
            ? visiblePromptHistory : List.of();
    }

    static List<PromptHistory.Entry> promptHistoryFromMessages(List<Message> messages) {
        List<PromptHistory.Entry> historyEntries = new ArrayList<>();
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (!(messages.get(i) instanceof UserMessage user)
                    || user.isMeta()
                    || (user.origin() != null && user.origin() != MessageOrigin.USER)
                    || MessageConstants.isSyntheticMessage(user)) continue;
            String text = MessageConstants.getContentText(user.message());
            if (text == null || text.trim().isEmpty() || text.length() > 100_000
                    || isTeammateEnvelope(text)) continue;
            historyEntries.add(new PromptHistory.Entry(
                text, user.sessionIdValue(),
                user.timestampValue() == null ? 0L : user.timestampValue().toEpochMilli(),
                null, null, Map.of()));
        }
        return List.copyOf(historyEntries);
    }

    private static boolean isTeammateEnvelope(String text) {
        if (Strings.CS.startsWith(text, "<teammate-message ")) return true;
        int newline = text.indexOf('\n');
        return Strings.CS.startsWith(text, "Another Claude session sent a message")
            && newline >= 0
            && Strings.CS.startsWith(text.substring(newline + 1), "<teammate-message ");
    }

    private void replayLeaderHistory() {
        messagePanel.clear();
        collapser.resetTurn();
        for (SDKMessage message : history.events()) collapser.dispatch(message, messagePanel);
    }

    private void rebuildVisibleTranscript() {
        messagePanel.clear();
        collapser.resetTurn();
        String taskId = ViewedTeammateHolder.instance().viewingTaskId();
        if (taskId == null) {
            visibleHistoryTaskId = null;
            visiblePromptHistory = List.of();
            for (SDKMessage message : history.events()) collapser.dispatch(message, messagePanel);
        } else {
            var teammate = taskRegistry.getTeammateHandle(taskId);
            if (teammate.isPresent()) renderTeammate(teammate.get());
            else renderLocalAgent(taskId);
        }
        messagePanel.invalidate();
        refreshComplete();
    }

    private void syncTeammateListener() {
        String taskId = ViewedTeammateHolder.instance().viewingTaskId();
        stopLocalAgentWatcher();
        if (activeTeammateTaskId != null && !activeTeammateTaskId.equals(taskId)) {
            taskRegistry.getTeammateHandle(activeTeammateTaskId)
                .ifPresent(teammate -> teammate.setTranscriptListener(null));
            activeTeammateTaskId = null;
        }
        if (activeLocalAgentTaskId != null && !activeLocalAgentTaskId.equals(taskId)) {
            taskRegistry.getAgentHandle(activeLocalAgentTaskId)
                .ifPresent(agent -> agent.setTranscriptListener(null));
            activeLocalAgentTaskId = null;
        }
        // A new view starts unseeded, so its first render re-reads the transcript from disk.
        transcriptLatch.viewChangedTo(taskId);
        if (taskId != null) {
            taskRegistry.getTeammateHandle(taskId).ifPresent(teammate -> {
                teammate.setTranscriptListener(this::requestRebuild);
                activeTeammateTaskId = taskId;
            });
            if (ViewedTeammateHolder.instance().isViewingLocalAgent()
                    && agentTranscriptResolver != null) {
                taskRegistry.getAgentHandle(taskId).ifPresent(agent -> {
                    agent.setTranscriptListener(this::requestRebuild);
                    activeLocalAgentTaskId = taskId;
                });
                startLocalAgentWatcher(agentTranscriptResolver.apply(taskId));
            }
        }
    }

    /**
     * Watches an agent's transcript file for appends the in-memory mirror cannot carry.
     *
     * <p>The mirror is fed by a progress callback filtered to tool-bearing content, so a plain-text
     * answer lands on disk without reaching the handle. This watcher covers that gap: when the file
     * has grown but the mirror is silent, the new tail is read back and merged into the mirror.
     * While the mirror is still growing the poll is a no-op, which is the common case for a running
     * agent and what keeps a repaint from re-parsing the whole JSONL.
     */
    private void startLocalAgentWatcher(Path transcript) {
        if (transcript == null) return;
        activeLocalAgentTranscript = transcript;
        activeLocalAgentStamp = transcriptStamp(transcript);
        localAgentWatchFuture = localAgentWatcher.scheduleWithFixedDelay(() -> {
            Path active = activeLocalAgentTranscript;
            if (active == null || !ViewedTeammateHolder.instance().isViewingLocalAgent()) return;
            long next = transcriptStamp(active);
            if (next == activeLocalAgentStamp) return;
            activeLocalAgentStamp = next;
            if (!reloadMirrorTail()) requestRebuild();
        }, 250L, 250L, TimeUnit.MILLISECONDS);
    }

    /**
     * Re-reads the viewed agent's transcript and folds any new rows into its handle, returning true
     * when the mirror absorbed them (so the caller can skip a redundant rebuild). Returns false when
     * there is no live handle, leaving the caller to render straight from disk.
     */
    private boolean reloadMirrorTail() {
        String taskId = ViewedTeammateHolder.instance().viewingTaskId();
        if (taskId == null) return false;
        Optional<LocalAgentTask> handle = taskRegistry.getAgentHandle(taskId);
        if (handle.isEmpty()) return false;
        Path transcript = agentTranscriptResolver == null
            ? null : agentTranscriptResolver.apply(taskId);
        if (transcript == null) return false;
        // seedDisplayTranscript fires the listener only when it added rows, so a no-op read must not
        // force a repaint — otherwise the watcher would defeat the point of mirroring.
        return handle.get().seedDisplayTranscript(loadAgentTranscript(transcript, taskId, sessions));
    }

    private void stopLocalAgentWatcher() {
        ScheduledFuture<?> future = localAgentWatchFuture;
        localAgentWatchFuture = null;
        if (future != null) future.cancel(false);
        activeLocalAgentTranscript = null;
        activeLocalAgentStamp = -1L;
    }

    private void requestRebuild() {
        if (gui != null) gui.getGUIThread().invokeLater(this::rebuildVisibleTranscript);
        else rebuildVisibleTranscript();
    }

    private void renderTeammate(InProcessTeammateTask teammate) {
        String name = teammate.name() == null ? teammate.getTaskId() : teammate.name();
        messagePanel.appendLine("── Teammate: " + name + " ──", LanternaTheme.claude());
        List<Message> messages = teammate.displayTranscript();
        visibleHistoryTaskId = teammate.getTaskId();
        visiblePromptHistory = promptHistoryFromMessages(messages);
        for (Message message : messages) {
            SDKMessage sdkMessage = toSdkMessage(message);
            if (sdkMessage != null) collapser.dispatch(sdkMessage, messagePanel);
        }
    }

    /**
     * Renders a backgrounded/foreground local agent, preferring the handle's in-memory mirror over
     * a fresh JSONL parse. The released client renders a viewed agent from an in-memory transcript
     * and only touches disk to seed it once; doing a full read + parse on every repaint is what
     * made switching main/sub feel slow on large agent transcripts.
     *
     * <p>The mirror is not complete on its own: the progress callback that feeds it is filtered to
     * tool-bearing content, so a plain-text answer never reaches it. The first render of a view
     * therefore always seeds from disk, and later repaints reuse that seed plus live appends.
     */
    private void renderLocalAgent(String taskId) {
        Path transcript = agentTranscriptResolver == null ? null : agentTranscriptResolver.apply(taskId);
        if (transcript == null) {
            visibleHistoryTaskId = taskId;
            visiblePromptHistory = List.of();
            return;
        }
        String label = taskRegistry.get(taskId)
            .map(TaskState::description).orElse(taskId);
        messagePanel.appendLine("── Agent: " + label + " ──", LanternaTheme.claude());
        List<Message> messages = mergePendingLocalAgentInput(
            localAgentMessages(taskId, transcript),
            localAgentPendingInput.getOrDefault(taskId, List.of()));
        visibleHistoryTaskId = taskId;
        visiblePromptHistory = promptHistoryFromMessages(messages);
        for (Message message : messages) {
            SDKMessage sdkMessage = toSdkMessage(message);
            if (sdkMessage != null) collapser.dispatch(sdkMessage, messagePanel);
        }
    }

    /**
     * Resolves the rows to render for a local agent: the in-memory mirror once the handle exists
     * and has been seeded, otherwise a disk read that both seeds the mirror and supplies this
     * frame. Only the first render of a view reads the transcript file.
     */
    private List<Message> localAgentMessages(String taskId, Path transcript) {
        Optional<LocalAgentTask> handle = taskRegistry.getAgentHandle(taskId);
        if (handle.isEmpty()) {
            // Foreground agent that completed before it was backgrounded, or an evicted one:
            // there is no live mirror to render from, so fall back to the persisted transcript.
            return loadAgentTranscript(transcript, taskId, sessions);
        }
        LocalAgentTask agent = handle.get();
        if (!transcriptLatch.needsRead(taskId)) {
            return agent.displayTranscript();
        }
        // Seed once per view, then serve every later repaint from the mirror.
        agent.seedDisplayTranscript(loadAgentTranscript(transcript, taskId, sessions));
        transcriptLatch.markRead(taskId);
        return agent.displayTranscript();
    }

    static List<Message> mergePendingLocalAgentInput(
            List<Message> persisted, List<UserMessage> pending) {
        if (pending == null || pending.isEmpty()) return persisted;
        List<Message> merged = new ArrayList<>(persisted);
        for (UserMessage overlay : pending) {
            boolean alreadyPersisted = persisted.stream()
                .filter(UserMessage.class::isInstance)
                .map(UserMessage.class::cast)
                .anyMatch(user -> user.timestampValue() != null && overlay.timestampValue() != null
                    && !user.timestampValue().isBefore(overlay.timestampValue())
                    && Objects.equals(user.message().text(), overlay.message().text()));
            if (!alreadyPersisted) merged.add(overlay);
        }
        return List.copyOf(merged);
    }

    static SDKMessage toSdkMessage(Message message) {
        return switch (message) {
            case UserMessage user -> new SDKMessage.User(user);
            case AssistantMessage assistant -> new SDKMessage.Assistant(assistant, Usage.EMPTY);
            case SystemMessage system -> new SDKMessage.System(system);
            default -> null;
        };
    }

    /**
     * Reads a sub-agent's own transcript.
     */
    static List<Message> loadAgentTranscript(
            Path transcript,
            String agentId,
            InteractiveSessionPort sessions) {
        return sessions == null ? List.of() : sessions.readAgentMessages(transcript, agentId);
    }

    static long transcriptStamp(Path transcript) {
        try {
            if (transcript == null || !Files.isRegularFile(transcript)) return -1L;
            long size = Files.size(transcript);
            long modified = FileUtils.modificationTimeMillis(transcript);
            return (modified * 31L) ^ size;
        } catch (Exception _) {
            return -1L;
        }
    }

    private void refreshComplete() {
        try {
            screen.refresh(Screen.RefreshType.COMPLETE);
        } catch (Exception _) {
            // Refresh failure is non-fatal; the next GUI frame will repaint.
        }
    }
}
