package com.claudecode.ui.lanterna.transcript;

import org.apache.commons.lang3.Strings;
import com.claudecode.core.text.FormatUtils;
import com.claudecode.core.text.StringUtils;

import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.ContentBlock;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.constants.Figures;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.ThinkingBlock;
import com.claudecode.core.message.ToolResultBlock;
import com.claudecode.keybindings.KeybindingHints;
import com.claudecode.keybindings.UserKeybindingsStore;
import com.claudecode.tools.mcp.McpCollapseClassifier;
import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TextColor;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.claudecode.ui.lanterna.theme.LanternaTheme;


/**
 * Projects a run of consecutive read/search, MCP, and thinking messages onto a single collapsed
 * group line plus an optional {@code ⎿} detail row, replacing the per-tool transcript entries the
 * dispatcher would otherwise emit.
 *
 * <ul>
 *   <li>Covers {@code src/components/messages/CollapsedToolUseGroup.tsx} — the collapsed
 *       read/search group: header counts, active/finished verb forms, the blinking bullet, the
 *       {@code ⎿} hint row, and the ctrl+o expansion registration.</li>
 *   <li>Covers the group reducer that walks the transcript and folds adjacent collapsible tool
 *       calls into one projection, including the MCP server-name and query hints.</li>
 *   <li>Covers {@code src/utils/git.ts}'s post-Bash operation detection — the commit/push/merge/PR
 *       badge appended after a Bash tool result.</li>
 *   <li><b>Thinking display is ported from the 2.1.236 bundle, not 197</b> (the rest of this
 *       repository's terminal UI baseline). 197's collapsed group has no thinking branch at all.
 *       From 236: {@code VBp} (the collapsed_read_search reducer) with its {@code YDa} initializer
 *       and {@code nIS} projection supply {@code thoughtForMs} — {@code += min(Δtimestamp, $ui)}
 *       with {@code $ui = 600000} — and {@code latestThinkingSummary}, which every {@code tool_use}
 *       resets; {@code XxS} restricts the source to a non-blank {@code thinking} block at
 *       {@code content[0]}; {@code N3l} emits the {@code Thinking for}/{@code Thought for} header
 *       segment ahead of every count part; {@code DCh} is the one-second live clock; {@code G0h}
 *       holds the summary {@code egw = 3000} ms past its reset; and {@code ngw} clamps the summary
 *       row to {@code tgw = 10} wrapped lines at {@code width - u7i} columns.</li>
 * </ul>
 *
 * <p>236's {@code isLiveBriefTurn} has no Java counterpart and is treated as constantly false, and
 * its {@code Ps()} fullscreen gate on the live clock is treated as constantly true because this
 * Lanterna TUI is always fullscreen-equivalent.
 */
public class MessageCollapser {

    private static final Logger log = LoggerFactory.getLogger(MessageCollapser.class);




    static final Set<String> READ_SEARCH_TOOLS = Set.of("Read", "Grep", "Glob", "LS");

    /** 236's {@code $ui}: the most a single inter-message gap may contribute to the total. */
    private static final long MAX_THINKING_SPAN_MS = 600_000;
    /** 236's {@code egw}: how long the {@code ⎿} row keeps a summary a tool call has reset. */
    private static final long SUMMARY_HOLD_MS = 3_000;
    /** 236's {@code tgw}: the summary row's wrapped-line budget. */
    private static final int SUMMARY_MAX_LINES = 10;
    /** 236's {@code DCh} redraws on a {@code Cg(1000)} interval. */
    private static final long CLOCK_TICK_MS = 1_000;

    /**
     * Drives the live "Thinking for 24s" counter. One shared daemon thread for every collapser,
     * mirroring {@link MessagePanel}'s blink scheduler: the panel serialises its own mutations, so
     * repainting a group header off the event thread is safe.
     */
    private static final ScheduledExecutorService THINKING_CLOCK_SCHEDULER =
        Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "thinking-clock");
            thread.setDaemon(true);
            return thread;
        });

    private final LanternaMessageDispatcher downstream;
    private boolean verbose;
    private boolean showAll; // Ctrl+E: force-expand, bypass all collapses
    private UserKeybindingsStore keybindingsStore;

    private boolean loading;

    // ── Per-group collapse state ─────────────────────────────────────────────

    // Buffer holds pending (tool-call, tool-result) SDKMessage pairs accumulated
    // this turn.  Non-tool messages flush the buffer first.
    private final List<SDKMessage> pendingToolMessages = new ArrayList<>();
    private final List<SDKMessage.StreamEvent> readSearchMessages = new ArrayList<>();
    private long readSearchGroupSequence;

    // Counter of consecutive Read/Grep/Glob/LS tool calls in the current run.
    private int readSearchRun = 0;
    private final Set<String> readFilePaths = new LinkedHashSet<>();
    private int readOperationCount = 0;
    private final Deque<String> unresolvedToolIds = new ArrayDeque<>();
    private final Map<String, String> collapsedToolNamesById = new LinkedHashMap<>();
    private long collapsedToolSequence;
    private boolean collapsedGroupHasError;


    private String               latestDisplayHint = null;
    private int                  mcpCallCount      = 0;
    private final Set<String>    mcpServerNames    = new LinkedHashSet<>();
    private int                  memoryReadCount   = 0;
    private int                  memoryWriteCount  = 0;
    private int                  searchCount       = 0;  // Grep/Glob pattern searches
    private int                  listCount         = 0;  // LS calls (split from readSearchRun for active-form phrasing)

    // ── Thinking projection (2.1.236) ────────────────────────────────────────

    /** 236's {@code thoughtForMs}: the group's accumulated, per-gap-clamped thinking time. */
    private long    thoughtForMs           = 0;
    /** 236's {@code latestThinkingSummary}: live value, reset to absent by any tool call. */
    private String  thinkingSummary        = null;
    /** {@code G0h}'s retained value — the last non-null summary, kept past its reset. */
    private String  heldSummary            = null;
    private long    heldSummaryExpiresAtMs = 0;
    /** Anchor for the live clock's "time since the last thinking block" term. */
    private long    lastThinkingAtMs       = 0;
    /** 236's {@code s}: the previously seen message's timestamp, the Δ baseline. */
    private Instant previousMessageAt      = null;

    private LongSupplier clock = System::currentTimeMillis;
    private ScheduledFuture<?> thinkingClock;
    private String renderedThinkingDuration = null;

    /**
     * Serialises the group repaint between the event thread and the clock ticker. The GUI thread
     * never acquires it, so it can never invert against the panel's own lock.
     */
    private final Object renderLock = new Object();

