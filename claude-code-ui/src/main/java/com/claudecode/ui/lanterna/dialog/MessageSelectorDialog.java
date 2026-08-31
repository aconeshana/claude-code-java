package com.claudecode.ui.lanterna.dialog;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.commands.XmlConstants;
import com.claudecode.core.diff.FileChangeResult;
import com.claudecode.core.diff.StructuredPatchHunk;
import com.claudecode.core.engine.FileHistoryManager;
import com.claudecode.core.message.ContentBlock;
import com.claudecode.core.message.HumanTurns;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageConstants;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.UserMessage;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.core.text.DisplayTagUtils;
import com.claudecode.core.text.FormatUtils;
import com.claudecode.core.text.XmlTagUtils;
import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.gui2.AbstractComponent;
import com.googlecode.lanterna.gui2.ComponentRenderer;
import com.googlecode.lanterna.gui2.Direction;
import com.googlecode.lanterna.gui2.Interactable;
import com.googlecode.lanterna.gui2.LinearLayout;
import com.googlecode.lanterna.gui2.Panel;
import com.googlecode.lanterna.gui2.TextGUIGraphics;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import com.claudecode.ui.lanterna.overlay.InlineOverlay;
import com.claudecode.ui.lanterna.components.SpinnerFrames;
import com.claudecode.ui.lanterna.theme.LanternaTheme;
import com.claudecode.ui.lanterna.input.ContextKeybindingDispatcher;
import com.claudecode.keybindings.KeybindingHints;
import com.claudecode.keybindings.UserKeybindingsStore;
import com.claudecode.ui.lanterna.input.InputPanel;
import com.claudecode.ui.lanterna.input.SearchInput;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Inline {@code /rewind} picker — sits in the SmartLayout stack just above {@link InputPanel},
 * occupying zero rows when idle.
 */
public final class MessageSelectorDialog extends Panel implements InlineOverlay {

    private static final int DEFAULT_TERMINAL_ROWS = 26;
    private static final int DEFAULT_TERMINAL_COLUMNS = 60;
    private static final int MAX_VISIBLE_OPTIONS = 5;
    private static final int MAX_CONFIRMATION_CHARS = 500;
    private static final int MAX_CONFIRMATION_SOURCE_LINES = 4;
    private static final int LEFT_PAD = 2;
    private static final long EXIT_DOUBLE_PRESS_MS = 800;
    private static final long SPINNER_FRAME_MS = 120;
    private static final List<String> SUMMARIZING_SPINNER_FRAMES =
        SpinnerFrames.defaultAnimationFrames();

    /** A selectable message entry, or the trailing virtual "(current)" placeholder. */
    record Entry(String display, UserMessage message, boolean isCurrent, boolean isPreviousSession,
                 FileHistoryManager.DiffStats codeDiff, boolean codeRestoreAvailable) {
        Entry(String display, UserMessage message, FileHistoryManager.DiffStats codeDiff,
              boolean codeRestoreAvailable) {
            this(display, message, false, false, codeDiff, codeRestoreAvailable);
        }

        static Entry current() {
            return new Entry("(current)", null, true, false, null, false);
        }

        static Entry previousSession(String sessionId) {
            return new Entry("/resume " + sessionId + " (previous session)",
                null, false, true, null, false);
        }
    }

    /** Action the user picked on the restore-options menu. */
    public enum RestoreAction {
        RESTORE_CONVERSATION,          // rewind messages + textForResubmit (default)
        RESTORE_CODE,                  // rewind tracked files only, conversation unchanged
        RESTORE_CODE_AND_CONVERSATION,
        SUMMARIZE_FROM,                // partial-compact FROM the picked message onward
        SUMMARIZE_UP_TO                // partial-compact UP TO the picked message
    }

    /** Final selection returned to the caller. Never carries a Summarize action —
     *  those resolve internally via {@link SummarizeExecutor} and hide the dialog
     *  with a {@code null} result. */
    public record Selection(UserMessage message, RestoreAction action) {}

    /**
     * Executes a confirmed Summarize action. Implementations do the real work (partial
     * compact) off the GUI thread and must invoke exactly one of {@code onSuccess}/
     * {@code onFailure} — already marshaled onto the Lanterna GUI thread — when done, so
     * the dialog's own field mutations ({@code hide()}, label updates) stay thread-safe.
     */
    public interface SummarizeExecutor {
        void execute(UserMessage message, RestoreAction action, String feedback,
                     Runnable onSuccess, Consumer<String> onFailure);
    }

    /** Executes a confirmed code/conversation restore while the selector owns loading/error UI. */
    public interface RestoreExecutor {
        void execute(UserMessage message, RestoreAction action,
                     Runnable onSuccess, Consumer<String> onFailure);
    }

    private enum Phase {
        PICK_MESSAGE, PICK_OPTION, SUMMARIZING, RESTORING, RESTORE_ERROR
    }

    /** Options list entry for the options phase. */
    private record Option(String label, RestoreAction action) {}

    private final Body body;
    private IntSupplier terminalRowsSupplier = () -> DEFAULT_TERMINAL_ROWS;
    private IntSupplier terminalColumnsSupplier = () -> DEFAULT_TERMINAL_COLUMNS;
    private int lastMeasuredTerminalRows = -1;
    private int lastMeasuredTerminalColumns = -1;

    private boolean active;
    private Phase phase;
    private List<Entry> entries = List.of();
    private boolean hasRealMessages;
    private int   selectedIndex;
    private int   scrollOffset;
    private UserMessage pickedMessage = null;
    private List<Option> optionList = List.of();
    private int   optionSelectedIndex = 0;
    private final SearchInput summarizeFromFeedback;
    private final SearchInput summarizeUpToFeedback;
    private String summarizeError = null;
    private SummarizeExecutor summarizeExecutor;
    private RestoreExecutor restoreExecutor;
    private String restoreError;
    private Runnable onResumePreviousSession;
    private Consumer<Selection> onResult;
    private boolean preselected;
    private Runnable onPreRestore = () -> {};
    private boolean preRestoreRan;
    private Consumer<Runnable> guiInvoker;
    private long diffLoadGeneration;
    private Supplier<? extends List<Message>> liveMessagesSupplier;
    private List<Message> lastLiveMessages = List.of();
    private List<FileHistoryManager.Snapshot> lastFileHistorySnapshots = List.of();
    private String parentSessionId;

    /** Non-null enables checkpoint diff inspection; code actions still require changed files. */
    private FileHistoryManager fileHistoryManager;
    /** Diff preview for {@link #pickedMessage}, computed once on entering {@link Phase#PICK_OPTION}. */
    private FileHistoryManager.DiffStats diffPreview;
    private boolean canRestoreCode;


