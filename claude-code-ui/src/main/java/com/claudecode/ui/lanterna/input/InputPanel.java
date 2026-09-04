package com.claudecode.ui.lanterna.input;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.core.constants.Figures;
import com.claudecode.core.engine.SessionIdentity;
import com.claudecode.core.imagestore.ImageStore;
import com.claudecode.core.message.PastedContent;
import com.claudecode.keybindings.UserKeybindingsStore;
import com.claudecode.keybindings.KeybindingHints;
import com.claudecode.core.annotation.Explanation;
import com.claudecode.permissions.PermissionMode;
import com.claudecode.core.queue.QueuedCommand;
import com.claudecode.runtime.turn.QueuedInputDraft;
import com.claudecode.runtime.sessionhost.SessionCollaborationController;
import com.claudecode.tools.tasks.InProcessTeammateTask;
import com.claudecode.tools.tasks.TaskRegistry;
import com.claudecode.tools.tasks.TaskState;
import com.claudecode.tools.tasks.TaskStatus;
import com.claudecode.tools.tasks.TaskType;
import com.claudecode.tools.workflows.WorkflowRun;
import com.claudecode.tools.workflows.WorkflowRunStore;
import com.claudecode.ui.lanterna.components.HighlightedTextBox.Highlight;
import com.claudecode.ui.vim.VimMode;
import com.claudecode.ui.vim.VimStateMachine;
import com.claudecode.ui.lanterna.transcript.ViewedTeammateHolder;
import com.claudecode.core.paste.ImagePaste;
import com.claudecode.core.paste.InputPasteTruncation;
import com.claudecode.core.paste.PastedRefParser;
import com.googlecode.lanterna.CursorStyle;
import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.gui2.Component;
import com.googlecode.lanterna.gui2.Container;
import com.googlecode.lanterna.gui2.Direction;
import com.googlecode.lanterna.gui2.Interactable;
import com.googlecode.lanterna.gui2.Label;
import com.googlecode.lanterna.gui2.LinearLayout;
import com.googlecode.lanterna.gui2.Panel;
import com.googlecode.lanterna.gui2.TextBox;
import com.googlecode.lanterna.input.FocusEventKeyStroke;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.input.MouseAction;
import com.googlecode.lanterna.input.PasteKeyStroke;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.claudecode.ui.lanterna.components.HighlightedTextBox;
import com.claudecode.ui.lanterna.features.settings.UiSettings;
import com.claudecode.ui.lanterna.status.StatusLineComponent;
import com.claudecode.ui.lanterna.suggest.SuggestionPanel;
import com.claudecode.ui.lanterna.theme.LanternaTheme;

/**
 * Input area for the Lanterna prompt component stack.
 */
public class InputPanel extends Panel {

    private static final Logger log = LoggerFactory.getLogger(InputPanel.class);
    /** High-volume key protocol tracing is opt-in even when general DEBUG is enabled. */
    private static final boolean KEY_DIAGNOSTICS =
        Boolean.getBoolean("claude.ui.keyDiagnostics");

    private static final int PROMPT_INPUT_COLUMN_OVERHEAD = 3;

    public enum Mode { NORMAL, BASH }

    private Mode   mode         = Mode.NORMAL;

    private Mode   modeOverride = null;
    private String permMode;

    private BooleanSupplier bypassPermissionsModeAvailable = () -> true;

    private BooleanSupplier leftArrowOpensAgents =
        () -> UiSettings.readGlobalBoolean("leftArrowOpensAgents", true);

    // Vim mode state
    private boolean          vimEnabled = false;
    private final VimStateMachine vim    = new VimStateMachine();

    // Child components
    private final Label  promptLabel;
    private final TextBox textBox;
    /**
     * Last value delivered by Lanterna's text-change listener. Keeping this
     * immutable String snapshot avoids rebuilding the whole multi-line value
     * several times for every key (undo, mode detection, chip pruning,
     * typeahead, highlights, and ghost text all consume the same value).
     */
    private String currentText = "";
/**
 * Cached visual row count, including.
 */
    private int currentTextRows = 1;
    /** Width of the editable content region before PromptTextLayout reserves its cursor cell. */
    private int inputContentColumns = 80 - PROMPT_INPUT_COLUMN_OVERHEAD;
    /** Immutable visual projection rebuilt after text or pane-width changes. */
    private PromptTextLayout textLayout = PromptTextLayout.create("", inputContentColumns);

    private List<HighlightedTextBox.Highlight> currentHighlights = List.of();
    private HighlightedTextBox.Highlight historySearchHighlight;
    /** Exact-command argument hint painted inline after the caret, never in the footer. */
    private volatile String argumentHint;
    /**
     * Pure Emacs/readline editing layer (cursor/word motion + kill/yank ring).
     * Constructed inside {@link PromptTextBox}'s instance initializer, so it can
     * capture that class's {@code super.handleKeyStroke} for faithful
     * cross-row caret motion. Only invoked from key dispatch, so the assignment
     * ordering (before {@code textBoxRef.set}) is irrelevant.
     */
    private ReadlineEngine engine;
    private final Label  hintMainLabel;
    private final Label  hintSuffixLabel;
    private final Label  vimModeLabel;
/** established prompt hint/tasks row; coordinator rows are mounted after it. */
    private final Panel hintRow;
    /** Collaboration footer group, always the final visual row. */
    private final Panel collaborationRow;


    /** The pill text ("1 shell"); dim, SGR.REVERSE while selected. Empty = hidden. */
    private final Label tasksPillLabel;
    /** Dynamic multi-agent pill row; the single summary pill is inserted here when needed. */
    private final Panel tasksPillsPanel;
    /** Optional original-only attention CTA next to the ordinary task pill. */
    private final Label tasksHintLabel;
    /** Permanent keyboard-focusable footer entry for one optional IM channel. */
    @Explanation("Permanent per-session IM collaboration footer control")
    private final Label collaborationPillLabel;
    /**
     * ≡ project-drawer button — leftmost footer stop (its spatial position).
     * A Java-side extension with no 197 counterpart. Selected via keyboard
     * (first ↓ from empty input), clicked, Enter toggles the drawer;
     * {@code projectsButtonActive} mirrors the drawer's open state.
     */
    private final Label projectsButtonLabel;
    private boolean projectsButtonSelected;
    private boolean projectsButtonActive;
    private boolean projectsButtonMousePressed;
    private boolean projectsButtonMouseHovered;
    private volatile SessionCollaborationController collaborationController;
    private AutoCloseable collaborationSubscription;
    private boolean collaborationPillSelected;
    /** Footer and teammate state machine; this panel only renders its projection. */
    private final PromptTaskNavigationController taskNavigation =
        new PromptTaskNavigationController();
/**
 * Press/release latch for the clickable.
 */
    private boolean tasksPillMousePressed;

    private boolean tasksPillMouseHovered;
    private final PromptTaskNavigationController.Host taskNavigationHost =
        new PromptTaskNavigationController.Host() {
            @Override public void openTasksDialog() {
                if (actions != null) actions.openTasksDialog();
            }
            @Override public void refreshHint() { updateHint(); }
            @Override public void clearStatusLine() { InputPanel.this.clearTransientStatusLine(); }
            @Override public void showTeammateStatus(InProcessTeammateTask task) {
                String name = task.name() == null ? task.getTaskId() : task.name();
                String preview = task.lastMessagePreview(160).replace("\n", " ");
                setTransientStatusLine("Viewing @" + name + " — "
                    + (preview.isEmpty() ? "(idle)" : preview), 0);
            }
            @Override public void showInterruptedHint() {
                showTemporaryHint("Interrupted teammate turn (Esc)",
                    LanternaTheme.welcomeDim(), HINT_TIMEOUT_MS);
            }
            @Override public void showPermissionModeHint(PermissionMode mode) {
                showTemporaryHint("Teammate mode → " + mode.title(),
                    LanternaTheme.colorFor(mode), HINT_TIMEOUT_MS);
            }
            @Override public void teammateViewChanged() {
                if (actions != null) actions.teammateViewChanged();
            }
            @Override public void setTeammateTreeExpanded(boolean expanded) {
                taskNavigation.setTeammateTreeExpanded(expanded);
                if (actions != null) actions.setTeammateTreeExpanded(expanded);
            }
            @Override public boolean isTeammateTreeExpanded() {
                return actions != null && actions.isTeammateTreeExpanded();
            }
        };
    /**
     * Unified tasks-footer navigation for the optional background pill,
     * {@code main}, and local-agent rows. Null until wired via
     * {@link #setCoordinatorNavigation}.
     */
    private CoordinatorNavigationController coordinatorNavigation;
    /** The rendered coordinator panel; refreshed from the tick. Null until wired. */
    private CoordinatorPanelView coordinatorPanel;
    /** Lanterna component backing {@link #coordinatorPanel}, when it has one. */
    private Component coordinatorPanelComponent;

    private WorkflowRunStore workflowRuns;
    /** Current-process task projection; persisted workflow history has no footer row. */
    private TaskRegistry taskRegistry;

    private boolean workflowFooterSelected;

    private int workflowFooterIndex;
    /** Keeps selection on the same workflow when another row is evicted. */
    private String selectedWorkflowTaskId;
    /** Resolves an agent task id to its display name for the coordinator panel. */
    private Function<String, String> coordinatorNameResolver = _ -> null;
    private final CoordinatorNavigationController.Host coordinatorNavigationHost =
        new CoordinatorNavigationController.Host() {
            @Override public void teammateViewChanged() {
                if (actions != null) actions.teammateViewChanged();
            }
            @Override public void refreshHint() {
                updateHint();
                refreshCoordinatorPanel();
            }
            @Override public void clearStatusLine() { InputPanel.this.clearTransientStatusLine(); }
        };
    /** Periodic pill refresh; runs only while this panel is attached to a GUI. */
    private ScheduledFuture<?> pillRefreshFuture;

    /**
     * User-set prompt-bar color from {@code /color}.
     */
    private TextColor sessionColor;
    /** Session name set by {@code /rename}. Shown as a colored badge in the top divider. */
    private String agentName;
    private int lastDividerWidth = 80;
    private final SuggestionPanel suggestionPanel; // between divider and hint
    private SuggestionContext suggestionContext = SuggestionContext.NONE;

    private enum SuggestionContext { NONE, STANDARD, BASH_PATH }
    /** Reactive queued-input preview above the prompt divider; never enters transcript history. */
    private final Panel queuedPreviewPanel;
    /** Plain lines retained for deterministic headless tests and change-gated rerenders. */
    private List<String> queuedPreviewLines = List.of();

    private final StatusLineComponent statusLineComponent;
    /** Persistent custom/native HUD state; transient progress must never destroy it. */
    private String persistentStatusText;
    private int persistentStatusPadding;

    private boolean persistentStatusVisible;
    /** Short-lived progress/view text, used only while no persistent HUD is visible. */
    private String transientStatusText;
    private int transientStatusPadding;
    /** Row-panel: left dashes + optional colored badge + trailing dashes. */
    private final Panel topDividerPanel;
    /** Left dashes — foreground = bannerColor when banner active, else border color. */
    private final Label topDividerLeft;
    /**
     * Colored badge showing the session name from {@code /rename}.
     */
    private final Label topDividerBadge;
    /**
     * Trailing {@code ──} after the badge.
     */
    private final Label topDividerTrail;
    private String historyBorderLabel;
    private final Label  bottomDivider;

    /**
     * The single outward port for every REPL action / notification this panel
     * fires — submit, cancel, overlay toggles, permission-mode change,
     * message-actions navigation, and the query / pasted-content / cursor-style
     * / focus notifications. Replaces the former bag of ~19 individual
     * {@code setOnXxx} callback setters. See {@link InputActions} for the
     * anti-rot invariant (new REPL action → new interface method, NOT a new
     * {@code onXxx} field + setter).
     */
    private InputActions actions;

    private BooleanSupplier hasMessages;

    /**
     * Opt-in keybinding store (gate on). When non-null and enabled, matched
     * Chat/Global keys are routed through {@link #dispatchViaResolver} instead of
     * the hardcoded readline switch below. Null in headless / when customization
     * is disabled, so the existing dispatch is unchanged.
     */
    private UserKeybindingsStore keybindingsStore;
    /** Shared resolver bridge owns pending-chord state and timeout semantics. */
    private final ContextKeybindingDispatcher keybindingDispatcher =
        new ContextKeybindingDispatcher();

    // ── Push-to-talk hold detection (terminal auto-repeat accumulator) ──
    // A terminal has no distinct key-release events, so a sustained hold
    // surfaces as a stream of repeated single-character KeyStroke events.
    // Faithful to the 197 bundle (voice handler: qYo=120ms reset window,
    // zRf=5 hold threshold), we accumulate consecutive space events within
    // the reset window and only classify the burst as a HOLD once it crosses
    // the threshold; an ordinary single tap (accumulation never reaching the
    // floor) is a plain space.
    private static final long PTT_RESET_WINDOW_MS = 120;
    private static final int PTT_HOLD_THRESHOLD = 5;
    private int pttAccumulated;
    private long pttLastEventMs;

    private static final long KILL_AGENTS_CONFIRM_WINDOW_MS = 3000;
    private long lastKillAgentsPressMs;


    // Images are inserted as [Image #N] chips in the textBox; orphaned chips
    // (deleted by the user) are pruned on every text change.
    private final PromptPastedContentController pastedContent =
        new PromptPastedContentController();
    /** Draft-only undo history; never rewinds QuerySession conversation state. */
    private final DraftUndoBuffer draftUndo = new DraftUndoBuffer(50);
    /** Monotonic suppression token observed by nested PromptTextBox key dispatches. */
    private long draftUndoSuppressionGeneration;

    private StashedPrompt stashedPrompt;
    // Defaults to an unshared identity so a bare `new InputPanel` (tests,
    // any caller that doesn't wire a session) keeps working; real wiring
    // replaces this via wireSessionIdentity with the SAME instance the
    // QuerySession/HookEngine use, so a single switchToSession call is
    // visible here too without a separate setSessionId sync step.
    private SessionIdentity sessionIdentity = SessionIdentity.newRandom();

    /**
     * True while {@link #handleVimKey} is forwarding a key to the underlying
     * TextBox via {@code textBox.handleKeyStroke(key)}. The textBox is a
     * {@link PromptTextBox} whose {@code handleKeyStroke} override routes back
     * here — without this guard the call would re-enter {@code handleVimKey}
     * for the same key and recurse until {@code StackOverflowError}. Same
     * pattern as the {@code moveCaretTo} fix (which used
     * {@code setCaretPosition} to sidestep the keystroke entirely); vim needs
     * the real key event, so it uses a reentrancy flag instead.
     */
    private boolean inVimKeyDispatch = false;

    /** Scheduler onto the Lanterna GUI thread — set by LanternaReplScreen. */
    private Consumer<Runnable> guiInvoker;
    /**
     * Input bytes already waiting in one PTY read are dispatched separately by
     * Lanterna, but the suggestion/query consumer only needs their final prompt
     * state. Keep at most one delivery queued for the current GUI cycle.
     */
    private boolean queryChangeScheduled;
    /** Invalidates a queued query callback when a batch publishes synchronously. */
    private long queryChangeGeneration;
    /** Nesting guard owned by the GUI host's terminal-drain cycle. */
    private int guiInputBatchDepth;

    private boolean messageActionsActive = false;
    private String messageActionsHint = "";

    // ── History navigation state ───────────────────────────────────────────── ── History
    // navigation (Up/Down/Ctrl+R) — extracted to InputHistoryController ──.
    private final InputHistoryController historyController =
        new InputHistoryController(new InputEditingSurface() {
            @Override public String currentText() { return textBox.getText(); }
            @Override public Mode currentModeOverride() { return modeOverride; }
            @Override public Map<Integer, PastedContent> snapshotPasted() {
                return pastedContent.snapshot();
            }
            @Override public int currentCursorOffset() { return caretCol(); }
            @Override public void applyEntry(PromptHistory.Entry entry, boolean cursorToStart) {
                applyHistoryEntry(entry, cursorToStart);
            }
            @Override public void restoreDraft(String text, Mode mode,
                                               Map<Integer, PastedContent> pasted,
                                               boolean cursorToStart) {
                modeOverride = mode;
                textBox.setText(text);
                TextBoxOffsetAdapter.setOffset(textBox, cursorToStart ? 0 : text.length());
                restorePastedContents(pasted);
            }
            @Override public void applySearchEntry(PromptHistory.Entry entry, int cursorOffset) {
                applyHistoryEntry(entry, false);
                moveCaretTo(Math.min(cursorOffset, textBox.getText().length()));
            }
            @Override public void restoreSearchDraft(String text, Mode mode,
                                                     Map<Integer, PastedContent> pasted,
                                                     int cursorOffset) {
                modeOverride = mode;
                textBox.setText(text);
                restorePastedContents(pasted);
                moveCaretTo(Math.min(cursorOffset, text.length()));
                updateMode();
                fireQueryChange();
            }
            @Override public void setText(String text) { textBox.setText(text); }
            @Override public void setTextCaretEnd(String text) {
                textBox.setText(text);
                moveCaretToTextEnd();
            }
            @Override public void refreshModeAndQuery() { updateMode(); fireQueryChange(); }
            @Override public void showHint(String text, TextColor color, long timeoutMs) {
                showTemporaryHint(text, color, timeoutMs);
            }
            @Override public void setHistoryLabel(String label) {
                historyBorderLabel = label;
                updateTopDivider(lastDividerWidth);
            }
            @Override public String historySearchShortcut() {
                return KeybindingHints.shortcut(keybindingsStore,
                    "history:search", "Global", "ctrl+r");
            }
            @Override public void setHistorySearchStatus(String query, boolean failedMatch) {
                if (query != null && hintTimer != null) {
                    hintTimer.cancel(false);
                    hintTimer = null;
                }
                historySearchStatus = query == null ? null
                    : (failedMatch ? "no matching prompt: " : "search prompts: ") + query;
                updateHint();
            }
            @Override public void setHistorySearchHighlight(int start, int length) {
                historySearchHighlight = length <= 0 ? null
                    : new HighlightedTextBox.Highlight(start, start + length,
                        LanternaTheme.toolWarning(), false, 20);
                refreshInputHighlights();
                textBox.invalidate();
            }
            @Override public void invokeLater(Runnable task) {
                if (guiInvoker != null) guiInvoker.accept(task);
                else task.run();
            }
        });

    /** Move to the real final line/column without synthesizing a re-entrant End key. */
    private void moveCaretToTextEnd() {
        int row = Math.max(0, textBox.getLineCount() - 1);
        textBox.setCaretPosition(row, textBox.getLine(row).length());
    }

    private DraftUndoBuffer.Snapshot captureDraftSnapshot() {
        return new DraftUndoBuffer.Snapshot(
            currentText, caretCol(), pastedContent.snapshot(), modeOverride);
    }

    private void suppressDraftUndoRecording() {
        draftUndoSuppressionGeneration++;
    }