/**
     * Last non-read-search tool name (Bash/Edit/Write/…) — used by buildActiveGroupPhrase to render
     * "Running $ <cmd>…" when no read/search/MCP run is active.
     */
    private String               lastNonReadSearchToolName = null;

/**
     * Listener fired whenever counters mutate — wired to SpinnerComponent::setSpinnerTip so the
     * dialog-above-spinner shows live progress.
     */
    private Consumer<String> phraseListener;

    // Index of the live "⏺ Reading…" indicator line in the panel (-1 = not shown).
    // Set when the first READ_SEARCH tool_call_start arrives; replaced by the final
    // summary in flushReadSearchRun().
    private int activeRunLineIdx = -1;
    private int activeRunRowCount = 0;

// ── Git operation tracking ─────────────────────────────────────────────.
    private String lastBashCommand = null;
    private String gitCommitSha    = null;
    private String gitCommitKind   = null;  // "committed"/"amended"/"cherry-picked"
    private String gitPushBranch   = null;
    private String gitMergeRef     = null;
    private String gitMergeAction  = null;  // "merged"/"rebased onto"
    private int    gitPrNumber     = 0;
    private String gitPrAction     = null;  // "created"/"merged"/"edited"/"closed"

    public MessageCollapser(LanternaMessageDispatcher downstream, boolean verbose) {
        this.downstream = downstream;
        this.verbose    = verbose;
        this.showAll    = false;
    }

    public void setVerbose(boolean verbose) { this.verbose = verbose; }
    public void setShowAll(boolean showAll) { this.showAll = showAll; }
    public void setKeybindingsStore(UserKeybindingsStore store) { this.keybindingsStore = store; }

    /** Test seam for the live thinking clock — production always reads the wall clock. */
    void setClock(LongSupplier clock) { this.clock = clock; }


    public void setLoading(boolean loading, MessagePanel panel) {
        this.loading = loading;
        if (!loading && (readSearchRun > 0 || mcpCallCount > 0 || hasThinking())) {
            flushReadSearchRun(panel);
        }
        emitPhrase();
    }