    private volatile String pendingExitKey = null;
    private long lastInterruptPress;
    private long lastExitPress;
    private LongSupplier currentTimeMillis = System::currentTimeMillis;
    private Runnable exitAction = () -> {};
    private final ScheduledExecutorService exitTimer =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "rewind-exit-timer");
            t.setDaemon(true);
            return t;
        });
    private ScheduledFuture<?> interruptExitTimerFuture;
    private ScheduledFuture<?> exitExitTimerFuture;
    private ScheduledFuture<?> summarizingSpinnerFuture;
    private int summarizingSpinnerFrame;
    private final ContextKeybindingDispatcher keybindings =
        new ContextKeybindingDispatcher();
    private UserKeybindingsStore keybindingsStore;

    private enum ExitGesture {
        INTERRUPT, EXIT
    }

    public MessageSelectorDialog() {
        super(new LinearLayout(Direction.VERTICAL).setSpacing(0));
        SearchInput.Listener feedbackListener = new SearchInput.Listener() {
            @Override public void onExit() { }
            @Override public void onChange() { invalidate(); }
        };
        this.summarizeFromFeedback = new SearchInput(feedbackListener, false);
        this.summarizeUpToFeedback = new SearchInput(feedbackListener, false);
        this.body = new Body();
        this.body.setLayoutData(LinearLayout.createLayoutData(LinearLayout.Alignment.FILL));
        addComponent(body);
    }

    public void setKeybindingsStore(UserKeybindingsStore store) {
        keybindingsStore = store;
        keybindings.setStore(store);
    }

    public synchronized void setGuiInvoker(Consumer<Runnable> invoker) {
        guiInvoker = invoker;
    }

    public synchronized void setExitAction(Runnable action) {
        exitAction = action != null ? action : () -> {};
    }

    synchronized void setCurrentTimeMillisForTest(LongSupplier supplier) {
        currentTimeMillis = supplier != null ? supplier : System::currentTimeMillis;
    }

    public void setTerminalRowsSupplier(IntSupplier supplier) {
        terminalRowsSupplier = supplier != null ? supplier : () -> DEFAULT_TERMINAL_ROWS;
        lastMeasuredTerminalRows = -1;
        invalidate();
    }

    public void setTerminalColumnsSupplier(IntSupplier supplier) {
        terminalColumnsSupplier = supplier != null ? supplier : () -> DEFAULT_TERMINAL_COLUMNS;
        lastMeasuredTerminalColumns = -1;
        invalidate();
    }

    /**
     * Activate the dialog against the engine's current message list and wire the result callback.
     */
    public synchronized void show(List<Message> allMessages, SummarizeExecutor summarizeExecutor,
                                   Consumer<Selection> onResult) {
        show(allMessages, null, null, summarizeExecutor, onResult);
    }

    /**
     * Full overload: a non-null {@code fileHistoryManager} enables checkpoint diff inspection.
     */
    public synchronized void show(List<Message> allMessages, FileHistoryManager fileHistoryManager,
                                   SummarizeExecutor summarizeExecutor, Consumer<Selection> onResult) {
        show(allMessages, fileHistoryManager, null, summarizeExecutor, onResult);
    }

    public synchronized void show(List<Message> allMessages, FileHistoryManager fileHistoryManager,
                                   RestoreExecutor restoreExecutor,
                                   SummarizeExecutor summarizeExecutor,
                                   Consumer<Selection> onResult) {
        show(allMessages, fileHistoryManager, restoreExecutor, summarizeExecutor, onResult,
            null, null);
    }

    public synchronized void show(List<Message> allMessages, FileHistoryManager fileHistoryManager,
                                   RestoreExecutor restoreExecutor,
                                   SummarizeExecutor summarizeExecutor,
                                   Consumer<Selection> onResult,
                                   String parentSessionId,
                                   Runnable onResumePreviousSession) {
        show(allMessages, fileHistoryManager, restoreExecutor, summarizeExecutor, onResult,
            parentSessionId, onResumePreviousSession, null);
    }

    public synchronized void show(List<Message> allMessages, FileHistoryManager fileHistoryManager,
                                   RestoreExecutor restoreExecutor,
                                   SummarizeExecutor summarizeExecutor,
                                   Consumer<Selection> onResult,
                                   String parentSessionId,
                                   Runnable onResumePreviousSession,
                                   Runnable onPreRestore) {
        liveMessagesSupplier = null;
        initialize(allMessages, fileHistoryManager, restoreExecutor, summarizeExecutor, onResult,
            parentSessionId, onResumePreviousSession);
        activateMessagePicker(onPreRestore);
    }

    /**
     * Live-state overload matching 2.1.197's reactive {@code messages} prop. The numeric cursor is
     * intentionally preserved when rows are inserted or removed while the picker is open.
     */
    public synchronized void show(
            Supplier<? extends List<Message>> allMessages,
            FileHistoryManager fileHistoryManager,
            RestoreExecutor restoreExecutor,
            SummarizeExecutor summarizeExecutor,
            Consumer<Selection> onResult,
            String parentSessionId,
            Runnable onResumePreviousSession,
            Runnable onPreRestore) {
        liveMessagesSupplier = allMessages != null ? allMessages : List::of;
        initialize(readLiveMessages(), fileHistoryManager, restoreExecutor, summarizeExecutor,
            onResult, parentSessionId, onResumePreviousSession);
        activateMessagePicker(onPreRestore);
    }

    private void activateMessagePicker(Runnable onPreRestore) {
        this.preselected = false;
        this.onPreRestore = onPreRestore != null ? onPreRestore : () -> {};
        this.preRestoreRan = false;
        this.selectedIndex = entries.isEmpty() ? 0 : entries.size() - 1;
        this.active = true;
        enterMessagePhase();
    }

    /**
     * Opens directly on the restore-options phase for Message Actions.
     */
    public synchronized void showPreselected(
            List<Message> allMessages,
            FileHistoryManager fileHistoryManager,
            UserMessage preselectedMessage,
            Runnable onPreRestore,
            SummarizeExecutor summarizeExecutor,
            Consumer<Selection> onResult) {
        showPreselected(allMessages, fileHistoryManager, preselectedMessage, onPreRestore,
            null, summarizeExecutor, onResult);
    }

    public synchronized void showPreselected(
            List<Message> allMessages,
            FileHistoryManager fileHistoryManager,
            UserMessage preselectedMessage,
            Runnable onPreRestore,
            RestoreExecutor restoreExecutor,
            SummarizeExecutor summarizeExecutor,
            Consumer<Selection> onResult) {
        liveMessagesSupplier = null;
        initialize(allMessages, fileHistoryManager, restoreExecutor, summarizeExecutor, onResult,
            null, null);
        activatePreselected(preselectedMessage, onPreRestore);
    }

    public synchronized void showPreselected(
            Supplier<? extends List<Message>> allMessages,
            FileHistoryManager fileHistoryManager,
            UserMessage preselectedMessage,
            Runnable onPreRestore,
            RestoreExecutor restoreExecutor,
            SummarizeExecutor summarizeExecutor,
            Consumer<Selection> onResult) {
        liveMessagesSupplier = allMessages != null ? allMessages : List::of;
        initialize(readLiveMessages(), fileHistoryManager, restoreExecutor, summarizeExecutor,
            onResult, null, null);
        activatePreselected(preselectedMessage, onPreRestore);
    }

    private void activatePreselected(UserMessage preselectedMessage, Runnable onPreRestore) {
        this.preselected = true;
        this.onPreRestore = onPreRestore != null ? onPreRestore : () -> {};
        this.preRestoreRan = false;
        this.pickedMessage = preselectedMessage;
        this.active = true;
        enterOptionsPhase();
    }

    private void initialize(List<Message> allMessages, FileHistoryManager fileHistoryManager,
                            RestoreExecutor restoreExecutor,
                            SummarizeExecutor summarizeExecutor, Consumer<Selection> onResult,
                            String parentSessionId, Runnable onResumePreviousSession) {
        diffLoadGeneration++;
        pickedMessage = null;
        diffPreview = null;
        canRestoreCode = false;
        this.fileHistoryManager = fileHistoryManager;
        this.parentSessionId = parentSessionId;
        this.onResumePreviousSession = onResumePreviousSession;
        rebuildEntries(allMessages);
        this.restoreExecutor = restoreExecutor;
        this.summarizeExecutor = summarizeExecutor;
        this.onResult = onResult;
        this.scrollOffset = 0;
        this.summarizeFromFeedback.reset("");
        this.summarizeUpToFeedback.reset("");
    }

    private void rebuildEntries(List<Message> allMessages) {
        rebuildEntries(allMessages, readFileHistorySnapshots());
    }

    private void rebuildEntries(
            List<Message> allMessages, List<FileHistoryManager.Snapshot> fileHistorySnapshots) {
        List<Entry> built = new ArrayList<>();
        if (StringUtils.isNotBlank(parentSessionId) && onResumePreviousSession != null) {
            built.add(Entry.previousSession(parentSessionId));
        }
        List<UserMessage> selectableMessages = allMessages.stream()
            .filter(UserMessage.class::isInstance)
            .map(UserMessage.class::cast)
            .filter(MessageSelectorDialog::isSelectable)
            .toList();
        for (int index = 0; index < selectableMessages.size(); index++) {
            UserMessage um = selectableMessages.get(index);
            String display = messageDisplay(um, true);
            boolean canRestore = fileHistoryManager != null
                && fileHistorySnapshots.stream()
                    .anyMatch(snapshot -> snapshot.messageId().equals(um.uuid()));
            FileHistoryManager.DiffStats codeDiff = canRestore
                ? computeDiffStatsBetweenMessages(allMessages, um.uuid(),
                    index + 1 < selectableMessages.size()
                        ? selectableMessages.get(index + 1).uuid() : null)
                : null;
            built.add(new Entry(display, um, codeDiff, canRestore));
        }
        this.hasRealMessages = !built.isEmpty();
        // The official selector always carries a trailing virtual current row; it is hidden when
        // there is no previous target and remains the safe default selection otherwise.
        built.add(Entry.current());
        this.entries = built;
        this.lastLiveMessages = List.copyOf(allMessages);
        this.lastFileHistorySnapshots = fileHistorySnapshots;
    }

    private synchronized void refreshLiveEntries() {
        if (!active || liveMessagesSupplier == null) return;
        List<Message> latest = readLiveMessages();
        List<FileHistoryManager.Snapshot> fileHistorySnapshots = readFileHistorySnapshots();
        if (sameMessageIdentities(lastLiveMessages, latest)
                && lastFileHistorySnapshots.equals(fileHistorySnapshots)) {
            return;
        }
        rebuildEntries(latest, fileHistorySnapshots);
        body.invalidate();
        invalidate();
    }

    private List<FileHistoryManager.Snapshot> readFileHistorySnapshots() {
        return fileHistoryManager == null ? List.of() : fileHistoryManager.snapshotsView();
    }

    private List<Message> readLiveMessages() {
        List<Message> messages = liveMessagesSupplier != null
            ? liveMessagesSupplier.get() : lastLiveMessages;
        return messages == null ? List.of() : List.copyOf(messages);
    }

    private static boolean sameMessageIdentities(List<Message> left, List<Message> right) {
        if (left.size() != right.size()) return false;
        for (int index = 0; index < left.size(); index++) {
            if (left.get(index) != right.get(index)) return false;
        }
        return true;
    }

    private static String messageDisplay(UserMessage message, boolean truncateToRow) {
        return messageDisplay(message, truncateToRow, DEFAULT_TERMINAL_COLUMNS - 10);
    }

    private static String messageDisplay(
            UserMessage message, boolean truncateToRow, int rowWidth) {
        String raw = joinedTextBlocks(message.message());
        String text = DisplayTagUtils.stripDisplayTags(
            raw == null ? "(no prompt)" : raw);
        if (MessageConstants.isEmptyMessageText(text)) return "((empty message))";

        String bash = XmlTagUtils.extractTag(text, XmlConstants.BASH_INPUT_TAG).orElse(null);
        if (bash != null) return "! " + bash;
        String command = XmlTagUtils.extractTag(text, XmlConstants.COMMAND_MESSAGE_TAG).orElse(null);
        if (command != null) {
            boolean skill = Strings.CS.equals("true",
                XmlTagUtils.extractTag(text, XmlConstants.SKILL_FORMAT_TAG).orElse(null));
            if (skill) return "Skill(" + command + ")";
            String args = XmlTagUtils.extractTag(text, XmlConstants.COMMAND_ARGS_TAG).orElse("");
            return "/" + command + " " + args;
        }
        return truncateToRow ? truncate(text, rowWidth) : text;
    }

    /** Matches 2.1.197's text extraction: join every text block and ignore non-text blocks. */
    private static String joinedTextBlocks(MessageContent content) {
        if (content == null) return null;
        if (content.text() != null) return content.text();
        List<ContentBlock> blocks = content.blocks();
        if (blocks == null || blocks.isEmpty()) return null;
        String joined = blocks.stream()
            .filter(TextBlock.class::isInstance)
            .map(TextBlock.class::cast)
            .map(TextBlock::text)
            .collect(java.util.stream.Collectors.joining("\n"));
        return StringUtils.trimToNull(joined);
    }

    /** Aggregates structured Edit/Write results after one selectable prompt and before the next. */
    private static FileHistoryManager.DiffStats computeDiffStatsBetweenMessages(
            List<Message> messages, String fromMessageId, String toMessageId) {
        int startIndex = indexOfMessage(messages, fromMessageId);
        if (startIndex < 0) return FileHistoryManager.DiffStats.EMPTY;
        int endIndex = toMessageId == null ? messages.size() : indexOfMessage(messages, toMessageId);
        if (endIndex < 0) endIndex = messages.size();

        Set<String> filesChanged = new LinkedHashSet<>();
        int insertions = 0;
        int deletions = 0;
        for (int index = startIndex + 1; index < endIndex; index++) {
            if (!(messages.get(index) instanceof UserMessage user)
                    || !MessageConstants.isToolUseResultMessage(user)) {
                continue;
            }
            FileDiffContribution contribution = fileDiffContribution(user.toolUseResult());
            if (contribution == null) continue;
            filesChanged.add(contribution.filePath());
            insertions += contribution.insertions();
            deletions += contribution.deletions();
        }
        return new FileHistoryManager.DiffStats(
            List.copyOf(filesChanged), insertions, deletions);
    }

    private static int indexOfMessage(List<Message> messages, String uuid) {
        for (int index = 0; index < messages.size(); index++) {
            if (Strings.CS.equals(messages.get(index).uuid(), uuid)) return index;
        }
        return -1;
    }

    private record FileDiffContribution(String filePath, int insertions, int deletions) {}

    private static FileDiffContribution fileDiffContribution(Object payload) {
        if (payload instanceof FileChangeResult result) {
            if (result.filePath() == null || result.filePath().isEmpty()
                    || result.structuredPatch() == null) {
                return null;
            }
            if (Strings.CS.equals("create", result.type())) {
                int lines = result.content() == null
                    ? 0 : result.content().split("\\r?\\n", -1).length;
                return new FileDiffContribution(result.filePath(), lines, 0);
            }
            int insertions = 0;
            int deletions = 0;
            for (StructuredPatchHunk hunk : result.structuredPatch()) {
                insertions += hunk.addedCount();
                deletions += hunk.removedCount();
            }
            return new FileDiffContribution(result.filePath(), insertions, deletions);
        }
        if (payload == null) return null;
        try {
            JsonNode tree = JsonUtils.getMapper().valueToTree(payload);
            JsonNode filePath = tree.get("filePath");
            JsonNode structuredPatch = tree.get("structuredPatch");
            if (!tree.isObject() || filePath == null || !filePath.isTextual()
                    || filePath.textValue().isEmpty() || !isJsonTruthy(structuredPatch)) {
                return null;
            }
            if (tree.path("type").isTextual()
                    && Strings.CS.equals("create", tree.path("type").textValue())) {
                JsonNode content = tree.get("content");
                int lines = content != null && content.isTextual()
                    ? content.textValue().split("\\r?\\n", -1).length : 0;
                return new FileDiffContribution(filePath.textValue(), lines, 0);
            }

            int insertions = 0;
            int deletions = 0;
            if (structuredPatch.isArray()) {
                patchLoop:
                for (JsonNode hunk : structuredPatch) {
                    JsonNode lines = hunk.get("lines");
                    if (lines == null || !lines.isArray()) break;
                    for (JsonNode line : lines) {
                        if (!line.isTextual()) break patchLoop;
                        if (Strings.CS.startsWith(line.textValue(), "+")) insertions++;
                        if (Strings.CS.startsWith(line.textValue(), "-")) deletions++;
                    }
                }
            }
            return new FileDiffContribution(filePath.textValue(), insertions, deletions);
        } catch (IllegalArgumentException _) {
            return null;
        }
    }

    private static boolean isJsonTruthy(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) return false;
        if (value.isBoolean()) return value.booleanValue();
        if (value.isNumber()) return value.doubleValue() != 0 && !Double.isNaN(value.doubleValue());
        if (value.isTextual()) return !value.textValue().isEmpty();
        return true;
    }

    @Override public boolean isActive() { return active; }


    public static boolean isSelectable(UserMessage um) {
        return HumanTurns.isTypedTurn(um);
    }

    // ── phase transitions ───────────────────────────────────────────────────────

    private void enterMessagePhase() {
        this.phase = Phase.PICK_MESSAGE;
        this.optionList = List.of();
        this.scrollOffset = 0;
        invalidate();
    }

    /**
     * Populate {@link #optionList} for the second phase and reset the cursor.
     */
    private void enterOptionsPhase() {
        if (fileHistoryManager == null || guiInvoker == null) {
            applyDiffPreview(fileHistoryManager != null ? computeDiffPreview(pickedMessage) : null);
            return;
        }

        UserMessage target = pickedMessage;
        long generation = ++diffLoadGeneration;
        if (preselected) {
            applyDiffPreview(null);
        }
        Consumer<Runnable> invoker = guiInvoker;
        Thread.ofVirtual().name("rewind-diff-preview").start(() -> {
            FileHistoryManager.DiffStats loaded = computeDiffPreview(target);
            invoker.accept(() -> applyLoadedDiffPreview(generation, target, loaded));
        });
    }

    private synchronized void applyLoadedDiffPreview(
            long generation, UserMessage target, FileHistoryManager.DiffStats loaded) {
        if (!active || generation != diffLoadGeneration || pickedMessage != target) return;
        applyDiffPreview(loaded);
    }

    private void applyDiffPreview(FileHistoryManager.DiffStats loaded) {
        this.diffPreview = loaded;
        this.canRestoreCode = diffPreview != null
            && diffPreview.filesChanged() != null
            && !diffPreview.filesChanged().isEmpty();
        List<Option> opts = new ArrayList<>();
        if (canRestoreCode) {
            opts.add(new Option("Restore code and conversation", RestoreAction.RESTORE_CODE_AND_CONVERSATION));
            opts.add(new Option("Restore conversation",          RestoreAction.RESTORE_CONVERSATION));
            opts.add(new Option("Restore code",                  RestoreAction.RESTORE_CODE));
        } else {
            opts.add(new Option("Restore conversation",          RestoreAction.RESTORE_CONVERSATION));
        }
        opts.add(new Option("Summarize from here",        RestoreAction.SUMMARIZE_FROM));
        opts.add(new Option("Summarize up to here",       RestoreAction.SUMMARIZE_UP_TO));
        opts.add(new Option("Never mind",                 null)); // null → cancel to list
        this.optionList = opts;
        this.optionSelectedIndex = 0;
        this.scrollOffset = 0;
        this.phase = Phase.PICK_OPTION;
        invalidate();
    }

    private FileHistoryManager.DiffStats computeDiffPreview(UserMessage target) {
        try {
            if (target == null || !fileHistoryManager.canRestore(target.uuid())) return null;
            return fileHistoryManager.getDiffStats(target.uuid());
        } catch (Exception _) {
            return null;
        }
    }

    private static boolean isSummarizeAction(RestoreAction action) {
        return action == RestoreAction.SUMMARIZE_FROM
            || action == RestoreAction.SUMMARIZE_UP_TO;
    }

    private SearchInput feedbackFor(RestoreAction action) {
        return action == RestoreAction.SUMMARIZE_UP_TO
            ? summarizeUpToFeedback : summarizeFromFeedback;
    }

    /**
     * Confirms a Summarize action and hands it to {@link #summarizeExecutor}.
     */
    private void enterSummarizingPhase(RestoreAction action, String feedback) {
        runPreRestoreOnce();
        this.phase = Phase.SUMMARIZING;
        this.summarizeError = null;
        startSummarizingSpinner();
        invalidate();

        if (summarizeExecutor == null) {
            // Defensive fallback — production wiring always sets this.
            resolve(null);
            return;
        }
        summarizeExecutor.execute(pickedMessage, action, feedback,
            () -> resolve(null),
            this::showSummarizeError);
    }

    private synchronized void showSummarizeError(String errMsg) {
        if (!active || phase != Phase.SUMMARIZING) return;
        stopSummarizingSpinner();
        summarizeError = "Failed to summarize:\n" + StringUtils.defaultString(errMsg);
        invalidate();
    }

    private synchronized void startSummarizingSpinner() {
        stopSummarizingSpinner();
        summarizingSpinnerFrame = 0;
        if (SpinnerFrames.REDUCED_MOTION) return;
        summarizingSpinnerFuture = exitTimer.scheduleAtFixedRate(
            this::advanceSummarizingSpinner, SPINNER_FRAME_MS, SPINNER_FRAME_MS,
            TimeUnit.MILLISECONDS);
    }

    private void advanceSummarizingSpinner() {
        Consumer<Runnable> invoker;
        synchronized (this) {
            if (!active || phase != Phase.SUMMARIZING || summarizeError != null) return;
            invoker = guiInvoker;
        }
        Runnable advance = () -> {
            synchronized (MessageSelectorDialog.this) {
                if (!active || phase != Phase.SUMMARIZING || summarizeError != null) return;
                summarizingSpinnerFrame++;
                invalidate();
            }
        };
        if (invoker != null) invoker.accept(advance);
        else advance.run();
    }

    private synchronized void stopSummarizingSpinner() {
        if (summarizingSpinnerFuture == null) return;
        summarizingSpinnerFuture.cancel(false);
        summarizingSpinnerFuture = null;
    }


    private static String describeOption(RestoreAction action) {
        if (action == null) return "The conversation will be unchanged.";
        return switch (action) {
            case RESTORE_CONVERSATION, RESTORE_CODE_AND_CONVERSATION -> "The conversation will be forked.";
            case RESTORE_CODE -> "The conversation will be unchanged.";
            case SUMMARIZE_FROM -> "Messages after this point will be summarized.";
            case SUMMARIZE_UP_TO -> "Preceding messages will be summarized. This and subsequent "
                + "messages will remain unchanged — you will stay at the end of the conversation.";
        };
    }


    private String describeOptionSecondLine(RestoreAction action) {
        if (action == null) return "The code will be unchanged.";
        return switch (action) {
            case RESTORE_CONVERSATION -> "The code will be unchanged.";
            case RESTORE_CODE, RESTORE_CODE_AND_CONVERSATION -> showsCodeRestore(action)
                ? describeCodeRestore() : "The code will be unchanged.";
            case SUMMARIZE_FROM, SUMMARIZE_UP_TO -> null;
        };
    }

    private boolean showsCodeRestore(RestoreAction action) {
        return canRestoreCode
            && (action == RestoreAction.RESTORE_CODE
                || action == RestoreAction.RESTORE_CODE_AND_CONVERSATION);
    }


    private String describeCodeRestore() {
        if (diffPreview == null) return null;
        if (diffPreview.filesChanged().isEmpty()) {
            return "The code has not changed (nothing will be restored).";
        }
        return "The code will be restored +" + diffPreview.insertions() + " -" + diffPreview.deletions()
            + " in " + fileLabel(diffPreview.filesChanged()) + ".";
    }

    private static String fileLabel(List<String> filesChanged) {
        if (filesChanged.size() == 1) return baseName(filesChanged.getFirst());
        if (filesChanged.size() == 2) return baseName(filesChanged.getFirst()) + " and " + baseName(filesChanged.get(1));
        return baseName(filesChanged.getFirst()) + " and " + (filesChanged.size() - 1) + " other files";
    }

    private static String baseName(String path) {
        int idx = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return idx >= 0 ? path.substring(idx + 1) : path;
    }

    @Override
    public synchronized void handleKey(KeyStroke key, AtomicBoolean deliver) {
        if (!active) return;
        refreshLiveEntries();

        switch (phase) {
            case PICK_MESSAGE -> handlePickMessageKey(key, deliver);
            case PICK_OPTION -> handlePickOptionKey(key, deliver);
            case SUMMARIZING -> {
                if (!handleGlobalKey(key, deliver)) handleSummarizingKey(key, deliver);
            }
            case RESTORING -> {
                boolean handled = handleGlobalKey(key, deliver);
                if (!handled && optionList.isEmpty() && key.getKeyType() == KeyType.ESCAPE) {
                    resolve(null);
                }
                deliver.set(false);
            }
            case RESTORE_ERROR -> {
                if (!handleGlobalKey(key, deliver)) handleRestoreErrorKey(key, deliver);
            }
        }
    }

    private void handlePickMessageKey(KeyStroke key, AtomicBoolean deliver) {
        ContextKeybindingDispatcher.Result resolved =
            keybindings.resolve("MessageSelector", key);
        if (resolved instanceof ContextKeybindingDispatcher.Result.Consumed) {
            deliver.set(false);
            return;
        }
        if (resolved instanceof ContextKeybindingDispatcher.Result.Action(String action)) {
            if (dispatchGlobalAction(action) || dispatchMessageSelectorAction(action)) {
                deliver.set(false);
                return;
            }
        }
        if (handleNativeExitKey(key, deliver)) return;
        if (key.getKeyType() == KeyType.ESCAPE) {
            resolve(null);
            deliver.set(false);
            return;
        }
        if (entries.isEmpty()) return;
        if (isUpKey(key)) { moveSelection(-1); deliver.set(false); return; }
        if (isDownKey(key)) { moveSelection(1); deliver.set(false); return; }
        if (key.getKeyType() == KeyType.ENTER) {
            if (hasRealMessages && selectedIndex >= 0 && selectedIndex < entries.size()) {
                selectMessageEntry(entries.get(selectedIndex));
            }
            deliver.set(false);
        }
    }

    private void selectMessageEntry(Entry picked) {
        if (picked.isCurrent()) {
            resolve(null);
        } else if (picked.isPreviousSession()) {
            resumePreviousSession();
        } else {
            pickedMessage = picked.message();
            if (fileHistoryManager == null) {
                beginRestoreOrResolve(RestoreAction.RESTORE_CONVERSATION);
            } else {
                enterOptionsPhase();
            }
        }
    }

    private boolean dispatchMessageSelectorAction(String action) {
        if (!hasRealMessages) return Strings.CS.startsWith(action, "messageSelector:");
        return switch (action) {
            case "messageSelector:up" -> { moveSelection(-1); yield true; }
            case "messageSelector:down" -> { moveSelection(1); yield true; }
            case "messageSelector:top" -> {
                selectedIndex = 0;
                invalidate();
                yield true;
            }
            case "messageSelector:bottom" -> {
                selectedIndex = entries.size() - 1;
                invalidate();
                yield true;
            }
            case "messageSelector:select" -> {
                if (selectedIndex >= 0 && selectedIndex < entries.size()) {
                    selectMessageEntry(entries.get(selectedIndex));
                }
                yield true;
            }
            default -> false;
        };
    }

    private void handlePickOptionKey(KeyStroke key, AtomicBoolean deliver) {
        Option focused = optionList.get(optionSelectedIndex);
        boolean inputFocused = isSummarizeAction(focused.action());
        ContextKeybindingDispatcher.Result resolved = keybindings.resolve("Select", key);
        if (resolved instanceof ContextKeybindingDispatcher.Result.Consumed) {
            deliver.set(false);
            return;
        }
        if (resolved instanceof ContextKeybindingDispatcher.Result.Action(String action)
                && (dispatchGlobalAction(action)
                    || dispatchSelectAction(action, focused, inputFocused))) {
            deliver.set(false);
            return;
        }
        if (handleNativeExitKey(key, deliver)) return;
        if (key.getKeyType() == KeyType.ESCAPE) {
            cancelOptionPhase();
            deliver.set(false);
            return;
        }
        if (inputFocused) {
            if (isInputUpKey(key)) { moveOptionSelection(-1); deliver.set(false); return; }
            if (isInputDownKey(key)) { moveOptionSelection(1); deliver.set(false); return; }
            handleSummarizeInputKey(key, focused.action(), deliver);
            return;
        }
        if (isUpKey(key)) { moveOptionSelection(-1); deliver.set(false); return; }
        if (isDownKey(key)) { moveOptionSelection(1); deliver.set(false); return; }
        if (key.getKeyType() == KeyType.PAGE_UP) {
            moveOptionPage(-1);
            deliver.set(false);
            return;
        }
        if (key.getKeyType() == KeyType.PAGE_DOWN) {
            moveOptionPage(1);
            deliver.set(false);
            return;
        }
        int digit = plainDigitValue(key);
        if (digit >= 0) {
            int optionIndex = digit - 1;
            if (optionIndex >= 0 && optionIndex < optionList.size()) {
                selectOption(optionList.get(optionIndex));
            }
            deliver.set(false);
            return;
        }
        if (key.getKeyType() == KeyType.ENTER) {
            selectOption(focused);
            deliver.set(false);
        }
    }

    private boolean dispatchSelectAction(String action, Option focused, boolean inputFocused) {
        return switch (action) {
            case "select:previous" -> {
                if (inputFocused) yield false;
                moveOptionSelection(-1);
                yield true;
            }
            case "select:next" -> {
                if (inputFocused) yield false;
                moveOptionSelection(1);
                yield true;
            }
            case "select:pageUp" -> {
                if (inputFocused) yield false;
                moveOptionPage(-1);
                yield true;
            }
            case "select:pageDown" -> {
                if (inputFocused) yield false;
                moveOptionPage(1);
                yield true;
            }
            case "select:first" -> {
                if (inputFocused) yield false;
                optionSelectedIndex = 0;
                invalidate();
                yield true;
            }
            case "select:last" -> {
                if (inputFocused) yield false;
                optionSelectedIndex = optionList.size() - 1;
                invalidate();
                yield true;
            }
            case "select:accept" -> {
                selectOption(focused);
                yield true;
            }
            case "select:cancel" -> {
                cancelOptionPhase();
                yield true;
            }
            default -> false;
        };
    }

    private void cancelOptionPhase() {
        if (preselected) resolve(null);
        else enterMessagePhase();
    }

    private void selectOption(Option option) {
        if (option.action() == null) {
            // A preselected Message Actions edit closes outright; ordinary
            // /rewind returns to the message list.
            if (preselected) resolve(null);
            else enterMessagePhase();
            return;
        }
        if (isSummarizeAction(option.action())) {
            String feedback = feedbackFor(option.action()).query().trim();
            enterSummarizingPhase(option.action(), feedback.isEmpty() ? null : feedback);
            return;
        }
        beginRestoreOrResolve(option.action());
    }

    private void handleSummarizeInputKey(KeyStroke key, RestoreAction action,
                                         AtomicBoolean deliver) {
        if (key.getKeyType() == KeyType.ENTER) {
            selectOption(optionList.get(optionSelectedIndex));
        } else {
            feedbackFor(action).handleKey(key);
        }
        deliver.set(false);
    }

    private static boolean isInputUpKey(KeyStroke key) {
        return key.getKeyType() == KeyType.ARROW_UP || isCtrlChar(key, 'p');
    }

    private static boolean isInputDownKey(KeyStroke key) {
        return key.getKeyType() == KeyType.ARROW_DOWN || isCtrlChar(key, 'n');
    }

    private static int plainDigitValue(KeyStroke key) {
        if (key.getKeyType() != KeyType.CHARACTER || key.getCharacter() == null
                || key.isCtrlDown() || key.isAltDown()) {
            return -1;
        }
        char character = key.getCharacter();
        if (character >= '0' && character <= '9') return character - '0';
        return character >= '\uFF10' && character <= '\uFF19'
            ? character - '\uFF10' : -1;
    }

    private void runPreRestoreOnce() {
        if (preRestoreRan) return;
        preRestoreRan = true;
        onPreRestore.run();
    }

    private void resumePreviousSession() {
        Runnable resume = onResumePreviousSession;
        runPreRestoreOnce();
        resolve(null);
        if (resume != null) resume.run();
    }

    private void beginRestoreOrResolve(RestoreAction action) {
        runPreRestoreOnce();
        if (restoreExecutor == null) {
            resolve(new Selection(pickedMessage, action));
            return;
        }
        phase = Phase.RESTORING;
        restoreError = null;
        invalidate();
        restoreExecutor.execute(pickedMessage, action,
            () -> resolve(null), this::showRestoreError);
    }

    private synchronized void showRestoreError(String message) {
        if (!active) return;
        restoreError = message;
        phase = Phase.RESTORE_ERROR;
        invalidate();
    }

    private void handleRestoreErrorKey(KeyStroke key, AtomicBoolean deliver) {
        if (key.getKeyType() == KeyType.ESCAPE) {
            resolve(null);
        }
        deliver.set(false);
    }

    private void handleSummarizingKey(KeyStroke key, AtomicBoolean deliver) {

        if (key.getKeyType() == KeyType.ESCAPE && summarizeError != null) {
            resolve(null);
        }
        // Nothing in the prompt beneath this modal may observe keys while compact owns the UI.
        deliver.set(false);
    }

    private void moveSelection(int delta) {
        selectedIndex = Math.max(0, Math.min(entries.size() - 1, selectedIndex + delta));
        invalidate();
    }

    private void moveOptionSelection(int delta) {
        optionSelectedIndex = Math.floorMod(optionSelectedIndex + delta, optionList.size());
        invalidate();
    }

    private void moveOptionPage(int direction) {
        optionSelectedIndex = Math.max(0, Math.min(optionList.size() - 1,
            optionSelectedIndex + direction * visibleOptionCount()));
        invalidate();
    }


    private static boolean isUpKey(KeyStroke key) {
        if (key.getKeyType() == KeyType.ARROW_UP) return true;
        if (key.getKeyType() != KeyType.CHARACTER || key.getCharacter() == null) return false;
        char c = key.getCharacter();
        return key.isCtrlDown() ? c == 'p' : c == 'k';
    }


    private static boolean isDownKey(KeyStroke key) {
        if (key.getKeyType() == KeyType.ARROW_DOWN) return true;
        if (key.getKeyType() != KeyType.CHARACTER || key.getCharacter() == null) return false;
        char c = key.getCharacter();
        return key.isCtrlDown() ? c == 'n' : c == 'j';
    }

    private static boolean isCtrlChar(KeyStroke key, char c) {
        return key.getKeyType() == KeyType.CHARACTER && key.isCtrlDown()
            && key.getCharacter() != null && Character.toLowerCase(key.getCharacter()) == c;
    }




    private boolean handleGlobalKey(KeyStroke key, AtomicBoolean deliver) {
        ContextKeybindingDispatcher.Result resolved = keybindings.resolve("Global", key);
        if (resolved instanceof ContextKeybindingDispatcher.Result.Consumed) {
            deliver.set(false);
            return true;
        }
        if (resolved instanceof ContextKeybindingDispatcher.Result.Action(String action)
                && dispatchGlobalAction(action)) {
            deliver.set(false);
            return true;
        }
        return handleNativeExitKey(key, deliver);
    }

    private boolean dispatchGlobalAction(String action) {
        return switch (action) {
            case "app:interrupt" -> {
                handleExitKeyPress(ExitGesture.INTERRUPT,
                    KeybindingHints.shortcut(keybindingsStore, action, "Global", "Ctrl-C"));
                yield true;
            }
            case "app:exit" -> {
                handleExitKeyPress(ExitGesture.EXIT,
                    KeybindingHints.shortcut(keybindingsStore, action, "Global", "Ctrl-D"));
                yield true;
            }
            default -> false;
        };
    }

    private boolean handleNativeExitKey(KeyStroke key, AtomicBoolean deliver) {
        if (isCtrlChar(key, 'c')) {
            handleExitKeyPress(ExitGesture.INTERRUPT, "Ctrl-C");
            deliver.set(false);
            return true;
        }
        if (isCtrlChar(key, 'd')) {
            handleExitKeyPress(ExitGesture.EXIT, "Ctrl-D");
            deliver.set(false);
            return true;
        }
        return false;
    }

    private void handleExitKeyPress(ExitGesture gesture, String keyName) {
        long now = currentTimeMillis.getAsLong();
        long previous = gesture == ExitGesture.INTERRUPT ? lastInterruptPress : lastExitPress;
        if (previous != 0L && now - previous <= EXIT_DOUBLE_PRESS_MS) {
            clearExitGesture(gesture);
            pendingExitKey = null;
            invalidate();
            exitAction.run();
            return;
        }
        if (gesture == ExitGesture.INTERRUPT) lastInterruptPress = now;
        else lastExitPress = now;
        pendingExitKey = keyName;
        replaceExitTimer(gesture, exitTimer.schedule(() -> {
            synchronized (MessageSelectorDialog.this) {
                clearExitGesture(gesture);
                pendingExitKey = null;
                invalidate();
            }
        }, EXIT_DOUBLE_PRESS_MS, TimeUnit.MILLISECONDS));
        invalidate();
    }

    private void replaceExitTimer(ExitGesture gesture, ScheduledFuture<?> replacement) {
        if (gesture == ExitGesture.INTERRUPT) {
            if (interruptExitTimerFuture != null) interruptExitTimerFuture.cancel(false);
            interruptExitTimerFuture = replacement;
        } else {
            if (exitExitTimerFuture != null) exitExitTimerFuture.cancel(false);
            exitExitTimerFuture = replacement;
        }
    }

    private void clearExitGesture(ExitGesture gesture) {
        if (gesture == ExitGesture.INTERRUPT) {
            lastInterruptPress = 0L;
            if (interruptExitTimerFuture != null) interruptExitTimerFuture.cancel(false);
            interruptExitTimerFuture = null;
        } else {
            lastExitPress = 0L;
            if (exitExitTimerFuture != null) exitExitTimerFuture.cancel(false);
            exitExitTimerFuture = null;
        }
    }

    private synchronized void resolve(Selection selection) {
        if (!active) return;
        Consumer<Selection> cb = onResult;
        hide();
        if (cb != null) cb.accept(selection);
    }

    private synchronized void hide() {
        diffLoadGeneration++;
        stopSummarizingSpinner();
        active = false;
        phase = null;
        entries = List.of();
        optionList = List.of();
        pickedMessage = null;
        summarizeFromFeedback.reset("");
        summarizeUpToFeedback.reset("");
        summarizeError = null;
        summarizeExecutor = null;
        restoreError = null;
        restoreExecutor = null;
        onResumePreviousSession = null;
        parentSessionId = null;
        onResult = null;
        liveMessagesSupplier = null;
        lastLiveMessages = List.of();
        canRestoreCode = false;
        pendingExitKey = null;
        clearExitGesture(ExitGesture.INTERRUPT);
        clearExitGesture(ExitGesture.EXIT);
        invalidate();
    }

    // ── sizing ───────────────────────────────────────────────────────────────

    private int visibleRowCount() {
        if ((phase == Phase.PICK_MESSAGE || phase == Phase.RESTORING && optionList.isEmpty())
                && !hasRealMessages) {
            return 0;
        }
        if (phase == Phase.PICK_MESSAGE || phase == Phase.RESTORING && optionList.isEmpty()) {
            int terminalRows = terminalRows();
            // Lanterna always runs in the alternate/fullscreen buffer, matching the 197
            // fullscreen branch that sizes the rewind picker from half the terminal height.
            int layoutRows = Math.floorDiv(terminalRows, 2);
            int entryHeight = messageEntryHeight();
            int available = Math.max(2, Math.floorDiv(layoutRows - 12, entryHeight));
            return Math.min(available, entries.size());
        }
        return Math.min(visibleOptionCount(), optionList.size());
    }

    private int visibleOptionCount() {
        return Math.min(MAX_VISIBLE_OPTIONS, Math.max(1, terminalRows() - 8));
    }

    private int messageEntryHeight() {
        return fileHistoryManager == null ? 2 : 3;
    }

    private int optionDescriptionLineCount() {
        if (optionList.isEmpty()) return 0;
        return describeOptionSecondLine(optionList.get(optionSelectedIndex).action()) == null ? 1 : 2;
    }

    private int optionListStartRow() {
        return confirmationDescriptionStartRow(terminalColumns()) + optionDescriptionLineCount();
    }

    private int confirmationDescriptionStartRow(int columns) {
        int headerRows = confirmationHeaderLines(columns).size();
        int promptRows = confirmationPromptLines(columns).size();
        int timestampRows = previewTimestamp(pickedMessage).isEmpty() ? 0 : 1;
        // divider(0), title(1), wrapped confirmation, then the bordered prompt/timestamp block.
        return 2 + headerRows + promptRows + timestampRows;
    }

    private int terminalColumns() {
        return Math.max(1, terminalColumnsSupplier.getAsInt());
    }

    private int terminalRows() {
        return Math.max(0, terminalRowsSupplier.getAsInt());
    }

    private List<String> confirmationPromptLines(int columns) {
        String prompt = messageDisplay(pickedMessage, false);
        String limited = prompt.substring(0, Math.min(MAX_CONFIRMATION_CHARS, prompt.length()));
        String[] sourceLines = limited.split("\n", -1);
        int sourceLineCount = Math.min(MAX_CONFIRMATION_SOURCE_LINES, sourceLines.length);
        int width = Math.max(1, columns - LEFT_PAD - 4);
        List<String> result = new ArrayList<>();
        for (int index = 0; index < sourceLineCount; index++) {
            String sourceLine = sourceLines[index];
            List<String> wordWrapped = DialogText.wrapWords(sourceLine, width);
            if (wordWrapped.isEmpty()) {
                result.add("");
                continue;
            }
            for (String line : wordWrapped) {
                List<String> hardWrapped = FormatUtils.wrapText(line, width);
                if (hardWrapped.isEmpty()) result.add("");
                else result.addAll(hardWrapped);
            }
        }
        return List.copyOf(result);
    }

    private List<String> confirmationHeaderLines(int columns) {
        String target = diffPreview == null ? "the conversation " : "";
        String header = "Confirm you want to restore " + target
            + "to the point before you sent this message:";
        return DialogText.wrapWords(header, Math.max(1, columns - LEFT_PAD));
    }

    private int totalRows() {
        if (!active) return 0;
        return switch (phase) {
            // divider + title + header + scroll markers + fixed-height entries + footer
            case PICK_MESSAGE -> 7 + visibleRowCount() * messageEntryHeight();
            case PICK_OPTION -> optionListStartRow() + visibleRowCount()
                + (canRestoreCode ? 2 : 1);
            // divider(1) + status(1)
            case SUMMARIZING -> summarizeError != null ? errorStateRows(summarizeError)
                : optionListStartRow() + 1 + (canRestoreCode ? 2 : 1);
            case RESTORING -> optionList.isEmpty()
                ? 7 + visibleRowCount() * messageEntryHeight()
                : optionListStartRow() + visibleRowCount() + (canRestoreCode ? 2 : 1);
            case RESTORE_ERROR -> errorStateRows(restoreError);
        };
    }

    private static int errorStateRows(String error) {
        return 3 + errorLines(error).size();
    }

    private static List<String> errorLines(String error) {
        return List.of(StringUtils.defaultString(error).split("\\R", -1));
    }

    @Override
    public synchronized TerminalSize calculatePreferredSize() {
        if (!active) return new TerminalSize(0, 0);
        refreshLiveEntries();
        int rows = terminalRows();
        int columns = terminalColumns();
        if (rows != lastMeasuredTerminalRows || columns != lastMeasuredTerminalColumns) {
            lastMeasuredTerminalRows = rows;
            lastMeasuredTerminalColumns = columns;
            body.invalidate();
        }
        TerminalSize parent = super.calculatePreferredSize();
        return new TerminalSize(
            Math.max(DEFAULT_TERMINAL_COLUMNS,
                Math.max(parent.getColumns(), columns)),
            totalRows());
    }

    @Override public Interactable nextFocus(Interactable fromThis) {
        return active ? super.nextFocus(fromThis) : null;
    }

    @Override public Interactable previousFocus(Interactable fromThis) {
        return active ? super.previousFocus(fromThis) : null;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────


    // true); keep the UI-local InlineOverlay clip at this rendering boundary.
    private static String truncate(String s, int maxColumns) {
        return FormatUtils.truncateSingleLine(s.strip(), Math.max(0, maxColumns));
    }

    private static String previewTimestamp(UserMessage m) {
        if (m == null) return "";
        return m.timestamp()
            .map(ts -> "(" + FormatUtils.formatRelativeTimeAgo(ts, FormatUtils.RelativeTimeStyle.NARROW) + ")")
            .orElse("");
    }

    // ── Rendering ────────────────────────────────────────────────────────────

    private final class Body extends AbstractComponent<Body> {
        @Override protected ComponentRenderer<Body> createDefaultRenderer() {
            return new BodyRenderer();
        }
    }

    private final class BodyRenderer implements ComponentRenderer<Body> {
        @Override public TerminalSize getPreferredSize(Body c) {
            refreshLiveEntries();
            return active
                ? new TerminalSize(Math.max(DEFAULT_TERMINAL_COLUMNS, terminalColumns()), totalRows())
                : new TerminalSize(0, 0);
        }

        @Override public void drawComponent(TextGUIGraphics g, Body c) {
            if (!active) return;
            refreshLiveEntries();
            g.fill(' ');
            int cols = g.getSize().getColumns();

            g.setForegroundColor(LanternaTheme.divider());
            g.putString(0, 0, "─".repeat(Math.max(0, cols)));

            switch (phase) {
                case PICK_MESSAGE -> drawPickMessage(g);
                case PICK_OPTION -> drawPickOption(g);
                case SUMMARIZING -> drawSummarizing(g);
                case RESTORING -> {
                    if (optionList.isEmpty()) drawPickMessage(g);
                    else drawPickOption(g);
                }
                case RESTORE_ERROR -> drawRestoreError(g);
            }
        }

        private void drawPickMessage(TextGUIGraphics g) {
            g.setForegroundColor(LanternaTheme.suggestion());
            g.enableModifiers(SGR.BOLD);
            g.putString(LEFT_PAD, 1, "Rewind");
            g.disableModifiers(SGR.BOLD);

            g.setForegroundColor(LanternaTheme.inputText());
            g.putString(LEFT_PAD, 2, !hasRealMessages
                ? "Nothing to rewind to yet."
                : fileHistoryManager != null
                    ? "Restore the code and/or conversation to the point before…"
                    : "Restore and fork the conversation to the point before…");

            int total = entries.size();
            int visible = visibleRowCount();
            int offset = messageWindowOffset(selectedIndex, total, visible);
            if (offset > 0) {
                g.setForegroundColor(LanternaTheme.welcomeDim());
                g.putString(LEFT_PAD + 2, 3, "↑ " + offset + " more above");
            }
            int entryHeight = messageEntryHeight();
            int listStart = 4;
            for (int i = 0; i < visible; i++) {
                int ei = offset + i;
                Entry e = entries.get(ei);
                boolean selected = ei == selectedIndex;
                String label = e.message() == null
                    ? e.display()
                    : messageDisplay(e.message(), true,
                        Math.max(0, g.getSize().getColumns() - 10));
                int row = listStart + i * entryHeight;
                if (selected) {
                    g.setForegroundColor(LanternaTheme.permission());
                    g.enableModifiers(SGR.BOLD);
                    g.putString(LEFT_PAD, row, "❯ ");
                    g.disableModifiers(SGR.BOLD);
                }
                g.setForegroundColor(selected
                    ? LanternaTheme.suggestion() : LanternaTheme.inputText());
                if (e.isCurrent()) g.enableModifiers(SGR.ITALIC);
                g.putString(LEFT_PAD + 2, row, label);
                if (e.isCurrent()) g.disableModifiers(SGR.ITALIC);
                if (fileHistoryManager != null && e.message() != null) {
                    drawCodeMetadata(g, e, selected, row + 1);
                }
            }

            int below = total - offset - visible;
            int belowRow = listStart + visible * entryHeight;
            if (below > 0) {
                g.setForegroundColor(LanternaTheme.welcomeDim());
                g.putString(LEFT_PAD + 2, belowRow, "↓ " + below + " more below");
            }
            g.setForegroundColor(LanternaTheme.welcomeDim());
            g.enableModifiers(SGR.ITALIC);
            g.putString(LEFT_PAD, belowRow + 2, pendingExitKey != null
                ? "Press " + pendingExitKey + " again to exit"
                : hasRealMessages ? "Enter to continue · Esc to cancel" : "Esc to cancel");
            g.disableModifiers(SGR.ITALIC);
        }

        private void drawCodeMetadata(TextGUIGraphics g, Entry entry, boolean selected, int row) {
            if (!entry.codeRestoreAvailable()) {
                g.setForegroundColor(LanternaTheme.toolWarning());
                g.putString(LEFT_PAD + 2, row, "⚠ No code restore");
                return;
            }
            FileHistoryManager.DiffStats stats = entry.codeDiff();
            g.setForegroundColor(selected
                ? LanternaTheme.statusFg() : LanternaTheme.welcomeDim());
            if (stats == null || stats.filesChanged().isEmpty()) {
                g.putString(LEFT_PAD + 2, row, "No code changes");
                return;
            }
            String changed = stats.filesChanged().size() == 1
                ? baseName(stats.filesChanged().getFirst())
                : stats.filesChanged().size() + " files changed";
            drawDiffStats(g, LEFT_PAD + 2, row, changed + " ", stats, "",
                selected ? LanternaTheme.statusFg() : LanternaTheme.welcomeDim());
        }

        private void drawPickOption(TextGUIGraphics g) {
            int listStart = drawConfirmationContext(g);

            int total = optionList.size();
            int visible = visibleRowCount();
            int offset = clampScroll(optionSelectedIndex, total, visible);
            boolean disabled = phase == Phase.RESTORING;
            for (int i = 0; i < visible; i++) {
                int ei = offset + i;
                Option opt = optionList.get(ei);
                boolean selected = !disabled && ei == optionSelectedIndex;
                int row = listStart + i;
                if (selected) {
                    g.setForegroundColor(LanternaTheme.suggestion());
                    g.putString(LEFT_PAD, row, "❯");
                } else if (i == 0 && offset > 0) {
                    g.setForegroundColor(LanternaTheme.welcomeDim());
                    g.putString(LEFT_PAD, row, "↑");
                } else if (i == visible - 1 && offset + visible < total) {
                    g.setForegroundColor(LanternaTheme.welcomeDim());
                    g.putString(LEFT_PAD, row, "↓");
                }
                String prefix = (ei + 1) + ". ";
                g.setForegroundColor(selected
                    ? LanternaTheme.suggestion() : LanternaTheme.inputText());
                if (selected && isSummarizeAction(opt.action())) {
                    String fixedLabel = prefix + opt.label() + ": ";
                    int labelColumn = LEFT_PAD + 2;
                    g.putString(labelColumn, row, fixedLabel);
                    drawFeedbackInput(g, opt.action(), row,
                        labelColumn + FormatUtils.displayWidth(fixedLabel));
                } else {
                    g.putString(LEFT_PAD + 2, row, prefix + optionLabel(opt, selected));
                }
            }
            drawCodeRestoreWarning(g, listStart + visible + 1);
        }

        private int drawConfirmationContext(TextGUIGraphics g) {
            g.setForegroundColor(LanternaTheme.suggestion());
            g.enableModifiers(SGR.BOLD);
            g.putString(LEFT_PAD, 1, "Rewind");
            g.disableModifiers(SGR.BOLD);

            g.setForegroundColor(LanternaTheme.inputText());
            int row = 2;
            for (String headerLine : confirmationHeaderLines(g.getSize().getColumns())) {
                g.putString(LEFT_PAD, row++, headerLine);
            }

            List<String> promptLines = confirmationPromptLines(g.getSize().getColumns());
            for (String promptLine : promptLines) {
                g.setForegroundColor(LanternaTheme.welcomeDim());
                g.putString(LEFT_PAD, row, "│");
                g.setForegroundColor(LanternaTheme.inputText());
                g.putString(LEFT_PAD + 2, row, promptLine);
                row++;
            }
            String ts = previewTimestamp(pickedMessage);
            if (!ts.isEmpty()) {
                g.setForegroundColor(LanternaTheme.welcomeDim());
                g.putString(LEFT_PAD, row, "│");
                g.putString(LEFT_PAD + 2, row, ts);
                row++;
            }

            Option current = optionList.get(optionSelectedIndex);
            g.setForegroundColor(LanternaTheme.welcomeDim());
            g.putString(LEFT_PAD, row, describeOption(current.action()));
            String secondLine = describeOptionSecondLine(current.action());
            if (secondLine != null) {
                if (showsCodeRestore(current.action()) && diffPreview != null) {
                    drawDiffStats(g, LEFT_PAD, row + 1, "The code will be restored ",
                        diffPreview, " in " + fileLabel(diffPreview.filesChanged()) + ".",
                        LanternaTheme.welcomeDim());
                } else {
                    g.putString(LEFT_PAD, row + 1, secondLine);
                }
            }
            return row + (secondLine == null ? 1 : 2);
        }

        private void drawDiffStats(TextGUIGraphics g, int column, int row, String prefix,
                                   FileHistoryManager.DiffStats stats, String suffix,
                                   com.googlecode.lanterna.TextColor baseColor) {
            g.setForegroundColor(baseColor);
            g.putString(column, row, prefix);
            int cursor = column + FormatUtils.displayWidth(prefix);
            String added = "+" + stats.insertions();
            g.setForegroundColor(LanternaTheme.diffAddedWord());
            g.putString(cursor, row, added);
            cursor += FormatUtils.displayWidth(added);
            g.setForegroundColor(baseColor);
            g.putString(cursor, row, " ");
            cursor++;
            String removed = "-" + stats.deletions();
            g.setForegroundColor(LanternaTheme.diffRemovedWord());
            g.putString(cursor, row, removed);
            cursor += FormatUtils.displayWidth(removed);
            g.setForegroundColor(baseColor);
            g.putString(cursor, row, suffix);
        }

        private void drawCodeRestoreWarning(TextGUIGraphics g, int row) {
            if (canRestoreCode) {
                g.setForegroundColor(LanternaTheme.welcomeDim());
                g.putString(LEFT_PAD, row,
                    "⚠ Rewinding does not affect files edited manually or via bash.");
            }
        }

        private String optionLabel(Option option, boolean selected) {
            if (!isSummarizeAction(option.action())) return option.label();
            String value = feedbackFor(option.action()).query()
                .replace('\n', ' ').replace('\r', ' ');
            if (!selected && value.isEmpty()) return option.label();
            return option.label() + ": "
                + (value.isEmpty() ? "add context (optional)" : value);
        }

        private void drawFeedbackInput(TextGUIGraphics g, RestoreAction action,
                                       int row, int inputColumn) {
            SearchInput input = feedbackFor(action);
            String value = input.query()
                .replace('\n', ' ').replace('\r', ' ');
            int caret = Math.min(input.cursorOffset(), value.length());
            int available = Math.max(1, g.getSize().getColumns() - inputColumn);
            String shown;
            int caretColumn;
            String under;
            if (value.isEmpty()) {
                shown = FormatUtils.truncateNoEllipsis("add context (optional)", available);
                caretColumn = 0;
                under = firstGraphemeAt(shown, 0);
            } else {
                String before = value.substring(0, caret);
                String visibleBefore = takeTailToWidth(before, available - 1);
                caretColumn = FormatUtils.displayWidth(visibleBefore);
                String after = FormatUtils.truncateNoEllipsis(
                    value.substring(caret), available - caretColumn);
                shown = visibleBefore + after;
                under = caret < value.length() ? firstGraphemeAt(value, caret) : " ";
            }
            g.putString(inputColumn, row, shown);
            int column = inputColumn + caretColumn;
            if (column >= g.getSize().getColumns()) return;
            if (FormatUtils.displayWidth(under) > g.getSize().getColumns() - column) {
                under = " ";
            }
            g.setForegroundColor(LanternaTheme.suggestion());
            g.enableModifiers(SGR.REVERSE);
            g.putString(column, row, under);
            g.disableModifiers(SGR.REVERSE);
        }

        private String takeTailToWidth(String value, int maxWidth) {
            if (maxWidth <= 0) return "";
            if (FormatUtils.displayWidth(value) <= maxWidth) return value;
            String truncated = FormatUtils.truncateStartToWidth(value, maxWidth + 1);
            return Strings.CS.startsWith(truncated, "…") ? truncated.substring(1) : truncated;
        }

        private String firstGraphemeAt(String value, int offset) {
            if (offset < 0 || offset >= value.length()) return " ";
            java.text.BreakIterator iterator =
                java.text.BreakIterator.getCharacterInstance(Locale.ROOT);
            iterator.setText(value);
            int end = iterator.following(offset);
            return end == java.text.BreakIterator.DONE
                ? value.substring(offset) : value.substring(offset, end);
        }

        private void drawSummarizing(TextGUIGraphics g) {
            if (summarizeError != null) {
                drawErrorState(g, summarizeError);
                return;
            }
            int spinnerRow = drawConfirmationContext(g);
            String glyph = SpinnerFrames.REDUCED_MOTION
                ? SpinnerFrames.REDUCED_MOTION_DOT
                : SpinnerFrames.glyphAt(
                    SUMMARIZING_SPINNER_FRAMES, summarizingSpinnerFrame);
            g.setForegroundColor(LanternaTheme.inputText());
            g.putString(LEFT_PAD, spinnerRow, glyph + "  Summarizing…");
            drawCodeRestoreWarning(g, spinnerRow + 2);
        }

        private void drawRestoreError(TextGUIGraphics g) {
            drawErrorState(g, restoreError);
        }

        private void drawErrorState(TextGUIGraphics g, String error) {
            g.setForegroundColor(LanternaTheme.suggestion());
            g.enableModifiers(SGR.BOLD);
            g.putString(LEFT_PAD, 1, "Rewind");
            g.disableModifiers(SGR.BOLD);
            List<String> lines = errorLines(error);
            g.setForegroundColor(LanternaTheme.toolError());
            for (int index = 0; index < lines.size(); index++) {
                g.putString(LEFT_PAD, 2 + index, lines.get(index));
            }
            g.setForegroundColor(LanternaTheme.welcomeDim());
            g.enableModifiers(SGR.ITALIC);
            g.putString(LEFT_PAD, 2 + lines.size(), "Esc to cancel");
            g.disableModifiers(SGR.ITALIC);
        }

        /** Keeps {@code selected} within a {@code visible}-row scroll window. */
        private int clampScroll(int selected, int total, int visible) {
            int offset = scrollOffset;
            if (selected < offset) offset = selected;
            if (selected >= offset + visible) offset = selected - visible + 1;
            offset = Math.max(0, Math.min(offset, Math.max(0, total - visible)));
            scrollOffset = offset;
            return offset;
        }

        /** Centers the selected message where possible, matching the original selector. */
        private int messageWindowOffset(int selected, int total, int visible) {
            if (visible <= 0) return 0;
            int offset = Math.max(0,
                Math.min(selected - visible / 2, Math.max(0, total - visible)));
            scrollOffset = offset;
            return offset;
        }
    }
}
