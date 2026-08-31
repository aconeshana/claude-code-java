package com.claudecode.ui.lanterna.transcript;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import com.claudecode.ui.MarkdownRenderer;
import com.claudecode.core.constants.Figures;
import com.claudecode.core.text.FormatUtils;
import com.claudecode.keybindings.KeybindingHints;
import com.claudecode.keybindings.UserKeybindingsStore;
import com.claudecode.ui.lanterna.components.AnsiToSegments;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TerminalTextUtils;
import com.googlecode.lanterna.TextCharacter;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.gui2.AbstractComponent;
import com.googlecode.lanterna.gui2.ComponentRenderer;
import com.googlecode.lanterna.gui2.TextGUIGraphics;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import com.claudecode.ui.lanterna.theme.LanternaTheme;

/**
 * Scrollable message area — the main content panel.
 */
public class MessagePanel extends AbstractComponent<MessagePanel> {

    private static final int MAX_LINES      = 10_000;

    // ── Content store ──────────────────────────────────────────────────────
    private final List<StyledLine> lines = new ArrayList<>();
    /** Non-persistent rows projected after the transcript while an inline interaction is active. */
    private List<StyledLine> transientTail = List.of();
    private final ReadWriteLock lock     = new ReentrantReadWriteLock();
    /** Monotonic key for the expensive source-line → wrapped-display-row projection. */
    private final AtomicLong renderRevision = new AtomicLong();
    private final Object renderCacheMonitor = new Object();
    private volatile CachedRenderLayout cachedRenderLayout = CachedRenderLayout.EMPTY;
    private long renderLayoutBuildCount;
    private long sourceProjectionBuildCount;
    /** Changes whenever source rows ahead of stored external ranges can move/disappear. */
    private final AtomicLong contentEpoch = new AtomicLong();
    /** Logical rendered-message regions; physical rows may wrap but identity stays stable. */
    private final List<LogicalMessage> logicalMessages = new ArrayList<>();
    /** Alternate physical rows for logical messages whose collapsed projection is expandable. */
    private final Map<String, ExpansionRows> expansionRows = new ConcurrentHashMap<>();
    private String selectedLogicalMessageId;
    private long logicalMessageSequence;
    private long expandableToolOutputSequence;
    private UserKeybindingsStore keybindingsStore;

    public void setKeybindingsStore(UserKeybindingsStore store) {
        this.keybindingsStore = store;
    }

    private String expandHint() {
        return KeybindingHints.expand(keybindingsStore);
    }
    /**
     * Welcome/Pokemon rows are retained outside the bounded transcript so a /clear, resume rebuild, or
     * MAX_LINES trim cannot permanently discard the history header.
     */
    private List<StyledLine> historyTopAnchor = List.of();
    private int historyTopAnchorStart = -1;
    private int historyTopAnchorCount;
    private boolean historyTopAnchorInTranscript;

    private record CachedRenderLayout(long revision, int width,
                                      List<StyledLine> sourceLines,
                                      LogicalMessage selectedMessage,
                                      List<List<StyledLine>> sourceRows,
                                      List<StyledLine> rows) {
        private static final CachedRenderLayout EMPTY =
            new CachedRenderLayout(Long.MIN_VALUE, -1,
                List.of(), null, List.of(), List.of());
    }

    /**
     * Immutable indexed view over per-source-line projections. This preserves
     * random access for viewport rendering without copying every wrapped row
     * into a second full-transcript list whenever the streamed tail changes.
     */
    private static final class ProjectedRowsView extends AbstractList<StyledLine>
            implements RandomAccess {
        private final List<List<StyledLine>> sourceRows;
        private final int[] cumulativeEnds;
        private final int size;

        private ProjectedRowsView(List<List<StyledLine>> sourceRows) {
            this.sourceRows = sourceRows;
            this.cumulativeEnds = new int[sourceRows.size()];
            int total = 0;
            for (int i = 0; i < sourceRows.size(); i++) {
                total += sourceRows.get(i).size();
                cumulativeEnds[i] = total;
            }
            this.size = total;
        }

        @Override
        public StyledLine get(int index) {
            if (index < 0 || index >= size) throw new IndexOutOfBoundsException(index);
            int low = 0;
            int high = cumulativeEnds.length - 1;
            while (low < high) {
                int middle = (low + high) >>> 1;
                if (index < cumulativeEnds[middle]) high = middle;
                else low = middle + 1;
            }
            int previousEnd = low == 0 ? 0 : cumulativeEnds[low - 1];
            return sourceRows.get(low).get(index - previousEnd);
        }

        @Override
        public int size() {
            return size;
        }
    }

    /** Must be called while holding the transcript write lock. */
    private void markRenderChangedLocked() {
        renderRevision.incrementAndGet();
    }

// ── Blink state (for pending tool-use lines) ────────────────────────────.
    private static final long BLINK_INTERVAL_MS = 600;
    private static final ScheduledExecutorService BLINK_SCHEDULER =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "msg-blink");
            t.setDaemon(true);
            return t;
        });
    /** line-index → {onSegments, offSegments} */
    private final Map<Integer, BlinkEntry> blinkEntries = new ConcurrentHashMap<>();
    private final AtomicBoolean blinkPhase = new AtomicBoolean(true);  // true = ⏺ visible
    private ScheduledFuture<?> blinkFuture;