/**
     * Wire a listener (typically {@code SpinnerComponent::setSpinnerTip}) — fires on every counter
     * mutation with the current active-group phrase, or {@code ""} when nothing is running.
     */
    public void setPhraseChangeListener(Consumer<String> listener) {
        this.phraseListener = listener;
    }

    private void emitPhrase() {
        if (phraseListener != null) phraseListener.accept(buildActiveGroupPhrase());
    }

    /**
     * Build present-continuous summary for the currently-running tool group.
     */
    public String buildActiveGroupPhrase() {
        if ((readSearchRun > 0 || mcpCallCount > 0)
                && unresolvedToolIds.isEmpty() && !loading) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        if (memoryReadCount > 0) {
            parts.add("recalling " + memoryReadCount + " "
                + (memoryReadCount == 1 ? "memory" : "memories"));
        }
        if (memoryWriteCount > 0) {
            parts.add("writing " + memoryWriteCount + " "
                + (memoryWriteCount == 1 ? "memory" : "memories"));
        }
        if (searchCount > 0) {
            parts.add("searching for " + searchCount + " pattern" + (searchCount > 1 ? "s" : ""));
        }
        int fileCount = regularReadCount();
        if (fileCount > 0) {
            parts.add("reading " + fileCount + " file" + (fileCount > 1 ? "s" : ""));
        }
        if (listCount > 0) {
            parts.add("listing " + listCount + (listCount > 1 ? " directories" : " directory"));
        }
        if (mcpCallCount > 0) {
            String srv = mcpServerNames.isEmpty()
                ? "MCP" : String.join(", ", mcpServerNames);
            parts.add("calling " + srv + (mcpCallCount > 1
                ? " " + mcpCallCount + " times" : ""));
        }
        if (parts.isEmpty() && lastNonReadSearchToolName != null) {
            if (Strings.CS.equals("Bash", lastNonReadSearchToolName) && lastBashCommand != null) {
                String c = lastBashCommand.length() > 50
                    ? FormatUtils.truncate(lastBashCommand, 50)
                    : lastBashCommand;
                parts.add("running $ " + c);
            } else {
                parts.add("running " + lastNonReadSearchToolName);
            }
        }
        if (parts.isEmpty()) return "";
        String joined = String.join(", ", parts);
        String first = Character.toUpperCase(joined.charAt(0)) + joined.substring(1);
        return first + "… " + KeybindingHints.expand(keybindingsStore);
    }

    public void resetTurn() {
        flushPending(null);
        downstream.resetTurn();
    }

    /** Returns true when no tool calls are in-flight — safe to trigger replay. */
    public boolean isIdle() {
        return downstream.isIdle();
    }

    /**
     * Process one message — either buffer it (if a tool event) or flush
     * the pending buffer and pass it straight through.
     */
    public void dispatch(SDKMessage message, MessagePanel panel) {
        if (verbose || showAll) {
            downstream.dispatch(message, panel);
            return;
        }

        if (message instanceof SDKMessage.StreamEvent event) {
            String et = event.eventType();
            if (Strings.CS.equals("tool_call_start", et)) {
                handleToolCallStart(event, panel);
                return;
            }
            if (Strings.CS.equals("tool_result_success", et) || Strings.CS.equals("tool_result_error", et)) {
                handleToolResult(event, panel);
                return;
            }
            // Streaming preview events — forward directly to downstream WITHOUT flushing.
            if (Strings.CS.equals("tool_streaming_start", et) || Strings.CS.equals("tool_streaming_done", et)) {
                String info = event.data() instanceof String s ? s : "";
                String toolName = Strings.CS.contains(info, "|") ? info.split("\\|", 2)[0] : info;
                if (isCollapsibleTool(toolName)) {
                    if (Strings.CS.equals("tool_streaming_done", et)) {
                        handleToolCallStart(new SDKMessage.StreamEvent(
                            "tool_call_start", info), panel);
                    }
                    return;
                }
                downstream.dispatch(event, panel);
                return;
            }
            // Content events mark the start of Claude's response — flush any pending
// tool output first.  Preserves the compatibility rule where tool results are flushed before text.
            if (Strings.CS.equals("content_block_delta", et) || Strings.CS.equals("content_block_start", et)
                    || Strings.CS.equals("content_block_stop", et)) {
                if (log.isDebugEnabled()) {
                    log.debug("[COLLAPSER] content_block_delta: readSearchRun={} data={}", readSearchRun,
                        event.data() instanceof String s
                            ? s.substring(0, Math.min(40, s.length())) : event.data());
                }
                flushPending(panel);
                downstream.dispatch(event, panel);
                return;
            }
            // All other StreamEvents (hook_call_start/done, permission_*, etc.):
            if (readSearchRun > 0) {
                return;
            }
            if (!pendingToolMessages.isEmpty()) {
                pendingToolMessages.add(event);
                return;
            }
            downstream.dispatch(event, panel);
            return;
        }

        // 236 tracks the previous message's timestamp across the whole transcript walk; the Δ a
        // thinking block contributes is measured against whatever came before it.
        Instant previousAt = previousMessageAt;
        timestampOf(message).ifPresent(at -> previousMessageAt = at);

        if (message instanceof SDKMessage.User user
                && absorbCollapsedToolResults(user, panel)) {
            return;
        }
        // The final assistant envelope repeats streamed tool_use blocks. It must
        // not seal the active projection before their real results arrive.
        if (message instanceof SDKMessage.Assistant assistant) {
            absorbThinking(assistant, previousAt, panel);
            downstream.dispatch(message, panel);
            return;
        }

        // Non-StreamEvent message: flush any pending collapsed output first.
        if (log.isDebugEnabled()) {
            log.debug("[COLLAPSER] non-StreamEvent: {} readSearchRun={}",
                message.getClass().getSimpleName(), readSearchRun);
        }
        flushPending(panel);
        downstream.dispatch(message, panel);
    }

    // ── Thinking projection ─────────────────────────────────────────────────

    /** 236's {@code se}: whether the group has anything thinking-related to show. */
    private boolean hasThinking() {
        return thoughtForMs > 0 || thinkingSummary != null;
    }

    /**
     * Folds one assistant message's leading thinking block into the group. The block itself is
     * still handed downstream, which renders nothing for it outside verbose/transcript mode — so
     * the collapsed header stays the only visible trace, exactly as in 236.
     */
    private void absorbThinking(SDKMessage.Assistant assistant, Instant previousAt,
            MessagePanel panel) {
        String thinking = leadingThinkingText(assistant);
        if (thinking == null) return;

        String summary = FormatUtils.flattenToSingleLine(thinking);
        if (!summary.isEmpty()) {
            thinkingSummary        = summary;
            heldSummary            = summary;
            heldSummaryExpiresAtMs = Long.MAX_VALUE;
        }
        Instant at = assistant.message().timestamp().orElse(null);
        if (previousAt != null && at != null) {
            long elapsed = Duration.between(previousAt, at).toMillis();
            if (elapsed > 0) thoughtForMs += Math.min(elapsed, MAX_THINKING_SPAN_MS);
        }
        lastThinkingAtMs = clock.getAsLong();
        renderCollapsedGroup(panel, true);
    }

    /** 236's {@code XxS}: only {@code content[0]}, and only when the thinking text is non-blank. */
    private static String leadingThinkingText(SDKMessage.Assistant assistant) {
        AssistantMessage envelope = assistant.message();
        if (envelope == null || envelope.message() == null) return null;
        List<ContentBlock> content = envelope.message().content();
        if (content == null || content.isEmpty()
                || !(content.getFirst() instanceof ThinkingBlock block)
                || org.apache.commons.lang3.StringUtils.isBlank(block.thinking())) {
            return null;
        }
        return block.thinking();
    }

    /** 236 resets {@code latestThinkingSummary} on every tool call; {@code G0h} holds it 3s. */
    private void clearThinkingSummary() {
        if (thinkingSummary == null) return;
        thinkingSummary        = null;
        heldSummaryExpiresAtMs = clock.getAsLong() + SUMMARY_HOLD_MS;
    }

    /** The summary the {@code ⎿} row should show, or {@code null} once the 3s hold lapses. */
    private String stickyThinkingSummary() {
        if (heldSummary == null) return null;
        return clock.getAsLong() < heldSummaryExpiresAtMs ? heldSummary : null;
    }

    /** 236's {@code aa(Math.max(1000, ie))}, with the live term {@code DCh} adds while active. */
    private String thinkingDurationText(boolean active) {
        long total = thoughtForMs;
        if (active && lastThinkingAtMs > 0) {
            total += Math.min(MAX_THINKING_SPAN_MS,
                Math.max(0, clock.getAsLong() - lastThinkingAtMs));
        }
        return FormatUtils.formatDuration(Math.max(1000, total));
    }

    private static Optional<Instant> timestampOf(SDKMessage message) {
        return switch (message) {
            case SDKMessage.Assistant assistant -> assistant.message() == null
                ? Optional.empty() : assistant.message().timestamp();
            case SDKMessage.User user -> user.message() == null
                ? Optional.empty() : user.message().timestamp();
            default -> Optional.empty();
        };
    }

    private void startThinkingClock(MessagePanel panel) {
        if (panel == null || thinkingClock != null) return;
        thinkingClock = THINKING_CLOCK_SCHEDULER.scheduleAtFixedRate(
            () -> tickThinkingClock(panel), CLOCK_TICK_MS, CLOCK_TICK_MS, TimeUnit.MILLISECONDS);
    }

    private void stopThinkingClock() {
        if (thinkingClock != null) {
            thinkingClock.cancel(false);
            thinkingClock = null;
        }
        renderedThinkingDuration = null;
    }

    /** Repaints only when the rendered second actually changed, so idle ticks cost nothing. */
    private void tickThinkingClock(MessagePanel panel) {
        try {
            synchronized (renderLock) {
                if (!hasThinking() || activeRunLineIdx < 0
                        || Strings.CS.equals(renderedThinkingDuration,
                            thinkingDurationText(true))) {
                    return;
                }
            }
            renderCollapsedGroup(panel, true);
        } catch (RuntimeException e) {
            log.debug("[COLLAPSER] thinking clock tick failed", e);
        }
    }

    // ── Internal ────────────────────────────────────────────────────────────

    private void handleToolCallStart(SDKMessage.StreamEvent event, MessagePanel panel) {
        clearThinkingSummary();
        String info     = event.data() instanceof String s ? s : "";
        String toolName = Strings.CS.contains(info, "|") ? info.split("\\|", 2)[0] : info;
        String argsJson = "";
        String toolUseId = null;
        if (Strings.CS.contains(info, "|")) {
            String[] parts = info.split("\\|", 3);
            toolUseId = parts.length > 1 ? parts[1] : null;
            argsJson = parts.length > 2 ? parts[2] : "";
        }

        if (McpCollapseClassifier.isCollapsible(toolName)) {
            String serverName = extractMcpServerName(toolName);
            mcpCallCount++;
            mcpServerNames.add(serverName);
            String query = extractJsonString(argsJson, "query");
            if (query != null) {
                latestDisplayHint = "\"" + query + "\"";
            } else {
                latestDisplayHint = serverName;
            }
            readSearchMessages.add(event);
            trackCollapsedTool(toolUseId, toolName);
            renderCollapsedGroup(panel, true);
            emitPhrase();
            return;
        }

        if (READ_SEARCH_TOOLS.contains(toolName)) {
            readSearchMessages.add(event);
            // Accumulate into the read/search run.
            readSearchRun++;
            trackCollapsedTool(toolUseId, toolName);

            String filePath = extractJsonString(argsJson, "file_path");
            if (filePath == null) filePath = extractJsonString(argsJson, "path");

            boolean mem = isMemoryPath(filePath);

            if (Strings.CS.equals(toolName, "Grep") || Strings.CS.equals(toolName, "Glob")) {
                // Only count as a pattern search when a "pattern" field is actually present.
                // When only "file_path" is given, treat it as a file read for counting purposes.
                String pattern = extractJsonString(argsJson, "pattern");
                if (org.apache.commons.lang3.StringUtils.isNotBlank(pattern)) {
                    searchCount++;
                    latestDisplayHint = "\"" + pattern + "\"";
                } else if (org.apache.commons.lang3.StringUtils.isNotBlank(filePath)) {
                    latestDisplayHint = getDisplayPath(filePath);
                    readFilePaths.add(filePath);
                } else {
                    readOperationCount++;
                }
            } else {
                // Read / LS
                if (Strings.CS.equals("LS", toolName)) {
                    listCount++;
                    if (org.apache.commons.lang3.StringUtils.isNotBlank(filePath)) {
                        latestDisplayHint = getDisplayPath(filePath);
                    }
                } else if (org.apache.commons.lang3.StringUtils.isNotBlank(filePath)) {
                    if (mem) {
                        memoryReadCount++;
                    } else {
                        latestDisplayHint = getDisplayPath(filePath);
                        readFilePaths.add(filePath);
                    }
                } else {
                    readOperationCount++;
                }
            }
            renderCollapsedGroup(panel, true);
        } else {
            // Non-read-search, non-MCP tool: flush any accumulated run first.
            flushReadSearchRun(panel);
            // Track Bash commands for git operation detection.
            if (Strings.CS.equals("Bash", toolName)) {
                lastBashCommand = extractJsonString(argsJson, "command");
            }
            lastNonReadSearchToolName = toolName;
            pendingToolMessages.add(event);
        }
        emitPhrase();
    }

    private void handleToolResult(SDKMessage.StreamEvent event, MessagePanel panel) {
        String info     = event.data() instanceof String s ? s : "";
        int pipe        = info.indexOf('|');
        String toolName = pipe > 0 ? info.substring(0, pipe) : info;
        String result   = pipe > 0 ? info.substring(pipe + 1) : "";

        if (readSearchRun > 0) {
            readSearchMessages.add(event);
            resolveCollapsedTool(toolName);
            if (Strings.CS.equals("tool_result_error", event.eventType())) {
                collapsedGroupHasError = true;
            }
            if (panel != null && panel.snapshotLineCount() > 0) {
                renderCollapsedGroup(panel, isCollapsedGroupActive());
            }
            emitPhrase();
            return;
        }
        if (mcpCallCount > 0) {
            readSearchMessages.add(event);
            resolveCollapsedTool(toolName);
            if (Strings.CS.equals("tool_result_error", event.eventType())) {
                collapsedGroupHasError = true;
            }
            if (panel != null && panel.snapshotLineCount() > 0) {
                renderCollapsedGroup(panel, isCollapsedGroupActive());
            }
            emitPhrase();
            return;
        }
        // Git operation detection for Bash tool results.
        if (Strings.CS.equals("Bash", toolName) && lastBashCommand != null) {
            detectAndStoreGitOp(lastBashCommand, result);
            lastBashCommand = null;
        }
        // Tool completed — clear active non-read-search marker so phrase clears.
        lastNonReadSearchToolName = null;
        pendingToolMessages.add(event);
        emitPhrase();
    }

    /**
     * Flush buffered messages to the downstream renderer. If there is an
     * active read/search run, emit the collapsed summary line first.
     */
    private void flushPending(MessagePanel panel) {
        flushReadSearchRun(panel);
        if (panel != null) {
            for (SDKMessage msg : pendingToolMessages) {
                downstream.dispatch(msg, panel);
            }
            // Append git badge after the tool result if a git operation was detected.
            String gitBadge = buildGitBadge();
            if (gitBadge != null) {
                panel.appendMixed(List.of(
                    new MessagePanel.Segment(gitBadge, LanternaTheme.toolSuccess())
                ));
            }
        }
        pendingToolMessages.clear();
        // Reset git fields after each flush
        lastBashCommand = null;
        gitCommitSha    = null;
        gitCommitKind   = null;
        gitPushBranch   = null;
        gitMergeRef     = null;
        gitMergeAction  = null;
        gitPrNumber     = 0;
        gitPrAction     = null;
    }

    /**
     * Emit the collapsed summary and optional ⤿ hint line when a run ends.
     */
    private void flushReadSearchRun(MessagePanel panel) {
        if (readSearchRun == 0 && mcpCallCount == 0 && !hasThinking()) return;
        if (panel != null) {
            renderCollapsedGroup(panel, false);
            int groupStart = activeRunLineIdx >= 0
                ? activeRunLineIdx : panel.snapshotLineCount() - 1;
            panel.registerExpandableLogicalMessage(
                "read-search:" + (++readSearchGroupSequence),
                MessagePanel.LogicalMessageKind.TOOL,
                groupStart,
                Math.max(groupStart, groupStart + activeRunRowCount - 1),
                readSearchCopyText(),
                downstream.renderExpandedToolEvents(readSearchMessages));
        }

        // Reset all group state
        readSearchRun    = 0;
        searchCount      = 0;
        listCount        = 0;
        readFilePaths.clear();
        readOperationCount = 0;
        unresolvedToolIds.clear();
        collapsedToolNamesById.clear();
        collapsedGroupHasError = false;
        readSearchMessages.clear();
        latestDisplayHint = null;
        stopThinkingClock();
        thoughtForMs           = 0;
        thinkingSummary        = null;
        heldSummary            = null;
        heldSummaryExpiresAtMs = 0;
        lastThinkingAtMs       = 0;
        mcpCallCount     = 0;
        mcpServerNames.clear();
        memoryReadCount  = 0;
        memoryWriteCount = 0;
        lastBashCommand  = null;
        lastNonReadSearchToolName = null;
        activeRunLineIdx = -1;
        activeRunRowCount = 0;
        gitCommitSha     = null;
        gitCommitKind    = null;
        gitPushBranch    = null;
        gitMergeRef      = null;
        gitMergeAction   = null;
        gitPrNumber      = 0;
        gitPrAction      = null;
        emitPhrase();
    }

    private String readSearchCopyText() {
        return readSearchMessages.stream()
            .filter(event -> Strings.CS.equals("tool_result_success", event.eventType())
                || Strings.CS.equals("tool_result_error", event.eventType()))
            .map(event -> event.data() instanceof String data && Strings.CS.contains(data, "|")
                ? data.substring(data.indexOf('|') + 1) : "")
            .filter(text -> !org.apache.commons.lang3.StringUtils.isBlank(text))
            .collect(Collectors.joining("\n\n"));
    }



    private int regularReadCount() {
        return readFilePaths.isEmpty() ? readOperationCount : readFilePaths.size();
    }

    private void trackCollapsedTool(String toolUseId, String toolName) {
        String id = org.apache.commons.lang3.StringUtils.isBlank(toolUseId)
            ? "legacy-collapsed-" + (++collapsedToolSequence) : toolUseId;
        collapsedToolNamesById.put(id, toolName);
        unresolvedToolIds.addLast(id);
    }

    private void resolveCollapsedTool(String toolName) {
        unresolvedToolIds.stream()
          .filter(id -> Strings.CS.equals(toolName, collapsedToolNamesById.get(id)))
          .findFirst().ifPresent(unresolvedToolIds::remove);
    }

    private boolean absorbCollapsedToolResults(SDKMessage.User user, MessagePanel panel) {
        if (user.message() == null || user.message().message() == null
                || user.message().message().blocks() == null) {
            return false;
        }
        List<ToolResultBlock> results = user.message().message().blocks().stream()
            .filter(ToolResultBlock.class::isInstance)
            .map(ToolResultBlock.class::cast)
            .toList();
        if (results.isEmpty() || results.stream().anyMatch(result ->
                !collapsedToolNamesById.containsKey(result.toolUseId()))) {
            return false;
        }
        for (ToolResultBlock result : results) {
            String toolName = collapsedToolNamesById.get(result.toolUseId());
            String text = result.content() == null ? "" : result.content().stream()
                .filter(TextBlock.class::isInstance)
                .map(TextBlock.class::cast)
                .map(TextBlock::text)
                .filter(org.apache.commons.lang3.StringUtils::isNotBlank)
                .collect(Collectors.joining("\n"));
            readSearchMessages.add(new SDKMessage.StreamEvent(
                result.isError() ? "tool_result_error" : "tool_result_success",
                toolName + "|" + text));
            unresolvedToolIds.remove(result.toolUseId());
            collapsedGroupHasError |= result.isError();
        }
        renderCollapsedGroup(panel, isCollapsedGroupActive());
        emitPhrase();
        return true;
    }

    private void renderCollapsedGroup(MessagePanel panel, boolean active) {
        if (panel == null
                || (readSearchRun == 0 && mcpCallCount == 0 && !hasThinking())) {
            return;
        }
        synchronized (renderLock) {
            List<List<MessagePanel.Segment>> rows = collapsedGroupRows(active, panel);
            if (activeRunLineIdx < 0) {
                if (panel.snapshotLineCount() > 0) panel.appendMixed(List.of());
                activeRunLineIdx = panel.snapshotLineCount();
                for (List<MessagePanel.Segment> row : rows) panel.appendMixed(row);
            } else if (activeRunLineIdx < panel.snapshotLineCount()) {
                panel.replaceLines(activeRunLineIdx, activeRunRowCount, rows);
            } else if (!active) {
                // Lightweight test panels may record appendMixed without retaining
                // MessagePanel's internal rows. Preserve their observable projection.
                panel.appendMixed(rows.getFirst());
            }
            activeRunRowCount = rows.size();
            if (active && !collapsedGroupHasError) {
                panel.startBlinkLine(activeRunLineIdx, rows.getFirst());
            } else {
                panel.stopBlinkLine(activeRunLineIdx, rows.getFirst());
            }
            renderedThinkingDuration = hasThinking() ? thinkingDurationText(active) : null;
            if (active && hasThinking()) {
                startThinkingClock(panel);
            } else if (!active) {
                stopThinkingClock();
            }
        }
    }

    private List<List<MessagePanel.Segment>> collapsedGroupRows(boolean active,
            MessagePanel panel) {
        TextColor color = active ? TextColor.ANSI.DEFAULT : LanternaTheme.welcomeDim();
        List<MessagePanel.Segment> header = new ArrayList<>();
        header.add(new MessagePanel.Segment(active ? Figures.BLACK_CIRCLE + " " : "  ",
            active ? LanternaTheme.assistantDot() : color));

        // 236 pushes this segment first, which is what makes every count part below read as a
        // comma-joined, lower-cased continuation: "Thinking for 24s, searching for 3 patterns…".
        boolean thinking = hasThinking();
        if (thinking) {
            header.add(new MessagePanel.Segment(
                active ? "Thinking for " : "Thought for ", color));
            header.add(new MessagePanel.Segment(thinkingDurationText(active),
                color, null, null, Set.of(SGR.BOLD)));
        }

// Each count-part's "first" flag is derived from its index in a runtime-built list, so the
// leading part is not a compile-time constant.
        record CountPart(String activeFirst, String active, String doneFirst,
                String done, int count, String singular, String plural) {}
        List<CountPart> parts = new ArrayList<>();
        if (memoryReadCount > 0) {
            parts.add(new CountPart("Recalling", "Recalling", "Recalled", "Recalled",
                memoryReadCount, "memory", "memories"));
        }
        if (memoryWriteCount > 0) {
            parts.add(new CountPart("Writing", "writing", "Wrote", "wrote",
                memoryWriteCount, "memory", "memories"));
        }
        if (searchCount > 0) {
            parts.add(new CountPart("Searching for", "searching for",
                "Searched for", "searched for", searchCount, "pattern", "patterns"));
        }
        int readCount = regularReadCount();
        if (readCount > 0) {
            parts.add(new CountPart("Reading", "reading", "Read", "read",
                readCount, "file", "files"));
        }
        if (listCount > 0) {
            parts.add(new CountPart("Listing", "listing", "Listed", "listed",
                listCount, "directory", "directories"));
        }
        for (int i = 0; i < parts.size(); i++) {
            boolean first = i == 0 && !thinking;
            CountPart p = parts.get(i);
            String verb = active
                ? first ? p.activeFirst() : p.active()
                : first ? p.doneFirst() : p.done();
            appendCountPart(header, first, verb, p.count(),
                p.count() == 1 ? p.singular() : p.plural(), color);
        }
        if (mcpCallCount > 0) {
            boolean first = parts.isEmpty() && !thinking;
            if (!first) header.add(new MessagePanel.Segment(", ", color));
            String serverLabel = mcpServerNames.isEmpty()
                ? "MCP" : String.join(", ", mcpServerNames);
            header.add(new MessagePanel.Segment(
                (active
                    ? first ? "Calling " : "calling "
                    : first ? "Called " : "called ") + serverLabel, color));
            if (mcpCallCount > 1) {
                header.add(new MessagePanel.Segment(" ", color));
                header.add(new MessagePanel.Segment(Integer.toString(mcpCallCount),
                    color, null, null, Set.of(SGR.BOLD)));
                header.add(new MessagePanel.Segment(" times", color));
            }
        }
        if (active) header.add(new MessagePanel.Segment("…", color));
        header.add(new MessagePanel.Segment(" ", color));
        header.add(new MessagePanel.Segment(
            KeybindingHints.expand(keybindingsStore), LanternaTheme.welcomeDim()));

        List<List<MessagePanel.Segment>> rows = new ArrayList<>();
        rows.add(List.copyOf(header));
        // One row, two sources: a live thinking summary wins over the tool display hint, and
        // reverts to it once G0h's hold lapses.
        String summary = active ? stickyThinkingSummary() : null;
        String body = summary != null
            ? MessagePanel.truncateToLines(summary, summaryWidth(panel), SUMMARY_MAX_LINES)
            : latestDisplayHint;
        if (active && body != null) {
            rows.add(List.of(
                new MessagePanel.Segment(Figures.RESULT_PREFIX, LanternaTheme.welcomeDim()),
                summary != null
                    ? new MessagePanel.Segment(body, LanternaTheme.welcomeDim(), null, null,
                        Set.of(SGR.ITALIC))
                    : new MessagePanel.Segment(body, LanternaTheme.welcomeDim())));
        }
        return rows;
    }

    /** 236's {@code y - u7i}: the columns left once the {@code ⎿} gutter is subtracted. */
    private static int summaryWidth(MessagePanel panel) {
        var size = panel == null ? null : panel.getSize();
        int columns = size == null || size.getColumns() <= 0 ? 80 : size.getColumns();
        return Math.max(1, columns - Figures.INDENT_COLS);
    }

    private static void appendCountPart(List<MessagePanel.Segment> target,
            boolean first, String verb, int count, String noun, TextColor color) {
        if (!first) target.add(new MessagePanel.Segment(", ", color));
        target.add(new MessagePanel.Segment(verb + " ", color));
        target.add(new MessagePanel.Segment(Integer.toString(count),
            color, null, null, Set.of(SGR.BOLD)));
        target.add(new MessagePanel.Segment(" " + noun, color));
    }

    private boolean isCollapsedGroupActive() {
        return !unresolvedToolIds.isEmpty() || loading;
    }

    private static boolean isCollapsibleTool(String toolName) {
        return READ_SEARCH_TOOLS.contains(toolName)
            || McpCollapseClassifier.isCollapsible(toolName);
    }

    // ── Static helpers ───────────────────────────────────────────────────────


    private static final Pattern GIT_CMD_RE =
        Pattern.compile("\\bgit(?:\\s+-[cC]\\s+\\S+|\\s+--\\S+=\\S+)*\\s+(commit|push|cherry-pick|merge(?!-)|rebase)\\b");
    private static final Pattern COMMIT_SHA_RE =
        Pattern.compile("\\[\\S+\\s+([0-9a-f]{6,40})]");
    private static final Pattern PUSH_BRANCH_RE =
        Pattern.compile("\\s+(\\S+)\\s+->");
    private static final Pattern PR_URL_RE =
        Pattern.compile("https://github\\.com/[^/]+/[^/]+/pull/(\\d+)");

    /** Returns "parent/filename" for display (last 2 path components). */
    static String getDisplayPath(String path) {
        if (org.apache.commons.lang3.StringUtils.isBlank(path)) return "";
        int slash = path.lastIndexOf('/');
        if (slash <= 0) return path;
        int prev = path.lastIndexOf('/', slash - 1);
        return prev < 0 ? path.substring(slash + 1) : path.substring(prev + 1);
    }

    /** True when the path refers to a Claude-managed memory file/directory. */
    private static boolean isMemoryPath(String path) {
        if (path == null) return false;
        return (Strings.CS.contains(path, "/.claude/") && (Strings.CS.contains(path, "/memory/") || Strings.CS.endsWith(path, "CLAUDE.md")))
            || Strings.CS.endsWith(path, "/CLAUDE.md");
    }

    /** Extracts the MCP server name from a tool name of the form mcp__server__tool. */
    private static String extractMcpServerName(String toolName) {
        // mcp__<server>__<tool>
        int first  = toolName.indexOf("__");
        int second = toolName.indexOf("__", first + 2);
        if (first < 0) return toolName;
        return second < 0 ? toolName.substring(first + 2) : toolName.substring(first + 2, second);
    }

    /**
     * Detects git operations from a Bash tool command+output pair and stores the result in the git*
     * fields for inclusion in the next buildReadSearchSummary() call.
     */
    private void detectAndStoreGitOp(String cmd, String output) {
        if (org.apache.commons.lang3.StringUtils.isBlank(cmd)) return;
        // gh pr commands (check first, before git command check)
        if (Strings.CS.contains(cmd, "gh pr ")) {
            Matcher prUrl = PR_URL_RE.matcher(output);
            if (prUrl.find()) gitPrNumber = Integer.parseInt(prUrl.group(1));
            if      (Strings.CS.contains(cmd, "gh pr create")) gitPrAction = "created";
            else if (Strings.CS.contains(cmd, "gh pr merge"))  gitPrAction = "merged";
            else if (Strings.CS.contains(cmd, "gh pr edit"))   gitPrAction = "edited";
            else if (Strings.CS.contains(cmd, "gh pr close"))  gitPrAction = "closed";
        }
        Matcher m = GIT_CMD_RE.matcher(cmd);
        if (!m.find()) return;
        switch (m.group(1)) {
            case "commit", "cherry-pick" -> {
                Matcher sha = COMMIT_SHA_RE.matcher(output);
                if (sha.find()) {
                    String full = sha.group(1);
                    gitCommitSha  = full.substring(0, Math.min(7, full.length()));
                    gitCommitKind = Strings.CS.equals("cherry-pick", m.group(1)) ? "cherry-picked"
                        : Strings.CS.contains(cmd, "--amend") ? "amended" : "committed";
                }
            }
            case "push" -> {
                Matcher br = PUSH_BRANCH_RE.matcher(output);
                if (br.find()) gitPushBranch = br.group(1);
            }
            case "merge" -> {
                if (Strings.CS.contains(output, "Fast-forward") || Strings.CS.contains(output, "Merge made by")) {
                    gitMergeRef    = extractFirstNonFlagArg(cmd, "merge");
                    gitMergeAction = "merged";
                }
            }
            case "rebase" -> {
                if (Strings.CS.contains(output, "Successfully rebased")) {
                    gitMergeRef    = extractFirstNonFlagArg(cmd, "rebase");
                    gitMergeAction = "rebased onto";
                }
            }
        }
    }

    private static String extractFirstNonFlagArg(String cmd, String verb) {
        int idx = cmd.indexOf(verb);
        if (idx < 0) return null;
        for (String tok : cmd.substring(idx + verb.length()).trim().split("\\s+")) {
            if (!org.apache.commons.lang3.StringUtils.isBlank(tok) && !Strings.CS.startsWith(tok, "-")) return tok;
        }
        return null;
    }

    /**
     * Builds a one-line git operation badge for display after a Bash tool result.
     */
    private String buildGitBadge() {
        List<String> parts = new ArrayList<>();
        if (gitCommitSha != null && gitCommitKind != null) {
            parts.add(StringUtils.capitalize(gitCommitKind) + " " + gitCommitSha);
        }
        if (gitPushBranch != null) {
            String verb = parts.isEmpty() ? "Pushed to" : "pushed to";
            parts.add(verb + " " + gitPushBranch);
        }
        if (gitMergeRef != null && gitMergeAction != null) {
            String verb = parts.isEmpty() ? StringUtils.capitalize(gitMergeAction) : gitMergeAction;
            parts.add(verb + " " + gitMergeRef);
        }
        if (gitPrNumber > 0 && gitPrAction != null) {
            String verb = parts.isEmpty() ? StringUtils.capitalize(gitPrAction) : gitPrAction;
            parts.add(verb + " PR #" + gitPrNumber);
        }
        return parts.isEmpty() ? null : "⏺ " + String.join(", ", parts);
    }


    /**
     * Extracts a string field from a minimal JSON snippet.
     * Handles simple {@code "key":"value"} patterns without a full JSON parser.
     */
    private static String extractJsonString(String json, String key) {
        if (org.apache.commons.lang3.StringUtils.isBlank(json)) return null;
        String field = "\"" + key + "\"";
        int idx = json.indexOf(field);
        if (idx < 0) return null;
        // Skip whitespace and colon
        int colon = json.indexOf(':', idx + field.length());
        if (colon < 0) return null;
        int start = json.indexOf('"', colon + 1);
        if (start < 0) return null;
        // Find closing quote, respecting escapes
        int end = start + 1;
        while (end < json.length()) {
            char c = json.charAt(end);
            if (c == '\\') { end += 2; continue; }
            if (c == '"')  break;
            end++;
        }
        return end > start + 1 ? json.substring(start + 1, end) : null;
    }
}
