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
import com.claudecode.tools.tasks.TaskRegistry;
import com.claudecode.tools.tasks.TaskState;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
        if (taskId != null) {
            taskRegistry.getTeammateHandle(taskId).ifPresent(teammate -> {
                teammate.setTranscriptListener(this::requestRebuild);
                activeTeammateTaskId = taskId;
            });
            if (ViewedTeammateHolder.instance().isViewingLocalAgent()
                    && agentTranscriptResolver != null) {
                startLocalAgentWatcher(agentTranscriptResolver.apply(taskId));
            }
        }
    }

    private void startLocalAgentWatcher(Path transcript) {
        if (transcript == null) return;
        activeLocalAgentTranscript = transcript;
        activeLocalAgentStamp = transcriptStamp(transcript);
        localAgentWatchFuture = localAgentWatcher.scheduleWithFixedDelay(() -> {
            Path active = activeLocalAgentTranscript;
            if (active == null || !ViewedTeammateHolder.instance().isViewingLocalAgent()) return;
            long next = transcriptStamp(active);
            if (next != activeLocalAgentStamp) {
                activeLocalAgentStamp = next;
                requestRebuild();
            }
        }, 250L, 250L, TimeUnit.MILLISECONDS);
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
            loadAgentTranscript(transcript, taskId, sessions),
            localAgentPendingInput.getOrDefault(taskId, List.of()));
        visibleHistoryTaskId = taskId;
        visiblePromptHistory = promptHistoryFromMessages(messages);
        for (Message message : messages) {
            SDKMessage sdkMessage = toSdkMessage(message);
            if (sdkMessage != null) collapser.dispatch(sdkMessage, messagePanel);
        }
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