    private static final long DOUBLE_PRESS_TIMEOUT_MS = 800;
    private static final ScheduledExecutorService ESC_SCHEDULER =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "esc-timeout");
            t.setDaemon(true);
            return t;
        });
    private static final ExecutorService PASTE_EXECUTOR =
        Executors.newSingleThreadExecutor(Thread.ofVirtual().name("clipboard-paste").factory());
    private boolean           escOnce         = false;
    private ScheduledFuture<?> escTimer        = null;
    // True when input was EMPTY on the first Esc (double-Esc → MessageSelector vs. clear).
    private boolean           escEmptyFirst   = false;
    // Whether a query is in flight — set by LanternaReplScreen.
    private volatile boolean  isLoading       = false;
    private final AtomicInteger pendingPastes = new AtomicInteger();
    private final AtomicBoolean deferredPasteSubmit = new AtomicBoolean();
    // Whether transcript mode is active — set by LanternaReplScreen.
    // When true, Ctrl+E fires actions.transcriptShowAll() instead of engine.end().
    private volatile boolean  isTranscriptMode = false;

    // ── Temporary hint notification state ───────────────────────────────────.
    private static final long HINT_TIMEOUT_MS = 1000;
    private ScheduledFuture<?> hintTimer = null;
    private String historySearchStatus;

    /**
     * When true, the panel reports {@code (0,0)} preferred size so its host layout collapses it.
     */
    private boolean suppressed = false;

    /** Hide / restore the entire prompt bar. Caller must call {@code invalidate()}
     *  via the parent so SmartLayout re-runs. */
    public void setSuppressed(boolean suppressed) {
        this.suppressed = suppressed;
        invalidate();
    }

    @Override
    public synchronized TerminalSize calculatePreferredSize() {
        if (suppressed) return new TerminalSize(0, 0);
        return super.calculatePreferredSize();
    }

    // ── Construction ────────────────────────────────────────────────────────

    public InputPanel() {
        this("default");
    }

    public InputPanel(String initialPermissionMode) {
        this.permMode = StringUtils.isBlank(initialPermissionMode)
            ? "default" : initialPermissionMode;
        setLayoutManager(new LinearLayout(Direction.VERTICAL));

        topDividerLeft = new Label("");
        topDividerLeft.setForegroundColor(LanternaTheme.divider());
        topDividerBadge = new Label("");
        topDividerTrail = new Label("");
        // Spacing=0 — LinearLayout(HORIZONTAL) defaults to 1, which would
        // insert a 1-column gap between each label. With three labels that
        // burns 2 columns of the total width, making the top divider shorter
        // than the bottom divider (which is a single Label with no gaps).
        topDividerPanel = new Panel(new LinearLayout(Direction.HORIZONTAL).setSpacing(0));
        topDividerPanel.addComponent(topDividerLeft);
        topDividerPanel.addComponent(topDividerBadge);
        topDividerPanel.addComponent(topDividerTrail);
        queuedPreviewPanel = new Panel(new LinearLayout(Direction.VERTICAL).setSpacing(0));

        promptLabel = new Label("❯ ");
        promptLabel.setForegroundColor(LanternaTheme.toolSuccess());

        // Inline highlights painted over the input text.


        // ultrareview, buddy, tokenBudget, slackChannel, btw); add more
        // suppliers as their commands are implemented.
        Supplier<List<Highlight>> highlightSupplier = () -> currentHighlights;
        textBox = new PromptTextBox(new TerminalSize(80, 1),
                                    TextBox.Style.MULTI_LINE,
                                    highlightSupplier);
        // Keep the TextBox's explicitPreferredSize.rows in lock-step with the
        // current lineCount so the wrapping SmartLayout gives us the right
        // vertical slice after Shift+Enter (add row), Backspace-merge (remove
        // row), setText("") on submit (reset to 1), paste-inline (grow), etc.
        //
        // Rationale: Lanterna's TextBox constructor calls setPreferredSize()
        // with the initial (80, 1), which becomes explicitPreferredSize.
        // AbstractComponent.getPreferredSize() short-circuits to that value
        // and never consults renderer.getPreferredSize() again — so the field
        // is permanently 1-row tall unless we refresh it ourselves.
        textBox.setTextChangeListener((newText, byUser) -> {
            currentText = newText == null ? "" : newText;
            refreshInputHighlights();
            // A genuine keystroke edit (typing / backspacing) detaches the line from the history
            // cursor, so the slash/@ suggestion dropdown resumes — WITHOUT tearing down the
            // navigation state (index / draft / cache), so Up keeps stepping and Down still
            // returns to the draft. History-apply uses textBox.setText(...) → byUser=false, so
            // arrowing through recalled entries stays suppressed.
            if (byUser && historyController.hasHistoryCursor()) {
                historyController.onUserEdit();
            }
            refreshTextLayout();
        });

        Panel promptRow = new Panel(new LinearLayout(Direction.HORIZONTAL));
        promptRow.addComponent(promptLabel);
        // FILL (stretch to promptRow's height) + CAN_GROW (absorb every column
        // promptRow has left over after promptLabel) — without CAN_GROW, a
        // horizontal LinearLayout's FILL only stretches the CROSS axis (rows);
        // the MAIN axis (columns) stays pinned at the component's own
        // preferred width forever. textBox's preferred width starts at the
        // constructor's TerminalSize(80, 1) and is never recomputed to the
        // real terminal width elsewhere (the textChangeListener below only
        // ever touches preferred *rows*, carrying the *columns* forward
        // unchanged) — so without CAN_GROW here, textBox is invisibly capped
        // at ~80 columns no matter how wide the real terminal is. A pasted
        // single line wider than that (e.g. a Finder drag-and-drop path with
        // a CJK filename, where each character costs 2 terminal columns)
        // scrolls to keep the caret visible and hides its own beginning —
        // which the real terminal window had plenty of room to show in full.
        promptRow.addComponent(textBox,
            LinearLayout.createLayoutData(LinearLayout.Alignment.FILL, LinearLayout.GrowPolicy.CAN_GROW));

        bottomDivider = new Label("");
        bottomDivider.setForegroundColor(LanternaTheme.divider());

        // Suggestion dropdown — lives between the bottom divider and the hint row
        suggestionPanel = new SuggestionPanel();

        // Inline ghost text — dim argument hint shown at the caret for commands that
        // have a progressive argument contract.

        // tracks the latest selected suggestion + current input.
        ((HighlightedTextBox) textBox).setGhostTextSupplier(this::inlineGhostText);
        ((HighlightedTextBox) textBox).setVisualLayoutSupplier(
            () -> textLayout, this::caretCol);

        hintMainLabel   = new Label("");
        hintSuffixLabel = new Label("");
        tasksPillLabel  = new Label("");
        tasksPillsPanel  = new Panel(new LinearLayout(Direction.HORIZONTAL).setSpacing(0));
        tasksHintLabel  = new Label("");
        collaborationPillLabel = new Label("Collaboration: Off");
        collaborationPillLabel.setForegroundColor(LanternaTheme.welcomeDim());
        projectsButtonLabel = new Label("≡ ");
        projectsButtonLabel.setForegroundColor(LanternaTheme.welcomeDim());
        vimModeLabel    = new Label("");
        vimModeLabel.setForegroundColor(LanternaTheme.welcomeDim());
        hintRow = new Panel(new LinearLayout(Direction.HORIZONTAL));
        // ≡ is the leftmost footer control, so it is also the first keyboard
        // stop (↓/→ walk the footer left→right).
        hintRow.addComponent(projectsButtonLabel);
        hintRow.addComponent(hintMainLabel);
        hintRow.addComponent(hintSuffixLabel);

        // PromptInputFooterLeftSide's [modePart][tasksPart][...parts] order


        // Both labels are empty (zero-width) when no background tasks exist.
        hintRow.addComponent(tasksPillsPanel);
        hintRow.addComponent(tasksHintLabel);
        hintRow.addComponent(vimModeLabel);
        collaborationRow = new Panel(new LinearLayout(Direction.HORIZONTAL).setSpacing(0));
        collaborationRow.addComponent(new Label("  "));
        collaborationRow.addComponent(collaborationPillLabel);
        statusLineComponent = new StatusLineComponent();
        // Construct before updateHint(): a surviving teammate-view selection
        // may ask the navigation host to clear this component during initial
        // projection (full-suite tests expose the same process-lifetime state
        // that a real session resume can carry).
        updateHint();

        addComponent(queuedPreviewPanel,
            LinearLayout.createLayoutData(LinearLayout.Alignment.FILL));
        addComponent(topDividerPanel);
        // FILL so promptRow (and in turn textBox, via its own FILL+CAN_GROW
        // layout data above) actually receives InputPanel's real width from
        // the outer VERTICAL LinearLayout — without it, promptRow is sized to
        // the sum of its children's own preferred widths (promptLabel +
        // textBox's pinned ~80 columns), capped well short of a wide terminal.
        addComponent(promptRow, LinearLayout.createLayoutData(LinearLayout.Alignment.FILL));
        addComponent(bottomDivider);
        addComponent(suggestionPanel);   // ← below divider, above hint

        // left column). FILL so it receives InputPanel's real width for
        // truncation — Alignment.FILL stretches the cross-axis (= width in a
        // VERTICAL layout); see the promptRow note above.
        addComponent(statusLineComponent,
            LinearLayout.createLayoutData(LinearLayout.Alignment.FILL));
        addComponent(hintRow);
        addComponent(collaborationRow);
    }

    /**
     * The multi-line prompt text box — extracted from an inline anonymous
     * {@link HighlightedTextBox} subclass to a named (non-static) inner class
     * so its ordered {@link #handleKeyStroke} protocol (readline shortcuts,
     * vim dispatch, chip-aware editing, history, suggestions, paste handling)
     * has a readable class boundary instead of living inside an anonymous
     * initializer.
     *
     * <p>Non-static on purpose: {@code handleKeyStroke} and its {@code rl_*}
     * helpers read and mutate {@link InputPanel}'s private state directly
     * ({@code actions}, {@code historyController}, {@code pastedContent},
     * {@code modeOverride}, {@code escOnce}, {@code isLoading}, …) — the same
     * reasoning as {@code LanternaReplScreen.ReplInputActions}. Promoting this
     * to a standalone top-level class would require threading every one of
     * those fields through explicit ports for no behavioral gain.
     */
    private final class PromptTextBox extends HighlightedTextBox {

        PromptTextBox(TerminalSize preferredSize, TextBox.Style style,
                      Supplier<List<Highlight>> highlightSupplier) {
            super(preferredSize, style, highlightSupplier);
        }

        {
            engine = new ReadlineEngine(
                this,
                super::handleKeyStroke,
                InputPanel.this::fireQueryChange);
        }

        /**
         * Handle a bracketed-paste payload delivered by the terminal
         * (Lanterna's BracketedPastePattern decoded the
         * {@code \e[200~ ... \e[201~} wrapper into a PasteKeyStroke).
         * <p>
         * Path matches {@link #rl_imagePaste()} but skips the clipboard
         * image probe — bracketed paste carries only text. If the user
         * has an image in their clipboard, the terminal either sends an
         * empty paste (no-op here) or nothing at all; Ctrl+V remains the
         * fallback for image paste.
         */
        private Result rl_handlePaste(String pastedText) {
            beginPaste();
            PASTE_EXECUTOR.execute(() -> {
                try {
                // Empty bracketed paste on macOS = user pasted an image with Cmd+V.
                if (StringUtils.isEmpty(pastedText)) {
                    ImagePaste.ImageWithDimensions img = ImagePaste.getImageFromClipboard();
                    if (img != null) {
                        final int pasteId = pastedContent.nextId();
                        PastedContent content =
                            new PastedContent(
                                pasteId, "image", img.base64(), img.mediaType(),
                                null, img.dimensions(), null);
                        pastedContent.put(content);
                        scheduleChipInsert(PastedRefParser.formatImageRef(pasteId), true);
                    }
                    return;
                }

                // 1. Drag-dropped image path? (VSCode Terminal pastes file paths)
                ImagePaste.ImageWithDimensions fromPath =
                    ImagePaste.tryReadImageFromPath(pastedText);
                if (fromPath != null) {
                    final int pasteId = pastedContent.nextId();
                    PastedContent content =
                        new PastedContent(
                            pasteId, "image", fromPath.base64(), fromPath.mediaType(),
                            null, fromPath.dimensions(), ImagePaste.asImageFilePath(pastedText));
                    ImageStore.cacheImagePath(content, sessionIdentity.get());
                    pastedContent.put(content);
                    scheduleChipInsert(
                        PastedRefParser.formatImageRef(pasteId), true);
                    return;
                }

                // 2. Large/multiline text paste → [Pasted text #N +X lines] chip.
                String stripped = PromptPasteTextPolicy.normalize(pastedText);
                int numLines = PastedRefParser.getPastedTextRefNumLines(stripped);
                if (PromptPasteTextPolicy.shouldFoldIntoChip(stripped, numLines)) {
                    final int pasteId = pastedContent.nextId();
                    PastedContent content = PastedContent.text(pasteId, stripped);
                    pastedContent.put(content);
                    scheduleChipInsert(PastedRefParser.formatPastedTextRef(pasteId, numLines));
                    return;
                }

                // 3. Small single-line text — insert inline at cursor.
                // Strip newlines: SINGLE_LINE TextBox cannot hold \n and inserting
                // one would cause Lanterna to fire an ENTER event → premature submit.
                final String inline = stripped.replace('\n', ' ').stripTrailing();
                if (!inline.isEmpty() && guiInvoker != null) {
                    guiInvoker.accept(() -> {
                        insertChipAtCursor(inline);
                        firePastedContentsChange();
                    });
                }
                } finally {
                    completePasteOnGui(null);
                }
            });
            return Result.HANDLED;
        }

        @Override
        public Result handleKeyStroke(KeyStroke key) {
            if (deferredPasteSubmit.get() && key.getKeyType() != KeyType.ENTER) {
                deferredPasteSubmit.set(false);
            }
            if (guiInputBatchDepth > 0) {
                if (canBufferPlainCharacter(key)) {
                    flushBufferedBackspaces(false);
                    bufferPlainCharacter(key.getCharacter());
                    return Result.HANDLED;
                }
                if (canBufferPlainBackspace(key)) {
                    flushBufferedPlainInput(false);
                    bufferPlainBackspace();
                    return Result.HANDLED;
                }
                // A navigation/submit key may follow buffered edits in the
                // same terminal poll. Commit them before taking the snapshot
                // for that distinct action.
                flushBufferedPlainInput(true);
                flushBufferedBackspaces(true);
            }
            String textBefore = currentText;
            int cursorBefore = caretCol();
            Mode modeBefore = modeOverride;
            Map<Integer, PastedContent> pastedBefore = pastedContent.isEmpty()
                ? Map.of() : pastedContent.snapshot();
            long suppressionAtEntry = draftUndoSuppressionGeneration;
            Result result = handleKeyStrokeRouted(key);
            if (suppressionAtEntry == draftUndoSuppressionGeneration) {
                boolean textChanged = !textBefore.equals(currentText);
                boolean modeChanged = modeBefore != modeOverride;
                boolean pastedChanged =
                    (!pastedBefore.isEmpty() || !pastedContent.isEmpty()) && !pastedBefore.equals(
                        pastedContent.snapshot());

                // navigation, and coalesces rapid typing for one second.
                if (textChanged || modeChanged || pastedChanged) {
                    draftUndo.recordDebounced(new DraftUndoBuffer.Snapshot(
                        textBefore, cursorBefore, pastedBefore, modeBefore));
                }
            }
            return result;
        }

        private Result handleKeyStrokeRouted(KeyStroke key) {
            // [DIAG] per-keystroke key-protocol probe (debug-level). Enable by setting
            // this class to DEBUG in logback to diagnose key encoding / Shift+Enter
            // issues or unrecognised key bindings (e.g. Ctrl+V arriving as plain 'v'
            // instead of ctrlDown=true) — supersedes the old dedicated
            // {@code -Danthropic.debug}/{@code ANTHROPIC_DEBUG}-gated CHARACTER-only log.
            if (KEY_DIAGNOSTICS && log.isDebugEnabled()) {
                KeyType kt = key.getKeyType();
                String charInfo = (kt == KeyType.CHARACTER && key.getCharacter() != null)
                    ? String.format("'%c'/0x%02x", key.getCharacter(), (int) key.getCharacter())
                    : "n/a";
                log.debug("[key-diag] type={} char={} shift={} ctrl={} alt={} class={}",
                    kt, charInfo,
                    key.isShiftDown(), key.isCtrlDown(), key.isAltDown(),
                    key.getClass().getSimpleName());
            }
            if (KEY_DIAGNOSTICS && log.isDebugEnabled()
                    && key.getKeyType() == KeyType.ARROW_UP
                    && !key.isShiftDown() && !key.isCtrlDown() && !key.isAltDown()) {
                log.debug("[key-up] handleKeyStroke ARROW_UP, row={}, lineCount={}, isSearching={}, suggVisible={}, vim={}",
                    getCaretPosition().getRow(), getLineCount(), historyController.isSearching(), suggestionPanel.isVisible(), vimEnabled);
            }
            // Re-entrancy fence: if vim is forwarding a key via
            // textBox.handleKeyStroke(key) we must NOT re-route through
            // handleVimKey — that would recurse on the same key. Send
            // it straight to the Lanterna TextBox parent.
            if (inVimKeyDispatch) {
                return super.handleKeyStroke(key);
            }
            // DEC 1004 focus events — route to MessagePanel.setFocused via callback.

            if (key.getKeyType() == KeyType.FOCUS_EVENT && key instanceof FocusEventKeyStroke fek) {
                if (actions != null) actions.focusChanged(fek.isFocused());
                return Result.HANDLED;
            }
            // Mouse events — let them bubble to the window listener which
            // routes scrolls to messagePanel. We must NOT consume here:
            // InputPanel is the focused component so events arrive here
            // first, and returning HANDLED would short-circuit the
            // window-level handler in LanternaReplScreen.
            if (key instanceof MouseAction) {
                return Result.UNHANDLED;
            }
            if (canUsePlainCharacterFastPath(key)) {
                // The established PromptInput sends ordinary text directly to its
                // input buffer. Avoid walking every modal/readline/navigation
                // stage when none of those states can own this character.
                KillRing.INSTANCE.resetAccumulation();
                KillRing.INSTANCE.resetYankState();
                return handleDefaultTextBoxKeyStroke(key);
            }
            // Ordered pre-editor protocol. A non-null result terminates routing;
            // null means only that the next stage must see the same keystroke.
            // Keep these stages in this order: their precedence is observable UI
            // behavior, not an implementation detail.
            Result preEditorResult = tryHandleMessageActionsKeyStroke(key);
            if (preEditorResult != null) return preEditorResult;
            preEditorResult = tryHandleTeammateKeyStroke(key);
            if (preEditorResult != null) return preEditorResult;
            preEditorResult = tryHandleFooterKeyStroke(key);
            if (preEditorResult != null) return preEditorResult;
            preEditorResult = tryHandleHistorySearchKeyStroke(key);
            if (preEditorResult != null) return preEditorResult;
            preEditorResult = tryHandlePasteOrModeCycleKeyStroke(key);
            if (preEditorResult != null) return preEditorResult;
            preEditorResult = tryHandleAutocompleteKeyStroke(key);
            if (preEditorResult != null) return preEditorResult;
            preEditorResult = tryHandleConfiguredKeybindingKeyStroke(key);
            if (preEditorResult != null) return preEditorResult;
            preEditorResult = tryHandleEmptyPromptAgentsKeyStroke(key);
            if (preEditorResult != null) return preEditorResult;
            Result editorResult = tryExitBashModeAtInputStart(key);
            if (editorResult != null) return editorResult;
            if (vimEnabled) {
                // Ctrl/Alt modifier combos bypass vim and use the
                // readline path. claude-code's global bindings (Ctrl+V
                // imagePaste, Ctrl+A home, Ctrl+P history, Ctrl+R search,
                // Alt+B/F word-wise nav) take priority over vim semantics
                // even inside INSERT mode — keystrokeToChar would have
                // dropped the ctrl flag and inserted a literal 'v' for
                // Ctrl+V. Plain (unmodified) keys still go through vim
                // so hjkl, escape→NORMAL, etc. work as expected.
                if (!key.isCtrlDown() && !key.isAltDown()) {
                    return handleVimKey(key);
                }
            }
            editorResult = tryHandleReadlineKeyStroke(key);
            if (editorResult != null) return editorResult;
            editorResult = tryHandleChipEditingKeyStroke(key);
            if (editorResult != null) return editorResult;
            editorResult = tryHandlePromptNavigationKeyStroke(key);
            if (editorResult != null) return editorResult;

            editorResult = tryHandleSubmitKeyStroke(key);
            if (editorResult != null) return editorResult;
            editorResult = tryHandleEscapeKeyStroke(key);
            if (editorResult != null) return editorResult;
            resetDoubleEscapeGateAfterNonEscapeKey();
            insertLazySpaceAfterChip(key);
            editorResult = tryEnterBashMode(key);
            if (editorResult != null) return editorResult;
            return handleDefaultTextBoxKeyStroke(key);
        }

        private boolean canUsePlainCharacterFastPath(KeyStroke key) {
            if (key.getKeyType() != KeyType.CHARACTER
                    || key.getCharacter() == null
                    || Character.isISOControl(key.getCharacter())
                    || key.isCtrlDown()
                    || key.isAltDown()) {
                return false;
            }
            return canUsePlainCharacterFastPath(key.getCharacter());
        }

        private boolean canUsePlainCharacterFastPath(char character) {
            if (Character.isISOControl(character) || !plainInputStateAllowsDirectEdit()) {
                return false;
            }
            return character != '!'
                || !textBox.getText().isEmpty()
                || modeOverride != null;
        }

        private boolean plainInputStateAllowsDirectEdit() {
            return !suggestionPanel.isVisible() && plainInputStateAllowsBatch();
        }

        private boolean plainInputStateAllowsBatch() {
            return !messageActionsActive
                && !taskNavigation.isActive()
                && !taskNavigation.isPillSelected()
                && !collaborationPillSelected
                && !workflowFooterSelected
                && !isCoordinatorPanelSelected()
                && !historyController.isSearching()
                && !customKeybindingsEnabled()
                && !vimEnabled
                && !escOnce
                && pastedContent.isEmpty();
        }

        private final StringBuilder bufferedPlainInput = new StringBuilder();
        private DraftUndoBuffer.Snapshot bufferedInputStart;
        private int bufferedBackspaces;
        private int bufferedBackspaceCaret;
        private DraftUndoBuffer.Snapshot bufferedBackspaceStart;

        private boolean canBufferPlainCharacter(KeyStroke key) {
            if (key.getKeyType() != KeyType.CHARACTER
                    || key.getCharacter() == null
                    || Character.isISOControl(key.getCharacter())
                    || key.isCtrlDown() || key.isAltDown()) {
                return false;
            }
            if (key.getCharacter() == '!' && !bufferedPlainInput.isEmpty()) {
                return canUsePlainCharacterFastPath('a');
            }
            return canUsePlainCharacterFastPath(key.getCharacter());
        }

        private void bufferPlainCharacter(char character) {
            if (bufferedPlainInput.isEmpty()) bufferedInputStart = captureDraftSnapshot();
            bufferedPlainInput.append(character);
        }

        private boolean bufferPlainText(String text) {
            if (text == null || text.length() < 2 || !plainInputStateAllowsBatch()) return false;
            boolean hasBufferedPrefix = !bufferedPlainInput.isEmpty()
                || !textBox.getText().isEmpty() || modeOverride != null;
            for (int index = 0; index < text.length(); index++) {
                char character = text.charAt(index);
                if (Character.isISOControl(character)
                        || character == '!' && !hasBufferedPrefix) return false;
                hasBufferedPrefix = true;
            }
            if (bufferedPlainInput.isEmpty()) bufferedInputStart = captureDraftSnapshot();
            bufferedPlainInput.append(text);
            return true;
        }

        private void flushBufferedPlainInput(boolean publishImmediately) {
            if (bufferedPlainInput.isEmpty()) return;
            String insertion = bufferedPlainInput.toString();
            bufferedPlainInput.setLength(0);
            int caret = caretCol();
            String before = currentText;
            String merged = before.substring(0, caret) + insertion + before.substring(caret);
            ((HighlightedTextBox) textBox).setTextPreservingViewport(merged);
            TextBoxOffsetAdapter.setOffset(textBox, caret + insertion.length());
            if (historyController.hasHistoryCursor()) historyController.onUserEdit();
            KillRing.INSTANCE.resetAccumulation();
            KillRing.INSTANCE.resetYankState();
            if (bufferedInputStart != null) {
                draftUndo.recordDebounced(bufferedInputStart);
                bufferedInputStart = null;
            }
            if (publishImmediately) deliverQueryChangeImmediately();
            else fireQueryChange();
        }

        private boolean canBufferPlainBackspace(KeyStroke key) {
            if (key.getKeyType() != KeyType.BACKSPACE
                    || key.isShiftDown() || key.isCtrlDown() || key.isAltDown()) {
                return false;
            }
            return canBufferPlainBackspaces(1);
        }

        private boolean canBufferPlainBackspaces(int count) {
            if (count < 1
                    || messageActionsActive
                    || taskNavigation.isActive()
                    || taskNavigation.isPillSelected()
                    || collaborationPillSelected
                    || workflowFooterSelected
                    || isCoordinatorPanelSelected()
                    || historyController.isSearching()
                    || customKeybindingsEnabled()
                    || vimEnabled
                    || escOnce
                    || !pastedContent.isEmpty()
                    || modeOverride != null
                    || currentText.indexOf('\n') >= 0) {
                return false;
            }
            int caret = bufferedBackspaces == 0 ? caretCol() : bufferedBackspaceCaret;
            int end = caret - bufferedBackspaces;
            int start = end - count;
            if (start < 0) return false;
            for (int index = start; index < end; index++) {
                if (Character.isSurrogate(currentText.charAt(index))) return false;
            }
            return true;
        }

        private void bufferPlainBackspace() {
            bufferPlainBackspaces(1);
        }

        private void bufferPlainBackspaces(int count) {
            if (bufferedBackspaces == 0) {
                bufferedBackspaceCaret = caretCol();
                bufferedBackspaceStart = captureDraftSnapshot();
            }
            bufferedBackspaces += count;
        }

        private void flushBufferedBackspaces(boolean publishImmediately) {
            if (bufferedBackspaces == 0) return;
            int count = bufferedBackspaces;
            bufferedBackspaces = 0;
            int caret = bufferedBackspaceCaret;
            bufferedBackspaceCaret = 0;
            int start = Math.max(0, caret - count);
            String before = currentText;
            textBox.setText(before.substring(0, start) + before.substring(caret));
            TextBoxOffsetAdapter.setOffset(textBox, start);
            if (historyController.hasHistoryCursor()) historyController.onUserEdit();
            KillRing.INSTANCE.resetAccumulation();
            KillRing.INSTANCE.resetYankState();
            if (bufferedBackspaceStart != null) {
                draftUndo.recordDebounced(bufferedBackspaceStart);
                bufferedBackspaceStart = null;
            }
            if (publishImmediately) deliverQueryChangeImmediately();
            else fireQueryChange();
        }

        /**
         * Message-actions overlay is the first modal key surface after terminal
         * focus/mouse events. It consumes every key while active.
         */
        private Result tryHandleMessageActionsKeyStroke(KeyStroke key) {
            if (!messageActionsActive) return null;
            if (customKeybindingsEnabled()) {
                Result resolved = dispatchViaResolver(key,
                    List.of("MessageActions", "Global"),
                    this::dispatchMessageActionsAction);
                if (resolved != null) return resolved;
            }
            if (key.getKeyType() == KeyType.ESCAPE) {
                if (actions != null) actions.messageActionsEscape();
                return Result.HANDLED;
            }
            if (key.getKeyType() == KeyType.CHARACTER
                    && key.getCharacter() != null && key.getCharacter() == 'c'
                    && key.isCtrlDown()) {
                if (actions != null) actions.messageActionsForceExit();
                return Result.HANDLED;
            }
            if (key.getKeyType() == KeyType.ARROW_UP
                    || (key.getKeyType() == KeyType.CHARACTER
                        && key.getCharacter() == 'k')) {
                if (actions != null) actions.messageActionsPrev();
                return Result.HANDLED;
            }
            if (key.getKeyType() == KeyType.ARROW_DOWN
                    || (key.getKeyType() == KeyType.CHARACTER
                        && key.getCharacter() == 'j')) {
                if (actions != null) actions.messageActionsNext();
                return Result.HANDLED;
            }
            if (key.getKeyType() == KeyType.CHARACTER) {
                char ch = key.getCharacter();
                if (ch == 'c') {
                    if (actions != null) actions.messageActionsCopy();
                    return Result.HANDLED;
                }
                if (ch == 'p') {
                    if (actions != null) actions.messageActionsCopyPrimaryInput();
                    return Result.HANDLED;
                }
                if (ch == '\n' || ch == '\r') {
                    if (actions != null) actions.messageActionsEdit();
                    return Result.HANDLED;
                }
            }
            if (key.getKeyType() == KeyType.ENTER) {
                if (actions != null) actions.messageActionsEdit();
                return Result.HANDLED;
            }
            // Other keys are consumed but ignored in message actions mode.
            return Result.HANDLED;
        }

        /**
         * Teammate navigation owns only its navigation keys; all other keys fall
         * through to the footer and editor stages below.
         */
        private Result tryHandleTeammateKeyStroke(KeyStroke key) {
            if (!taskNavigation.isActive()) return null;
            boolean exitingLocalAgentView = key.getKeyType() == KeyType.ESCAPE
                && coordinatorNavigation != null
                && coordinatorNavigation.isViewingLocalAgent();
            Result result = taskNavigation.handleTeammateKey(key, taskNavigationHost);
            if (result != null && exitingLocalAgentView) clearFooterSelection();
            return result;
        }

        /**
         * While the tasks pill owns focus, Footer/Global bindings precede the
         * pill's native navigation. A null result deliberately falls through to
         * global Ctrl/Alt handling.
         */
        private Result tryHandleFooterKeyStroke(KeyStroke key) {
            boolean footerSelected = workflowFooterSelected
                || isCoordinatorPanelSelected()
                || projectsButtonSelected
                || taskNavigation.isPillSelected()
                || collaborationPillSelected;
            if (!footerSelected) return null;
            if (customKeybindingsEnabled()) {
                Result resolved = dispatchViaResolver(key,
                    List.of("Footer", "Chat", "Global"),
                    this::dispatchFooterOrChatAction);
                if (resolved != null) return resolved;
            }
            return handleSelectedFooterNativeKey(key);
        }

        private Result handleSelectedFooterNativeKey(KeyStroke key) {
            KeyStroke normalized = normalizeNativeFooterKey(key);
            if (projectsButtonSelected) {
                return handleProjectsButtonKey(normalized);
            }
            if (workflowFooterSelected) {
                Result r = handleWorkflowFooterKey(normalized);
                if (r != null) return r;
            }
            // Subagent coordinator panel owns footer focus independently of the
            // teammate/bash pill.
            if (isCoordinatorPanelSelected()) {
                Result r = handleCoordinatorPanelKey(normalized);
                if (r != null) return r;
            }
            if (!taskNavigation.isPillSelected() && !collaborationPillSelected) return null;
            if (collaborationPillSelected) {
                return handleCollaborationPillKey(normalized);
            }
            if (normalized.getKeyType() == KeyType.ARROW_DOWN) {
                if (!visibleWorkflowRuns().isEmpty()) selectCurrentWorkflowFooter();
                else selectCollaborationFooter();
                return Result.HANDLED;
            }
            if (normalized.getKeyType() == KeyType.ARROW_RIGHT) {
                if (!visibleWorkflowRuns().isEmpty()) selectCurrentWorkflowFooter();
                else selectCollaborationFooter();
                return Result.HANDLED;
            }
            if (normalized.getKeyType() == KeyType.ARROW_LEFT) {
                // ← walks back left — from the tasks pill that is the ≡ button.
                selectProjectsButton();
                return Result.HANDLED;
            }
            return taskNavigation.handlePillKey(normalized, taskNavigationHost);
        }

        /**
         * Keys while the ≡ projects button is selected: ↑/Esc leave the footer,
         * ↓/→ resume the released pill chain, Enter toggles the drawer.
         */
        private Result handleProjectsButtonKey(KeyStroke key) {
            KeyType type = key.getKeyType();
            if (type == KeyType.ARROW_UP || type == KeyType.ESCAPE) {
                clearFooterSelection();
                updateHint();
                return Result.HANDLED;
            }
            if (type == KeyType.ARROW_DOWN || type == KeyType.ARROW_RIGHT) {
                selectFirstFooterStopAfterProjectsButton();
                updateHint();
                return Result.HANDLED;
            }
            if (type == KeyType.ARROW_LEFT) {
                return Result.HANDLED; // leftmost stop — nowhere further to go
            }
            if (type == KeyType.ENTER && !key.isShiftDown()) {
                projectsButtonSelected = false;
                refreshFooterPills();
                updateHint();
                if (actions != null) actions.toggleProjectPanel();
                return Result.HANDLED;
            }
            return Result.HANDLED; // swallow everything else while footer-focused
        }

        private KeyStroke normalizeNativeFooterKey(KeyStroke key) {
            if (key.getKeyType() != KeyType.CHARACTER || key.getCharacter() == null
                    || !key.isCtrlDown() || key.isAltDown() || key.isShiftDown()) {
                return key;
            }
            return switch (Character.toLowerCase(key.getCharacter())) {
                case 'p' -> new KeyStroke(KeyType.ARROW_UP);
                case 'n' -> new KeyStroke(KeyType.ARROW_DOWN);
                default -> key;
            };
        }

        /** History search is an overlay, so its resolver and native handler run before paste or text editing. */
        private Result tryHandleHistorySearchKeyStroke(KeyStroke key) {
            if (!historyController.isSearching()) return null;
            if (customKeybindingsEnabled()) {
                Result resolved = dispatchViaResolver(key,
                    List.of("HistorySearch", "Global"),
                    this::dispatchHistorySearchOrGlobalAction);
                if (resolved != null) return resolved;
            }
            Result result = historyController.handleSearchKey(key);
            if (result != null) return result;
            // If not consumed, fall through to normal handling.
            historyController.exitSearch();
            return null;
        }

        /**
         * Bracketed paste precedes mode cycling because a paste event must never
         * be interpreted as ordinary printable input.
         */
        private Result tryHandlePasteOrModeCycleKeyStroke(KeyStroke key) {
            // Bracketed paste — terminal wrapped the paste with \e[200~ ... \e[201~
            // and Lanterna's BracketedPastePattern decoded it into a
            // PasteKeyStroke carrying the verbatim text. Preferred over Ctrl+V
            // clipboard probing because the terminal knows what the user actually
            // pasted without us shelling out to pbpaste/xclip.
            if (key.getKeyType() == KeyType.PASTE && key instanceof PasteKeyStroke pks) {
                return rl_handlePaste(pks.getPastedText());
            }
            if (key.getKeyType() == KeyType.REVERSE_TAB
                    || (key.getKeyType() == KeyType.TAB && key.isShiftDown())
                    || (key.getKeyType() == KeyType.TAB && key.isAltDown())) {

                // Shift+Tab cycles its permission mode rather than the leader's.
                if (taskNavigation.isViewing()) {
                    taskNavigation.cycleViewedPermissionMode(taskNavigationHost);
                } else {
                    cyclePermissionMode();
                }
                return Result.HANDLED;
            }
            return null;
        }

        /** Autocomplete consumes its navigation and Escape before normal bindings or request cancellation. */
        private Result tryHandleAutocompleteKeyStroke(KeyStroke key) {
            if (!suggestionPanel.isVisible()) return null;
            if (customKeybindingsEnabled()) {
                Result resolved = dispatchViaResolver(key,
                    List.of("Chat", "Autocomplete", "Global"),
                    this::dispatchAutocompleteOrChatAction);
                if (resolved != null) return resolved;
            }
            return handleSuggestionKey(key);
        }

        /**
         * Resolver-driven Chat/Global dispatch is opt-in and deliberately leaves
         * Enter/Escape to their stateful native handlers below.
         */
        private Result tryHandleConfiguredKeybindingKeyStroke(KeyStroke key) {
            // Matched Chat/Global keys use user overrides; unmatched keys retain
            // the readline fallback. Bash typing bypasses the resolver entirely.
            if (modeOverride == Mode.BASH) return null;
            if (!customKeybindingsEnabled()
                    && !keybindingDispatcher.hasPendingChord()
                    && !startsDefaultControlChord(key)) {
                return null;
            }
            return dispatchViaResolver(key);
        }

        private Result tryHandleEmptyPromptAgentsKeyStroke(KeyStroke key) {
            if (key.getKeyType() != KeyType.ARROW_LEFT
                    || key.isCtrlDown() || key.isAltDown() || key.isShiftDown()
                    || isLoading || modeOverride != null || taskNavigation.isViewing()
                    || !currentText.isEmpty() || !pastedContent.isEmpty()
                    || !leftArrowOpensAgents.getAsBoolean() || actions == null) {
                return null;
            }
            actions.openAgents();
            return Result.HANDLED;
        }

        private boolean startsDefaultControlChord(KeyStroke key) {
            return key.getKeyType() == KeyType.CHARACTER
                && key.isCtrlDown() && !key.isAltDown()
                && key.getCharacter() != null
                && Character.toLowerCase(key.getCharacter()) == 'x';
        }

        private boolean customKeybindingsEnabled() {
            return keybindingsStore != null && keybindingsStore.isEnabled();
        }

        /**
         * At the start of an empty bash-mode input, destructive/editing keys
         * return to prompt mode. Escape intentionally returns null after that
         * transition so the normal double-Escape protocol still runs.
         */
        private Result tryExitBashModeAtInputStart(KeyStroke key) {
            if (modeOverride != Mode.BASH || caretCol() != 0) return null;
            KeyType keyType = key.getKeyType();
            boolean ctrlU = key.isCtrlDown() && !key.isAltDown()
                && key.getCharacter() != null
                && Character.toLowerCase(key.getCharacter()) == 'u';
            if (keyType == KeyType.BACKSPACE || keyType == KeyType.DELETE || ctrlU) {
                resetMode();
                fireQueryChange();
                return Result.HANDLED;
            }
            if (keyType == KeyType.ESCAPE) {
                resetMode();
                // Fall through to the normal Escape handling below.
            }
            return null;
        }

        /**
         * Readline/Emacs shortcuts match useTextInput's Ctrl/Meta mapping.
         * The kill/yank reset side effects deliberately run for every key that
         * reaches this phase, including a key with no matching shortcut.
         */
        private Result tryHandleReadlineKeyStroke(KeyStroke key) {
            if (!ReadlineEngine.isKillKey(key)) KillRing.INSTANCE.resetAccumulation();
            if (!ReadlineEngine.isYankKey(key)) KillRing.INSTANCE.resetYankState();
            if (key.getKeyType() == KeyType.CHARACTER) {
                char ch = key.getCharacter();

                // Ctrl+letter dispatch — Lanterna decodes the terminal raw
                // control byte into a CHARACTER KeyStroke with ctrlDown=true.
                if (key.isCtrlDown() && !key.isAltDown()) {
                    Result ctrlResult = switch (Character.toLowerCase(ch)) {
                        case 'a' -> engine.home();            // Ctrl+A
                        case 'b' -> actions.backgroundForegroundTasks()
                            ? Result.HANDLED : engine.left(); // Ctrl+B: task priority, then readline
                        case 'd' -> rl_ctrlD();               // Ctrl+D
                        case 'e' -> rl_ctrlE();               // Ctrl+E
                        case 'f' -> engine.right();           // Ctrl+F
                        case 'k' -> engine.killToEnd();       // Ctrl+K
                        case 'n' -> rl_historyDown();         // Ctrl+N
                        case 'p' -> rl_historyUp();           // Ctrl+P
                        case 'u' -> engine.killToStart();     // Ctrl+U
                        case 'w' -> engine.killWordBefore();  // Ctrl+W
                        case 'y' -> engine.yank();            // Ctrl+Y
                        case 'v' -> rl_imagePaste();          // Ctrl+V
                        case 'l' -> rl_redrawScreen();        // Ctrl+L
                        case 'o' -> rl_toggleTranscript();    // Ctrl+O
                        case 'g' -> rl_externalEditor();      // Ctrl+G
                        case 's' -> rl_stash();               // Ctrl+S
                        case '_' -> { rl_undo(); yield Result.HANDLED; } // Ctrl+_
                        case 'r' -> rl_historySearch();       // Ctrl+R
                        case 't' -> {
                            if (actions != null) actions.toggleTodos();
                            yield Result.HANDLED;
                        }
                        default -> null;
                    };
                    if (ctrlResult != null) return ctrlResult;
                }

                // Alt+X: Lanterna sets isAltDown for ESC-prefixed sequences.
                if (key.isAltDown()) {
                    Result altResult = switch (ch) {
                        case 'b', 'B' -> engine.prevWord();
                        case 'f', 'F' -> engine.nextWord();
                        case 'd', 'D' -> engine.killWordAfter();
                        case 'y', 'Y' -> engine.yankPop();
                        case 't', 'T' -> {
                            if (actions != null) actions.toggleThinking();
                            yield Result.HANDLED;
                        }
                        case 'p', 'P' -> {
                            if (actions != null) actions.openModelPicker();
                            yield Result.HANDLED;
                        }
                        case 'o', 'O' -> {
                            if (actions != null) actions.toggleFastMode();
                            yield Result.HANDLED;
                        }
                        default -> null;
                    };
                    if (altResult != null) return altResult;
                }
            }

            // Some terminals send modifier-arrow sequences instead of Alt+B/F.
            if (key.getKeyType() == KeyType.ARROW_LEFT && (key.isCtrlDown() || key.isAltDown())) {
                return engine.prevWord();
            }
            if (key.getKeyType() == KeyType.ARROW_RIGHT && (key.isCtrlDown() || key.isAltDown())) {
                return engine.nextWord();
            }
            if (key.getKeyType() == KeyType.BACKSPACE && key.isAltDown()) {
                return engine.killWordBefore();
            }
            if (key.getKeyType() == KeyType.DELETE && key.isAltDown()) {
                return engine.killWordAfter();
            }
            return null;
        }

        /**
         * Image and pasted-text chips are atomic cursor tokens. This stage runs
         * after modifier readline shortcuts but before ordinary arrow/history
         * routing, matching the compatibility Cursor token semantics.
         */
        private Result tryHandleChipEditingKeyStroke(KeyStroke key) {
            if (key.getKeyType() == KeyType.ARROW_LEFT
                    && !key.isCtrlDown() && !key.isAltDown()
                    && hopLeftOverChip()) {
                return Result.HANDLED;
            }
            if (key.getKeyType() == KeyType.ARROW_RIGHT
                    && !key.isCtrlDown() && !key.isAltDown()
                    && hopRightOverChip()) {
                return Result.HANDLED;
            }

            if (key.getKeyType() == KeyType.BACKSPACE
                    && !key.isCtrlDown() && !key.isAltDown()
                    && chipBackspace()) {
                pruneOrphanedPastedContents();
                return Result.HANDLED;
            }

            if (key.getKeyType() == KeyType.DELETE
                    && !key.isCtrlDown() && !key.isAltDown()
                    && chipDelete()) {
                pruneOrphanedPastedContents();
                return Result.HANDLED;
            }
            return null;
        }

        /**
         * Shift arrows activate overlay/task navigation; plain vertical arrows
         * first move within multiline text and only then fall back to history.
         */
        private Result tryHandlePromptNavigationKeyStroke(KeyStroke key) {
            if (key.getKeyType() == KeyType.ARROW_UP && key.isShiftDown()
                    && !key.isCtrlDown() && !key.isAltDown()) {
                if (taskNavigation.hasRunningTeammates()) {
                    taskNavigation.handleShiftSelection(-1, taskNavigationHost);
                } else if (actions != null) {
                    actions.toggleMessageActions();
                }
                return Result.HANDLED;
            }
            if (key.getKeyType() == KeyType.ARROW_DOWN && key.isShiftDown()
                    && !key.isCtrlDown() && !key.isAltDown()) {
                taskNavigation.handleShiftSelection(1, taskNavigationHost);
                return Result.HANDLED;
            }
            if (key.getKeyType() == KeyType.ARROW_UP
                    && !key.isShiftDown() && !key.isCtrlDown() && !key.isAltDown()) {
                return rl_historyUp();
            }
            if (key.getKeyType() == KeyType.ARROW_DOWN
                    && !key.isShiftDown() && !key.isCtrlDown() && !key.isAltDown()) {
                return rl_historyDown();
            }
            return null;
        }

        /** Handles Shift/Alt Enter newline insertion and the ordinary submit flow. */
        private Result tryHandleSubmitKeyStroke(KeyStroke key) {
            // Forward a real unmodified ENTER so Lanterna's MULTI_LINE TextBox
            // performs its split-line-at-caret path instead of inserting a glyph.
            if (key.getKeyType() == KeyType.ENTER
                    && (key.isShiftDown() || key.isAltDown())) {
                if (KEY_DIAGNOSTICS && log.isDebugEnabled()) {
                    log.debug("[key-diag] Shift/Alt+Enter branch entered — forwarding ENTER (multi-line split)");
                    log.debug("[key-diag] BEFORE split: lineCount={} text=[{}] caret={}",
                        getLineCount(), getText().replace("\n", "\\n"), getCaretPosition());
                }
                Result result = super.handleKeyStroke(new KeyStroke(KeyType.ENTER));
                if (KEY_DIAGNOSTICS && log.isDebugEnabled()) {
                    log.debug("[key-diag] AFTER split: lineCount={} text=[{}] caret={} result={}",
                        getLineCount(), getText().replace("\n", "\\n"), getCaretPosition(), result);
                }
                return result;
            }
            if (key.getKeyType() != KeyType.ENTER || key.isShiftDown()) return null;
            if (pendingPastes.get() > 0) {
                deferredPasteSubmit.set(true);
                return Result.HANDLED;
            }

            String text = getText();
            if (KEY_DIAGNOSTICS && log.isDebugEnabled()) {
                log.debug("[key-diag] Enter submit branch: lineCount={} text=[{}]",
                    getLineCount(), text.replace("\n", "\\n"));
            }
            // Catch genuinely huge unbracketed pastes before they can be submitted.
            if (PromptPasteTextPolicy.looksLikeUnbracketedPaste(text)) {
                String stripped = PromptPasteTextPolicy.normalize(text);
                int numLines = PastedRefParser.getPastedTextRefNumLines(stripped);
                int pasteId = pastedContent.nextId();
                pastedContent.put(PastedContent.text(pasteId, stripped));
                setText("");
                insertChipAtCursor(PastedRefParser.formatPastedTextRef(pasteId, numLines));
                firePastedContentsChange();
                return Result.HANDLED;
            }

            String submitText = prependModePrefix(text.trim());
            if (taskNavigation.isViewing()) {
                taskNavigation.injectViewed(submitText);
                suppressDraftUndoRecording();
                draftUndo.clear();
                setText("");
                pastedContent.clear();
                firePastedContentsChange();
                resetMode();
                return Result.HANDLED;
            }
            // Image-only input is intentionally submit-able.
            if ((!StringUtils.isBlank(submitText) || !pastedContent.isEmpty()) && actions != null) {
                actions.submit(submitText);
                suppressDraftUndoRecording();
                draftUndo.clear();
                setText("");
                pastedContent.clear();
                firePastedContentsChange();
                resetMode();
            }
            return Result.HANDLED;
        }

        /**
         * Escape priority: loading cancel, idle non-empty double-clear, then
         * idle empty double-open-selector. Returning null is reserved solely
         * for a non-Escape key.
         */
        private Result tryHandleEscapeKeyStroke(KeyStroke key) {
            if (key.getKeyType() != KeyType.ESCAPE) return null;
            if (isLoading) {
                if (escTimer != null) { escTimer.cancel(false); escTimer = null; }
                if (hintTimer != null) { hintTimer.cancel(false); hintTimer = null; }
                escOnce = false;
                escEmptyFirst = false;
                if (actions != null) actions.cancel();
                return Result.HANDLED;
            }

            // Original priority: recover queued human prompts before either
            // non-empty double-clear or empty double-Esc message selection.
            if (popEditableQueuedCommands()) return Result.HANDLED;

            String text = getText();
            if (!StringUtils.isBlank(text)) {
                escEmptyFirst = false;
                if (!escOnce) {
                    escOnce = true;
                    if (escTimer != null) escTimer.cancel(false);
                    escTimer = ESC_SCHEDULER.schedule(() -> {
                        escOnce = false;
                        escEmptyFirst = false;
                        escTimer = null;
                    }, DOUBLE_PRESS_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                    showTemporaryHint("Esc again to clear", LanternaTheme.welcomeDim(), HINT_TIMEOUT_MS);
                } else {
                    if (escTimer != null) { escTimer.cancel(false); escTimer = null; }
                    if (hintTimer != null) { hintTimer.cancel(false); hintTimer = null; }
                    escOnce = false;

                    historyController.addEntry(text, System.getProperty("user.dir"));
                    setText("");
                    hideSuggestions();
                    resetMode();
                    resetHistory();
                    updateHint();
                    fireQueryChange();
                }
                return Result.HANDLED;
            }

            if (!escOnce || !escEmptyFirst) {
                if (hasMessages != null && !hasMessages.getAsBoolean()) return Result.HANDLED;
                escOnce = true;
                escEmptyFirst = true;
                if (escTimer != null) escTimer.cancel(false);
                escTimer = ESC_SCHEDULER.schedule(() -> {
                    escOnce = false;
                    escEmptyFirst = false;
                    escTimer = null;
                }, DOUBLE_PRESS_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } else {
                if (escTimer != null) { escTimer.cancel(false); escTimer = null; }
                escOnce = false;
                escEmptyFirst = false;
                if (actions != null) actions.showMessageSelector();
            }
            return Result.HANDLED;
        }

        /** Any non-Escape key cancels a pending double-Escape gate. */
        private void resetDoubleEscapeGateAfterNonEscapeKey() {
            if (!escOnce) return;
            escOnce = false;
            if (escTimer != null) { escTimer.cancel(false); escTimer = null; }
        }

        /** Inserts the deferred separator after an image chip before forwarding printable input. */
        private void insertLazySpaceAfterChip(KeyStroke key) {
            boolean isPrintable = key.getKeyType() == KeyType.CHARACTER
                && key.getCharacter() != null
                && key.getCharacter() != ' '
                && !key.isCtrlDown() && !key.isAltDown();
            if (pastedContent.consumeLazySpace(isPrintable)) {
                super.handleKeyStroke(new KeyStroke(' ', false, false));
            }
        }

        /** Enters bash mode when an unmodified leading {@code !} is typed. */
        private Result tryEnterBashMode(KeyStroke key) {
            if (key.getKeyType() != KeyType.CHARACTER
                    || key.getCharacter() == null
                    || key.getCharacter() != '!'
                    || key.isCtrlDown()
                    || key.isAltDown()
                    || !textBox.getText().isEmpty()
                    || modeOverride != null) {
                return null;
            }
            modeOverride = Mode.BASH;
            updateMode();
            fireQueryChange();
            return Result.HANDLED;
        }

        /** Final TextBox fallback, including the panel's mode/query notifications. */
        private Result handleDefaultTextBoxKeyStroke(KeyStroke key) {
            Result result = super.handleKeyStroke(key);
            // Ordinary plain-character input is already guaranteed NORMAL by
            // canUsePlainCharacterFastPath(). Avoid re-reading the complete
            // prompt merely to prove the mode did not change on every byte of
            // a PTY burst. All fallback keys retain the full mode transition.
            if (!canUsePlainCharacterFastPath(key)) updateMode();
            fireQueryChange();
            return result;
        }

        // ── Readline helpers ────────────────────────────────────────────
        // Pure cursor/word/kill-yank editing lives in ReadlineEngine (see
        // the `engine` field). The methods below stay in the panel because
        // they depend on panel state (transcript / EOF / history / overlay
        // actions) — they delegate to the engine only for editing.

        /** Ctrl+E: end-of-line (default) or transcript show-all when in transcript mode. */
        private Result rl_ctrlE() {
            if (isTranscriptMode && actions != null) {
                actions.transcriptShowAll();
                return Result.HANDLED;
            }
            return engine.end();
        }
        private Result rl_historyUp() {
            int caret = caretCol();
            int moved = textLayout.moveVertically(caret, -1);
            if (moved != caret) {
                TextBoxOffsetAdapter.setOffset(textBox, moved);
                fireQueryChange();
                return Result.HANDLED;
            }
            if (popEditableQueuedCommands()) return Result.HANDLED;
            return historyController.up();
        }
        private Result rl_historyDown() {
            int caret = caretCol();
            int moved = textLayout.moveVertically(caret, 1);
            if (moved != caret) {
                TextBoxOffsetAdapter.setOffset(textBox, moved);
                fireQueryChange();
                return Result.HANDLED;
            }
            return historyDownOrEnterFooter();
        }

        /**
         * Ctrl+L — redraw screen (clears visual artifacts).
         */
        private Result rl_redrawScreen() {
            if (actions != null) actions.redrawScreen();
            return Result.HANDLED;
        }

        /**
         * Ctrl+O — toggle transcript mode.
         */
        private Result rl_toggleTranscript() {
            if (actions != null) actions.toggleTranscript();
            return Result.HANDLED;
        }

        /**
         * Ctrl+G — open external editor.
         */
        private Result rl_externalEditor() {
            if (actions != null) actions.externalEditor();
            return Result.HANDLED;
        }

        /**
         * Ctrl+S — push/pop the current prompt.
         */
        private Result rl_stash() {
            String input = getText();
            if (input.trim().isEmpty() && stashedPrompt != null) {
                suppressDraftUndoRecording();
                StashedPrompt restored = stashedPrompt;
                stashedPrompt = null;
                textBox.setText(restored.text());
                moveCaretTo(Math.min(restored.cursorOffset(), restored.text().length()));
                restorePastedContents(restored.pastedContents());
                updateMode();
                fireQueryChange();
                invalidate();
            } else if (!input.trim().isEmpty()) {
                suppressDraftUndoRecording();
                stashedPrompt = new StashedPrompt(input, caretCol(),
                    pastedContent.snapshot());
                textBox.setText("");
                moveCaretTo(0);
                restorePastedContents(Map.of());
                updateMode();
                fireQueryChange();
                invalidate();
                if (actions != null) actions.stash();
            }
            return Result.HANDLED;
        }

        /**
         * Ctrl+_ — restore the previous prompt draft.
         */
        private void rl_undo() {
            DraftUndoBuffer.Snapshot previous = draftUndo.undo();
            if (previous == null) return;
            suppressDraftUndoRecording();
            modeOverride = previous.modeOverride();
            textBox.setText(previous.text());
            TextBoxOffsetAdapter.setOffset(textBox, previous.cursorOffset());
            restorePastedContents(previous.pastedContents());
            updateMode();
            fireQueryChange();
            invalidate();
        }


        private Result rl_historySearch() {
            if (actions != null && actions.openHistorySearch()) {
                return Result.HANDLED;
            }
            return historyController.toggleSearch();
        }

        private Result rl_ctrlD() {
            String text = getText();
            if (text.isEmpty()) {

                // Ctrl+D through the double-press exit gate. Request
                // cancellation is owned by Ctrl+C/Escape, not EOF.
                if (actions != null) {
                    actions.exitOnEmptyEof();
                }
            } else {
                // Delete char under cursor
                super.handleKeyStroke(new KeyStroke(KeyType.DELETE, false, false));
                fireQueryChange();
            }
            return Result.HANDLED;
        }

        /**
         * Ctrl+V — unified clipboard paste.
         */
        private Result rl_imagePaste() {
            beginPaste();
            PASTE_EXECUTOR.execute(() -> {
                try {
                // 1. Clipboard image
                ImagePaste.ImageWithDimensions img = ImagePaste.getImageFromClipboard();
                if (img != null) {
                    final int pasteId = pastedContent.nextId();
                    PastedContent content =
                        new PastedContent(
                            pasteId, "image", img.base64(), img.mediaType(),
                            null, img.dimensions(), null);
                    String cachedPath = ImageStore.cacheImagePath(content, sessionIdentity.get());
                    if (cachedPath != null) {
                        content = new PastedContent(
                            pasteId, "image", img.base64(), img.mediaType(),
                            null, img.dimensions(), cachedPath);
                    }
                    pastedContent.put(content);
                    scheduleChipInsert(PastedRefParser.formatImageRef(pasteId), true);
                    return;
                }

                // 2. Clipboard text — drag-dropped image path?
                String text = ImagePaste.getClipboardText();
                if (text == null) return;
                ImagePaste.ImageWithDimensions fromPath = ImagePaste.tryReadImageFromPath(text);
                if (fromPath != null) {
                    final int pasteId = pastedContent.nextId();
                    PastedContent content =
                        new PastedContent(
                            pasteId, "image", fromPath.base64(), fromPath.mediaType(),
                            null, fromPath.dimensions(), ImagePaste.asImageFilePath(text));
                    ImageStore.cacheImagePath(content, sessionIdentity.get());
                    pastedContent.put(content);
                    scheduleChipInsert(
                        PastedRefParser.formatImageRef(pasteId), true);
                    return;
                }

                // 3. Large/multiline text paste → [Pasted text #N +X lines] chip
                String stripped = PromptPasteTextPolicy.normalize(text);
                int numLines = PastedRefParser.getPastedTextRefNumLines(stripped);
                if (PromptPasteTextPolicy.shouldFoldIntoChip(stripped, numLines)) {
                    final int pasteId = pastedContent.nextId();
                    PastedContent content = PastedContent.text(pasteId, stripped);
                    pastedContent.put(content);
                    scheduleChipInsert(
                        PastedRefParser
                            .formatPastedTextRef(pasteId, numLines));
                    return;
                }

                // 4. Small single-line text — insert inline at cursor.
                // Strip newlines: SINGLE_LINE TextBox cannot hold \n.
                final String inline = stripped.replace('\n', ' ').stripTrailing();
                if (!inline.isEmpty() && guiInvoker != null) {
                    guiInvoker.accept(() -> {
                        insertChipAtCursor(inline);
                        firePastedContentsChange();
                    });
                }
                } finally {
                    completePasteOnGui(null);
                }
            });
            return Result.HANDLED;
        }

        // ── Resolver-driven dispatch (opt-in keybinding customization) ──

        /**
         * Resolve {@code key} against the user keybinding store (Chat + Global contexts) and fire the
         * matched action.
         */
        private Result dispatchViaResolver(KeyStroke key) {
            return dispatchViaResolver(key, List.of("Chat", "Global"),
                this::dispatchChatAction);
        }

        /**
         * Resolve one key against an explicit active-context set. The dispatcher
         * owns chord state; this prompt-specific bridge only maps a resolved
         * action back to its UI policy and preserves deliberate fall-through for
         * submit/cancel.
         */
        private Result dispatchViaResolver(KeyStroke key, List<String> contexts,
                                           Function<String, Boolean> dispatcher) {
            ContextKeybindingDispatcher.Result resolved =
                keybindingDispatcher.resolve(contexts, key);
            return switch (resolved) {
                case ContextKeybindingDispatcher.Result.Action(String action) -> {
                    // voice:pushToTalk needs the accumulated burst state to
                    // distinguish a sustained hold (terminal auto-repeat) from a
                    // single tap before forwarding to the reserved voice port.
                    if (Strings.CS.equals(action, "voice:pushToTalk")) {
                        yield voicePushToTalk();
                    }
                    yield Boolean.TRUE.equals(dispatcher.apply(action)) ? Result.HANDLED : null;
                }
                case ContextKeybindingDispatcher.Result.Consumed() -> Result.HANDLED;
                case ContextKeybindingDispatcher.Result.None() -> null;
            };
        }

        /**
         * Push-to-talk fast path for the bound {@code voice:pushToTalk} action.
         * <p>
         * A terminal has no distinct key-release events, so a held space
         * surfaces as a stream of repeated single-space events which we
         * accumulate across the {@link #PTT_RESET_WINDOW_MS} burst window (see
         * the reserved-voice state above). Once the accumulation crosses
         * {@link #PTT_HOLD_THRESHOLD} the burst is classified as a HOLD; a
         * single tap stays short of the floor and is a plain space. We forward
         * the classification to the (currently no-op) voice port, which
         * consumes the key only while recording is active; otherwise — and for
         * taps — the space falls through to the input so normal typing is
         * unaffected.
         */
        private Result voicePushToTalk() {
            boolean held = accumulatePushToTalk();
            boolean consume = actions != null && actions.handlePushToTalk(held);
            return consume ? Result.HANDLED : null;
        }

        private boolean accumulatePushToTalk() {
            long now = System.currentTimeMillis();
            if (now - pttLastEventMs > PTT_RESET_WINDOW_MS) {
                // The previous burst decayed; this is the first event of a new one.
                pttAccumulated = 0;
            }
            pttAccumulated++;
            pttLastEventMs = now;
            return pttAccumulated >= PTT_HOLD_THRESHOLD;
        }

        private boolean dispatchAutocompleteOrChatAction(String action) {
            return switch (action) {
                case "autocomplete:previous" -> { suggestionPanel.moveUp(); yield true; }
                case "autocomplete:next" -> { suggestionPanel.moveDown(); yield true; }
                case "autocomplete:accept" -> {
                    acceptSelectedSuggestion();
                    yield true;
                }
                case "autocomplete:dismiss" -> { hideSuggestions(); yield true; }
                default -> dispatchChatAction(action);
            };
        }

        private boolean dispatchFooterOrChatAction(String action) {
            if (Strings.CS.startsWith(action, "footer:")) {
                KeyStroke canonical = switch (action) {
                    case "footer:up" -> new KeyStroke(KeyType.ARROW_UP);
                    case "footer:down" -> new KeyStroke(KeyType.ARROW_DOWN);
                    case "footer:next" -> new KeyStroke(KeyType.ARROW_RIGHT);
                    case "footer:previous" -> new KeyStroke(KeyType.ARROW_LEFT);
                    case "footer:openSelected" -> new KeyStroke(KeyType.ENTER);
                    case "footer:clearSelection" -> new KeyStroke(KeyType.ESCAPE);
                    case "footer:close" -> new KeyStroke('x', false, false);
                    default -> null;
                };
                return canonical != null && handleSelectedFooterNativeKey(canonical) != null;
            }
            return dispatchChatAction(action);
        }

        private boolean dispatchHistorySearchOrGlobalAction(String action) {
            if (Strings.CS.startsWith(action, "historySearch:")) {
                boolean consumed = historyController.handleSearchAction(action);
                if (!consumed && Strings.CS.equals("historySearch:execute", action)) {
                    // The action means execute regardless of which physical key
                    // the user bound to it. Re-enter after clearing search state
                    // with a canonical Enter so the normal submit path runs.
                    PromptTextBox.this.handleKeyStroke(new KeyStroke(KeyType.ENTER));
                    return true;
                }
                return consumed;
            }
            return dispatchChatAction(action);
        }

        private boolean dispatchMessageActionsAction(String action) {
            switch (action) {
                case "messageActions:prev" -> {
                    if (actions != null) actions.messageActionsPrev();
                }
                case "messageActions:next" -> {
                    if (actions != null) actions.messageActionsNext();
                }
                case "messageActions:prevUser" -> {
                    if (actions != null) actions.messageActionsPrevUser();
                }
                case "messageActions:nextUser" -> {
                    if (actions != null) actions.messageActionsNextUser();
                }
                case "messageActions:top" -> {
                    if (actions != null) actions.messageActionsTop();
                }
                case "messageActions:bottom" -> {
                    if (actions != null) actions.messageActionsBottom();
                }
                case "messageActions:escape" -> {
                    if (actions != null) actions.messageActionsEscape();
                }
                case "messageActions:ctrlc" -> {
                    if (actions != null) actions.messageActionsForceExit();
                }
                case "messageActions:enter" -> {
                    if (actions != null) actions.messageActionsEdit();
                }
                case "messageActions:c" -> {
                    if (actions != null) actions.messageActionsCopy();
                }
                case "messageActions:p" -> {
                    if (actions != null) actions.messageActionsCopyPrimaryInput();
                }
                default -> { return false; }
            }
            return true;
        }

        /**
         * Fire a resolved Chat/Global action. Returns {@code true} if the key was
         * consumed. Enter/Escape ({@code chat:submit}/{@code chat:cancel}) are
         * intentionally NOT consumed here — they are returned so the existing
         * submit/cancel logic (teammate injection, image-only submit, double-press
         * timers) runs. Every other known action routes to the same method the
         * hardcoded switch would have called, so default behaviour is unchanged;
         * unknown actions are consumed (no-op) so they don't leak into typed input.
         */
        private boolean dispatchChatAction(String action) {
            switch (action) {
                case "chat:submit", "chat:cancel" -> { return false; }
                case "history:previous"      -> { rl_historyUp(); return true; }
                case "history:next"          -> { rl_historyDown(); return true; }
                case "chat:undo"             -> { rl_undo(); return true; }
                case "chat:externalEditor"   -> { rl_externalEditor(); return true; }
                case "chat:stash"            -> { rl_stash(); return true; }
                case "chat:imagePaste"       -> { rl_imagePaste(); return true; }
                case "app:redraw"            -> { rl_redrawScreen(); return true; }
                case "app:toggleTranscript"  -> { rl_toggleTranscript(); return true; }
                case "app:toggleTodos"       -> { if (actions != null) actions.toggleTodos(); return true; }
                case "chat:thinkingToggle"   -> { if (actions != null) actions.toggleThinking(); return true; }
                case "chat:modelPicker"      -> { if (actions != null) actions.openModelPicker(); return true; }
                case "app:exit"              -> { rl_ctrlD(); return true; }
                case "history:search"        -> { rl_historySearch(); return true; }
                case "chat:cycleMode"        -> { cyclePermissionMode(); return true; }
                case "chat:killAgents"       -> { handleKillAgents(); return true; }
                case "chat:fastMode" -> {
                    if (actions != null) actions.toggleFastMode();
                    return true;
                }
                case "app:toggleTeammatePreview" -> {
                    log.debug("[keybindings] no-op for unsupported action: {}", action);
                    return true;
                }
                default -> {
                    log.debug("[keybindings] unsupported action: {}", action);
                    return true;
                }
            }
        }


        private void handleKillAgents() {
            boolean hasRunningAgents = taskNavigation.registry().listBackground().stream()
                .anyMatch(t -> t.type() == TaskType.LOCAL_AGENT
                    && t.status() == TaskStatus.RUNNING);
            if (!hasRunningAgents) {
                lastKillAgentsPressMs = 0;
                showTemporaryHint("No background agents running",
                    LanternaTheme.welcomeDim(), 2000);
                return;
            }

            long now = System.currentTimeMillis();
            if (lastKillAgentsPressMs != 0
                    && now - lastKillAgentsPressMs <= KILL_AGENTS_CONFIRM_WINDOW_MS) {
                lastKillAgentsPressMs = 0;
                if (actions != null) actions.killBackgroundAgents();
                return;
            }

            lastKillAgentsPressMs = now;
            String shortcut = keybindingsStore.currentResolver()
                .getBindingDisplayText("chat:killAgents", "Chat");
            if (StringUtils.isBlank(shortcut)) shortcut = "ctrl+x ctrl+k";
            showTemporaryHint("Press " + shortcut + " again to stop background agents",
                LanternaTheme.welcomeDim(), KILL_AGENTS_CONFIRM_WINDOW_MS);
        }

        /**
         * Schedule chip text insertion + change-notification on the GUI thread.
         * For image chips pass {@code armLazySpace=true} so a leading space
         * is auto-inserted before the next non-space printable keystroke.
         */
        private void scheduleChipInsert(String chip) {
            scheduleChipInsert(chip, false);
        }

        private void scheduleChipInsert(String chip, boolean armLazySpace) {
            if (guiInvoker != null) {
                guiInvoker.accept(() -> {
                    insertChipAtCursor(chip, armLazySpace);
                    firePastedContentsChange();
                });
            }
        }
    }


    // ── Layout ──────────────────────────────────────────────────────────────


    private void refreshInputHighlights() {
        List<HighlightedTextBox.Highlight> next = new ArrayList<>();
        List<BtwTriggers.Trigger> btwTriggers = BtwTriggers.find(currentText);
        if (!btwTriggers.isEmpty()) {
            next.add(new HighlightedTextBox.Highlight(
                btwTriggers.getFirst().start(), btwTriggers.getFirst().end(),
                BtwTriggers.highlightColor(), false, 15));
        }
        if (historySearchHighlight != null
                && historySearchHighlight.start() >= 0
                && historySearchHighlight.end() <= currentText.length()) {
            next.add(historySearchHighlight);
        }
        currentHighlights = List.copyOf(next);
    }


    private void refreshTextLayout() {
        PromptTextLayout next = PromptTextLayout.create(currentText, inputContentColumns);
        int rows = next.lineCount();
        textLayout = next;
        if (rows != currentTextRows) {
            currentTextRows = rows;
            // explicitPreferredSize is always non-null because TextBox's constructor
            // installs it. Preserve the growable preferred width and update only height.
            TerminalSize preferred = textBox.getPreferredSize();
            int columns = preferred != null ? preferred.getColumns() : 80;
            textBox.setPreferredSize(new TerminalSize(columns, rows));
            textBox.invalidate();
            // Lanterna invalidation does not bubble. Only walk ancestors when geometry
            // changes; doing it for ordinary characters would restore the former
            // child-to-parent lock-order risk on every keystroke.
            for (Container parent = textBox.getParent();
                 parent != null; parent = parent.getParent()) {
                parent.invalidate();
            }
        }
    }

    public void setWidth(int width) {
        lastDividerWidth = width;
        updateTopDivider(width);
        String line = "─".repeat(Math.max(1, width));
        bottomDivider.setText(line);
        updateBorderColor();
    }

    /**
     * Refresh divider text every time SmartLayout hands us a new size —
     * the initial {@link #setWidth} in LanternaReplScreen fires once at
     * startup, but the actual columns InputPanel receives can change on
     * subsequent layouts (Shift+Enter growing textBox rows shrinks
     * messagePanel; window resize; Welcome dialog dismiss). Without this
     * override the bottom divider stays stuck at the startup width and
     * ends up shorter than the panel.
     */
    @Override
    public synchronized InputPanel setSize(TerminalSize size) {
        super.setSize(size);
        if (size != null) {
            int contentColumns = Math.max(1,
                size.getColumns() - PROMPT_INPUT_COLUMN_OVERHEAD);
            if (contentColumns != inputContentColumns) {
                inputContentColumns = contentColumns;
                refreshTextLayout();
            }
            if (size.getColumns() != lastDividerWidth) setWidth(size.getColumns());
        }
        return this;
    }

    /**
     * Compute the current banner color.
     */
    private TextColor bannerColor() {
        return sessionColor != null ? sessionColor : LanternaTheme.agentCyan();
    }

    /**
     * Refresh the top divider.
     */
    private void updateTopDivider(int width) {
        if (StringUtils.isBlank(agentName)) {
            TextColor borderColor = mode == Mode.BASH
                ? LanternaTheme.bashBorder()
                : sessionColor != null ? sessionColor : LanternaTheme.promptBorder();
            if (StringUtils.isNotBlank(historyBorderLabel)) {
                String badge = " " + historyBorderLabel + " ";
                int left = Math.min(2, Math.max(0, width - badge.length()));
                int trailing = Math.max(0, width - left - badge.length());
                topDividerLeft.setText("─".repeat(left));
                topDividerBadge.setText(badge);
                topDividerBadge.setForegroundColor(LanternaTheme.welcomeDim());
                topDividerBadge.setBackgroundColor(TextColor.ANSI.DEFAULT);
                topDividerTrail.setText("─".repeat(trailing));
                topDividerTrail.setForegroundColor(borderColor);
                topDividerTrail.setBackgroundColor(TextColor.ANSI.DEFAULT);
            } else {
                topDividerLeft.setText("─".repeat(Math.max(1, width)));
                topDividerBadge.setText("");
                topDividerBadge.setForegroundColor(TextColor.ANSI.DEFAULT);
                topDividerBadge.setBackgroundColor(TextColor.ANSI.DEFAULT);
                topDividerTrail.setText("");
                topDividerTrail.setForegroundColor(TextColor.ANSI.DEFAULT);
                topDividerTrail.setBackgroundColor(TextColor.ANSI.DEFAULT);
            }
            topDividerLeft.setForegroundColor(borderColor);
            // Bottom divider must also follow the current border color —
            // otherwise /color pink flips the top border but the bottom one
            // stays gray. matches the banner-active branch below.
            bottomDivider.setForegroundColor(borderColor);
            bottomDivider.setBackgroundColor(TextColor.ANSI.DEFAULT);
        } else {
            TextColor bc = bannerColor();
            // badge = " NAME " (space-padded); trail = "──" without background
            String badgeText = " " + agentName + " ";
            String trail = "──";
            int dashes = Math.max(0, width - badgeText.length() - trail.length());
            topDividerLeft.setText(dashes > 0 ? "─".repeat(dashes) : "");
            topDividerLeft.setForegroundColor(bc);
            topDividerLeft.setBackgroundColor(TextColor.ANSI.DEFAULT);
            topDividerBadge.setText(badgeText);
            topDividerBadge.setForegroundColor(LanternaTheme.inverseText());
            topDividerBadge.setBackgroundColor(bc);
            topDividerTrail.setText(trail);
            topDividerTrail.setForegroundColor(bc);
            topDividerTrail.setBackgroundColor(TextColor.ANSI.DEFAULT);

            bottomDivider.setForegroundColor(bc);
            bottomDivider.setBackgroundColor(TextColor.ANSI.DEFAULT);
        }
    }

    /**
     * Set (or clear) the session name shown in the top divider.
     */
    public void setAgentName(String name) {
        this.agentName = (StringUtils.isNotBlank(name)) ? name : null;
        updateTopDivider(lastDividerWidth);
    }

    /**
     * Recompute the top/bottom border color.
     */
    private void updateBorderColor() {
        // Delegate entirely to updateTopDivider which handles both banner-active
        // and no-banner paths including correct bottom-divider color.
        updateTopDivider(lastDividerWidth);
    }

    // ── Public API ──────────────────────────────────────────────────────────

    /**
     * Renders {@code text} (possibly ANSI-colored, multi-line) as the custom status line above the hint
     * row, with {@code padding} horizontal inset.
     */
    public void setStatusLine(String text, int padding) {
        persistentStatusText = text;
        persistentStatusPadding = padding;
        renderEffectiveStatusLine();
    }

    /** Clears the custom status line (collapses to zero height). */
    public void clearStatusLine() {
        persistentStatusText = null;
        persistentStatusPadding = 0;
        renderEffectiveStatusLine();
    }

    /** Shows progress/navigation text without replacing the persistent HUD. */
    public void setTransientStatusLine(String text, int padding) {
        transientStatusText = text;
        transientStatusPadding = padding;
        renderEffectiveStatusLine();
    }

    /** Clears transient progress/navigation text and restores the persistent HUD. */
    public void clearTransientStatusLine() {
        transientStatusText = null;
        transientStatusPadding = 0;
        renderEffectiveStatusLine();
    }

    private void renderEffectiveStatusLine() {
        if (StringUtils.isNotBlank(persistentStatusText)) {
            statusLineComponent.setStatusText(persistentStatusText, persistentStatusPadding);
            persistentStatusVisible = true;
        } else if (StringUtils.isNotBlank(transientStatusText)) {
            statusLineComponent.setStatusText(transientStatusText, transientStatusPadding);
            persistentStatusVisible = false;
        } else {
            statusLineComponent.clear();
            persistentStatusVisible = false;
        }
        updateHint();
    }

    /** Wire the single outward action/notification port (replaces ~19 {@code setOnXxx} setters). */
    public void setActions(InputActions actions) { this.actions = actions; }

    /** Installs the opt-in keybinding resolver. Null when customization is disabled. */
    public void setKeybindingsStore(UserKeybindingsStore store) {
        this.keybindingsStore = store;
        keybindingDispatcher.setStore(store);
    }

    /**
     * Routes keyboard focus back to the text box. Used after a transient
     * overlay (e.g. the inline permission prompt) closes — we want the next
     * keystroke to go into the prompt, not the now-empty overlay region.
     */
    public void takeFocus() {
        if (textBox != null) textBox.takeFocus();
    }

    public void setHasMessages(BooleanSupplier supplier) { this.hasMessages = supplier; }

    public void setIsLoading(boolean loading) {
        boolean changed = this.isLoading != loading;
        this.isLoading = loading;
        if (changed) {
            updatePromptColor();
            updateHint();
            invalidate();
        }
    }

    /** Re-fire the current text+cursor through {@code actions.queryChanged} (called from background threads via invokeLater). */
    public void triggerQueryChange() { fireQueryChange(); }

    /** Wire the Lanterna GUI thread invoker so chip insertion off-thread is safe. */
    public void setGuiInvoker(Consumer<Runnable> invoker) {
        this.guiInvoker = invoker;
    }

    /** Starts one terminal-read input batch; called only by the GUI host. */
    public void beginGuiInputBatch() {
        guiInputBatchDepth++;
    }

    /** Accepts a decoded printable run when the prompt is the active input owner. */
    public boolean handleGuiTextBatch(Interactable focused, String text) {
        if (guiInputBatchDepth == 0
                || focused != textBox
                || textBox.getInputFilter() != null) {
            return false;
        }
        return ((PromptTextBox) textBox).bufferPlainText(text);
    }

    boolean handleGuiTextBatchForTest(String text) {
        return guiInputBatchDepth > 0
            && ((PromptTextBox) textBox).bufferPlainText(text);
    }

    /** Accepts a decoded Backspace run when the prompt is the active input owner. */
    public boolean handleGuiBackspaceBatch(Interactable focused, int count) {
        if (guiInputBatchDepth == 0 || focused != textBox || textBox.getInputFilter() != null) {
            return false;
        }
        PromptTextBox prompt = (PromptTextBox) textBox;
        if (!prompt.canBufferPlainBackspaces(count)) return false;
        prompt.flushBufferedPlainInput(false);
        prompt.bufferPlainBackspaces(count);
        return true;
    }

    boolean handleGuiBackspaceBatchForTest(int count) {
        PromptTextBox prompt = (PromptTextBox) textBox;
        if (guiInputBatchDepth == 0 || !prompt.canBufferPlainBackspaces(count)) return false;
        prompt.flushBufferedPlainInput(false);
        prompt.bufferPlainBackspaces(count);
        return true;
    }

    /** Publishes the final prompt state after Lanterna drains the PTY queue. */
    public void endGuiInputBatch() {
        if (guiInputBatchDepth == 0) return;
        if (guiInputBatchDepth == 1) {
            // Replace or close an already-visible dropdown before the first
            // input frame so it can never be paired with a newer prompt. When
            // no dropdown exists yet, commit the prompt first and let the same
            // GUI cycle build/show new suggestions afterwards; this preserves
            // immediate echo for a whole `/config` terminal write. File
            // discovery remains asynchronous inside SuggestionController.
            boolean replaceVisibleSuggestions = suggestionPanel.isVisible();
            ((PromptTextBox) textBox).flushBufferedPlainInput(replaceVisibleSuggestions);
            ((PromptTextBox) textBox).flushBufferedBackspaces(replaceVisibleSuggestions);
        }
        guiInputBatchDepth--;
    }

    /**
     * Wires the shared {@link SessionIdentity} used for
     * {@code ImageStore.cacheImagePath} — pass the SAME instance the
     * QuerySession/HookEngine hold so a single {@code switchToSession} call
     * is visible here too.
     */
    public void wireSessionIdentity(SessionIdentity sessionIdentity) {
        this.sessionIdentity = sessionIdentity;
    }

    /**
     * Apply a user-selected prompt-bar color from {@code /color}.
     */
    public void setSessionColor(String colorName) {
        if (colorName == null || Strings.CS.equals("default", colorName)) {
            this.sessionColor = null;
        } else {
            this.sessionColor = LanternaTheme.agentColor(colorName);
            // Unknown name → leave the field null, so we fall back to default.
        }

        // session color paints the input's TOP/BOTTOM BORDER, not the prompt
        // pointer. updatePromptColor re-renders ❯ in its mode-only color
        // (bash/plan/default) — sessionColor no longer touches the pointer.
        updateBorderColor();
        updatePromptColor();
        invalidate();
    }

    /** Returns a copy of the current pasted contents. */
    public Map<Integer, PastedContent> getPastedContents() {
        return pastedContent.snapshot();
    }

    /**
     * Public hook so external owners (LanternaReplScreen) can refresh the
     * hint row after mutating pasted contents off-thread.
     */
    public void refreshHint() {
        updateHint();
    }

    private void firePastedContentsChange() {
        if (actions != null) {
            actions.pastedContentsChanged(getPastedContents());
        }
    }


    private record InputTruncation(String text, boolean applied) {}


    private InputTruncation truncateForInput(String text) {
        if (text == null) return new InputTruncation("", false);
        // Take an id only once the length gate passes — nextId increments a counter.
        if (text.length() <= InputPasteTruncation.TRUNCATION_THRESHOLD) {
            return new InputTruncation(text, false);
        }
        int pasteId = pastedContent.nextId();
        InputPasteTruncation.Truncated truncated =
            InputPasteTruncation.maybeTruncateMessageForInput(text, pasteId);
        pastedContent.put(PastedContent.text(pasteId, truncated.placeholderContent()));
        firePastedContentsChange();
        return new InputTruncation(truncated.truncatedText(), true);
    }

    /**
     * Insert a chip string at the cursor position, preserving cursor placement at the end of the chip.
     */
    private void insertChipAtCursor(String chip) {
        insertChipAtCursor(chip, false);
    }

    /**
     * Insert a chip with optional lazy-space arming.
     */
    private void insertChipAtCursor(String chip, boolean armLazySpace) {
        draftUndo.record(captureDraftSnapshot());
        String text = textBox.getText();
        int caretCol = caretCol();
        // Bound caret (Lanterna can return past-end on empty input)
        if (caretCol > text.length()) caretCol = text.length();

        String prefix = pastedContent.prefixBeforeChipAndArm(armLazySpace);
        String newText = text.substring(0, caretCol) + prefix + chip + text.substring(caretCol);
        int newCaret = caretCol + prefix.length() + chip.length();
        textBox.setText(newText);
        // Move cursor to newCaret. Same reason as moveCaretTo: sending HOME
        // + N×ARROW_RIGHT through handleKeyStroke makes the overridden
        // routine call hopRightOverChip on every step — fine for plain text
        // but lethal right after inserting an [Image #N] chip, since the
        // first ARROW_RIGHT would hop the caret over the chip we just added.
        TextBoxOffsetAdapter.setOffset(textBox, newCaret);
        updateMode();
        fireQueryChange();
    }

    /**
     * Prune pastedContents entries whose {@code [Image #N]} chip is no longer present in the input
     * text.
     */
    private void pruneOrphanedImages() {
        if (pastedContent.isEmpty()) return;
        String text = currentText;
        Set<Integer> referenced = new HashSet<>();
        for (PastedRefParser.Ref ref : PastedRefParser.parseReferences(text)) {
            referenced.add(ref.id());
        }
        boolean changed = false;
        for (PastedContent c : pastedContent.valuesSnapshot()) {
            if (c.isImage() && !referenced.contains(c.id())) {
                pastedContent.remove(c.id());
                changed = true;
            }
        }
        if (changed) firePastedContentsChange();
    }

    /** Alias kept for clarity at call sites that prune after chip edits. */
    private void pruneOrphanedPastedContents() { pruneOrphanedImages(); }


    // [Image #N] chips are atomic: the cursor hops over them on left/right,
    // and backspace/delete remove them whole. Cursor never lands inside.

    private static final Pattern IMAGE_REF_AT_START = Pattern.compile("^\\[Image #\\d+]");
    private static final Pattern IMAGE_REF_AT_END = Pattern.compile("\\[Image #\\d+]$");
    /**
     * Matches a token ref ({@code [Pasted text #N]}, {@code [Image #N]}, {@code [...Truncated text #N
     * +M lines...]}) immediately before the caret.
     */
    private static final Pattern TOKEN_REF_AT_END =
        Pattern.compile("(^|\\s)\\[(Pasted text #\\d+(?: \\+\\d+ lines)?|Image #\\d+|\\.\\.\\.Truncated text #\\d+ \\+\\d+ lines\\.\\.\\.)]$");

    /** {@code imageRefStartingAt} — chip starts at offset. */
    private int[] imageRefStartingAt(String text, int offset) {
        Matcher m = IMAGE_REF_AT_START.matcher(text.substring(offset));
        return m.find() ? new int[]{offset, offset + m.group().length()} : null;
    }

    /** {@code imageRefEndingAt} — chip ends at offset. */
    private int[] imageRefEndingAt(String text, int offset) {
        Matcher m = IMAGE_REF_AT_END.matcher(text.substring(0, offset));
        return m.find() ? new int[]{offset - m.group().length(), offset} : null;
    }

    /** Package-private for {@code InputPanel*} tests (was exposed via {@code caretOffsetForTest()}). */
    int caretCol() {
        try {
            if (currentTextRows == 1 && textBox != null) {
                // The hot path is single-line. TextBoxOffsetAdapter otherwise
                // asks Lanterna for line count, line content, and full text on
                // every key; the row-local column is already the absolute
                // UTF-16 offset in this geometry.
                return Math.max(0, Math.min(
                    textBox.getCaretPosition().getColumn(), currentText.length()));
            }
            return TextBoxOffsetAdapter.offset(textBox);
        } catch (Exception _) {
            return 0;
        }
    }

    private void moveCaretTo(int col) {
        // Use setCaretPosition directly — sending synthetic HOME + ARROW_RIGHT
        // keystrokes routes back through the textBox's overridden
        // handleKeyStroke, which calls hopRightOverChip → moveCaretTo and
        // recurses until StackOverflowError. setCaretPosition bypasses the
        // input event chain entirely.
        TextBoxOffsetAdapter.setOffset(textBox, col);
    }


    private boolean hopLeftOverChip() {
        String text = textBox.getText();
        int caret = caretCol();
        int[] chip = imageRefEndingAt(text, caret);
        if (chip != null) {
            moveCaretTo(chip[0]);
            return true;
        }
        return false;
    }


    private boolean hopRightOverChip() {
        String text = textBox.getText();
        int caret = caretCol();
        int[] chip = imageRefStartingAt(text, caret);
        if (chip != null) {
            moveCaretTo(chip[1]);
            return true;
        }
        return false;
    }


    private boolean chipBackspace() {
        String text = textBox.getText();
        int caret = caretCol();

        // Case 1: cursor at chip.start → delete chip forward (+ trailing space)
        int[] chipAfter = imageRefStartingAt(text, caret);
        if (chipAfter != null) {
            int end = chipAfter[1];
            if (end < text.length() && text.charAt(end) == ' ') end++;
            String newText = text.substring(0, caret) + text.substring(end);
            textBox.setText(newText);
            moveCaretTo(caret);
            updateMode(); fireQueryChange();
            return true;
        }

        // Case 2: cursor after a pasted/truncated/image ref + next char is ws/EOL
        if (caret > 0 && (caret >= text.length() || Character.isWhitespace(text.charAt(caret)))) {
            String before = text.substring(0, caret);
            Matcher m = TOKEN_REF_AT_END.matcher(before);
            if (m.find()) {
                int matchStart = m.start() + m.group(1).length();
                String newText = text.substring(0, matchStart) + text.substring(caret);
                textBox.setText(newText);
                moveCaretTo(matchStart);
                updateMode(); fireQueryChange();
                return true;
            }
        }

        // Case 3: cursor at chip.end → backspace = left() hops to chip.start,
        // then delete chip.start.chip.end
        int[] chipBefore = imageRefEndingAt(text, caret);
        if (chipBefore != null) {
            String newText = text.substring(0, chipBefore[0]) + text.substring(chipBefore[1]);
            textBox.setText(newText);
            moveCaretTo(chipBefore[0]);
            updateMode(); fireQueryChange();
            return true;
        }

        return false;  // fallback to default char-by-char backspace
    }


    private boolean chipDelete() {
        String text = textBox.getText();
        int caret = caretCol();
        int[] chip = imageRefStartingAt(text, caret);
        if (chip != null) {
            String newText = text.substring(0, chip[0]) + text.substring(chip[1]);
            textBox.setText(newText);
            moveCaretTo(chip[0]);
            updateMode(); fireQueryChange();
            return true;
        }
        return false;
    }

    public void setVimEnabled(boolean enabled) {
        this.vimEnabled = enabled;
        if (enabled) {
            vim.reset();
            updateVimModeLabel();
        } else {
            vimModeLabel.setText("");
        }
    }

    public void setQueuedHint(boolean queued) {
        if (queued) {
            setHintLabel(hintMainLabel, "");
            setHintLabel(hintSuffixLabel, "Press up to edit queued messages");
            hintSuffixLabel.setForegroundColor(LanternaTheme.queuedText());
        } else {
            updateHint();
        }
    }

    /**
     * Render a live prompt-area queue projection. Unlike the former transcript
     * append path, these rows disappear when commands are popped or drained.
     */
    public void setQueuedCommands(List<QueuedCommand> commands) {
        List<String> immutableLines = QueuedPromptPreviewFormatter.format(commands);
        if (queuedPreviewLines.equals(immutableLines)) {
            setQueuedHint(!immutableLines.isEmpty());
            return;
        }
        queuedPreviewLines = immutableLines;
        queuedPreviewPanel.removeAllComponents();
        for (String line : queuedPreviewLines) {
            Label label = new Label(line);
            label.setForegroundColor(LanternaTheme.queuedText());
            label.addStyle(SGR.ITALIC);
            queuedPreviewPanel.addComponent(label,
                LinearLayout.createLayoutData(LinearLayout.Alignment.FILL));
        }
        setQueuedHint(!queuedPreviewLines.isEmpty());
        queuedPreviewPanel.invalidate();
    }

    private boolean popEditableQueuedCommands() {
        if (actions == null) return false;
        QueuedInputDraft restored = actions.popEditableQueuedCommands(getText(), caretCol());
        if (restored == null) return false;

        suppressDraftUndoRecording();
        mode = Mode.NORMAL;
        modeOverride = null;
        textBox.setText(restored.text());
        moveCaretTo(Math.min(restored.cursorOffset(), restored.text().length()));
        restored.pastedContents().values().forEach(pastedContent::put);
        hideSuggestions();
        historyController.reset();
        updateMode();
        firePastedContentsChange();
        fireQueryChange();
        setQueuedHint(false);
        if (vimEnabled) syncVimBuffer();
        invalidate();
        return true;
    }

    /**
     * Show suggestion items below the input divider.
     * @param items    list of (primary, description) pairs
     * @param termW    terminal width for column sizing
     */
    public void showSuggestions(List<SuggestionPanel.Suggestion> items, int termW) {
        suggestionContext = SuggestionContext.STANDARD;
        suggestionPanel.setSuggestions(items, termW);
    }

    /** Show path completions for the current bash token. */
    public void showBashPathSuggestions(List<SuggestionPanel.Suggestion> items, int termW) {
        suggestionContext = SuggestionContext.BASH_PATH;
        suggestionPanel.setSuggestions(items, termW);
    }

    /**
     * Show suggestion items with a pre-computed name-column width — see
     * {@link SuggestionPanel#setSuggestions(List, int, int)}.
     */
    public void showSuggestions(List<SuggestionPanel.Suggestion> items, int termW, int commandColumnWidth) {
        suggestionContext = SuggestionContext.STANDARD;
        suggestionPanel.setSuggestions(items, termW, commandColumnWidth);
    }

    /** Hide the suggestion dropdown. */
    public void hideSuggestions() {
        suggestionContext = SuggestionContext.NONE;
        suggestionPanel.hide();
    }

    /** Current displayed input mode; bash text itself intentionally omits the leading {@code !}. */
    public boolean isBashMode() {
        return mode == Mode.BASH;
    }

    /**
     * Show an argument hint as dim text directly after the input caret.
     */
    public void setArgumentHint(String hint) {
        argumentHint = StringUtils.isBlank(hint) ? null : hint;
        textBox.invalidate();
    }

    private String inlineGhostText() {
        String hint = argumentHint;
        if (StringUtils.isEmpty(hint)) return null;
        return Strings.CS.endsWith(currentText, " ") ? hint : " " + hint;
    }

    String inlineGhostTextForTest() {
        return inlineGhostText();
    }

    public void cyclePermissionMode() {


        // can stay strict — see PermissionMode.fromString / PermissionGate.parseMode.
        permMode = PermissionModeCycle.next(
            permMode, bypassPermissionsModeAvailable.getAsBoolean());
        updateHint();
        if (actions != null) actions.permissionModeChanged(permMode);
    }

    /** Installs the live session policy used by the Shift+Tab mode cycle. */
    public void setBypassPermissionsModeAvailable(BooleanSupplier available) {
        bypassPermissionsModeAvailable = available != null ? available : () -> true;
        if (!bypassPermissionsModeAvailable.getAsBoolean()
                &&Strings.CS.equals( "bypassPermissions", permMode)) {
            permMode = "default";
            updateHint();
        }
    }

    /** Returns the current permission mode (e.g. "bypass", "auto", "plan"). */
    public String getPermissionMode() { return this.permMode; }


    public String getVimMode() {
        return vimEnabled ? vim.getMode().name() : null;
    }

    /** Programmatically set the permission mode and refresh the hint row. */
    public void setPermissionMode(String mode) {
        this.permMode = mode;
        updateHint();
    }

    public String getText() { return currentText; }

    /**
     * Set the input text programmatically (e.g., from external editor).
     */
    public void setText(String text) {
        InputTruncation truncated = truncateForInput(text);
        textBox.setText(truncated.text());
        moveCaretToTextEnd();
        updateMode();
        fireQueryChange();
    }

    /**
     * Restores image pasted contents from a UserMessage (after rewind).
     */
    public void restoreImageChips(Map<Integer, PastedContent> images) {
        if (images == null || images.isEmpty()) return;
        restorePastedContents(images);
    }

    /** Replaces all image paste state, including clearing it when the replacement is empty. */
    public void replaceImageChips(Map<Integer, PastedContent> images) {
        restorePastedContents(images != null ? images : Map.of());
    }

    /**
     * Called by LanternaReplScreen after auto-restore (Esc interrupt) or MessageSelector.
     */
    public void setRestoredText(String text) {
        if (text == null) return;
        modeOverride = InputModes.overrideFromPrefix(text);
        InputTruncation truncated = truncateForInput(InputModes.stripPrefix(text));
        textBox.setText(truncated.text());
        if (truncated.applied()) {
            // END only reaches the end of the FIRST line; a truncated value is
            // routinely multi-line, so use the real final line/column.
            moveCaretToTextEnd();
        } else {
            textBox.handleKeyStroke(new KeyStroke(
                KeyType.END, false, false));
        }
        updateMode();
        fireQueryChange();
    }

    /** Wire a PromptHistory so Up/Down arrows navigate history. */
    public void setPromptHistory(PromptHistory history) {
        historyController.setPromptHistory(history);
    }

/**
     * Set session ID and project for history filtering.
     */
    public void setHistoryContext(String sessionId, String project) {
        historyController.setContext(sessionId, project);
    }

    /** Viewed teammate/local-agent prompts replace disk history while that transcript is active. */
    public void setLiveHistorySupplier(
            Supplier<List<PromptHistory.Entry>> liveHistorySupplier) {
        historyController.setLiveHistorySupplier(liveHistorySupplier);
    }

    /**
     * Whether the slash/@ suggestion dropdown should be suppressed right now — true while the user is
     * navigating history or reverse-i-searching.
     */
    public boolean isSuppressingSuggestions() {
        return historyController.isNavigating();
    }

    /**
     * Reset history navigation state after a submit.
     */
    public void resetHistory() {
        historyController.reset();
        escOnce           = false;
        modeOverride      = null;  // clear any mode from history entry

        if (hintTimer != null) { hintTimer.cancel(false); hintTimer = null; }
        updateHint();
    }

    // History navigation (Up=Ctrl+P / Down=Ctrl+N) + reverse-i-search moved to
    // InputHistoryController.

    /**
     * Apply a history entry: set text, restore pasted contents, and advance the pasted-content
     * controller's id sequence.
     */
    private void applyHistoryEntry(PromptHistory.Entry entry, boolean cursorToStart) {
        String display = entry.display();
        // Strip mode prefix BEFORE checking for newlines — the prefix is
        // single-char and matters for modeOverride, but the mode char '!'
        // is never a newline itself.
        Mode entryMode = InputModes.overrideFromPrefix(display);
        String body = InputModes.stripPrefix(display);
        modeOverride = entryMode;
        // Restore FIRST: restorePastedContents() clears the whole map, which would
        // drop a truncation chip registered before it (and it also advances the id
        // sequence past the entry's own chips so the new id can't collide).

        // directly editable instead of being collapsed into a pasted-text chip.
        restorePastedContents(entry.pastedContents());
        InputTruncation truncated = truncateForInput(body);
        textBox.setText(truncated.text());
        if (truncated.applied()) {
            moveCaretToTextEnd();
        } else {
            TextBoxOffsetAdapter.setOffset(textBox,
                cursorToStart ? 0 : truncated.text().length());
        }
        updateMode();
        fireQueryChange();
    }

    /** Apply an entry selected by the Ctrl+R history picker, with the caret at the end. */
    public void applyHistoryPickerEntry(PromptHistory.Entry entry) {
        applyHistoryEntry(entry, false);
    }

    /**
     * Replace current pasted contents with the given map, bumping the controller's id sequence past any
     * incoming id so future Ctrl+V doesn't collide.
     */
    private void restorePastedContents(Map<Integer, PastedContent> restored) {
        pastedContent.restore(restored);
        firePastedContentsChange();
    }

    private record StashedPrompt(
        String text,
        int cursorOffset,
        Map<Integer, PastedContent> pastedContents
    ) {}


    /**
     * Handle keys when suggestion panel is visible.
     * Returns non-null Result when the key was consumed for suggestion navigation.
     */
    private TextBox.Result handleSuggestionKey(KeyStroke key) {
        return switch (key.getKeyType()) {
            case ARROW_UP -> {
                suggestionPanel.moveUp();
                yield TextBox.Result.HANDLED;
            }
            case ARROW_DOWN -> {
                suggestionPanel.moveDown();
                yield TextBox.Result.HANDLED;
            }
            case ENTER -> {
                if (suggestionContext == SuggestionContext.BASH_PATH) {
                    hideSuggestions();

                    // Enter; only Tab/autocomplete:accept applies bash-path.
                    yield null;
                }
                SuggestionPanel.Suggestion s = suggestionPanel.acceptSelected();
                suggestionContext = SuggestionContext.NONE;
                if (s != null) fillFromSuggestion(s.primary());

                // executeOnReturn=true. The accepted command has already hidden
                // the panel, so route the same keystroke through the ordinary
                // submit path instead of requiring a second Enter.
                // Returning null lets PromptTextBox continue with the same
                // Enter into its ordinary submit stage. Non-command suggestions
                // remain accept-only.
                yield s != null && Strings.CS.startsWith(s.primary(), "/")
                    ? null
                    : TextBox.Result.HANDLED;
            }
            case TAB -> {
                acceptSelectedSuggestion();
                yield TextBox.Result.HANDLED;
            }
            case ESCAPE -> {
                hideSuggestions();
                yield TextBox.Result.HANDLED;
            }
            default -> null; // not consumed — fall through to normal handling
        };
    }

    private void acceptSelectedSuggestion() {
        SuggestionContext context = suggestionContext;
        SuggestionPanel.Suggestion suggestion = suggestionPanel.acceptSelected();
        suggestionContext = SuggestionContext.NONE;
        if (suggestion == null) return;
        if (context == SuggestionContext.BASH_PATH) {
            fillBashPathSuggestion(suggestion.primary());
        } else {
            fillFromSuggestion(suggestion.primary());
        }
    }

    /** Replace only the shell token immediately before the caret. */
    private void fillBashPathSuggestion(String replacementPath) {
        String text = textBox.getText();
        int cursor = Math.min(caretCol(), text.length());
        int tokenStart = text.substring(0, cursor).lastIndexOf(' ') + 1;
        boolean directory = Strings.CS.endsWith(replacementPath, "/");
        String replacement = replacementPath + (directory ? "" : " ");
        String updated = text.substring(0, tokenStart) + replacement + text.substring(cursor);
        textBox.setText(updated);
        TextBoxOffsetAdapter.setOffset(textBox, tokenStart + replacement.length());
        updateMode();
        hideSuggestions();
        fireQueryChange();
    }

    /**
     * Replace the input text with the chosen suggestion's primary text.
     */
    private void fillFromSuggestion(String primary) {
        // Strip "* " prefix used to mark skills in suggestion list
        String clean = Strings.CS.startsWith(primary, "* ") ? primary.substring(2) : primary;

        // ── Command suggestion: replace whole input ───────────────────────
        if (Strings.CS.startsWith(clean, "/")) {
            String filled = clean + " ";
            textBox.setText(filled);
            textBox.handleKeyStroke(new KeyStroke(KeyType.END, false, false));
            updateMode();
            hideSuggestions();
            fireQueryChange();
            return;
        }

        // ── File / directory suggestion: locate @token and replace it ─────.

        // AT_TOKEN_HEAD_RE = /^@[\p{L}\p{N}\p{M}_\-./\\[\]~:]*/u
        // PATH_CHAR_HEAD_RE = /^[\p{L}\p{N}\p{M}_\-./\\[\]~:]+/u

        // Steps:
        //  1. Find the last @ before cursor that is preceded by start or whitespace.
        //  2. Verify the text from @ to cursor matches the token head pattern.
        //  3. Extend the token to include any path characters AFTER the cursor.
        //  4. Build replacement: @suggestion (quoted if it has spaces) + trailing space.
        //  5. Replace [startPos .. startPos+tokenLen] in the full text.
        //  6. Reposition caret to end of replacement.

        String currentText = textBox.getText();
        int caretCol = caretCol();
        String beforeCursor = caretCol <= currentText.length()
            ? currentText.substring(0, caretCol) : currentText;
        String afterCursor = caretCol <= currentText.length()
            ? currentText.substring(caretCol) : "";

        // Find last @ preceded by start-of-string or whitespace
        int atIdx = -1;
        for (int i = beforeCursor.length() - 1; i >= 0; i--) {
            if (beforeCursor.charAt(i) == '@') {
                if (i == 0 || Character.isWhitespace(beforeCursor.charAt(i - 1))) {
                    atIdx = i;
                    break;
                }
            }
        }

        if (atIdx >= 0) {
            // Token head: everything from @ to cursor
            String tokenHead = beforeCursor.substring(atIdx); // includes '@'
            // Token tail: leading path chars after cursor (matches PATH_CHAR_HEAD_RE)
            Matcher tailM = Pattern
                .compile("^[\\w\\p{L}\\p{N}\\p{M}_\\-./\\\\()\\[\\]~:]+")
                .matcher(afterCursor);
            String tokenTail = tailM.find() ? tailM.group() : "";

            int tokenLen = tokenHead.length() + tokenTail.length();

            // Build replacement value (matches formatReplacementValue)
            boolean needsQuotes = Strings.CS.contains(clean, " ");
            String replacement = needsQuotes
                ? "@\"" + clean + "\" "
                : "@" + clean + " ";

            String newText = currentText.substring(0, atIdx)
                + replacement
                + currentText.substring(atIdx + tokenLen);
            int newCaret = atIdx + replacement.length();

            textBox.setText(newText);
            // Reposition caret: Home then ArrowRight × newCaret
            TextBoxOffsetAdapter.setOffset(textBox, newCaret);
        } else {
            // No @ token found — insert as plain text at end
            textBox.setText(clean + " ");
            textBox.handleKeyStroke(new KeyStroke(KeyType.END, false, false));
        }

        updateMode();
        hideSuggestions();
        fireQueryChange();
    }

    private void fireQueryChange() {

        // PromptInput useEffect([input, setPastedContents]).
        if (!pastedContent.isEmpty()) pruneOrphanedImages();
        if (actions == null) return;

        Consumer<Runnable> invoker = guiInvoker;
        if (invoker == null || (guiInputBatchDepth == 0 && isOnGuiThread())) {
            deliverQueryChange();
            return;
        }
        if (queryChangeScheduled) return;
        queryChangeScheduled = true;
        long generation = ++queryChangeGeneration;
        invoker.accept(() -> {
            if (!queryChangeScheduled || generation != queryChangeGeneration) return;
            queryChangeScheduled = false;
            deliverQueryChange();
        });
    }

    private void deliverQueryChangeImmediately() {
        queryChangeScheduled = false;
        queryChangeGeneration++;
        if (!pastedContent.isEmpty()) pruneOrphanedImages();
        deliverQueryChange();
    }

    private boolean isOnGuiThread() {
        return getTextGUI() != null
            && getTextGUI().getGUIThread().getThread() == Thread.currentThread();
    }

    private void deliverQueryChange() {
        InputActions currentActions = actions;
        if (currentActions != null) currentActions.queryChanged(currentText, caretCol());
    }

    // ── Vim key handling ─────────────────────────────────────────────────────

    private TextBox.Result handleVimKey(KeyStroke key) {
        VimMode vimMode = vim.getMode();

        // Sync vim's internal cursor & buffer with the textBox before
        // processing. The textBox can drift out of sync when readline
        // shortcuts (Ctrl+A, Ctrl+E, arrows in INSERT, paste chip insert)
        // move the caret without going through vim — without this sync the
        // next vim.processKey insertion would land at the stale cursor and
        // appear to overwrite a chip / earlier text.
        syncVimBuffer();
        try {
            int caretCol = caretCol();
            vim.setCursor(caretCol);
        } catch (Exception _) {}

        if (vimMode == VimMode.INSERT) {
            if (key.getKeyType() == KeyType.ARROW_LEFT || key.getKeyType() == KeyType.ARROW_RIGHT) {
                return dispatchToTextBox(key);
            }

            // to textInput.onInput → upOrHistoryUp / downOrHistoryDown).
            // Cannot use dispatchToTextBox here because it re-enters the override with
            // inVimKeyDispatch=true, which jumps straight to super.handleKeyStroke and
            // bypasses the history navigation code entirely.
            if (key.getKeyType() == KeyType.ARROW_UP
                    && !key.isShiftDown() && !key.isCtrlDown() && !key.isAltDown()) {
                return ((PromptTextBox) textBox).rl_historyUp();
            }
            if (key.getKeyType() == KeyType.ARROW_DOWN
                    && !key.isShiftDown() && !key.isCtrlDown() && !key.isAltDown()) {
                return ((PromptTextBox) textBox).rl_historyDown();
            }
            // Chip-aware Backspace/Delete: vim.processKey(127) would only
            // delete one char of "[Image #1]", leaving "[Image #1" garbage in
            // the buffer. Route to the same chipBackspace/chipDelete path
            // that the non-vim branch uses, then resync vim's buffer so its
            // internal state matches the textBox after the chip removal.
            if (key.getKeyType() == KeyType.BACKSPACE
                    && !key.isCtrlDown() && !key.isAltDown()) {
                if (chipBackspace()) {
                    pruneOrphanedPastedContents();
                    syncVimBuffer();
                    try { vim.setCursor(caretCol()); }
                    catch (Exception _) {}
                    return TextBox.Result.HANDLED;
                }
                return dispatchToTextBox(key);
            }
            if (key.getKeyType() == KeyType.DELETE
                    && !key.isCtrlDown() && !key.isAltDown()) {
                if (chipDelete()) {
                    pruneOrphanedPastedContents();
                    syncVimBuffer();
                    try { vim.setCursor(caretCol()); }
                    catch (Exception _) {}
                    return TextBox.Result.HANDLED;
                }
                return dispatchToTextBox(key);
            }
        }

        char c = keystrokeToChar(key, vimMode);
        if (c == 0) {
            if (vimMode == VimMode.INSERT) {
                TextBox.Result r = dispatchToTextBox(key);
                syncVimBuffer();
                return r;
            }
            return TextBox.Result.HANDLED;
        }

        if (c == '\n' || c == '\r') {
            // Enter in INSERT: submit. In NORMAL: no-op.
            if (vimMode == VimMode.INSERT) {
                String text = vim.getBuffer().trim();
                vim.reset();
                textBox.setText("");
                updateVimModeLabel();
                resetMode();
                if (!StringUtils.isBlank(text) && actions != null) {
                    actions.submit(prependModePrefix(text));
                }
            }
            return TextBox.Result.HANDLED;
        }

        if (c == 27) {
            if (vimMode == VimMode.INSERT) {
                vim.processKey(c);
                textBox.setText(vim.getBuffer());
                TextBoxOffsetAdapter.setOffset(textBox, vim.getCursor());
                updateVimModeLabel();
                return TextBox.Result.HANDLED;
            }
            if (actions != null) actions.cancel();
            return TextBox.Result.HANDLED;
        }

        vim.processKey(c);
        textBox.setText(vim.getBuffer());
        // Push vim's updated cursor back to the textBox so the caret stays
        // visually aligned. Without this, setText resets the textBox caret
        // and successive readline shortcuts work off a stale position.
        TextBoxOffsetAdapter.setOffset(textBox, vim.getCursor());
        updateMode();
        updateVimModeLabel();
        fireQueryChange();
        return TextBox.Result.HANDLED;
    }

    /**
     * Forward a keystroke to the underlying TextBox while bypassing the
     * subclass {@code handleKeyStroke} override that routes back through
     * vim. Sets {@link #inVimKeyDispatch} so the override knows to send the
     * key straight to Lanterna's parent {@code TextBox#handleKeyStroke}.
     * Must use try/finally to always clear the flag even if the inner call
     * throws.
     */
    private TextBox.Result dispatchToTextBox(KeyStroke key) {
        inVimKeyDispatch = true;
        try {
            return textBox.handleKeyStroke(key);
        } finally {
            inVimKeyDispatch = false;
        }
    }

    private void syncVimBuffer() {
        String boxText = textBox.getText();
        if (!boxText.equals(vim.getBuffer())) vim.setBuffer(boxText);
    }

    private static char keystrokeToChar(KeyStroke key, VimMode vimMode) {
        return switch (key.getKeyType()) {
            case CHARACTER   -> key.getCharacter();
            case BACKSPACE, DELETE -> (char) 127;
            case ESCAPE      -> (char) 27;
            case ENTER       -> '\n';
            case ARROW_LEFT   -> vimMode == VimMode.INSERT ? (char) 0 : 'h';
            case ARROW_RIGHT  -> vimMode == VimMode.INSERT ? (char) 0 : 'l';
            case ARROW_UP     -> vimMode == VimMode.INSERT ? (char) 0 : 'k';
            case ARROW_DOWN   -> vimMode == VimMode.INSERT ? (char) 0 : 'j';
            case HOME        -> vimMode == VimMode.INSERT ? (char) 0 : '0';
            case END         -> vimMode == VimMode.INSERT ? (char) 0 : '$';
            default          -> (char) 0;
        };
    }

    private void updateVimModeLabel() {
        if (!vimEnabled) {
            vimModeLabel.setText("");
            // Restore default cursor when vim is disabled
            if (actions != null) {
                actions.cursorStyleChanged(CursorStyle.DEFAULT);
            }
            return;
        }

        VimMode vm = vim.getMode();
        if (vm == VimMode.INSERT) {
            vimModeLabel.setText("  -- INSERT --");
            vimModeLabel.setForegroundColor(LanternaTheme.welcomeDim());
        } else {
            vimModeLabel.setText("");
        }
        // Sync cursor shape to Vim mode — DECSCUSR escape sequence emitted
        // by the Terminal's setCursorStyle default method.
        if (actions != null) {
            CursorStyle cs = switch (vm) {
                case INSERT -> CursorStyle.BLINKING_BAR;
                case NORMAL -> CursorStyle.STEADY_BLOCK;
            };
            actions.cursorStyleChanged(cs);
        }
    }

    /** Notify InputPanel when transcript mode is active (gates Ctrl+E routing). */
    public void setTranscriptMode(boolean active) {
        this.isTranscriptMode = active;
    }

    /** Set message actions active state (intercepts keys for navigation). */
    public void setMessageActionsActive(boolean active) {
        this.messageActionsActive = active;
        if (!active) messageActionsHint = "";
        updateHint();
    }

    /** Update the action list shown in the pinned footer while browsing messages. */
    public void setMessageActionsHint(String hint) {
        messageActionsHint = hint == null ? "" : hint;
        if (messageActionsActive) updateHint();
    }

    // ── Mode ─────────────────────────────────────────────────────────────────

    /**
     * Returns the text to pass to {@code actions.submit}, prepending the mode prefix when {@link
     * #modeOverride} is active (text shown in box has no prefix).
     */
    private String prependModePrefix(String text) {
        return InputModes.prependPrefix(text, modeOverride);
    }

    private void updateMode() {
        String text = vimEnabled ? vim.getBuffer() : currentText;
        if (modeOverride == null && mode == Mode.NORMAL
                && (text.isEmpty() || text.charAt(0) != '!')) {
            return;
        }
        Mode prefixMode = InputModes.fromPrefix(text);
        Mode newMode;
        if (modeOverride != null) {
            // modeOverride is set when a history entry had its prefix stripped.

            // until resetMode (Escape) or submit. The text in the box has no prefix.
            // Only clear override if user explicitly types a new mode prefix at position 0.
            if (prefixMode != Mode.NORMAL) {
                modeOverride = null;  // user typed "!" naturally → text-based detection takes over
                newMode = prefixMode;
            } else {
                newMode = modeOverride;  // keep override regardless of text content
            }
        } else {
            newMode = prefixMode;
        }
        if (newMode != mode) {
            mode = newMode;
            updatePromptColor();
            updateHint();
        }
    }

    private void resetMode() {
        mode = Mode.NORMAL;
        modeOverride = null;
        updatePromptColor();
        updateHint();
    }

    private void updatePromptColor() {

        //   - bash mode: <Text color="bashBorder" dimColor={isLoading}>! </Text>
        //   - else:      <Text color={undefined} dimColor={isLoading}>❯ </Text>

        // Notes on the alignment:

        //     prompt-input mode. The plan-state visual cue lives in the footer

        //     not in the ❯ pointer. We previously over-colored ❯ in PLAN.

        //     — NOT a dim grey. We pass null here so Lanterna emits no SGR for
        //     foreground, letting the terminal palette default through.
        //   • {@link #sessionColor} (the /color value) does NOT touch the ❯

        //     — see {@link #updateBorderColor}. Teammate-color tinting of ❯


        TextColor color;
        if (isLoading) {
            color = LanternaTheme.welcomeDim();  // dimColor=true wins
        } else if (mode == Mode.BASH) {
            color = LanternaTheme.bashBorder();
        } else {
            color = null;  // Use the terminal's default foreground.
        }
        promptLabel.setForegroundColor(color);
        // Update prompt symbol: bash uses "! ", others use "❯ "
        promptLabel.setText(mode == Mode.BASH ? "! " : Figures.POINTER + " ");
    }

    /**
     * Public shim over {@link #showTemporaryHint} for callers outside
     * InputPanel (REPL Ctrl+C handler etc.). Uses {@code welcomeDim} styling
     * to match the existing "Esc again to clear" / reverse-i-search hint look.
     */
    public void showTransientHint(String text, long timeoutMs) {
        showTemporaryHint(text, LanternaTheme.welcomeDim(), timeoutMs);
    }

    /**
     * Temporarily replaces the hint row with a notification for {@code timeoutMs}, then restores the
     * normal hint.
     */
    private void showTemporaryHint(String text, TextColor color, long timeoutMs) {
        // Cancel any existing hint timer
        if (hintTimer != null) { hintTimer.cancel(false); hintTimer = null; }
        setHintLabel(hintMainLabel, "  " + text);
        hintMainLabel.setForegroundColor(color);
        setHintLabel(hintSuffixLabel, "");
        hintTimer = ESC_SCHEDULER.schedule(() -> {
            hintTimer = null;
            updateHint();
        }, timeoutMs, TimeUnit.MILLISECONDS);
    }




    private TextBox.Result historyDownOrEnterFooter() {
        if (historyController.atBottom()) {
            // The ≡ projects button is the leftmost footer control, so the
            // first ↓ from the prompt selects it; the next ↓/→ resumes the
            // released chain via selectFirstFooterStopAfterProjectsButton().
            selectProjectsButton();
            return TextBox.Result.HANDLED;
        }
        return historyController.down();
    }

    /**
     * The released 197 footer entry chain, reached when advancing past the ≡
     * button: coordinator panel → workflow footer → tasks pill → Collaboration.
     */
    private void selectFirstFooterStopAfterProjectsButton() {
        projectsButtonSelected = false;
        if (coordinatorNavigation != null && coordinatorNavigation.panelAvailable()) {
            workflowFooterSelected = false;
            collaborationPillSelected = false;
            boolean hasBackgroundPill = taskNavigation.pillAvailable();
            coordinatorNavigation.selectPanel(hasBackgroundPill);
            if (hasBackgroundPill) taskNavigation.selectPill();
            else taskNavigation.deselectPill();
            refreshCoordinatorPanel();
            refreshFooterPills();
            return;
        }
        if (!visibleWorkflowRuns().isEmpty() && !taskNavigation.pillAvailable()) {
            collaborationPillSelected = false;
            selectCurrentWorkflowFooter();
            return;
        }
        collaborationPillSelected = !taskNavigation.pillAvailable();
        if (!collaborationPillSelected) taskNavigation.selectPill();
        refreshFooterPills();
    }

    /** Selects the ≡ button as the sole footer selection. */
    private void selectProjectsButton() {
        workflowFooterSelected = false;
        selectedWorkflowTaskId = null;
        collaborationPillSelected = false;
        taskNavigation.deselectPill();
        if (coordinatorNavigation != null) coordinatorNavigation.deselectPanel();
        projectsButtonSelected = true;
        refreshCoordinatorPanel();
        refreshFooterPills();
        updateHint();
    }

    /** Mirrors the project drawer's open state on the ≡ button. */
    public synchronized void setProjectsButtonActive(boolean active) {
        if (projectsButtonActive == active) return;
        projectsButtonActive = active;
        refreshFooterPills();
    }

    /** Whether the subagent coordinator panel currently owns keyboard focus.
     *  Package-private for {@code InputPanelTasksPillTest} (was exposed via
     *  {@code isCoordinatorPanelSelectedForTest()}). */
    boolean isCoordinatorPanelSelected() {
        return coordinatorNavigation != null && coordinatorNavigation.isPanelSelected();
    }


    private TextBox.Result handleWorkflowFooterKey(KeyStroke key) {
        List<WorkflowRun> workflows = visibleWorkflowRuns();
        if (workflows.isEmpty()) {
            workflowFooterSelected = false;
            refreshCoordinatorPanel();
            return null;
        }
        workflowFooterIndex = Math.max(0, Math.min(workflowFooterIndex, workflows.size() - 1));
        KeyType type = key.getKeyType();
        boolean plain = !key.isCtrlDown() && !key.isAltDown() && !key.isShiftDown();
        if (type == KeyType.ARROW_UP && plain) {
            if (workflowFooterIndex > 0) {
                workflowFooterIndex--;
                selectedWorkflowTaskId = workflows.get(workflowFooterIndex).taskId();
            } else {
                selectFooterBeforeWorkflows(true);
                return TextBox.Result.HANDLED;
            }
            refreshCoordinatorPanel();
            refreshFooterPills();
            updateHint();
            return TextBox.Result.HANDLED;
        }
        if (type == KeyType.ARROW_DOWN && plain) {
            if (workflowFooterIndex < workflows.size() - 1) {
                workflowFooterIndex++;
                selectedWorkflowTaskId = workflows.get(workflowFooterIndex).taskId();
                refreshCoordinatorPanel();
                updateHint();
            } else {
                selectCollaborationFooter();
            }
            return TextBox.Result.HANDLED;
        }
        if (type == KeyType.ARROW_RIGHT && plain) {
            selectCollaborationFooter();
            return TextBox.Result.HANDLED;
        }
        if (type == KeyType.ARROW_LEFT && plain) {
            selectFooterBeforeWorkflows(false);
            return TextBox.Result.HANDLED;
        }
        if (type == KeyType.ENTER && !key.isShiftDown()) {
            WorkflowRun run = workflows.get(workflowFooterIndex);
            workflowFooterSelected = false;
            selectedWorkflowTaskId = null;
            refreshCoordinatorPanel();
            if (actions != null) actions.openWorkflowDialog(run.taskId());
            return TextBox.Result.HANDLED;
        }
        if (type == KeyType.ESCAPE) {
            workflowFooterSelected = false;
            selectedWorkflowTaskId = null;
            refreshCoordinatorPanel();
            updateHint();
            return TextBox.Result.HANDLED;
        }
        if (type == KeyType.CHARACTER && key.getCharacter() != null
                && plain && key.getCharacter() == 'x') {
            WorkflowRun run = workflows.get(workflowFooterIndex);
            if (taskRegistry != null) {
                if (run.status().hasResult()) taskRegistry.dismissWorkflow(run.taskId());
                else taskRegistry.killWorkflow(run.taskId());
            }
            clampWorkflowFooterSelection();
            refreshCoordinatorPanel();
            refreshFooterPills();
            updateHint();
            return TextBox.Result.HANDLED;
        }
        if (type == KeyType.CHARACTER && key.getCharacter() != null
                && !key.isCtrlDown() && !key.isAltDown()) {
            return TextBox.Result.HANDLED;
        }
        return null;
    }

    private void selectWorkflowFooter(int index) {
        List<WorkflowRun> workflows = visibleWorkflowRuns();
        if (workflows.isEmpty()) return;
        workflowFooterSelected = true;
        workflowFooterIndex = Math.max(0, Math.min(index, workflows.size() - 1));
        selectedWorkflowTaskId = workflows.get(workflowFooterIndex).taskId();
        taskNavigation.deselectPill();
        collaborationPillSelected = false;
        if (coordinatorNavigation != null) coordinatorNavigation.deselectPanel();
        refreshCoordinatorPanel();
        refreshFooterPills();
        updateHint();
    }

    private void selectCurrentWorkflowFooter() {
        List<WorkflowRun> workflows = visibleWorkflowRuns();
        if (workflows.isEmpty()) return;
        selectWorkflowFooter(Math.min(workflowFooterIndex, workflows.size() - 1));
    }

    /** Selects the permanent footer item after tasks/coordinator/workflows. */
    private void selectCollaborationFooter() {
        workflowFooterSelected = false;
        taskNavigation.deselectPill();
        if (coordinatorNavigation != null) coordinatorNavigation.deselectPanel();
        collaborationPillSelected = true;
        refreshCoordinatorPanel();
        refreshFooterPills();
        updateHint();
    }

/**
     * Moves from Collaboration to the preceding.
     */
    private boolean selectFooterBeforeCollaboration() {
        List<WorkflowRun> workflows = visibleWorkflowRuns();
        if (!workflows.isEmpty()) {
            selectWorkflowFooter(Math.min(workflowFooterIndex, workflows.size() - 1));
            return true;
        }
        CoordinatorNavigationController nav = coordinatorNavigation;
        if (nav != null && nav.panelAvailable()) {
            collaborationPillSelected = false;
            boolean hasBackgroundPill = taskNavigation.pillAvailable();
            nav.selectPanel(hasBackgroundPill);
            if (hasBackgroundPill) taskNavigation.selectPill();
            else taskNavigation.deselectPill();
            refreshCoordinatorPanel();
            refreshFooterPills();
            updateHint();
            return true;
        }
        if (taskNavigation.pillAvailable()) {
            collaborationPillSelected = false;
            taskNavigation.selectPill();
            refreshFooterPills();
            updateHint();
            return true;
        }
        return false;
    }

    /** Moves from workflows to the preceding tasks group, if one is visible. */
    private boolean selectFooterBeforeWorkflows(boolean exitAtStart) {
        CoordinatorNavigationController nav = coordinatorNavigation;
        if (nav != null && nav.panelAvailable()) {
            workflowFooterSelected = false;
            selectedWorkflowTaskId = null;
            boolean hasBackgroundPill = taskNavigation.pillAvailable();
            nav.selectPanel(hasBackgroundPill);
            if (hasBackgroundPill) taskNavigation.selectPill();
            else taskNavigation.deselectPill();
            refreshCoordinatorPanel();
            refreshFooterPills();
            updateHint();
            return true;
        }
        if (taskNavigation.pillAvailable()) {
            workflowFooterSelected = false;
            selectedWorkflowTaskId = null;
            taskNavigation.selectPill();
            refreshCoordinatorPanel();
            refreshFooterPills();
            updateHint();
            return true;
        }
        if (exitAtStart) {
            workflowFooterSelected = false;
            selectedWorkflowTaskId = null;
            refreshCoordinatorPanel();
            refreshFooterPills();
            updateHint();
            return true;
        }
        return false;
    }

    private void clampWorkflowFooterSelection() {
        List<WorkflowRun> workflows = visibleWorkflowRuns();
        int count = workflows.size();
        if (count == 0) {
            workflowFooterSelected = false;
            workflowFooterIndex = 0;
            selectedWorkflowTaskId = null;
        } else {
            int retained = selectedWorkflowTaskId == null ? -1
                : IntStream.range(0, workflows.size())
                    .filter(index -> selectedWorkflowTaskId.equals(workflows.get(index).taskId()))
                    .findFirst().orElse(-1);
            workflowFooterIndex = retained >= 0 ? retained
                : Math.min(workflowFooterIndex, count - 1);
            if (workflowFooterSelected) {
                selectedWorkflowTaskId = workflows.get(workflowFooterIndex).taskId();
            }
        }
    }

    private List<WorkflowRun> visibleWorkflowRuns() {
        if (workflowRuns == null || taskRegistry == null) return List.of();
        var byTaskId = workflowRuns.list().stream()
            .collect(Collectors.toMap(WorkflowRun::taskId,
                Function.identity(), (left, _) -> left));
        return taskRegistry.listPanelWorkflowTasks(Instant.now()).stream()
            .map(task -> byTaskId.get(task.id()))
            .filter(Objects::nonNull)
            .toList();
    }

    /**
 * Footer-context key routing while the subagent coordinator panel owns focus.
     */
    private TextBox.Result handleCoordinatorPanelKey(KeyStroke key) {
        CoordinatorNavigationController nav = coordinatorNavigation;
        KeyType type = key.getKeyType();
        boolean plain = !key.isCtrlDown() && !key.isAltDown() && !key.isShiftDown();
        if (type == KeyType.ARROW_UP && plain) {
            int minimum = taskNavigation.pillAvailable() ? -1 : 0;
            if (nav.coordinatorIndex() > minimum) {
                nav.step(-1, coordinatorNavigationHost);
                if (nav.coordinatorIndex() < 0) taskNavigation.selectPill();
                else taskNavigation.deselectPill();
            } else {
                nav.deselectPanel();
                taskNavigation.deselectPill();
                updateHint();
            }
            refreshCoordinatorPanel();
            refreshFooterPills();
            return TextBox.Result.HANDLED;
        }
        if (type == KeyType.ARROW_DOWN && plain) {
            if (nav.coordinatorIndex() >= nav.panelAgents().size()) {
                if (!visibleWorkflowRuns().isEmpty()) selectCurrentWorkflowFooter();
                else selectCollaborationFooter();
                return TextBox.Result.HANDLED;
            }
            nav.step(1, coordinatorNavigationHost);
            if (nav.coordinatorIndex() >= 0) taskNavigation.deselectPill();
            refreshCoordinatorPanel();
            refreshFooterPills();
            return TextBox.Result.HANDLED;
        }
        if (type == KeyType.ARROW_RIGHT && plain) {
            if (!visibleWorkflowRuns().isEmpty()) selectCurrentWorkflowFooter();
            else selectCollaborationFooter();
            return TextBox.Result.HANDLED;
        }
        if (type == KeyType.ARROW_LEFT && plain) {
            return TextBox.Result.HANDLED;
        }
        if (type == KeyType.ENTER && !key.isShiftDown()) {
            if (nav.coordinatorIndex() < 0) {
                nav.deselectPanel();
                taskNavigation.handlePillAction("footer:openSelected", taskNavigationHost);
            } else {
                nav.openSelected(coordinatorNavigationHost);
            }
            refreshCoordinatorPanel();
            refreshFooterPills();
            return TextBox.Result.HANDLED;
        }
        if (type == KeyType.ESCAPE) {
            if (nav.handleEscape(coordinatorNavigationHost)) {
                taskNavigation.deselectPill();
                refreshCoordinatorPanel();
                refreshFooterPills();
                return TextBox.Result.HANDLED;
            }
            return null;
        }
        if (type == KeyType.CHARACTER && key.getCharacter() != null
                && plain && key.getCharacter() == 'x') {
            if (nav.coordinatorIndex() > 0 && !nav.isViewingSelectedAgent()) {
                nav.dismissSelected(coordinatorNavigationHost);
                refreshCoordinatorPanel();
                refreshFooterPills();
                return TextBox.Result.HANDLED;
            }
            if (nav.coordinatorIndex() <= 0) {
                return TextBox.Result.HANDLED;
            }
            clearFooterSelection();
            return null;
        }
        if (type == KeyType.CHARACTER && key.getCharacter() != null
                && !key.isCtrlDown() && !key.isAltDown()) {
            if (nav.isViewingSelectedAgent()) {
                clearFooterSelection();
                return null;
            }
            return TextBox.Result.HANDLED;
        }
        // Ctrl/Alt combos fall through to global handling.
        return null;
    }

    private TextBox.Result handleCollaborationPillKey(KeyStroke key) {
        KeyType type = key.getKeyType();
        if (type == KeyType.ARROW_UP) {
            if (!selectFooterBeforeCollaboration()) {
                collaborationPillSelected = false;
                refreshFooterPills();
            }
            return TextBox.Result.HANDLED;
        }
        if (type == KeyType.ESCAPE) {
            collaborationPillSelected = false;
            refreshFooterPills();
            return TextBox.Result.HANDLED;
        }
        if (type == KeyType.ARROW_LEFT) {
            selectFooterBeforeCollaboration();
            return TextBox.Result.HANDLED;
        }
        if (type == KeyType.ARROW_DOWN) {
            return TextBox.Result.HANDLED;
        }
        if (type == KeyType.ENTER) {
            collaborationPillSelected = false;
            refreshFooterPills();
            if (actions != null) actions.openCollaborationPicker();
            return TextBox.Result.HANDLED;
        }
        if (type == KeyType.ARROW_RIGHT) {
            return TextBox.Result.HANDLED;
        }
        if (type == KeyType.CHARACTER && key.isCtrlDown()
                && key.getCharacter() != null) {
            char ch = Character.toLowerCase(key.getCharacter());
            if (ch == 'p') {
                if (!selectFooterBeforeCollaboration()) {
                    collaborationPillSelected = false;
                    refreshFooterPills();
                }
                return TextBox.Result.HANDLED;
            }
            if (ch == 'n') {
                return TextBox.Result.HANDLED;
            }
            return null;
        }
        if (key.isCtrlDown() || key.isAltDown()) return null;
        return TextBox.Result.HANDLED;
    }

    private synchronized void refreshFooterPills() {
        refreshProjectsButton();
        refreshTasksPill();
        String value = collaborationController == null
            ? "Off" : collaborationController.current().displayValue();
        setLabelTextIfChanged(collaborationPillLabel,
            "Collaboration: " + value);
        if (collaborationPillSelected) collaborationPillLabel.addStyle(SGR.REVERSE);
        else collaborationPillLabel.removeStyle(SGR.REVERSE);
    }

    /** ≡ reflects three states: keyboard-selected (REVERSE), drawer-open (accent), idle (dim). */
    private void refreshProjectsButton() {
        projectsButtonLabel.setForegroundColor(projectsButtonActive
            ? LanternaTheme.suggestion() : LanternaTheme.welcomeDim());
        if (projectsButtonSelected || projectsButtonMouseHovered) {
            projectsButtonLabel.addStyle(SGR.REVERSE);
        } else {
            projectsButtonLabel.removeStyle(SGR.REVERSE);
        }
    }

    /**
     * Recomputes the pill + its trailing hint from the live registry.
     */
    synchronized void refreshTasksPill() {
        taskNavigation.synchronizeTeammateCount();
        if (coordinatorNavigation != null) {
            boolean coordinatorWasSelected = coordinatorNavigation.isPanelSelected();
            coordinatorNavigation.synchronizeBackgroundPill(taskNavigation.pillAvailable());
            if (coordinatorWasSelected
                    && !coordinatorNavigation.isPanelSelected()
                    && taskNavigation.pillAvailable()) {
                taskNavigation.selectPill();
            }
        }
        if (taskNavigation.isTeammateFooterVisible()) {
            renderTeammateFooter();
            setLabelTextIfChanged(tasksHintLabel, "");
            return;
        }
        tasksPillsPanel.removeAllComponents();
        tasksPillsPanel.addComponent(tasksPillLabel);
        PromptTaskNavigationController.PillView pill = taskNavigation.pillView();
        if (pill.label().isEmpty()) {
            tasksPillLabel.removeStyle(SGR.REVERSE);
            setLabelTextIfChanged(tasksPillLabel, "");
            setLabelTextIfChanged(tasksHintLabel, "");
            return;
        }
        tasksPillLabel.setForegroundColor(LanternaTheme.welcomeDim());
        if (pill.selected() || tasksPillMouseHovered) tasksPillLabel.addStyle(SGR.REVERSE);
        else tasksPillLabel.removeStyle(SGR.REVERSE);
        setLabelTextIfChanged(tasksPillLabel, pill.label());
        tasksHintLabel.setForegroundColor(LanternaTheme.welcomeDim());
        setLabelTextIfChanged(tasksHintLabel, pill.hint());
    }


    public boolean handleTasksPillMouse(MouseAction mouse) {
        return handleTasksPillMouseForTest(mouse, tasksPillsPanel.getGlobalPosition(),
            tasksPillsPanel.getSize());
    }

    /**
     * Click handling for the footer ≡ button — same press/release latch as the
     * tasks pill: press inside arms it, release inside activates, release
     * outside cancels. Java-side extension, no 197 counterpart.
     */
    public boolean handleProjectsButtonMouse(MouseAction mouse) {
        return handleProjectsButtonMouseForTest(mouse, projectsButtonLabel.getGlobalPosition(),
            projectsButtonLabel.getSize());
    }

    boolean handleProjectsButtonMouseForTest(MouseAction mouse, TerminalPosition origin,
                                             TerminalSize size) {
        if (mouse == null || origin == null || size == null) {
            projectsButtonMousePressed = false;
            projectsButtonMouseHovered = false;
            return false;
        }
        TerminalPosition point = mouse.getPosition();
        boolean inside = point.getColumn() >= origin.getColumn()
            && point.getColumn() < origin.getColumn() + size.getColumns()
            && point.getRow() >= origin.getRow()
            && point.getRow() < origin.getRow() + size.getRows();
        return switch (mouse.getActionType()) {
            case MOVE -> {
                if (projectsButtonMouseHovered != inside) {
                    projectsButtonMouseHovered = inside;
                    refreshProjectsButton();
                }
                yield inside;
            }
            case CLICK_DOWN -> {
                if (mouse.getButton() != 1) yield false;
                projectsButtonMousePressed = inside;
                yield inside;
            }
            case DRAG -> projectsButtonMousePressed;
            case CLICK_RELEASE -> {
                if (mouse.getButton() != 1) yield false;
                boolean activate = projectsButtonMousePressed && inside;
                boolean consume = projectsButtonMousePressed;
                projectsButtonMousePressed = false;
                if (activate) {
                    projectsButtonSelected = false;
                    refreshFooterPills();
                    updateHint();
                    if (actions != null) actions.toggleProjectPanel();
                }
                yield consume;
            }
            default -> false;
        };
    }

    boolean isProjectsButtonSelectedForTest() { return projectsButtonSelected; }
    boolean isProjectsButtonActiveForTest() { return projectsButtonActive; }

    /**
     * Handles the established prompt wrapper's bare-click cursor positioning.
     * Coordinates are absolute terminal cells and the hit target begins at the
     * text box, deliberately excluding the prompt glyph and layout gap.
     */
    public boolean handlePromptBareClick(int screenColumn, int screenRow) {
        return handlePromptBareClickForTest(screenColumn, screenRow,
            textBox.getGlobalPosition(), textBox.getSize());
    }

    boolean handlePromptBareClickForTest(int screenColumn, int screenRow,
                                         TerminalPosition origin, TerminalSize size) {
        if (origin == null || size == null
                || screenColumn < origin.getColumn()
                || screenColumn >= origin.getColumn() + size.getColumns()
                || screenRow < origin.getRow()
                || screenRow >= origin.getRow() + size.getRows()) {
            return false;
        }


        if (historyController.isSearching()) return true;

        clearFooterSelection();
        textBox.takeFocus();
        if (currentText.isEmpty()) return true;

        int localColumn = screenColumn - origin.getColumn();
        int localRow = screenRow - origin.getRow();
        int offset = textLayout.offsetAt(new PromptTextLayout.Position(localRow, localColumn));
        TextBoxOffsetAdapter.setOffset(textBox, offset);
        textBox.invalidate();
        fireQueryChange();
        return true;
    }

    private void clearFooterSelection() {
        boolean footerSelected = taskNavigation.isPillSelected()
            || isCoordinatorPanelSelected()
            || workflowFooterSelected
            || collaborationPillSelected
            || projectsButtonSelected;
        if (!footerSelected) return;
        taskNavigation.deselectPill();
        taskNavigationHost.setTeammateTreeExpanded(false);
        if (coordinatorNavigation != null) coordinatorNavigation.deselectPanel();
        workflowFooterSelected = false;
        selectedWorkflowTaskId = null;
        collaborationPillSelected = false;
        projectsButtonSelected = false;
        refreshCoordinatorPanel();
        refreshFooterPills();
        updateHint();
    }

    boolean handleTasksPillMouseForTest(MouseAction mouse, TerminalPosition origin,
                                        TerminalSize size) {
        if (mouse == null || origin == null || size == null
                || !taskNavigation.pillAvailable()
                || taskNavigation.isTeammateFooterVisible()) {
            tasksPillMousePressed = false;
            tasksPillMouseHovered = false;
            return false;
        }
        TerminalPosition point = mouse.getPosition();
        boolean inside = point.getColumn() >= origin.getColumn()
            && point.getColumn() < origin.getColumn() + size.getColumns()
            && point.getRow() >= origin.getRow()
            && point.getRow() < origin.getRow() + size.getRows();
        return switch (mouse.getActionType()) {
            case MOVE -> {
                if (tasksPillMouseHovered != inside) {
                    tasksPillMouseHovered = inside;
                    refreshTasksPill();
                }
                yield inside;
            }
            case CLICK_DOWN -> {
                if (mouse.getButton() != 1) yield false;
                tasksPillMousePressed = inside;
                yield inside;
            }
            case DRAG -> tasksPillMousePressed;
            case CLICK_RELEASE -> {
                if (mouse.getButton() != 1) yield false;
                boolean activate = tasksPillMousePressed && inside;
                boolean consume = tasksPillMousePressed;
                tasksPillMousePressed = false;
                if (activate) {
                    taskNavigation.deselectPill();
                    if (actions != null) actions.openTasksDialog();
                    refreshTasksPill();
                }
                yield consume;
            }
            default -> false;
        };
    }

    /** Renders the original multi-agent footer projection using the shared width window. */
    private void renderTeammateFooter() {
        PromptTaskNavigationController.TeammateFooterView footer =
            taskNavigation.teammateFooterView(Math.max(20, lastDividerWidth - 24));
        tasksPillsPanel.removeAllComponents();
        if (footer.showLeftArrow()) {
            Label left = new Label("← ");
            left.setForegroundColor(LanternaTheme.welcomeDim());
            tasksPillsPanel.addComponent(left);
        }
        StringBuilder plain = new StringBuilder();
        for (int i = 0; i < footer.visiblePills().size(); i++) {
            PromptTaskNavigationController.TeammatePillView pill = footer.visiblePills().get(i);
            if (i > 0) {
                tasksPillsPanel.addComponent(new Label(" "));
                plain.append(' ');
            }
            String text = "@" + pill.name();
            Label label = new Label(text);
            label.setForegroundColor(pill.idle()
                ? LanternaTheme.welcomeDim() : LanternaTheme.inputText());
            if (pill.viewed()) label.addStyle(SGR.BOLD);
            if (pill.selected()) label.addStyle(SGR.REVERSE);
            tasksPillsPanel.addComponent(label);
            plain.append(text);
        }
        if (footer.showRightArrow()) {
            Label right = new Label(" →");
            right.setForegroundColor(LanternaTheme.welcomeDim());
            tasksPillsPanel.addComponent(right);
        }
        Label expandHint = new Label(" · shift + ↓ expand");
        expandHint.setForegroundColor(LanternaTheme.welcomeDim());
        tasksPillsPanel.addComponent(expandHint);
        plain.append(" · shift + ↓ expand");
        // Keep the existing package-private test seam meaningful for both footer shapes.
        setLabelTextIfChanged(tasksPillLabel, plain.toString());
    }

    private static void setLabelTextIfChanged(Label label, String text) {
        if (!text.equals(label.getText())) {
            label.setText(text);
        }
    }

    /**
     * The pill must also refresh with no user input (a background task
     * finishing while the user idles has to clear the pill), so a 1s tick
     * re-reads the registry — same cadence precedent as
     * {@code BackgroundTasksDialog}'s refresh timer. Scoped to the attached
     * lifetime so test-constructed panels never leak scheduled tasks; label
     * mutation is change-gated (see {@link #refreshTasksPill}) so idle ticks
     * don't trigger redraws. Runs on {@code ESC_SCHEDULER}, which already
     * mutates these hint labels from its thread (hint-restore timers).
     */
    @Override
    public synchronized void onAdded(Container container) {
        super.onAdded(container);
        startTaskPillRefresh();
    }

    /**
     * Starts the live task-footer refresh after the REPL scene is attached.
     *
     * <p><b>Locking contract for {@link #taskPillTick} and everything it calls.</b>
     * The tick runs on {@code ESC_SCHEDULER}, not the GUI thread, and
     * {@link #refreshTasksPill} is {@code synchronized} — so the tick holds this
     * panel's own monitor, the same one Lanterna's {@code AbstractComponent}
     * uses for {@code draw()} / {@code calculatePreferredSize()}. The GUI thread
     * acquires monitors strictly top-down (TextGUI → window → container → leaf),
     * so the tick body must only ever descend: mutate this panel's own children
     * ({@code Label.setText}, colors, {@code Panel.addComponent}) and stop there.
     *
     * <p>It must never reach <i>upward</i> — no {@code getTheme()},
     * {@code getRenderer()}, {@code getThemeDefinition()}, {@code getPreferredSize()}
     * or {@code draw()} on this panel or an ancestor, because those recurse up the
     * parent chain and invert the GUI thread's order, deadlocking the whole TUI
     * silently. For the same reason the body must not block (the GUI thread stalls
     * behind this monitor for the duration): {@code TaskRegistry} reads are
     * lock-free by design, keep them that way.
     */
    public synchronized void startTaskPillRefresh() {
        if (pillRefreshFuture == null) {
            pillRefreshFuture = ESC_SCHEDULER.scheduleWithFixedDelay(
                this::taskPillTick, 1, 1, TimeUnit.SECONDS);
        }
    }

    /**
     * One periodic tick: advance the subagent coordinator lifecycle (auto-exit +
     * 30 s grace eviction), repaint its panel, then refresh the teammate/tasks
     * footer. The coordinator and teammate subsystems are stepped independently.
     */
    private void taskPillTick() {
        if (coordinatorNavigation != null) {
            coordinatorNavigation.tick(coordinatorNavigationHost);
            refreshCoordinatorPanel();
        }
        if (workflowFooterSelected) updateHint();
        refreshTasksPill();
    }

    @Override
    public synchronized void onRemoved(Container container) {
        if (pillRefreshFuture != null) {
            pillRefreshFuture.cancel(false);
            pillRefreshFuture = null;
        }
        super.onRemoved(container);
    }

    // Test hooks — package-private, no GUI thread needed.
    TextBox.Result handleKeyForTest(KeyStroke key) { return textBox.handleKeyStroke(key); }
    void setCaretOffsetForTest(int offset) { TextBoxOffsetAdapter.setOffset(textBox, offset); }
    String queuedPreviewTextForTest() { return String.join("\n", queuedPreviewLines); }
    boolean isTasksPillSelected() { return taskNavigation.isPillSelected(); }
    boolean isWorkflowFooterSelectedForTest() { return workflowFooterSelected; }
    int workflowFooterIndexForTest() { return workflowFooterIndex; }
    String selectedWorkflowTaskIdForTest() { return selectedWorkflowTaskId; }
    boolean isCollaborationPillSelected() { return collaborationPillSelected; }
    int hintRowVisualIndexForTest() { return getChildrenList().indexOf(hintRow); }
    int collaborationRowVisualIndexForTest() {
        return getChildrenList().indexOf(collaborationRow);
    }
    int coordinatorIndexForTest() {
        return coordinatorNavigation == null ? Integer.MIN_VALUE
            : coordinatorNavigation.coordinatorIndex();
    }
    String collaborationPillTextForTest() {
        return collaborationPillLabel.getText();
    }
    String tasksPillTextForTest() { return tasksPillLabel.getText(); }
    boolean isTasksPillHoveredForTest() { return tasksPillMouseHovered; }
    String tasksHintTextForTest() { return tasksHintLabel.getText(); }
    String hintTextForTest() { return hintMainLabel.getText(); }
    String leaderHintTextForTest() {
        String main = hintMainLabel.getText();
        String suffix = hintSuffixLabel.getText();
        if (!main.isEmpty() && !suffix.isEmpty()) return main + " " + suffix;
        return main + suffix;
    }
    void setLeftArrowOpensAgentsForTest(BooleanSupplier enabled) {
        leftArrowOpensAgents = enabled != null ? enabled : () -> true;
        updateHint();
    }
    boolean isHistorySearchingForTest() { return historyController.isSearching(); }
    String historySearchDraftForTest() { return historyController.searchDraftForTest(); }
    boolean isPastingForTest() { return pendingPastes.get() > 0; }
    void completePasteForTest(String insertedText) {
        completePasteOnGui(insertedText == null ? null : () -> insertChipAtCursor(insertedText));
    }
    int textRowsForTest() { return currentTextRows; }
    PromptTextLayout.Position visualCaretPositionForTest() {
        return textLayout.positionAt(caretCol());
    }
    Mode modeForTest() { return mode; }
    boolean plainCharacterFastPathForTest(KeyStroke key) {
        return ((PromptTextBox) textBox).canUsePlainCharacterFastPath(key);
    }
    public void setTaskRegistry(TaskRegistry registry) {
        this.taskRegistry = registry;
        taskNavigation.setRegistry(registry);
        if (coordinatorNavigation != null) coordinatorNavigation.setRegistry(registry);
        refreshFooterPills();
        refreshCoordinatorPanel();
    }

    public void setTeammateTreeExpanded(boolean expanded) {
        taskNavigation.setTeammateTreeExpanded(expanded);
        updateHint();
        invalidate();
    }


    public void setWorkflowRunStore(WorkflowRunStore workflowRuns) {
        this.workflowRuns = workflowRuns;
        refreshFooterPills();
        refreshCoordinatorPanel();
    }

    /**
     * Binds the subagent coordinator panel — its navigation state machine plus
     * the view it renders into. The two are wired together here so a single tick
     * can advance the model and repaint the panel. Independent of the teammate
     * footer; the controller owns the shared vertical selection while the
     * existing task controller still supplies the background-pill projection.
     */
    public void setCoordinatorNavigation(CoordinatorNavigationController navigation,
                                         CoordinatorPanelView panel,
                                         Function<String, String> agentNameResolver) {
        this.coordinatorNavigation = navigation;
        this.coordinatorPanel = panel;
        if (coordinatorPanelComponent != null) {
            removeComponent(coordinatorPanelComponent);
            coordinatorPanelComponent = null;
        }
        if (panel instanceof Component component) {
            removeComponent(collaborationRow);
            addComponent(component,
                LinearLayout.createLayoutData(LinearLayout.Alignment.FILL));
            addComponent(collaborationRow);
            coordinatorPanelComponent = component;
        }
        this.coordinatorNameResolver =
            agentNameResolver != null ? agentNameResolver : _ -> null;
        refreshCoordinatorPanel();
    }

    /**
     * Rebuilds the coordinator panel snapshot from the live navigation state:
     * the visible panel agents, the selection (only when the panel owns focus),
     * and which subagent transcript is being viewed. A no-op when the panel is
     * not wired. Runs the projection on whatever thread the tick uses; the panel
     * view is thread-safe.
     */
    void refreshCoordinatorPanel() {
        CoordinatorNavigationController nav = coordinatorNavigation;
        CoordinatorPanelView panel = coordinatorPanel;
        if (nav == null || panel == null) return;
        List<TaskState> agents = nav.panelAgents();
        int selectedIndex = nav.isPanelSelected() ? nav.coordinatorIndex() : -1;
        String viewingTaskId = nav.isViewingLocalAgent()
            ? ViewedTeammateHolder.instance().viewingTaskId() : null;
        List<WorkflowRun> workflows = visibleWorkflowRuns();
        clampWorkflowFooterSelection();
        int selectedWorkflowIndex = workflowFooterSelected ? workflowFooterIndex : -1;
        panel.refresh(agents, workflows, selectedIndex, selectedWorkflowIndex, viewingTaskId,
            Instant.now(), coordinatorNameResolver,
            taskRegistry == null ? _ -> 0 : taskRegistry::pendingAgentMessageCount);
    }

    /**
     * Binds the footer to the shared collaboration state. Session Link can
     * change that state from a virtual thread, so the listener projects the
     * new value through the configured GUI invoker before touching Lanterna.
     * Test-constructed panels have no invoker and refresh synchronously.
     */
    @Explanation("Live projection of Session Link collaboration state")
    public synchronized void setCollaborationController(
            SessionCollaborationController controller) {
        closeCollaborationSubscription();
        this.collaborationController = controller;
        if (controller != null) {
            collaborationSubscription = controller.subscribe(
                _ -> scheduleCollaborationRefresh(controller));
        }
        refreshFooterPills();
    }

    /** Releases the controller listener when the REPL is shutting down. */
    public synchronized void closeCollaborationBinding() {
        closeCollaborationSubscription();
        collaborationController = null;
    }

    private void scheduleCollaborationRefresh(
            SessionCollaborationController expectedController) {
        Runnable refresh = () -> {
            if (collaborationController == expectedController) refreshFooterPills();
        };
        Consumer<Runnable> invoker = guiInvoker;
        if (invoker == null || isOnGuiThread()) refresh.run();
        else invoker.accept(refresh);
    }

    private void closeCollaborationSubscription() {
        AutoCloseable subscription = collaborationSubscription;
        collaborationSubscription = null;
        if (subscription == null) return;
        try { subscription.close(); }
        catch (Exception failure) {
            log.debug("Failed to close collaboration footer subscription", failure);
        }
    }

    /** Package-private for {@code InputPanelKeyRoutingTest} (was exposed via {@code beginPasteForTest()}). */
    void beginPaste() {
        pendingPastes.incrementAndGet();
        updateHint();
    }

    private void completePasteOnGui(Runnable mutation) {
        Runnable completion = () -> {
            if (mutation != null) mutation.run();
            int remaining = pendingPastes.updateAndGet(value -> Math.max(0, value - 1));
            updateHint();
            if (remaining == 0 && deferredPasteSubmit.getAndSet(false)) {
                ((PromptTextBox) textBox).tryHandleSubmitKeyStroke(new KeyStroke(KeyType.ENTER));
            }
        };
        if (guiInvoker != null) guiInvoker.accept(completion);
        else completion.run();
    }

    private void updateHint() {
        // Don't overwrite an active temporary notification
        if (hintTimer != null) return;
        if (pendingPastes.get() > 0) {
            setHintLabel(hintMainLabel, "  Pasting text…");
            hintMainLabel.setForegroundColor(LanternaTheme.welcomeDim());
            setHintLabel(hintSuffixLabel, "");
            refreshTasksPill();
            updateVimModeLabel();
            return;
        }
        if (historySearchStatus != null) {
            setHintLabel(hintMainLabel, "  " + historySearchStatus);
            hintMainLabel.setForegroundColor(LanternaTheme.welcomeDim());
            setHintLabel(hintSuffixLabel, "");
            refreshTasksPill();
            updateVimModeLabel();
            return;
        }
        if (messageActionsActive) {
            setHintLabel(hintMainLabel, "  " + messageActionsHint);
            hintMainLabel.setForegroundColor(LanternaTheme.inputText());
            setHintLabel(hintSuffixLabel, " · ↑↓ navigate · esc back");
            hintSuffixLabel.setForegroundColor(LanternaTheme.welcomeDim());
            refreshTasksPill();
            updateVimModeLabel();
            return;
        }
        if (workflowFooterSelected) {
            renderWorkflowFooterHint();
            refreshTasksPill();
            updateVimModeLabel();
            return;
        }
        // Teammate-view mode shows navigation/status instead of the leader's

        if (taskNavigation.isActive()) {
            renderTeammateHint();
            refreshTasksPill();
            updateVimModeLabel();
            return;
        }
        renderLeaderHint();
        refreshTasksPill();
        updateVimModeLabel();
    }


    private void renderWorkflowFooterHint() {
        List<WorkflowRun> workflows = visibleWorkflowRuns();
        if (workflows.isEmpty()) {
            workflowFooterSelected = false;
            renderLeaderHint();
            return;
        }
        workflowFooterIndex = Math.min(workflowFooterIndex, workflows.size() - 1);
        WorkflowRun run = workflows.get(workflowFooterIndex);
        setHintLabel(hintMainLabel, "  enter view");
        hintMainLabel.setForegroundColor(LanternaTheme.welcomeDim());
        setHintLabel(hintSuffixLabel,
            " · x " + (run.status().hasResult() ? "clear" : "stop"));
        hintSuffixLabel.setForegroundColor(LanternaTheme.welcomeDim());
    }

    /** Leader-only hint banner (permission-mode chip + shift+tab hint). */
    private void renderLeaderHint() {

        PermissionMode mode = PermissionMode.fromString(permMode);
        String mainText;
        TextColor mainColor;
        if (mode == PermissionMode.DEFAULT) {
            mainText = "";
            mainColor = LanternaTheme.welcomeDim();
        } else {
            mainText = "  " + mode.symbol() + " "
                + mode.title().toLowerCase(Locale.ROOT) + " on";
            mainColor = LanternaTheme.colorFor(mode);
        }
        setHintLabel(hintMainLabel, mainText);
        hintMainLabel.setForegroundColor(mainColor);
        boolean showAgentsHint = !isLoading && leftArrowOpensAgents.getAsBoolean();
        String suffix = mode == PermissionMode.DEFAULT
            ? (persistentStatusVisible ? ""
                : "  ? for shortcuts" + (showAgentsHint ? " · ← for agents" : ""))
            : "(shift+tab to cycle)" + (showAgentsHint ? " · ← for agents" : "");
        // Chips live inline in the textBox — no extra count needed here.
        setHintLabel(hintSuffixLabel, suffix);
        hintSuffixLabel.setForegroundColor(LanternaTheme.welcomeDim());
    }

    private static void setHintLabel(Label label, String text) {
        String value = text == null ? "" : text;
        label.setText(value);
        label.setVisible(!value.isEmpty());
    }

/**
     * Hint banner shown while stepping/viewing a teammate.
     */
    private void renderTeammateHint() {
        PromptTaskNavigationController.TeammateHint hint =
            taskNavigation.teammateHint(taskNavigationHost);
        if (hint == null) {
            renderLeaderHint();
            return;
        }
        setHintLabel(hintMainLabel, hint.main());
        hintMainLabel.setForegroundColor(
            hint.accent() ? LanternaTheme.claude() : LanternaTheme.welcomeDim());
        setHintLabel(hintSuffixLabel, hint.suffix());
        hintSuffixLabel.setForegroundColor(LanternaTheme.welcomeDim());
    }

}