/**
     * When false, blink phase is frozen (⏺ stays visible).
     */
    private volatile boolean terminalFocused = true;

    private record BlinkEntry(List<Segment> onSegs, List<Segment> offSegs) {}

    /**
     * Start blinking the dot on the line at {@code lineIndex}.
     */
    public void startBlinkLine(int lineIndex, List<Segment> segments) {
        List<Segment> offSegs = new ArrayList<>(segments);
        if (!offSegs.isEmpty()) {
            Segment first = offSegs.getFirst();
            String blanked = " ".repeat(first.text().length());
            offSegs.set(0, new Segment(blanked, first.color(), first.bgColor(),
                first.hyperlinkUrl(), first.modifiers()));
        }
        blinkEntries.put(lineIndex, new BlinkEntry(List.copyOf(segments), List.copyOf(offSegs)));
        synchronized (this) {
            if (blinkFuture == null || blinkFuture.isDone()) {
                blinkFuture = BLINK_SCHEDULER.scheduleAtFixedRate(() -> {
                    if (terminalFocused) {
                        blinkPhase.set(!blinkPhase.get());
                    }
                    for (Map.Entry<Integer, BlinkEntry> e : blinkEntries.entrySet()) {
                        updateLine(e.getKey(),
                            blinkPhase.get() ? e.getValue().onSegs() : e.getValue().offSegs());
                    }
                }, BLINK_INTERVAL_MS, BLINK_INTERVAL_MS, TimeUnit.MILLISECONDS);
            }
        }
    }

    /**
     * Stop blinking the line at {@code lineIndex} and replace it with
     * {@code finalSegments} (the green/red done state). Pass null to keep
     * the current content.
     */
    public void stopBlinkLine(int lineIndex, List<Segment> finalSegments) {
        blinkEntries.remove(lineIndex);
        synchronized (this) {
            if (blinkEntries.isEmpty() && blinkFuture != null) {
                blinkFuture.cancel(false);
                blinkFuture = null;
            }
        }
        if (finalSegments != null) {
            updateLine(lineIndex, finalSegments);
        }
    }

    /**
     * Notify the panel whether the terminal window currently has focus.
     */
    public void setFocused(boolean focused) {
        this.terminalFocused = focused;
        if (focused) {
            // Resume with phase=true so the dot is immediately visible on re-focus
            blinkPhase.set(true);
        }
    }

    // ── Scroll state ───────────────────────────────────────────────────────
    private int scrollOffset      = 0;   // lines hidden at top
    private boolean autoScroll    = true; // follow bottom like a terminal
    private volatile String searchHighlightQuery = null; // non-null = highlight matches

    /**
     * Active virtual selection.
     */
    private volatile Selection selection;

    /**
     * Snapshot of the last frame's display rows (post wordWrap).
     */
    private volatile List<String> lastFrameRowTexts = List.of();

    public void setSelection(Selection sel) {
        this.selection = sel;
        invalidate();
    }

    // ──────────────────────────────────────────────────────────────────────

    /** Append a complete line (non-streaming). Thread-safe. */
    public void appendLine(String text, TextColor color) {
        appendLine(text, color, 0);
    }

    /** Append a complete line with a terminal-column inset applied to wrapping. */
    public void appendLine(String text, TextColor color, int wrapWidthInset) {
        lock.writeLock().lock();
        try {
            lines.add(new StyledLine(List.of(new Segment(text, color)), false,
                Math.max(0, wrapWidthInset)));
            trimIfNeeded();
            markRenderChangedLocked();
        } finally {
            lock.writeLock().unlock();
        }
        if (autoScroll) scrollToBottom();
        invalidate();
    }

    /** Append a line composed of multiple colored segments. */
    public void appendMixed(List<Segment> segments) {
        appendMixed(segments, 0);
    }

    /** Append a mixed-style line with a terminal-column inset applied to body wrapping. */
    public void appendMixed(List<Segment> segments, int wrapWidthInset) {
        lock.writeLock().lock();
        try {
            lines.add(new StyledLine(segments, false, Math.max(0, wrapWidthInset)));
            trimIfNeeded();
            markRenderChangedLocked();
        } finally {
            lock.writeLock().unlock();
        }
        if (autoScroll) scrollToBottom();
        invalidate();
    }

    /** Store Markdown as a width-reactive source projection instead of frozen rows. */
    public void appendMarkdown(String markdown, MarkdownRenderer renderer, boolean showBullet) {
        if (StringUtils.isBlank(markdown) || renderer == null) return;
        lock.writeLock().lock();
        try {
            lines.add(StyledLine.markdown(markdown, renderer, showBullet));
            trimIfNeeded();
            markRenderChangedLocked();
        } finally {
            lock.writeLock().unlock();
        }
        if (autoScroll) scrollToBottom();
        invalidate();
    }

    /**
     * Show a width-reactive, scrollable plan preview without adding it to transcript history.
     * The caller must clear it when the permission request resolves; the eventual tool result
     * remains the authoritative historical record.
     */
    public void showPlanApprovalPreview(String plan, MarkdownRenderer renderer) {
        String content = StringUtils.isBlank(plan)
            ? "No plan found. Please write your plan to the plan file first."
            : plan.stripTrailing();
        lock.writeLock().lock();
        try {
            transientTail = List.of(
                new StyledLine("Here is Claude's plan:", TextColor.ANSI.DEFAULT, false),
                StyledLine.DIVIDER,
                StyledLine.markdown(content, renderer, false),
                StyledLine.DIVIDER);
            markRenderChangedLocked();
        } finally {
            lock.writeLock().unlock();
        }
        scrollToBottom();
        invalidate();
    }

    /** Remove the active non-persistent interaction projection, if any. */
    public void clearTransientTail() {
        lock.writeLock().lock();
        try {
            if (transientTail.isEmpty()) return;
            transientTail = List.of();
            markRenderChangedLocked();
        } finally {
            lock.writeLock().unlock();
        }
        scrollToBottom();
        invalidate();
    }

    /**
     * Stores complete shell/tool output as one logical transcript row.
     */
    public void appendToolOutput(String content, TextColor color, boolean showAll) {
        int outputLine;
        lock.writeLock().lock();
        try {
            lines.add(StyledLine.toolOutput(content, color, showAll, expandHint()));
            trimIfNeeded();
            outputLine = lines.size() - 1;
            markRenderChangedLocked();
        } finally {
            lock.writeLock().unlock();
        }
        registerTruncatedToolOutput(outputLine, content, color, showAll);
        if (autoScroll) scrollToBottom();
        invalidate();
    }

    /** Replaces a pending tool row with complete width-aware output, or appends it. */
    public void updateToolOutputOrAppend(int index, String content, TextColor color,
                                         boolean showAll) {
        int outputLine;
        lock.writeLock().lock();
        try {
            StyledLine output = StyledLine.toolOutput(content, color, showAll, expandHint());
            if (index >= 0 && index < lines.size()) {
                lines.set(index, output);
                outputLine = index;
            } else {
                lines.add(output);
                trimIfNeeded();
                outputLine = lines.size() - 1;
            }
            markRenderChangedLocked();
        } finally {
            lock.writeLock().unlock();
        }
        registerTruncatedToolOutput(outputLine, content, color, showAll);
        if (autoScroll) scrollToBottom();
        invalidate();
    }

    private void registerTruncatedToolOutput(
            int outputLine, String content, TextColor color, boolean showAll) {
        if (showAll || outputLine < 0 || content == null || StringUtils.isBlank(content)) return;
        TerminalSize size = getSize();
        int width = size == null || size.getColumns() <= 0 ? 80 : size.getColumns();
        StyledLine collapsed = StyledLine.toolOutput(content, color, false, expandHint());
        boolean truncated = projectToolOutput(collapsed.toolOutput(), width).stream()
            .anyMatch(row -> Strings.CS.contains(row.text(), collapsed.toolOutput().expandHint()));
        if (!truncated) return;
        registerExpandableLogicalMessage(
            "tool-output:" + (++expandableToolOutputSequence),
            LogicalMessageKind.TOOL,
            outputLine,
            outputLine,
            content,
            List.of(StyledLine.toolOutput(content, color, true, expandHint())));
    }

    /** Append a horizontal divider line. */
    public void appendDivider() {
        lock.writeLock().lock();
        try {
            lines.add(StyledLine.DIVIDER);
            markRenderChangedLocked();
        } finally {
            lock.writeLock().unlock();
        }
        invalidate();
    }

    /** Mark current point in the message log, so we can later truncate back to it. */
    public int snapshotLineCount() {
        lock.readLock().lock();
        try {
            return lines.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Immutable styled-row snapshot used to compose alternate message projections. */
    List<StyledLine> snapshotStyledLines() {
        lock.readLock().lock();
        try {
            return List.copyOf(lines);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Replaces the segments of the line at the given index.
     * Used to upgrade a dim "pending tool" line to a green "done tool" line
     * in-place, without appending a new duplicate line.
     * No-op if the index is out of range.
     */
    public void updateLine(int index, List<Segment> segments) {
        lock.writeLock().lock();
        try {
            if (index >= 0 && index < lines.size()) {
                StyledLine replacement = new StyledLine(segments, false);
                lines.set(index, replacement);
                if (historyTopAnchorInTranscript
                        && index >= historyTopAnchorStart
                        && index < historyTopAnchorStart + historyTopAnchorCount) {
                    List<StyledLine> updatedAnchor = new ArrayList<>(historyTopAnchor);
                    updatedAnchor.set(index - historyTopAnchorStart, replacement);
                    historyTopAnchor = List.copyOf(updatedAnchor);
                }
                markRenderChangedLocked();
                invalidate();
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Replaces a contiguous source-line block while preserving all transcript rows after it.
     * Used by the welcome Pokémon command because different sprites have different heights.
     */
    public void replaceLines(int start, int count, List<List<Segment>> replacements) {
        if (start < 0 || count < 0 || replacements == null) return;
        lock.writeLock().lock();
        try {
            if (start > lines.size() || start + count > lines.size()) return;
            for (int i = 0; i < count; i++) lines.remove(start);
            int insert = start;
            for (List<Segment> segments : replacements) {
                lines.add(insert++, new StyledLine(List.copyOf(segments), false));
            }
            int delta = replacements.size() - count;
            adjustHistoryTopAnchorForReplacementLocked(start, count, delta);
            if (delta != 0) {
                for (int i = 0; i < logicalMessages.size(); i++) {
                    LogicalMessage message = logicalMessages.get(i);
                    if (message.startLine() >= start + count) {
                        logicalMessages.set(i, message.shifted(delta));
                    }
                }
            }
            markRenderChangedLocked();
        } finally {
            lock.writeLock().unlock();
        }
        if (autoScroll) scrollToBottom();
        invalidate();
    }

    /**
     * Retains a styled history header independently of the bounded transcript.
     * The source range lets it continue to behave like ordinary content until
     * that range is removed; after removal it is restored only at history top.
     */
    public void setHistoryTopAnchor(int sourceStart, List<List<Segment>> rows) {
        if (rows == null || rows.isEmpty()) return;
        lock.writeLock().lock();
        try {
            historyTopAnchor = styledLines(rows);
            historyTopAnchorCount = historyTopAnchor.size();
            historyTopAnchorStart = sourceStart;
            historyTopAnchorInTranscript = sourceStart >= 0
                && sourceStart + historyTopAnchorCount <= lines.size();
        } finally {
            lock.writeLock().unlock();
        }
        invalidate();
    }

    /**
     * Replaces the retained history header at its tracked source range when that range
     * still exists; otherwise updates only the detached copy shown at history top.
     * This deliberately does not use {@link #contentEpoch()}: streamed-tail truncation
     * changes the epoch even when the startup rows themselves remain intact.
     *
     * @return the new source start, or {@code -1} when the header is detached
     */
    public int replaceHistoryTopAnchor(List<List<Segment>> rows) {
        if (rows == null || rows.isEmpty()) return -1;
        int sourceStart = -1;
        lock.writeLock().lock();
        try {
            List<StyledLine> replacement = styledLines(rows);
            if (historyTopAnchorInTranscript
                    && historyTopAnchorStart >= 0
                    && historyTopAnchorStart + historyTopAnchorCount <= lines.size()) {
                sourceStart = historyTopAnchorStart;
                int oldCount = historyTopAnchorCount;
                for (int i = 0; i < oldCount; i++) lines.remove(sourceStart);
                lines.addAll(sourceStart, replacement);
                int delta = replacement.size() - oldCount;
                if (delta != 0) {
                    for (int i = 0; i < logicalMessages.size(); i++) {
                        LogicalMessage message = logicalMessages.get(i);
                        if (message.startLine() >= sourceStart + oldCount) {
                            logicalMessages.set(i, message.shifted(delta));
                        }
                    }
                }
            }
            historyTopAnchor = replacement;
            historyTopAnchorCount = replacement.size();
            historyTopAnchorStart = sourceStart;
            historyTopAnchorInTranscript = sourceStart >= 0;
            markRenderChangedLocked();
        } finally {
            lock.writeLock().unlock();
        }
        if (autoScroll) scrollToBottom();
        invalidate();
        return sourceStart;
    }

    private static List<StyledLine> styledLines(List<List<Segment>> rows) {
        List<StyledLine> styled = new ArrayList<>(rows.size());
        for (List<Segment> row : rows) {
            styled.add(new StyledLine(List.copyOf(row), false));
        }
        return List.copyOf(styled);
    }

    /** Token used by external replaceable blocks to detect clear/trim/resume invalidation. */
    public long contentEpoch() { return contentEpoch.get(); }

    /**
     * Updates the line at {@code index} in-place if it exists, otherwise appends.
     * Needed by MessageCollapser: in real terminals the live "⏺ Reading…" line exists
     * and can be replaced; in test StubPanels that don't call super.appendMixed, the
     * internal list is empty, so we fall back to appendMixed for correct capture.
     */
    public void updateLineOrAppend(int index, List<Segment> segments) {
        lock.writeLock().lock();
        int sz;
        try { sz = lines.size(); } finally { lock.writeLock().unlock(); }
        if (index >= 0 && index < sz) {
            updateLine(index, segments);
        } else {
            appendMixed(segments);
        }
    }

    /** Drop all lines added after the given snapshot point. */
    public void truncateLinesTo(int snapshot) {
        lock.writeLock().lock();
        try {
            while (lines.size() > snapshot) {
                lines.removeLast();
            }
            logicalMessages.removeIf(message -> message.startLine() >= snapshot);
            for (int i = 0; i < logicalMessages.size(); i++) {
                LogicalMessage message = logicalMessages.get(i);
                if (message.endLine() >= snapshot) {
                    logicalMessages.set(i, message.withEndLine(snapshot - 1));
                }
            }
            clearMissingLogicalSelectionLocked();
            retainExpansionRowsForExistingMessagesLocked();
            if (historyTopAnchorInTranscript
                    && snapshot < historyTopAnchorStart + historyTopAnchorCount) {
                historyTopAnchorInTranscript = false;
                historyTopAnchorStart = -1;
            }
            contentEpoch.incrementAndGet();
            markRenderChangedLocked();
        } finally {
            lock.writeLock().unlock();
        }
        invalidate();
    }

    /** Clear all messages. */
    public void clear() {
        lock.writeLock().lock();
        try {
            lines.clear();
            logicalMessages.clear();
            expansionRows.clear();
            selectedLogicalMessageId = null;
            historyTopAnchorInTranscript = false;
            historyTopAnchorStart = -1;
            scrollOffset = 0;
            autoScroll   = true;
            contentEpoch.incrementAndGet();
            markRenderChangedLocked();
        } finally {
            lock.writeLock().unlock();
        }
        invalidate();
    }

    // ── Scroll API ─────────────────────────────────────────────────────────

    // NOTE: MessagePanel is an AbstractComponent, not an InteractableComponent,
    // so Lanterna will never deliver KeyStroke events here directly. To enable
    // mouse/keyboard scrolling we'd need to either (a) wire a key handler on the
    // root window that calls scrollUp/scrollDown, or (b) refactor MessagePanel
    // to extend AbstractInteractableComponent and arbitrate focus with InputPanel.

    public void scrollUp(int lines)   { scroll(-lines, SelectionScrollMode.BOTH_ENDPOINTS); }
    public void scrollDown(int lines) { scroll(+lines, SelectionScrollMode.BOTH_ENDPOINTS); }

    public void scrollSelectionDragUp(int lines) {
        scroll(-lines, SelectionScrollMode.ANCHOR_ONLY);
    }

    public void scrollSelectionDragDown(int lines) {
        scroll(+lines, SelectionScrollMode.ANCHOR_ONLY);
    }

    /**
     * Get the text of the line at the current scroll position.
     * Used by message actions copy/edit. Returns null if no line is available.
     */
    public String getSelectedText() {
        lock.readLock().lock();
        try {
            if (lines.isEmpty()) return null;
            int idx = lines.size() - 1 - scrollOffset;
            if (idx < 0 || idx >= lines.size()) return null;
            StyledLine line = lines.get(idx);
            if (line.isDivider()) return null;
            StringBuilder sb = new StringBuilder();
            for (Segment seg : line.segments()) {
                sb.append(seg.text());
            }
            String text = sb.toString().trim();
            return text.isEmpty() ? null : text;
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Register a rendered logical message without coupling navigation to wrapped rows. */
    public LogicalMessage registerLogicalMessage(
            String id,
            LogicalMessageKind kind,
            int startLine,
            int endLine,
            String copyText,
            String editText,
            String primaryInputLabel,
            String primaryInput,
            boolean expandable) {
        return registerLogicalMessage(id, null, kind, startLine, endLine,
            copyText, editText, primaryInputLabel, primaryInput, expandable);
    }

    /**
     * Register a collapsed logical message together with the real rows displayed when expanded.
     * The collapsed rows are captured from the panel's current inclusive line range.
     */
    public LogicalMessage registerExpandableLogicalMessage(
            String id,
            LogicalMessageKind kind,
            int startLine,
            int endLine,
            String copyText,
            List<StyledLine> expandedLines) {
        LogicalMessage message = registerLogicalMessage(id, kind, startLine, endLine,
            copyText, null, null, null, true);
        if (message == null || expandedLines == null || expandedLines.isEmpty()) return message;
        lock.writeLock().lock();
        try {
            List<StyledLine> collapsed = List.copyOf(
                lines.subList(message.startLine(), message.endLine() + 1));
            expansionRows.put(message.id(), new ExpansionRows(collapsed, List.copyOf(expandedLines)));
        } finally {
            lock.writeLock().unlock();
        }
        return message;
    }

    /**
     * Full registration overload carrying the raw conversation UUID separately
     * from the render identity. A rendered user prompt may be split into blocks
     * or initially painted as a live echo, so its UI id is not a safe rewind key.
     */
    public LogicalMessage registerLogicalMessage(
            String id,
            String sourceUuid,
            LogicalMessageKind kind,
            int startLine,
            int endLine,
            String copyText,
            String editText,
            String primaryInputLabel,
            String primaryInput,
            boolean expandable) {
        lock.writeLock().lock();
        try {
            if (lines.isEmpty()) return null;
            int start = Math.max(0, Math.min(startLine, lines.size() - 1));
            int end = Math.max(start, Math.min(endLine, lines.size() - 1));
            String baseId = StringUtils.isBlank(id)
                ? "logical-" + UUID.randomUUID() : id;
            String uniqueId = baseId;
            while (containsLogicalMessageIdLocked(uniqueId)) {
                uniqueId = baseId + "#" + (++logicalMessageSequence);
            }
            LogicalMessage message = new LogicalMessage(
                uniqueId,
                sourceUuid,
                kind == null ? LogicalMessageKind.SYSTEM : kind,
                start,
                end,
                copyText == null ? "" : copyText,
                editText,
                primaryInputLabel,
                primaryInput,
                expandable,
                false);
            logicalMessages.add(message);
            logicalMessages.sort(Comparator.comparingInt(LogicalMessage::startLine)
                .thenComparingInt(LogicalMessage::endLine));
            markRenderChangedLocked();
            invalidate();
            return message;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Bind the raw UUID once the SDK user event arrives for a synchronously-painted live echo. */
    public void bindLatestUnboundUserSourceUuid(String sourceUuid) {
        if (StringUtils.isBlank(sourceUuid)) return;
        lock.writeLock().lock();
        try {
            for (int i = logicalMessages.size() - 1; i >= 0; i--) {
                LogicalMessage message = logicalMessages.get(i);
                if (message.kind() == LogicalMessageKind.USER
                        && (StringUtils.isBlank(message.sourceUuid()))) {
                    logicalMessages.set(i, message.withSourceUuid(sourceUuid));
                    invalidate();
                    return;
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Remove the selected raw prompt and every rendered row after it. */
    public void truncateFromSourceUuid(String sourceUuid) {
        if (StringUtils.isBlank(sourceUuid)) return;
        int start = -1;
        lock.readLock().lock();
        try {
            for (LogicalMessage message : logicalMessages) {
                if (sourceUuid.equals(message.sourceUuid())) {
                    start = message.startLine();
                    break;
                }
            }
        } finally {
            lock.readLock().unlock();
        }
        if (start >= 0) truncateLinesTo(start);
    }

    public Optional<LogicalMessage> enterMessageActions() {
        int target = -1;
        lock.readLock().lock();
        try {
            for (int i = logicalMessages.size() - 1; i >= 0; i--) {
                if (logicalMessages.get(i).kind() == LogicalMessageKind.USER) {
                    target = i;
                    break;
                }
            }
        } finally {
            lock.readLock().unlock();
        }
        return selectLogicalMessage(target);
    }

    public Optional<LogicalMessage> selectedLogicalMessage() {
        lock.readLock().lock();
        try {
            return selectedLogicalMessageLocked();
        } finally {
            lock.readLock().unlock();
        }
    }

    public Optional<LogicalMessage> selectPreviousLogicalMessage() {
        return selectRelativeLogicalMessage(-1, null);
    }

    public Optional<LogicalMessage> selectNextLogicalMessage() {
        return selectRelativeLogicalMessage(1, null);
    }

    public Optional<LogicalMessage> selectPreviousUserMessage() {
        return selectRelativeLogicalMessage(-1, LogicalMessageKind.USER);
    }

    public Optional<LogicalMessage> selectNextUserMessage() {
        return selectRelativeLogicalMessage(1, LogicalMessageKind.USER);
    }

    public Optional<LogicalMessage> selectTopLogicalMessage() {
        return selectLogicalMessage(0);
    }

    public Optional<LogicalMessage> selectBottomLogicalMessage() {
        return selectLogicalMessage(logicalMessages.size() - 1);
    }

    public Optional<LogicalMessage> toggleSelectedLogicalMessageExpanded() {
        lock.writeLock().lock();
        try {
            int index = selectedLogicalMessageIndexLocked();
            if (index < 0) return Optional.empty();
            LogicalMessage selected = logicalMessages.get(index);
            if (!selected.expandable()) return Optional.of(selected);
            LogicalMessage toggled = replaceLogicalMessageRowsLocked(
                index, selected, !selected.expanded());
            markRenderChangedLocked();
            invalidate();
            return Optional.of(toggled);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Toggles the expandable logical message painted at a viewport-local cell.
     */
    public Optional<LogicalMessage> toggleExpandableLogicalMessageAt(int column, int viewportRow) {
        TerminalSize size = getSize();
        if (size == null || column < 0 || viewportRow < 0
                || column >= size.getColumns() || viewportRow >= size.getRows()) {
            return Optional.empty();
        }
        int width = Math.max(1, size.getColumns());
        ViewportProjection viewport = viewportProjection(width, size.getRows());
        if (viewportRow < viewport.anchorRowCount()) return Optional.empty();
        int transcriptViewportRow = viewportRow - viewport.anchorRowCount();
        if (transcriptViewportRow >= viewport.transcriptRowCount()) {
            return Optional.empty();
        }
        int displayRow = viewport.startDisplayRow() + transcriptViewportRow;

        int sourceLine = sourceLineForDisplayRow(width, displayRow);
        if (sourceLine < 0) return Optional.empty();
        lock.writeLock().lock();
        try {
            for (int i = 0; i < logicalMessages.size(); i++) {
                LogicalMessage message = logicalMessages.get(i);
                if (!message.expandable()
                        || sourceLine < message.startLine() || sourceLine > message.endLine()) {
                    continue;
                }
                LogicalMessage toggled = replaceLogicalMessageRowsLocked(
                    i, message, !message.expanded());
                markRenderChangedLocked();
                invalidate();
                return Optional.of(toggled);
            }
            return Optional.empty();
        } finally {
            lock.writeLock().unlock();
        }
    }

    private int sourceLineForDisplayRow(int width, int displayRow) {
        CachedRenderLayout cached = cachedRenderLayout;
        if (cached.width() != width || cached.revision() != renderRevision.get()) {
            displayRowsForWidth(width);
            cached = cachedRenderLayout;
        }
        int firstDisplayRow = 0;
        for (int sourceLine = 0; sourceLine < cached.sourceRows().size(); sourceLine++) {
            int nextDisplayRow = firstDisplayRow + cached.sourceRows().get(sourceLine).size();
            if (displayRow >= firstDisplayRow && displayRow < nextDisplayRow) return sourceLine;
            firstDisplayRow = nextDisplayRow;
        }
        return -1;
    }

    /** Collapse the selected message without leaving Message Actions. */
    public Optional<LogicalMessage> collapseSelectedLogicalMessage() {
        lock.writeLock().lock();
        try {
            int index = selectedLogicalMessageIndexLocked();
            if (index < 0) return Optional.empty();
            LogicalMessage selected = logicalMessages.get(index);
            if (!selected.expanded()) return Optional.of(selected);
            LogicalMessage collapsed = replaceLogicalMessageRowsLocked(index, selected, false);
            markRenderChangedLocked();
            invalidate();
            return Optional.of(collapsed);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void clearLogicalMessageSelection() {
        lock.writeLock().lock();
        try {
            if (selectedLogicalMessageId == null) return;
            selectedLogicalMessageId = null;
            markRenderChangedLocked();
        } finally {
            lock.writeLock().unlock();
        }
        invalidate();
    }

    private Optional<LogicalMessage> selectRelativeLogicalMessage(
            int direction, LogicalMessageKind requiredKind) {
        lock.writeLock().lock();
        LogicalMessage selected = null;
        try {
            if (logicalMessages.isEmpty()) return Optional.empty();
            int current = selectedLogicalMessageIndexLocked();
            if (current < 0) current = direction < 0 ? logicalMessages.size() : -1;
            for (int i = current + direction;
                    i >= 0 && i < logicalMessages.size(); i += direction) {
                LogicalMessage candidate = logicalMessages.get(i);
                if (requiredKind == null || candidate.kind() == requiredKind) {
                    selectedLogicalMessageId = candidate.id();
                    markRenderChangedLocked();
                    selected = candidate;
                    break;
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
        if (selected != null) {
            scrollToLine(selected.endLine());
            invalidate();
        }
        return Optional.ofNullable(selected);
    }

    private Optional<LogicalMessage> selectLogicalMessage(int index) {
        LogicalMessage selected;
        lock.writeLock().lock();
        try {
            if (index < 0 || index >= logicalMessages.size()) return Optional.empty();
            selected = logicalMessages.get(index);
            selectedLogicalMessageId = selected.id();
            markRenderChangedLocked();
        } finally {
            lock.writeLock().unlock();
        }
        scrollToLine(selected.endLine());
        invalidate();
        return Optional.of(selected);
    }

    private Optional<LogicalMessage> selectedLogicalMessageLocked() {
        int index = selectedLogicalMessageIndexLocked();
        return index < 0 ? Optional.empty() : Optional.of(logicalMessages.get(index));
    }

    private int selectedLogicalMessageIndexLocked() {
        if (selectedLogicalMessageId == null) return -1;
        for (int i = 0; i < logicalMessages.size(); i++) {
            if (logicalMessages.get(i).id().equals(selectedLogicalMessageId)) return i;
        }
        return -1;
    }

    private boolean containsLogicalMessageIdLocked(String id) {
        for (LogicalMessage message : logicalMessages) {
            if (message.id().equals(id)) return true;
        }
        return false;
    }

    private void clearMissingLogicalSelectionLocked() {
        if (selectedLogicalMessageId != null && selectedLogicalMessageIndexLocked() < 0) {
            selectedLogicalMessageId = null;
        }
    }

    private LogicalMessage replaceLogicalMessageRowsLocked(
            int index, LogicalMessage selected, boolean expanded) {
        ExpansionRows variants = expansionRows.get(selected.id());
        if (variants == null) {
            LogicalMessage toggled = selected.withExpanded(expanded);
            logicalMessages.set(index, toggled);
            return toggled;
        }
        List<StyledLine> replacement = expanded ? variants.expanded() : variants.collapsed();
        int oldEnd = selected.endLine();
        int oldLength = oldEnd - selected.startLine() + 1;
        lines.subList(selected.startLine(), oldEnd + 1).clear();
        lines.addAll(selected.startLine(), replacement);
        int newEnd = selected.startLine() + replacement.size() - 1;
        int delta = replacement.size() - oldLength;
        LogicalMessage toggled = selected.withExpandedAndEnd(expanded, newEnd);
        logicalMessages.set(index, toggled);
        if (delta != 0) {
            for (int i = 0; i < logicalMessages.size(); i++) {
                if (i == index) continue;
                LogicalMessage message = logicalMessages.get(i);
                if (message.startLine() > oldEnd) {
                    logicalMessages.set(i, message.shifted(delta));
                }
            }
        }
        return toggled;
    }

    private void retainExpansionRowsForExistingMessagesLocked() {
        expansionRows.keySet().removeIf(id -> !containsLogicalMessageIdLocked(id));
    }

    boolean isLogicalLineSelectedForTest(int line) {
        return selectedLogicalMessage().map(message ->
            line >= message.startLine() && line <= message.endLine()).orElse(false);
    }

    int logicalMessageCountForTest() {
        lock.readLock().lock();
        try {
            return logicalMessages.size();
        } finally {
            lock.readLock().unlock();
        }
    }


    public void pageUp()              { scroll(-pageStep(), SelectionScrollMode.BOTH_ENDPOINTS); }
    public void pageDown()            { scroll(+pageStep(), SelectionScrollMode.BOTH_ENDPOINTS); }
    public void scrollToBottom()      { scrollOffset = 0; autoScroll = true; invalidate(); }
    public void scrollToTop()         { scrollOffset = maxScrollOffset(); autoScroll = false; invalidate(); }

    private int pageStep() {
        TerminalSize size = getSize();
        int visibleRows = size == null || size.getRows() <= 0 ? 24 : size.getRows();
        return Math.max(1, visibleRows / 2);
    }

    private int maxScrollOffset() {
        TerminalSize size = getSize();
        int width = size == null || size.getColumns() <= 0 ? 80 : size.getColumns();
        int visibleRows = size == null || size.getRows() <= 0 ? 24 : size.getRows();
        return Math.max(0, displayRowsForWidth(width).size() - visibleRows);
    }

    /** Set search highlight query — non-null highlights matching text in dim inverse. */
    public void setSearchHighlight(String query) {
        this.searchHighlightQuery = (StringUtils.isNotEmpty(query)) ? query.toLowerCase(Locale.ROOT) : null;
        invalidate();
    }

    /** Scroll to a specific line index (0-based from top). */
    public void scrollToLine(int lineIdx) {
        lock.writeLock().lock();
        try {
            int total = lines.size();
            if (lineIdx < 0 || lineIdx >= total) return;
            // scrollOffset = lines hidden at top = total - 1 - lineIdx
            scrollOffset = Math.max(0, total - 1 - lineIdx);
            autoScroll = false;
        } finally {
            lock.writeLock().unlock();
        }
        invalidate();
    }

    /**
     * Search through all lines for a query string. Returns a list of line
     * indices (0-based from top) that contain the query (case-insensitive).
     */
    public List<Integer> searchLines(String query) {
        List<Integer> results = new ArrayList<>();
        if (StringUtils.isEmpty(query)) return results;
        String lowerQuery = query.toLowerCase(Locale.ROOT);
        lock.readLock().lock();
        try {
            for (int i = 0; i < lines.size(); i++) {
                StyledLine line = lines.get(i);
                if (line.isDivider()) continue;
                StringBuilder sb = new StringBuilder();
                for (Segment seg : line.segments()) {
                    sb.append(seg.text());
                }
                if (Strings.CI.contains(sb.toString(), lowerQuery)) {
                    results.add(i);
                }
            }
        } finally {
            lock.readLock().unlock();
        }
        return results;
    }

    private enum SelectionScrollMode { BOTH_ENDPOINTS, ANCHOR_ONLY }

    private void scroll(int delta, SelectionScrollMode selectionMode) {
        TerminalSize size = getSize();
        int visibleRows = size == null ? 24 : size.getRows();
        int width = size == null || size.getColumns() <= 0 ? 80 : size.getColumns();
        int maxOffset = Math.max(0, displayRowsForWidth(width).size() - visibleRows);

        int oldOffset = scrollOffset;
        if (delta < 0) {
            // Scrolling up
            scrollOffset = Math.min(maxOffset, scrollOffset - delta);
        } else {
            // Scrolling down
            scrollOffset = Math.max(0, scrollOffset - delta);
        }
        autoScroll = (scrollOffset == 0);
// When the viewport moves, an active selection must follow the content.
        Selection sel = this.selection;
        if (sel != null && sel.hasSelection()) {
            int dRow = scrollOffset - oldOffset;
            if (dRow != 0) {
                int maxRow = Math.max(0, visibleRows - 1);
// Capture rows that are about to scroll off so getSelectedText still returns the
// original text.
                Selection.Bounds b = sel.getSelectionBounds();
                if (b != null) {
                    List<String> snap = this.lastFrameRowTexts;
                    if (dRow > 0) {
                        // Content moved down → bottom rows scroll off BELOW.
                        // Rows in [maxRow-dRow+1, maxRow] are leaving.
                        List<String> outgoing = new ArrayList<>();
                        int startR = Math.max(0, maxRow - dRow + 1);
                        for (int r = startR; r <= maxRow && r < snap.size(); r++) {
                            if (r >= b.start().row() && r <= b.end().row()) {
                                outgoing.add(snap.get(r));
                            }
                        }
                        if (!outgoing.isEmpty()) sel.captureScrolledRows("below", outgoing);
                    } else {
                        // dRow < 0: content moved up → top rows scroll off ABOVE.
                        List<String> outgoing = new ArrayList<>();
                        int upper = Math.min(-dRow, snap.size());
                        for (int r = 0; r < upper; r++) {
                            if (r >= b.start().row() && r <= b.end().row()) {
                                outgoing.add(snap.get(r));
                            }
                        }
                        if (!outgoing.isEmpty()) sel.captureScrolledRows("above", outgoing);
                    }
                }
                if (selectionMode == SelectionScrollMode.ANCHOR_ONLY) {
                    sel.shiftAnchor(dRow, maxRow);
                } else {
                    sel.shiftSelection(dRow, maxRow);
                }
            }
        }
        invalidate();
    }

    // ── Rendering ─────────────────────────────────────────────────────────

    @Override
    protected ComponentRenderer<MessagePanel> createDefaultRenderer() {
        return new MessageRenderer();
    }

    @Override
    public TerminalSize calculatePreferredSize() {
        // Return projected display height so SmartLayout places pinned components below
        // folded tool rows as well as ordinary source lines. Width changes invalidate the
// cached projection and therefore match OutputLine's reactive terminal-size render.
        TerminalSize current = getSize();
        int width = current == null || current.getColumns() <= 0 ? 80 : current.getColumns();
        int contentHeight = displayRowsForWidth(width).size();
        return new TerminalSize(80, Math.max(1, contentHeight));
    }

    // ──────────────────────────────────────────────────────────────────────

    private void trimIfNeeded() {
        int removed = 0;
        while (lines.size() > MAX_LINES) {
            lines.removeFirst();
            removed++;
        }
        if (removed == 0) return;
        contentEpoch.incrementAndGet();
        int shift = removed;
        if (historyTopAnchorInTranscript) {
            int anchorEnd = historyTopAnchorStart + historyTopAnchorCount;
            if (shift > historyTopAnchorStart) {
                historyTopAnchorInTranscript = false;
                historyTopAnchorStart = -1;
            } else if (shift < anchorEnd) {
                historyTopAnchorStart -= shift;
            }
        }
        logicalMessages.removeIf(message -> message.endLine() < shift);
        logicalMessages.replaceAll(logicalMessage -> logicalMessage.shifted(-shift));
        retainExpansionRowsForExistingMessagesLocked();
        clearMissingLogicalSelectionLocked();
    }

    // ── Data model ─────────────────────────────────────────────────────────

    public enum LogicalMessageKind {
        USER, ASSISTANT, TOOL, SYSTEM, ATTACHMENT
    }

    /**
     * One navigable message projected onto an inclusive physical-line range.
     * Copy/edit payloads deliberately retain the original unwrapped text.
     */
    public record LogicalMessage(
        String id,
        String sourceUuid,
        LogicalMessageKind kind,
        int startLine,
        int endLine,
        String copyText,
        String editText,
        String primaryInputLabel,
        String primaryInput,
        boolean expandable,
        boolean expanded
    ) {
        LogicalMessage withEndLine(int line) {
            return new LogicalMessage(id, sourceUuid, kind, startLine, Math.max(startLine, line),
                copyText, editText, primaryInputLabel, primaryInput, expandable, expanded);
        }

        LogicalMessage withExpanded(boolean value) {
            return new LogicalMessage(id, sourceUuid, kind, startLine, endLine,
                copyText, editText, primaryInputLabel, primaryInput, expandable, value);
        }

        LogicalMessage withExpandedAndEnd(boolean value, int line) {
            return new LogicalMessage(id, sourceUuid, kind, startLine, Math.max(startLine, line),
                copyText, editText, primaryInputLabel, primaryInput, expandable, value);
        }

        LogicalMessage withSourceUuid(String value) {
            return new LogicalMessage(id, value, kind, startLine, endLine,
                copyText, editText, primaryInputLabel, primaryInput, expandable, expanded);
        }

        LogicalMessage shifted(int delta) {
            return new LogicalMessage(id, sourceUuid, kind,
                Math.max(0, startLine + delta), Math.max(0, endLine + delta),
                copyText, editText, primaryInputLabel, primaryInput, expandable, expanded);
        }
    }

    private record ExpansionRows(List<StyledLine> collapsed, List<StyledLine> expanded) {}

    /** A colored/styled text segment within a line. */
    public record Segment(String text, TextColor color, TextColor bgColor, String hyperlinkUrl,
                          Set<SGR> modifiers) {
        public Segment {
            modifiers = modifiers == null ? Set.of() : Set.copyOf(modifiers);
        }
        public Segment(String text, TextColor color, TextColor bgColor, String hyperlinkUrl) {
            this(text, color, bgColor, hyperlinkUrl, Set.of());
        }
        public Segment(String text, TextColor color, TextColor bgColor) {
            this(text, color, bgColor, null, Set.of());
        }
        public Segment(String text, TextColor color) {
            this(text, color, null, null, Set.of());
        }
        /** Create a hyperlinked segment. */
        public static Segment hyperlink(String text, TextColor color, String url) {
            return new Segment(text, color, null, url, Set.of());
        }
        public static Segment hyperlink(String text, TextColor color, String url,
                                        Set<SGR> modifiers) {
            return new Segment(text, color, null, url, modifiers);
        }
    }

    record ToolOutputProjection(String content, TextColor color, boolean showAll,
                                String expandHint) {
        ToolOutputProjection {
            content = content == null ? "" : content;
            color = color == null ? TextColor.ANSI.DEFAULT : color;
            expandHint = expandHint == null ? "(ctrl+o to expand)" : expandHint;
        }
    }

    /** A logical source line, optionally carrying a width-reactive tool-output projection. */
    private record MarkdownProjection(String markdown, MarkdownRenderer renderer,
                                      boolean showBullet) {}

    public record StyledLine(List<Segment> segments, boolean isDivider, int wrapWidthInset,
                             ToolOutputProjection toolOutput,
                             MarkdownProjection markdownProjection) {
        public StyledLine(List<Segment> segments, boolean isDivider) {
            this(segments, isDivider, 0, null, null);
        }
        public StyledLine(List<Segment> segments, boolean isDivider, int wrapWidthInset) {
            this(segments, isDivider, wrapWidthInset, null, null);
        }
        public StyledLine(String text, TextColor color, boolean isDivider) {
            this(List.of(new Segment(text, color)), isDivider, 0, null, null);
        }
        private static StyledLine toolOutput(String content, TextColor color, boolean showAll,
                                             String expandHint) {
            ToolOutputProjection projection =
                new ToolOutputProjection(content, color, showAll, expandHint);
            return new StyledLine(List.of(new Segment(projection.content(), projection.color())),
                false, 0, projection, null);
        }
        private static StyledLine markdown(String markdown, MarkdownRenderer renderer,
                                           boolean showBullet) {
            MarkdownProjection projection = new MarkdownProjection(markdown, renderer, showBullet);
            return new StyledLine(List.of(), false, 0, null, projection);
        }
        static final StyledLine DIVIDER = new StyledLine(List.of(), true, 0, null, null);

        /** Plain text concatenation (for word-wrap width calculation). */
        public String text() {
            if (toolOutput != null) return toolOutput.content();
            if (markdownProjection != null) return markdownProjection.markdown();
            StringBuilder sb = new StringBuilder();
            for (Segment s : segments) sb.append(s.text());
            return sb.toString();
        }

        /** Backward-compat: first segment's color, or default. */
        public TextColor color() {
            if (toolOutput != null) return toolOutput.color();
            return segments.isEmpty() ? TextColor.ANSI.DEFAULT : segments.getFirst().color();
        }
    }

    /**
     * Hard-wraps a mixed-style line by terminal columns while preserving the style and
     * hyperlink carried by every segment. The previous renderer wrapped each overflowing
     * segment independently, which stranded prefixes such as {@code "  ⎿  "} on an empty
     * row before a long error body.
     */
    static List<StyledLine> wrapStyledSegments(List<Segment> segments, int width) {
        return wrapStyledSegments(segments, width, 0);
    }

    static List<StyledLine> wrapStyledSegments(List<Segment> segments, int width,
                                                int wrapWidthInset) {
        if (segments == null || segments.isEmpty()) {
            return List.of(new StyledLine("", TextColor.ANSI.DEFAULT, false));
        }
        if (width <= 0) return List.of(new StyledLine(List.copyOf(segments), false));

        List<StyledLine> hangingRows = wrapUniformToolResultBody(segments, width);
        if (hangingRows != null) return hangingRows;
        hangingRows = wrapBlackCircleBody(segments, width, wrapWidthInset);
        if (hangingRows != null) return hangingRows;

        List<StyledLine> rows = new ArrayList<>();
        List<Segment> row = new ArrayList<>();
        Segment hangingIndent = Strings.CS.equals(segments.getFirst().text(), Figures.RESULT_PREFIX)
            ? new Segment(Figures.RESULT_INDENT, segments.getFirst().color(),
                segments.getFirst().bgColor(), segments.getFirst().hyperlinkUrl(),
                segments.getFirst().modifiers())
            : null;
        int columns = 0;
        for (Segment source : segments) {
            String text = source.text() != null
                ? source.text().replaceAll("\\[[;\\d]*m", "") : "";
            StringBuilder chunk = new StringBuilder();
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                int charWidth = TerminalTextUtils.isCharDoubleWidth(c) ? 2 : 1;
                if (columns > 0 && columns + charWidth > width) {
                    appendStyledChunk(row, chunk, source);
                    rows.add(new StyledLine(List.copyOf(row), false));
                    row.clear();
                    columns = 0;
                    if (hangingIndent != null) {
                        row.add(hangingIndent);
                        columns = Figures.INDENT_COLS;
                    }
                }
                chunk.append(c);
                columns += charWidth;
            }
            appendStyledChunk(row, chunk, source);
        }
        if (!row.isEmpty()) rows.add(new StyledLine(List.copyOf(row), false));
        if (rows.isEmpty()) rows.add(new StyledLine("", TextColor.ANSI.DEFAULT, false));
        return rows;
    }

    /** Word-wrap a BLACK_CIRCLE-prefixed system/tool row with its two-column gutter. */
    private static List<StyledLine> wrapBlackCircleBody(List<Segment> segments, int width,
                                                         int wrapWidthInset) {
        String prefixText = Figures.BLACK_CIRCLE + " ";
        if (wrapWidthInset <= 0
                || !Strings.CS.equals(segments.getFirst().text(), prefixText)
                || segments.size() < 2 || width <= 2) {
            return null;
        }
        Segment bodyStyle = segments.get(1);
        for (int i = 2; i < segments.size(); i++) {
            Segment candidate = segments.get(i);
            if (!Objects.equals(bodyStyle.color(), candidate.color())
                    || !Objects.equals(bodyStyle.bgColor(), candidate.bgColor())
                    || !Objects.equals(bodyStyle.hyperlinkUrl(), candidate.hyperlinkUrl())
                    || !Objects.equals(bodyStyle.modifiers(), candidate.modifiers())) {
                return null;
            }
        }
        StringBuilder body = new StringBuilder();
        for (int i = 1; i < segments.size(); i++) {
            String text = segments.get(i).text();
            if (text != null) body.append(text.replaceAll("\\[[;\\d]*m", ""));
        }
        int prefixWidth = FormatUtils.displayWidth(prefixText);
        int bodyWidth = Math.max(1, width - wrapWidthInset);
        List<String> wrapped = wordWrapAtBoundaries(body.toString(), bodyWidth);
        List<StyledLine> rows = new ArrayList<>(wrapped.size());
        for (int i = 0; i < wrapped.size(); i++) {
            Segment prefix = i == 0
                ? segments.getFirst()
                : new Segment(" ".repeat(prefixWidth), segments.getFirst().color(),
                    segments.getFirst().bgColor(), segments.getFirst().hyperlinkUrl(),
                    segments.getFirst().modifiers());
            rows.add(new StyledLine(List.of(
                prefix,
                new Segment(wrapped.get(i), bodyStyle.color(), bodyStyle.bgColor(),
                    bodyStyle.hyperlinkUrl(), bodyStyle.modifiers())), false));
        }
        return rows;
    }

    /** Word-wrap the normal two-segment tool-result shape with a five-column gutter. */
    private static List<StyledLine> wrapUniformToolResultBody(List<Segment> segments, int width) {
        if (!Strings.CS.equals(segments.getFirst().text(), Figures.RESULT_PREFIX)
                || segments.size() < 2 || width <= Figures.INDENT_COLS) {
            return null;
        }
        Segment style = segments.get(1);
        for (int i = 2; i < segments.size(); i++) {
            Segment candidate = segments.get(i);
            if (!Objects.equals(style.color(), candidate.color())
                    || !Objects.equals(style.bgColor(), candidate.bgColor())
                    || !Objects.equals(style.hyperlinkUrl(), candidate.hyperlinkUrl())
                    || !Objects.equals(style.modifiers(), candidate.modifiers())) {
                return null;
            }
        }
        StringBuilder body = new StringBuilder();
        for (int i = 1; i < segments.size(); i++) {
            String text = segments.get(i).text();
            if (text != null) body.append(text.replaceAll("\\[[;\\d]*m", ""));
        }
        List<String> wrapped = wordWrapAtBoundaries(body.toString(), width - Figures.INDENT_COLS);
        List<StyledLine> rows = new ArrayList<>();
        for (int i = 0; i < wrapped.size(); i++) {
            Segment prefix = i == 0
                ? segments.getFirst()
                : new Segment(Figures.RESULT_INDENT, segments.getFirst().color(),
                    segments.getFirst().bgColor(), segments.getFirst().hyperlinkUrl(),
                    segments.getFirst().modifiers());
            rows.add(new StyledLine(List.of(
                prefix,
                new Segment(wrapped.get(i), style.color(), style.bgColor(), style.hyperlinkUrl(),
                    style.modifiers())), false));
        }
        return rows;
    }

    private static List<String> wordWrapAtBoundaries(String text, int width) {
        if (StringUtils.isEmpty(text)) return List.of("");
        List<String> rows = new ArrayList<>();
        String remaining = text.strip();
        while (!remaining.isEmpty()) {
            if (FormatUtils.displayWidth(remaining) <= width) {
                rows.add(remaining);
                break;
            }
            int columns = 0;
            int hardCut = 0;
            int lastWhitespace = -1;
            for (int i = 0; i < remaining.length();) {
                int codePoint = remaining.codePointAt(i);
                int charCount = Character.charCount(codePoint);
                String glyph = remaining.substring(i, i + charCount);
                int charWidth = FormatUtils.displayWidth(glyph);
                if (columns + charWidth > width) break;
                columns += charWidth;
                hardCut = i + charCount;
                if (Character.isWhitespace(codePoint)) lastWhitespace = i;
                i += charCount;
            }
            boolean endsAtWordBoundary = hardCut >= remaining.length()
                || Character.isWhitespace(remaining.codePointAt(hardCut));
            int cut = endsAtWordBoundary
                ? Math.max(1, hardCut)
                : lastWhitespace > 0 ? lastWhitespace : Math.max(1, hardCut);
            rows.add(remaining.substring(0, cut).stripTrailing());
            remaining = remaining.substring(cut).stripLeading();
        }
        return rows;
    }

    private static void appendStyledChunk(List<Segment> row, StringBuilder chunk, Segment source) {
        if (chunk.isEmpty()) return;
        row.add(new Segment(chunk.toString(), source.color(), source.bgColor(),
            source.hyperlinkUrl(), source.modifiers()));
        chunk.setLength(0);
    }

    private static List<StyledLine> projectToolOutput(ToolOutputProjection output, int width) {
        if (output.showAll()) return projectFullToolOutput(output, width);

        String formatted = ShellOutputFormatter.linkifyUrls(output.content());
        ShellOutputTruncator.PreparedContent prepared =
            ShellOutputTruncator.prepare(formatted, width);
        if (prepared.contentForWrapping().isEmpty()) return List.of();

        List<StyledLine> wrappedRows = new ArrayList<>();
        List<List<Segment>> logicalLines = AnsiToSegments.ansiToLines(
            prepared.contentForWrapping(), output.color());
        for (List<Segment> logicalLine : logicalLines) {
            if (logicalLine.isEmpty()) {
                wrappedRows.add(new StyledLine("", output.color(), false));
            } else {
                wrappedRows.addAll(wrapStyledSegments(logicalLine, prepared.wrapWidth()));
            }
        }
        ShellOutputTruncator.TruncatedRows<StyledLine> truncated =
            ShellOutputTruncator.truncateRows(wrappedRows, prepared);
        List<StyledLine> rows = new ArrayList<>(truncated.visibleRows().size() + 1);
        for (int i = 0; i < truncated.visibleRows().size(); i++) {
            String prefix = i == 0 ? Figures.RESULT_PREFIX : Figures.RESULT_INDENT;
            List<Segment> segments = new ArrayList<>(
                truncated.visibleRows().get(i).segments().size() + 1);
            segments.add(new Segment(prefix, LanternaTheme.welcomeDim()));
            segments.addAll(truncated.visibleRows().get(i).segments());
            rows.add(new StyledLine(List.copyOf(segments), false));
        }
        if (truncated.remainingRows() > 0) {
            rows.add(new StyledLine(List.of(new Segment(
                Figures.RESULT_INDENT + "… +" + truncated.remainingRows() + " lines "
                    + output.expandHint(),
                LanternaTheme.welcomeDim())), false));
        }
        return rows;
    }

    private static List<StyledLine> projectFullToolOutput(ToolOutputProjection output, int width) {
        String normalized = output.content().replace("\r\n", "\n").replace("\r", "\n")
            .stripTrailing();
        if (normalized.isEmpty()) return List.of();

        List<List<Segment>> logicalLines = AnsiToSegments.ansiToLines(
            ShellOutputFormatter.linkifyUrls(normalized), output.color());
        List<StyledLine> rows = new ArrayList<>();
        for (int i = 0; i < logicalLines.size(); i++) {
            String prefix = i == 0 ? Figures.RESULT_PREFIX : Figures.RESULT_INDENT;
            List<Segment> segments = new ArrayList<>(logicalLines.get(i).size() + 1);
            segments.add(new Segment(prefix, LanternaTheme.welcomeDim()));
            segments.addAll(logicalLines.get(i));
            rows.addAll(wrapStyledSegments(segments, width));
        }
        return rows;
    }

    /**
     * Returns the immutable, width-dependent wrapped transcript. Typing only
     * dirties the InputPanel, so repeated GUI frames hit this cache instead of
     * cloning and word-wrapping the entire conversation for every character.
     */
    private List<StyledLine> displayRowsForWidth(int width) {
        long revision = renderRevision.get();
        CachedRenderLayout cached = cachedRenderLayout;
        if (cached.revision() == revision && cached.width() == width) {
            return cached.rows();
        }
        synchronized (renderCacheMonitor) {
            revision = renderRevision.get();
            cached = cachedRenderLayout;
            if (cached.revision() == revision && cached.width() == width) {
                return cached.rows();
            }

            List<StyledLine> snapshot;
            LogicalMessage selectedMessage;
            long snapshotRevision;
            lock.readLock().lock();
            try {
                snapshot = new ArrayList<>(lines);
                snapshot.addAll(transientTail);
                selectedMessage = selectedLogicalMessageLocked().orElse(null);
                snapshotRevision = renderRevision.get();
            } finally {
                lock.readLock().unlock();
            }

            int reusableSources = reusableSourcePrefix(
                cached, snapshot, selectedMessage, width);
            List<List<StyledLine>> sourceRows = new ArrayList<>(snapshot.size());
            if (reusableSources > 0) {
                sourceRows.addAll(cached.sourceRows().subList(0, reusableSources));
            }
            for (int sourceLine = reusableSources; sourceLine < snapshot.size(); sourceLine++) {
                List<StyledLine> rows = projectSourceLine(
                    snapshot.get(sourceLine), sourceLine, selectedMessage, width);
                sourceRows.add(rows);
                sourceProjectionBuildCount++;
            }

            List<List<StyledLine>> immutableSourceRows = List.copyOf(sourceRows);
            List<StyledLine> immutableRows = new ProjectedRowsView(immutableSourceRows);
            renderLayoutBuildCount++;
            if (renderRevision.get() == snapshotRevision) {
                cachedRenderLayout = new CachedRenderLayout(
                    snapshotRevision, width, List.copyOf(snapshot), selectedMessage,
                    immutableSourceRows, immutableRows);
            }
            return immutableRows;
        }
    }

    private record ViewportProjection(List<StyledLine> rows, int anchorRowCount,
                                      int startDisplayRow, int transcriptRowCount) {}

    private ViewportProjection viewportProjection(int width, int height) {
        List<StyledLine> displayRows = displayRowsForWidth(width);
        int safeHeight = Math.max(0, height);
        int visibleCount = Math.min(displayRows.size(), safeHeight);
        int startDisplayRow = Math.max(0,
            displayRows.size() - visibleCount - scrollOffset);

        List<StyledLine> detachedAnchor = detachedHistoryTopAnchorRows(width, startDisplayRow);
        int anchorRowCount = Math.min(detachedAnchor.size(), safeHeight);
        int transcriptCapacity = Math.max(0, safeHeight - anchorRowCount);
        int transcriptRowCount = Math.min(transcriptCapacity,
            Math.max(0, displayRows.size() - startDisplayRow));

        List<StyledLine> viewportRows = new ArrayList<>(anchorRowCount + transcriptRowCount);
        viewportRows.addAll(detachedAnchor.subList(0, anchorRowCount));
        viewportRows.addAll(displayRows.subList(
            startDisplayRow, startDisplayRow + transcriptRowCount));
        return new ViewportProjection(List.copyOf(viewportRows), anchorRowCount,
            startDisplayRow, transcriptRowCount);
    }

    private List<StyledLine> detachedHistoryTopAnchorRows(int width, int startDisplayRow) {
        if (startDisplayRow != 0) return List.of();
        List<StyledLine> anchorSnapshot;
        lock.readLock().lock();
        try {
            if (historyTopAnchorInTranscript || historyTopAnchor.isEmpty()) return List.of();
            anchorSnapshot = historyTopAnchor;
        } finally {
            lock.readLock().unlock();
        }
        List<StyledLine> projected = new ArrayList<>();
        for (StyledLine line : anchorSnapshot) {
            projected.addAll(projectSourceLine(line, -1, null, width));
        }
        return List.copyOf(projected);
    }

    private void adjustHistoryTopAnchorForReplacementLocked(int start, int count, int delta) {
        if (!historyTopAnchorInTranscript) return;
        int replacedEnd = start + count;
        int anchorEnd = historyTopAnchorStart + historyTopAnchorCount;
        if (replacedEnd <= historyTopAnchorStart) {
            historyTopAnchorStart += delta;
        } else if (start < anchorEnd) {
            historyTopAnchorInTranscript = false;
            historyTopAnchorStart = -1;
        }
    }

    private static int reusableSourcePrefix(CachedRenderLayout cached,
                                            List<StyledLine> snapshot,
                                            LogicalMessage selectedMessage,
                                            int width) {
        if (cached.width() != width
                || !Objects.equals(cached.selectedMessage(), selectedMessage)) {
            return 0;
        }
        int limit = Math.min(snapshot.size(), cached.sourceLines().size());
        int prefix = 0;
        while (prefix < limit
                && snapshot.get(prefix) == cached.sourceLines().get(prefix)) {
            prefix++;
        }
        return Math.min(prefix, cached.sourceRows().size());
    }

    private static List<StyledLine> projectSourceLine(StyledLine sl, int sourceLine,
                                                       LogicalMessage selectedMessage,
                                                       int width) {
        List<StyledLine> renderedRows;
        if (sl.toolOutput() != null) {
            renderedRows = projectToolOutput(sl.toolOutput(), width);
        } else if (sl.markdownProjection() != null) {
            renderedRows = projectMarkdown(sl.markdownProjection(), width);
        } else if (sl.isDivider()) {
            renderedRows = List.of(sl);
        } else {
            boolean hasMetadata = false;
            for (Segment s : sl.segments()) {
                if (s.bgColor() != null || !s.modifiers().isEmpty()
                        || s.hyperlinkUrl() != null) {
                    hasMetadata = true;
                    break;
                }
            }
            if (sl.segments().size() > 1 || hasMetadata) {
                renderedRows = wrapStyledSegments(
                    sl.segments(), width, sl.wrapWidthInset());
            } else {
                int wrapWidth = Math.max(1, width - sl.wrapWidthInset());
                List<String> wrapped = wordWrap(sl.text(), wrapWidth);
                List<StyledLine> rows = new ArrayList<>(wrapped.size());
                for (String row : wrapped) {
                    rows.add(new StyledLine(row, sl.color(), false));
                }
                renderedRows = rows;
            }
        }
        boolean selected = selectedMessage != null
            && sourceLine >= selectedMessage.startLine()
            && sourceLine <= selectedMessage.endLine();
        if (selected && !sl.isDivider()) {
            renderedRows = withRowBackground(
                renderedRows, LanternaTheme.messageActionsBackground());
        }
        return List.copyOf(renderedRows);
    }

    private static List<StyledLine> projectMarkdown(MarkdownProjection projection, int width) {
        int markdownWidth = Math.max(1, width - 2);
        String rendered = projection.renderer().render(projection.markdown(), markdownWidth);
        List<List<Segment>> parsed = AnsiToSegments.ansiToLines(
            rendered, TextColor.ANSI.DEFAULT);
        int last = parsed.size();
        while (last > 0 && parsed.get(last - 1).isEmpty()) last--;
        if (last == 0) return List.of();

        List<StyledLine> rows = new ArrayList<>(last);
        Segment gutter = new Segment("  ", LanternaTheme.inputText());
        for (int i = 0; i < last; i++) {
            List<Segment> content = parsed.get(i);
            if (i > 0 && content.isEmpty()) {
                rows.add(new StyledLine("", TextColor.ANSI.DEFAULT, false));
                continue;
            }
            Segment prefix = i == 0
                ? new Segment(projection.showBullet() ? Figures.BLACK_CIRCLE + " " : "  ",
                    LanternaTheme.inputText())
                : gutter;
            rows.addAll(wrapMarkdownRow(prefix, content, markdownWidth));
        }
        return List.copyOf(rows);
    }

    /**
     * Word-wraps one already-rendered markdown line to {@code bodyWidth} columns.
     * {@link MarkdownRenderer} only wraps tables and code blocks to width;
     * plain paragraph/text nodes may be emitted unwrapped. This safety net keeps
     * a long unbroken paragraph from overrunning the terminal.
     */
    private static List<StyledLine> wrapMarkdownRow(Segment prefix, List<Segment> content, int bodyWidth) {
        int contentWidth = 0;
        for (Segment s : content) contentWidth += FormatUtils.displayWidth(s.text());
        if (contentWidth <= bodyWidth) {
            List<Segment> line = new ArrayList<>(content.size() + 1);
            line.add(prefix);
            line.addAll(content);
            return List.of(new StyledLine(List.copyOf(line), false));
        }

        StringBuilder flat = new StringBuilder();
        List<Segment> styleAt = new ArrayList<>();
        for (Segment s : content) {
            String text = s.text() == null ? "" : s.text();
            for (int i = 0; i < text.length(); i++) {
                flat.append(text.charAt(i));
                styleAt.add(s);
            }
        }
        String text = flat.toString();
        int n = text.length();
        Segment indent = new Segment(" ".repeat(FormatUtils.displayWidth(prefix.text())),
            prefix.color(), prefix.bgColor(), prefix.hyperlinkUrl(), prefix.modifiers());

        List<StyledLine> rows = new ArrayList<>();
        int pos = 0;
        while (pos < n && Character.isWhitespace(text.charAt(pos))) pos++;
        boolean first = true;
        while (pos < n) {
            int columns = 0;
            int cut = pos;
            int lastWhitespace = -1;
            int i = pos;
            while (i < n) {
                char c = text.charAt(i);
                int charWidth = TerminalTextUtils.isCharDoubleWidth(c) ? 2 : 1;
                if (columns + charWidth > bodyWidth) break;
                columns += charWidth;
                cut = i + 1;
                if (Character.isWhitespace(c)) lastWhitespace = i;
                i++;
            }
            boolean endsAtBoundary = cut >= n || Character.isWhitespace(text.charAt(cut));
            int breakAt = endsAtBoundary
                ? Math.max(pos + 1, cut)
                : lastWhitespace > pos ? lastWhitespace : Math.max(pos + 1, cut);
            int trimmedEnd = breakAt;
            while (trimmedEnd > pos && Character.isWhitespace(text.charAt(trimmedEnd - 1))) trimmedEnd--;

            List<Segment> row = new ArrayList<>();
            row.add(first ? prefix : indent);
            appendStyledRange(row, text, styleAt, pos, trimmedEnd);
            rows.add(new StyledLine(List.copyOf(row), false));

            pos = breakAt;
            while (pos < n && Character.isWhitespace(text.charAt(pos))) pos++;
            first = false;
        }
        if (rows.isEmpty()) {
            rows.add(new StyledLine(List.of(prefix), false));
        }
        return rows;
    }

    private static void appendStyledRange(List<Segment> row, String text, List<Segment> styleAt,
                                           int start, int end) {
        int i = start;
        while (i < end) {
            Segment style = styleAt.get(i);
            int j = i;
            StringBuilder chunk = new StringBuilder();
            while (j < end && styleAt.get(j) == style) {
                chunk.append(text.charAt(j));
                j++;
            }
            row.add(new Segment(chunk.toString(), style.color(), style.bgColor(),
                style.hyperlinkUrl(), style.modifiers()));
            i = j;
        }
    }

    List<StyledLine> displayRowsForTest(int width) {
        return displayRowsForWidth(width);
    }

    List<StyledLine> viewportRowsForTest(int width, int height) {
        return viewportProjection(width, height).rows();
    }

    long renderLayoutBuildCountForTest() {
        synchronized (renderCacheMonitor) {
            return renderLayoutBuildCount;
        }
    }

    long sourceProjectionBuildCountForTest() {
        synchronized (renderCacheMonitor) {
            return sourceProjectionBuildCount;
        }
    }

    boolean usesProjectedRowsViewForTest(int width) {
        return displayRowsForWidth(width) instanceof ProjectedRowsView;
    }

    private static List<StyledLine> withRowBackground(
            List<StyledLine> rows, TextColor background) {
        List<StyledLine> highlighted = new ArrayList<>(rows.size());
        for (StyledLine row : rows) {
            List<Segment> segments = new ArrayList<>(Math.max(1, row.segments().size()));
            if (row.segments().isEmpty()) {
                segments.add(new Segment("", TextColor.ANSI.DEFAULT, background));
            } else {
                for (Segment segment : row.segments()) {
                    segments.add(new Segment(segment.text(), segment.color(), background,
                        segment.hyperlinkUrl(), segment.modifiers()));
                }
            }
            highlighted.add(new StyledLine(List.copyOf(segments), false, row.wrapWidthInset()));
        }
        return highlighted;
    }

    private static List<String> wordWrap(String text, int width) {
        List<String> result = new ArrayList<>();
        if (StringUtils.isEmpty(text)) { result.add(""); return result; }
        if (width <= 0) { result.add(text); return result; }
        String stripped = text.replaceAll("\\[[;\\d]*m", "");
        int colWidth = TerminalTextUtils.getColumnWidth(stripped);
        if (colWidth <= width) { result.add(text); return result; }
        StringBuilder current = new StringBuilder();
        int currentCols = 0;
        for (int i = 0; i < stripped.length(); i++) {
            char c = stripped.charAt(i);
            int charWidth = TerminalTextUtils.isCharDoubleWidth(c) ? 2 : 1;
            if (currentCols + charWidth > width) {
                result.add(current.toString());
                current.setLength(0);
                currentCols = 0;
            }
            current.append(c);
            currentCols += charWidth;
        }
        if (!current.isEmpty()) result.add(current.toString());
        return result;
    }

    // ── Renderer ──────────────────────────────────────────────────────────

    private class MessageRenderer implements ComponentRenderer<MessagePanel> {

        @Override
        public TerminalSize getPreferredSize(MessagePanel c) {
            return c.calculatePreferredSize();
        }

        @Override
        public void drawComponent(TextGUIGraphics g, MessagePanel c) {
            TerminalSize size = g.getSize();
            int width  = size.getColumns();
            int height = size.getRows();

            // Clear background
            g.fill(' ');

            ViewportProjection viewport = viewportProjection(width, height);
            List<StyledLine> visibleRows = viewport.rows();
            if (visibleRows.isEmpty()) return;

            // Capture visible-row text snapshot for Selection.getSelectedText.
            // Index 0 = top of viewport, matches what mouse events see.
            // Done BEFORE the per-row draw loop so empty rows in [endRow, height)
// map to "" through the snapshotVisibleRows.get(y) null guard.
            List<String> frameRows = new ArrayList<>(visibleRows.size());
            for (StyledLine row : visibleRows) {
                frameRows.add(row.isDivider() ? "" : row.text());
            }
            lastFrameRowTexts = frameRows;

            for (int y = 0; y < visibleRows.size(); y++) {
                StyledLine row = visibleRows.get(y);
                if (row.isDivider()) {
                    drawDivider(g, y, width);
                } else {
                    // Route to drawSegments whenever multi-segment OR has any background
                    boolean useSegments = row.segments().size() > 1;
                    if (!useSegments) {
                        for (Segment s : row.segments()) {
                            if (s.bgColor() != null || !s.modifiers().isEmpty()
                                    || s.hyperlinkUrl() != null) {
                                useSegments = true;
                                break;
                            }
                        }
                    }
                    if (useSegments) drawSegments(g, y, row.segments(), width);
                    else             drawLine(g, y, row.text(), row.color(), width);
                }
            }


        }

        private void drawLine(TextGUIGraphics g, int y, String text, TextColor color, int width) {
            int x = 0;
            // Track OSC 8 hyperlink URL — extracted from embedded escape codes
            // (MarkdownRenderer emits \e]8;;url\e\text\e]8;;\e\). The ESC chars
            // are control chars that drawLine would normally skip; we parse
            // them here to capture the URL and attach it to the visible text
            // via TextCharacter.withHyperlink so the Screen's diff loop emits
            // the proper OSC 8 sequence.
            String currentHyperlink = null;
            for (int i = 0; i < text.length() && x < width; i++) {
                char c = text.charAt(i);
                // Detect OSC 8 opener: ESC ] 8 ; ; URL ESC \
                if (c == '' && i + 1 < text.length() && text.charAt(i + 1) == ']') {
                    // Look for "8;;...ESC \"
                    if (i + 3 < text.length() && text.charAt(i + 2) == '8'
                        && text.charAt(i + 3) == ';') {
                        int urlStart = i + 4;
                        // Skip second semicolon
                        if (urlStart < text.length() && text.charAt(urlStart) == ';') urlStart++;
                        // Find terminator ESC \
                        int end = -1;
                        int endLen = 1;
                        for (int j = urlStart; j < text.length(); j++) {
                            if (text.charAt(j) == '') { end = j; break; }
                            if (j + 1 < text.length() && text.charAt(j) == '' && text.charAt(j + 1) == '\\') {
                                end = j; endLen = 2; break;
                            }
                        }
                        if (end > urlStart) {
                            currentHyperlink = text.substring(urlStart, end);
                            i = end + endLen - 1; // skip past ESC \
                            continue;
                        }
                    }
                    // Detect OSC 8 closer: ESC ] 8 ; ; ESC \  (empty URL)
                    if (i + 3 < text.length() && text.charAt(i + 2) == '8'
                        && text.charAt(i + 3) == ';' && i + 4 < text.length()
                        && text.charAt(i + 4) == ';' && i + 5 < text.length()
                        && text.charAt(i + 5) == '' && i + 6 < text.length()
                        && text.charAt(i + 6) == '\\') {
                        currentHyperlink = null;
                        i = i + 6; // skip past ESC \
                        continue;
                    }
                    // Other ESC sequences (ANSI color codes) — skip the ESC byte
                    // and let the next iteration handle the rest. The CSI/OSC
                    // body will be skipped as control chars or processed above.
                    continue;
                }
                // Skip control characters (Lanterna throws on \n, \t, etc.)
                if (Character.isISOControl(c)) continue;
                TextCharacter tc = TextCharacter.fromCharacter(c, color, TextColor.ANSI.DEFAULT);
                if (StringUtils.isNotEmpty(currentHyperlink)) {
                    tc = tc.withHyperlink(currentHyperlink);
                }
                g.setCharacter(x, y, tc);
                x += TerminalTextUtils.isCharDoubleWidth(c) ? 2 : 1;
            }
        }

        private void drawDivider(TextGUIGraphics g, int y, int width) {
            TextColor c = LanternaTheme.divider();
            for (int x = 0; x < width; x++) {
                g.setCharacter(x, y, TextCharacter
                    .fromCharacter('─', c, TextColor.ANSI.DEFAULT));
            }
        }

        private void drawSegments(TextGUIGraphics g, int y, List<Segment> segments, int width) {
            int x = 0;
// Determine row background: if any segment specifies one, fill the whole row with it
// (so trailing whitespace also gets highlighted).
            TextColor rowBg = null;
            for (Segment s : segments) {
                if (s.bgColor() != null) { rowBg = s.bgColor(); break; }
            }
            if (rowBg != null) {
                // Fill full width with bg — paddingRight={1} means the bg extends

                for (int fx = 0; fx < width; fx++) {
                    g.setCharacter(fx, y, TextCharacter
                        .fromCharacter(' ', TextColor.ANSI.DEFAULT, rowBg));
                }
            }
            for (Segment seg : segments) {
                String text = seg.text();
                TextColor color = seg.color();
                TextColor bg = seg.bgColor() != null ? seg.bgColor()
                              : (rowBg != null ? rowBg : TextColor.ANSI.DEFAULT);
                String url = seg.hyperlinkUrl();
                // Search highlight: if query is active, highlight matching substrings
                if (StringUtils.isNotEmpty(searchHighlightQuery)) {
                    String lowerText = text.toLowerCase(Locale.ROOT);
                    int qLen = searchHighlightQuery.length();
                    int searchStart = 0;
                    for (int i = 0; i < text.length() && x < width; ) {
                        int matchIdx = lowerText.indexOf(searchHighlightQuery, searchStart);
                        if (matchIdx < 0 || matchIdx >= text.length()) {
                            // No more matches — render remaining chars normally
                            for (int j = i; j < text.length() && x < width; j++) {
                                char c = text.charAt(j);
                                if (!Character.isISOControl(c)) {
                                    TextCharacter tc = styledCharacter(c, color, bg, seg.modifiers());
                                    if (StringUtils.isNotEmpty(url)) tc = tc.withHyperlink(url);
                                    g.setCharacter(x, y, tc);
                                    x += TerminalTextUtils.isCharDoubleWidth(c) ? 2 : 1;
                                }
                            }
                            break;
                        }
                        // Render chars before the match normally
                        for (int j = i; j < matchIdx && x < width; j++) {
                            char c = text.charAt(j);
                            if (!Character.isISOControl(c)) {
                                TextCharacter tc = styledCharacter(c, color, bg, seg.modifiers());
                                if (StringUtils.isNotEmpty(url)) tc = tc.withHyperlink(url);
                                g.setCharacter(x, y, tc);
                                x += TerminalTextUtils.isCharDoubleWidth(c) ? 2 : 1;
                            }
                        }
                        // Render matched chars with highlighted color
                        TextColor hlColor = LanternaTheme.claude();
                        TextColor hlBg = LanternaTheme.userQueryBg();
                        for (int j = matchIdx; j < matchIdx + qLen && x < width; j++) {
                            char c = text.charAt(j);
                            if (!Character.isISOControl(c)) {
                                TextCharacter tc = styledCharacter(c, hlColor, hlBg, seg.modifiers());
                                if (StringUtils.isNotEmpty(url)) tc = tc.withHyperlink(url);
                                g.setCharacter(x, y, tc);
                                x += TerminalTextUtils.isCharDoubleWidth(c) ? 2 : 1;
                            }
                        }
                        i = matchIdx + qLen;
                        searchStart = i;
                    }
                } else {
                    // Normal rendering (no search highlight)
                    for (int i = 0; i < text.length() && x < width; i++) {
                        char c = text.charAt(i);
                        if (Character.isISOControl(c)) continue;
                        TextCharacter tc = styledCharacter(c, color, bg, seg.modifiers());
                        if (StringUtils.isNotEmpty(url)) {
                            tc = tc.withHyperlink(url);
                        }
                        g.setCharacter(x, y, tc);
                        x += TerminalTextUtils.isCharDoubleWidth(c) ? 2 : 1;
                    }
                }
            }
        }

        private static TextCharacter styledCharacter(char c, TextColor color, TextColor background,
                                                     Set<SGR> modifiers) {
            TextCharacter character = TextCharacter.fromCharacter(c, color, background);
            return modifiers == null || modifiers.isEmpty()
                ? character : character.withModifiers(modifiers);
        }

    }
}
