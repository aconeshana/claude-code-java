package com.claudecode.ui.lanterna.components;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.runtime.tasks.TaskBoardPort;
import com.claudecode.core.text.FormatUtils;
import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TextCharacter;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.gui2.AbstractInteractableComponent;
import com.googlecode.lanterna.gui2.InteractableRenderer;
import com.googlecode.lanterna.gui2.TextGUIGraphics;
import com.googlecode.lanterna.input.KeyStroke;

import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.function.BooleanSupplier;
import com.claudecode.ui.lanterna.theme.LanternaTheme;

/**
 * Thread-safe Spinner as a Lanterna Component.
 */
public class SpinnerComponent extends AbstractInteractableComponent<SpinnerComponent> {

    /** Ping-pong frame set delegated to SpinnerFrames — avoids duplicating platform detection. */
    private static final List<String> SPINNER_FRAMES = SpinnerFrames.defaultAnimationFrames();

    static final TerminalSize EMPTY_SIZE = new TerminalSize(0, 0);

    private static final long FRAME_MS = 120;

    private static final ScheduledExecutorService SCHEDULER =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "spinner-anim");
            t.setDaemon(true);
            return t;
        });

    // ── State ──────────────────────────────────────────────────────────────
    private final AtomicInteger     frame       = new AtomicInteger(0);
/**
     * Response length for token count estimation.
     */
    private final AtomicInteger     responseLength = new AtomicInteger(0);


    private volatile Object thinkingStatus = null;


    private volatile String effortSuffix = "";
    private final AtomicReference<String> verb  = new AtomicReference<>("Thinking");
    private final AtomicReference<String> suffix = new AtomicReference<>("");
    private final AtomicBoolean     stalled     = new AtomicBoolean(false);
    /** true while a tool is executing — triggers sinusoidal color animation. */
    private volatile boolean        toolUseMode = false;
    /** true while in requesting mode — switches metric glyph to ↑ (vs ↓). */
    private volatile boolean        requestingMode = false;
    /**
     * Optional tip text shown beneath the spinner line.
     */
    private volatile String         spinnerTip  = null;
    /**
     * How many times the user has used /btw.
     */
    private volatile int            btwUseCount = 0;

    private volatile boolean        tipsEnabled = true;

    private volatile boolean        verbose = false;
    /**
     * Smooth-animated response length — increments toward {@link #responseLength} each tick.
     */
    private volatile int            displayedResponseLength = 0;
    /** When active thinking started (ms), for shimmer delay. -1 means not thinking. */
    private volatile long           thinkingStartMs = -1;
    /** Start index of thinking text in the built string for shimmer coloring. -1 if absent. */
    private volatile int            thinkingTextStart = -1;
    /** Exclusive end index of thinking text in the built string. -1 if absent. */
    private volatile int            thinkingTextEnd   = -1;
    /** Terminal column count hint for progressive width gating — updated each draw. */
    private volatile int            lastDrawColumns = 80;
    /**
     * Caller-supplied color override for the spinner verb and icon.
     */
    private volatile TextColor      overrideColor       = null;
    /**
     * Caller-supplied shimmer color override for the verb glimmer effect.
     */
    private volatile TextColor      overrideShimmerColor = null;
    /**
     * Caller-supplied verb override — replaces the random verb chosen at start.
     */
    private volatile String         overrideMessage      = null;
    /** Immutable Task* V2 snapshot shared with the persistent board. */
    private volatile TaskBoardPort.Snapshot taskSnapshot = TaskBoardPort.Snapshot.EMPTY;
    /** Live teammate timing/token metrics supplied by the task registry. */
    private volatile Supplier<List<TeammateMetric>> runningTeammateMetricsSupplier = List::of;
    /** Viewed in-process teammate id; local-agent views deliberately return null. */
    private volatile Supplier<String> viewedTeammateIdSupplier = () -> null;
    private volatile BooleanSupplier teammateSelectionModeSupplier = () -> false;
    private volatile Supplier<Integer> teammateSelectedIndexSupplier = () -> -1;
    private volatile boolean teammateTreeExpanded = false;
    /** Fired once when a leader-idle swarm transitions from running to empty. */
    private volatile Runnable teammateSwarmFinishedListener = () -> { };
    private final AtomicBoolean teammateSwarmObserved = new AtomicBoolean(false);
    private volatile boolean leaderIdle = false;
    private volatile boolean staticIdleDisplay = false;
    private volatile String renderedVerb = "";
    private final Map<String, Long> teammateIdleSeenAt = new HashMap<>();
    private final Map<String, String> teammateFrozenDurations = new HashMap<>();
    /**
     * Earliest derived leader-turn start observed while a teammate swarm remains active.
     * Read and updated only by the Lanterna GUI render thread.
     */
    private long teammateTurnStartMs = -1L;

    public record TeammateMetric(String taskId, String name, String colorName,
                                 boolean idle, boolean shutdownRequested,
                                 boolean awaitingPlanApproval, String activity, String verb,
                                 String pastVerb, long startMs, long pausedMs,
                                 long tokens, int toolUses) {
        public TeammateMetric(long startMs, long pausedMs, long tokens) {
            this(null, null, null, false, false, false, null, null, null,
                startMs, pausedMs, tokens, 0);
        }

        public TeammateMetric(String taskId, String name, boolean idle, String verb,
                              long startMs, long pausedMs, long tokens) {
            this(taskId, name, null, idle, false, false, null, verb, null,
                startMs, pausedMs, tokens, 0);
        }
    }
    /**
     * Epoch-ms when compaction started, or -1 when not compacting.
     */
    private volatile long           compactingStartMs = -1;



    private volatile int            compactPercentFloor = 0;


    private static final char COMPACT_BAR_FILL  = '▰';   // ▰
    private static final char COMPACT_BAR_EMPTY = '▱';   // ▱
    private static final int  COMPACT_BAR_MAX_WIDTH = 40;
    private static final int  COMPACT_BAR_MIN_WIDTH = 8;
    private static final int  COMPACT_BAR_INDENT    = 2;

    /**
     * Epoch-ms when the timer was paused (permission dialog opened), or -1 if not paused.
     */
    private volatile long           pauseStartMs    = -1;
    /**
     * Total milliseconds the timer has been frozen across all pause/resume cycles this turn.
     */
    private final AtomicLong        totalPausedMs   = new AtomicLong(0L);


    private static final long BTW_TIP_THRESHOLD_MS   =     30_000L;   // 30s
    private static final long CLEAR_TIP_THRESHOLD_MS =  1_800_000L;   // 30min
    private static final String BTW_TIP   = "Use /btw to ask a quick side question without interrupting Claude's current work";
    private static final String CLEAR_TIP = "Use /clear to start fresh when switching topics and free up context";


    private static final long   THINKING_DELAY_MS     = 3_000L;
    private static final double THINKING_GLOW_PERIOD_S = 2.0;
    // RGB(153,153,153) → RGB(185,185,185) interpolated via sine wave

    /** Start of the currently visible animation segment (standalone fallback clock). */
    private volatile long           startMs     = 0;
    /** Stable start of the submitted turn; unlike {@link #startMs}, never resets mid-turn. */
    private volatile long           turnStartMs = 0;
    /** True from {@link #startTurn()} until {@link #finishTurnClock()}. */
    private volatile boolean        turnClockActive = false;
    private volatile long           lastActivityMs = 0;
    /** Start index of the metric "(…)" section in the built text for dim coloring. -1 if absent. */
    private volatile int            metricStart = -1;
    /** Exclusive end index of the metric "(…)" section. -1 if absent. */
    private volatile int            metricEnd   = -1;
    private ScheduledFuture<?>      animFuture;

    // ──────────────────────────────────────────────────────────────────────

    public SpinnerComponent() {
        setVisible(false);
    }

/**
     * Show spinner with a randomly-chosen verb.
     */
    public synchronized void start() {
        start(SpinnerVerbs.randomActive());
    }

    /**
     * Begin a submitted turn and show its spinner. The turn clock is distinct
     * from the animation lifecycle because streamed text temporarily hides the
     * spinner and later model/tool phases show it again.
     */
    synchronized void startTurn() {
        beginTurnClock();
        showTurnSpinner();
    }

    /**
     * Anchors the authoritative submitted-turn clock synchronously. The caller
     * invokes this at the submission boundary before any GUI work is queued, so
     * a busy render loop cannot shorten the recorded turn duration.
     */
    synchronized void beginTurnClock() {
        this.leaderIdle = false;
        this.turnClockActive = true;
        this.turnStartMs = System.currentTimeMillis();
        this.pauseStartMs = -1;
        this.totalPausedMs.set(0L);
    }

    /** Shows the visual spinner without changing the already-anchored clock. */
    synchronized void showTurnSpinner() {
        this.leaderIdle = false;
        start(SpinnerVerbs.randomActive());
    }

    /** Show spinner and start animation loop. */
    public synchronized void start(String verb) {
        this.verb.set(verb);
        this.startMs = System.currentTimeMillis();
        this.lastActivityMs = startMs;
        this.stalled.set(false);
        this.frame.set(0);
        this.displayedResponseLength = 0;
        this.thinkingStartMs = -1;
        this.thinkingTextStart = -1;
        this.thinkingTextEnd   = -1;
        if (!turnClockActive) {
            this.pauseStartMs = -1;
            this.totalPausedMs.set(0L);
        }
        setVisible(true);

        if (animFuture == null || animFuture.isDone()) {
            animFuture = SCHEDULER.scheduleAtFixedRate(this::tick, 0, FRAME_MS, TimeUnit.MILLISECONDS);
        }
    }

    /** Hide spinner and stop animation. */
    public synchronized void stop() {
        if (animFuture != null) {
            animFuture.cancel(false);
            animFuture = null;
        }
        spinnerTip = null; // clear tip on stop
        displayedResponseLength = 0;
        thinkingStartMs = -1;
        if (!turnClockActive) {
            pauseStartMs = -1;
            totalPausedMs.set(0L);
        }
        overrideColor        = null;
        overrideShimmerColor = null;
        overrideMessage      = null;
        compactingStartMs    = -1;
        compactPercentFloor  = 0;
        setVisible(false);
        suffix.set("");
        leaderIdle = false;
    }




    public synchronized void finishLeaderTurn() {
        if (runningTeammateMetrics().isEmpty()) {
            stop();
            return;
        }
        leaderIdle = true;
        teammateSwarmObserved.set(true);
        setVisible(true);
        if (animFuture == null || animFuture.isDone()) {
            animFuture = SCHEDULER.scheduleAtFixedRate(this::tick, 0, FRAME_MS,
                TimeUnit.MILLISECONDS);
        }
    }

    public void setVerb(String v)   { verb.set(v); lastActivityMs = System.currentTimeMillis(); }
    public String getCurrentVerb()  { return verb.get(); }
    /** Update the lifecycle suffix and repaint even when the animation is paused. */
    public void setSuffix(String s) { suffix.set(s != null ? s : ""); invalidate(); }
    public boolean isSpinning()     { return isVisible(); }

    /**
     * Switch between normal mode and tool-use mode.
     */
    public void setToolUseMode(boolean active) {
        this.toolUseMode = active;
        lastActivityMs = System.currentTimeMillis();
    }

    boolean isToolUseMode() {
        return toolUseMode;
    }

    /** Switch to requesting mode (uploading) — metric shows ↑ instead of ↓. */
    public void setRequestingMode(boolean requesting) {
        this.requestingMode = requesting;
        this.toolUseMode = false;
        lastActivityMs = System.currentTimeMillis();
    }

    boolean isRequestingMode() {
        return requestingMode;
    }

    /**
 * Enters/leaves compacting mode — shows the time-based progress bar under the spinner line.
     */
    public void setCompacting(boolean active) {
        if (active) {
            if (compactingStartMs < 0) {
                compactingStartMs = System.currentTimeMillis();
                compactPercentFloor = 0;
            }
        } else {
            compactingStartMs = -1;
            compactPercentFloor = 0;
        }
        invalidate();
    }

    /** True while the compaction progress bar is active. */
    public boolean isCompacting() { return compactingStartMs >= 0; }

    /**
 * Elapsed-time → percent curve for the compaction progress bar.
     */
    static int compactPercent(long elapsedMs) {
        double seconds = Math.max(0, elapsedMs) / 1000.0;
        double saturation = 1 - Math.exp(-seconds / 90.0);
        return Math.min(95, (int) Math.round(saturation * 100));
    }

    /**
     * Renders the pill bar text for the given percent and cell width, e.g.
     */
    static String compactBarText(int percent, int width) {
        int fill = (int) Math.round(percent / 100.0 * width);
        return String.valueOf(COMPACT_BAR_FILL).repeat(fill)
             + String.valueOf(COMPACT_BAR_EMPTY).repeat(Math.max(0, width - fill));
    }

    // ── Animation ─────────────────────────────────────────────────────────

    private void tick() {
        List<TeammateMetric> teammateMetrics = runningTeammateMetrics();
        if (!teammateMetrics.isEmpty()) {
            teammateSwarmObserved.set(true);
        } else if (teammateSwarmObserved.compareAndSet(true, false)) {
            boolean completedWhileLeaderIdle = leaderIdle;
            if (completedWhileLeaderIdle) {
                synchronized (this) {
                    if (animFuture != null) {
                        animFuture.cancel(false);
                        animFuture = null;
                    }
                    setVisible(false);
                    leaderIdle = false;
                }
            }
            try {
                teammateSwarmFinishedListener.run();
            } catch (RuntimeException _) {
                // Presentation callbacks must never terminate the animation scheduler.
            }
            if (completedWhileLeaderIdle) return;
        }
        frame.incrementAndGet();
        long now = System.currentTimeMillis();

        if (toolUseMode) lastActivityMs = now;
        stalled.set(now - lastActivityMs > SpinnerFrames.SHIMMER_STALL_MS);


        int target = responseLength.get();
        int current = displayedResponseLength;
        if (current < target) {
            int gap = target - current;
            int increment = gap < 70  ? 3
                          : gap < 200 ? Math.max(8, (int) Math.ceil(gap * 0.15))
                          : 50;
            displayedResponseLength = Math.min(current + increment, target);
        }

        // Invalidate triggers a repaint via Lanterna's GUI thread
        invalidate();
    }

    // ── Rendering ─────────────────────────────────────────────────────────

    @Override
    protected InteractableRenderer<SpinnerComponent> createDefaultRenderer() {
        return new SpinnerRenderer();
    }

    @Override
    public TerminalSize calculatePreferredSize() {
        if (!isVisible()) return EMPTY_SIZE;
        String text = buildText();
        List<TreeRow> treeRows = teammateTreeRows(lastDrawColumns);
        String tip = treeRows.isEmpty() ? effectiveTip() : null;
        boolean compacting = isCompacting();

        // breathing row inside this component so SmartLayout reserves it.
        int rows = 2 + (compacting ? 1 : 0)
            + (treeRows.isEmpty() ? 0 : 1 + treeRows.size())
            + (StringUtils.isNotBlank(tip) ? 1 : 0);
        int cols = text.length() + 1;
        for (TreeRow row : treeRows) cols = Math.max(cols, row.text().length() + 1);
        if (compacting) {
            // indent + bar + space + "NN%" — so the bar row isn't clipped when
            // the spinner line itself is shorter than the bar.
            cols = Math.max(cols, COMPACT_BAR_INDENT + COMPACT_BAR_MAX_WIDTH + 5);
        }
        return new TerminalSize(cols, rows);
    }

    private record TreeSegment(String text, TextColor color, boolean bold) { }
    private record TreeRow(List<TreeSegment> segments) {
        String text() {
            StringBuilder out = new StringBuilder();
            segments.forEach(segment -> out.append(segment.text()));
            return out.toString();
        }
    }

    private List<TreeRow> teammateTreeRows(int columns) {
        if (!teammateTreeExpanded) return List.of();
        List<TeammateMetric> teammates = runningTeammateMetrics();
        if (teammates.isEmpty()) return List.of();
        boolean selecting;
        int selected;
        try {
            selecting = teammateSelectionModeSupplier.getAsBoolean();
            selected = teammateSelectedIndexSupplier.get();
        } catch (RuntimeException _) {
            selecting = false;
            selected = -1;
        }
        String viewed;
        try {
            viewed = viewedTeammateIdSupplier.get();
        } catch (RuntimeException _) {
            viewed = null;
        }
        boolean allIdle = teammates.stream().allMatch(TeammateMetric::idle);
        long now = System.currentTimeMillis();
        synchronized (teammateIdleSeenAt) {
            for (TeammateMetric teammate : teammates) {
                if (teammate.idle()) teammateIdleSeenAt.putIfAbsent(teammate.taskId(), now);
                else teammateIdleSeenAt.remove(teammate.taskId());
                if (!allIdle) teammateFrozenDurations.remove(teammate.taskId());
            }
        }

        List<TreeRow> rows = new ArrayList<>();
        boolean leaderForegrounded = viewed == null;
        boolean leaderSelected = selecting && selected == -1;
        boolean leaderHighlighted = leaderForegrounded || leaderSelected;
        List<TreeSegment> leader = new ArrayList<>();
        leader.add(segment("   ", null, false));
        leader.add(segment(leaderSelected ? "❯" : " ",
            leaderSelected ? LanternaTheme.suggestion() : null, leaderSelected));
        leader.add(segment(leaderHighlighted ? "╒═ " : "┌─ ",
            leaderHighlighted ? TextColor.ANSI.DEFAULT : LanternaTheme.welcomeDim(),
            leaderHighlighted));
        leader.add(segment("team-lead",
            leaderSelected ? LanternaTheme.suggestion() : LanternaTheme.agentCyan(),
            leaderHighlighted));
        if (!leaderForegrounded) {
            leader.add(segment(leaderIdle ? ": Idle" : ": " + resolveVerb() + "…",
                LanternaTheme.welcomeDim(), false));
        }
        long leaderTokens = Math.round(Math.max(0, displayedResponseLength) / 4D);
        if (leaderTokens > 0) leader.add(segment(" · " + FormatUtils.formatNumber(leaderTokens)
            + " tokens", leaderHighlighted ? TextColor.ANSI.DEFAULT : LanternaTheme.welcomeDim(), false));
        if (leaderHighlighted) leader.add(segment(" · shift + ↑/↓ to select",
            LanternaTheme.welcomeDim(), false));
        if (leaderSelected && !leaderForegrounded) leader.add(segment(" · enter to view",
            LanternaTheme.welcomeDim(), false));
        rows.add(new TreeRow(List.copyOf(leader)));

        for (int index = 0; index < teammates.size(); index++) {
            TeammateMetric teammate = teammates.get(index);
            boolean selectedRow = selecting && selected == index;
            boolean foregrounded = Strings.CS.equals(viewed, teammate.taskId());
            boolean highlighted = selectedRow || foregrounded;
            boolean last = !selecting && index == teammates.size() - 1;
            String connector = highlighted ? (last ? "╘═ " : "╞═ ") : (last ? "└─ " : "├─ ");
            List<TreeSegment> line = new ArrayList<>();
            line.add(segment("   ", null, false));
            line.add(segment(selectedRow ? "❯" : " ",
                selectedRow ? LanternaTheme.suggestion() : null, selectedRow));
            line.add(segment(connector,
                selectedRow ? TextColor.ANSI.DEFAULT : LanternaTheme.welcomeDim(), selectedRow));

            String fullName = "@" + StringUtils.defaultIfBlank(teammate.name(), teammate.taskId());
            int basePrefix = 8;
            int fullNameWidth = FormatUtils.displayWidth(fullName);
            boolean showName = columns >= 60
                && columns - basePrefix - fullNameWidth - 2 >= 25;
            if (showName) {
                TextColor nameColor = selectedRow ? LanternaTheme.suggestion()
                    : StringUtils.isBlank(teammate.colorName()) ? LanternaTheme.agentCyan()
                    : LanternaTheme.agentColor(teammate.colorName());
                line.add(segment(fullName, nameColor, selectedRow));
                line.add(segment(": ", selectedRow ? TextColor.ANSI.DEFAULT
                    : LanternaTheme.welcomeDim(), false));
            }

            String status = teammateStatus(teammate, highlighted, allIdle, now);
            if (!status.isEmpty()) {
                TextColor statusColor = teammate.awaitingPlanApproval()
                    ? LanternaTheme.statusCost() : LanternaTheme.welcomeDim();
                line.add(segment(status, statusColor, false));
            }
            String stats = " · " + teammate.toolUses() + " tool "
                + (teammate.toolUses() == 1 ? "use" : "uses") + " · "
                + FormatUtils.formatNumber(teammate.tokens()) + " tokens";
            int availableForActivity = columns - basePrefix - (showName ? fullNameWidth + 2 : 0);
            int statsWidth = FormatUtils.displayWidth(stats);
            int minActivityWidth = 25;
            int viewHintWidth = FormatUtils.displayWidth(" · enter to view");
            boolean showViewHint = selectedRow && !foregrounded
                && availableForActivity > viewHintWidth + statsWidth + minActivityWidth + 5;
            int selectHintWidth = FormatUtils.displayWidth(" · shift + ↑/↓ to select");
            boolean showSelectHint = highlighted
                && availableForActivity > selectHintWidth
                    + (showViewHint ? viewHintWidth : 0)
                    + statsWidth + minActivityWidth + 5;
            boolean showStats = availableForActivity > statsWidth + minActivityWidth + 5;
            if (showStats) line.add(segment(stats, LanternaTheme.welcomeDim(), false));
            if (showSelectHint) line.add(segment(" · shift + ↑/↓ to select",
                LanternaTheme.welcomeDim(), false));
            if (showViewHint) line.add(segment(" · enter to view",
                LanternaTheme.welcomeDim(), false));
            rows.add(new TreeRow(List.copyOf(line)));
        }

        if (selecting) {
            boolean hideSelected = selected == teammates.size();
            rows.add(new TreeRow(List.of(
                segment("   ", null, false),
                segment(hideSelected ? "❯" : " ",
                    hideSelected ? LanternaTheme.suggestion() : null, hideSelected),
                segment(hideSelected ? "╘═ " : "└─ ",
                    hideSelected ? TextColor.ANSI.DEFAULT : LanternaTheme.welcomeDim(), hideSelected),
                segment("hide", hideSelected ? TextColor.ANSI.DEFAULT
                    : LanternaTheme.welcomeDim(), hideSelected),
                segment(hideSelected ? " · enter to collapse" : "",
                    LanternaTheme.welcomeDim(), false))));
        }
        return List.copyOf(rows);
    }

    private String teammateStatus(TeammateMetric teammate, boolean highlighted,
                                  boolean allIdle, long now) {
        if (teammate.shutdownRequested()) return "[stopping]";
        if (teammate.awaitingPlanApproval()) return "[awaiting approval]";
        if (teammate.idle()) {
            if (allIdle) {
                synchronized (teammateIdleSeenAt) {
                    String duration = teammateFrozenDurations.computeIfAbsent(teammate.taskId(),
                        _ -> FormatUtils.formatDuration(Math.max(0L,
                            now - teammate.startMs() - teammate.pausedMs())));
                    return StringUtils.defaultIfBlank(teammate.pastVerb(), "Worked")
                        + " for " + duration;
                }
            }
            long idleStart;
            synchronized (teammateIdleSeenAt) {
                idleStart = teammateIdleSeenAt.getOrDefault(teammate.taskId(), now);
            }
            return "Idle for " + FormatUtils.formatDuration(Math.max(0L, now - idleStart));
        }
        if (highlighted) return "";
        String activity = StringUtils.defaultIfBlank(teammate.activity(), teammate.verb());
        activity = StringUtils.defaultIfBlank(activity, "Working");
        return activity.endsWith("…") ? activity : activity + "…";
    }

    private static TreeSegment segment(String text, TextColor color, boolean bold) {
        return new TreeSegment(text, color, bold);
    }

    @Override
    public Result handleKeyStroke(KeyStroke key) {
        return Result.UNHANDLED;
    }

    private String buildText() {
        List<TeammateMetric> runningTeammateMetrics = runningTeammateMetrics();
        boolean hasRunningTeammates = !runningTeammateMetrics.isEmpty();
        boolean allIdle = hasRunningTeammates
            && runningTeammateMetrics.stream().allMatch(TeammateMetric::idle);
        TeammateMetric foregrounded = foregroundedTeammate(runningTeammateMetrics);
        if (leaderIdle && hasRunningTeammates && foregrounded == null) {
            staticIdleDisplay = true;
            renderedVerb = "";
            return "✻ Idle" + (allIdle ? "" : " · teammates running");
        }
        if (foregrounded != null && foregrounded.idle()) {
            staticIdleDisplay = true;
            renderedVerb = "";
            return allIdle
                ? "✻ Worked for " + FormatUtils.formatDuration(
                    Math.max(0L, System.currentTimeMillis() - foregrounded.startMs()))
                : "✻ Idle";
        }
        staticIdleDisplay = false;
        String icon = SpinnerFrames.glyphAt(SPINNER_FRAMES, frame.get());
        String verbStr = foregrounded != null && StringUtils.isNotBlank(foregrounded.verb())
            ? foregrounded.verb() : resolveVerb();
        renderedVerb = verbStr;
        long nowMs = System.currentTimeMillis();
        long leaderElapsedMs = adjustedElapsedMs();
        long derivedStartMs = nowMs - leaderElapsedMs;
        teammateTurnStartMs = nextTeammateTurnStartMs(
            teammateTurnStartMs, derivedStartMs, hasRunningTeammates);
        long elapsedMs = effectiveElapsedMs(leaderElapsedMs, nowMs,
            teammateTurnStartMs, hasRunningTeammates);
        String sfx     = suffix.get();

        String thinkingText = buildThinkingText();
        boolean wantsThinking = !thinkingText.isEmpty();


        String prefixStr = icon + " " + verbStr + "… ";
        int availableSpace = availableMetricSpace(lastDrawColumns, verbStr);

        // Build candidate timer + tokens strings
        long totalTokens = foregrounded != null
            ? Math.max(0L, foregrounded.tokens())
            : effectiveTokenCount(displayedResponseLength,
                teammateTreeExpanded ? List.of() : runningTeammateMetrics);

        // response token exists; 16s is only the empty-response fallback.
        boolean wantsTimer = shouldShowTimer(verbose, elapsedMs, hasRunningTeammates,
            wantsThinking, totalTokens);
        String timerStr  = wantsTimer ? FormatUtils.formatDuration(elapsedMs) : null;
        String tokensStr = null;
        if (wantsTimer && totalTokens > 0) {
            String arrow = hasRunningTeammates ? "" : (requestingMode ? "↑ " : "↓ ");
            tokensStr = arrow + FormatUtils.formatNumber(totalTokens) + " tokens";
        }




        int used = 0;
        boolean showThinking = wantsThinking
                && availableSpace > FormatUtils.displayWidth(thinkingText);
        if (showThinking) used += FormatUtils.displayWidth(thinkingText);

        boolean showTimer = timerStr != null
                && availableSpace > used + (used > 0 ? 3 : 0)
                    + FormatUtils.displayWidth(timerStr);
        if (showTimer) used += (used > 0 ? 3 : 0) + FormatUtils.displayWidth(timerStr);

        boolean showTokens = tokensStr != null
                && availableSpace > used + (used > 0 ? 3 : 0)
                    + FormatUtils.displayWidth(tokensStr);

        // thinkingOnly: only thinking is shown (no suffix/timer/tokens) — rendered without dim
        // wrapper so thinking shimmer is the sole color.
        boolean thinkingOnly = showThinking && thinkingStatus instanceof String
                && sfx.isEmpty() && !showTimer && !showTokens;

        // Build the output string
        StringBuilder sb = new StringBuilder(prefixStr);
        this.metricStart     = -1; this.metricEnd      = -1;
        this.thinkingTextStart = -1; this.thinkingTextEnd = -1;

        if (foregrounded != null) {
            sb.append("(esc to interrupt ")
                .append(StringUtils.defaultIfBlank(foregrounded.name(), foregrounded.taskId()))
                .append(")");
            return sb.toString();
        }

        boolean hasContent = !sfx.isEmpty() || showTimer || showTokens || showThinking;
        if (hasContent) {
            if (thinkingOnly) {
                // "(thinking…)" — shimmer color only, no dim wrapper
                sb.append("(");
                this.thinkingTextStart = sb.length();
                sb.append(thinkingText);
                this.thinkingTextEnd = sb.length();
                sb.append(")");
            } else {
                // "(suffix · elapsed · tokens · thinking)" — all dim; thinking sub-range may shimmer
                this.metricStart = sb.length();
                sb.append("(");
                boolean first = true;
                if (!sfx.isEmpty())  { sb.append(sfx);      first = false; }

                if (showTimer)  { if (!first) sb.append(" · "); sb.append(timerStr);  first = false; }
                if (showTokens) { if (!first) sb.append(" · "); sb.append(tokensStr); first = false; }
                if (showThinking) {
                    if (!first) sb.append(" · ");
                    this.thinkingTextStart = sb.length();
                    sb.append(thinkingText);
                    this.thinkingTextEnd   = sb.length();
                }
                sb.append(")");
                this.metricEnd = sb.length();
            }
        }
        return sb.toString();
    }


    static boolean shouldShowTimer(boolean verbose, long elapsedMs) {
        return shouldShowTimer(verbose, elapsedMs, false);
    }

    static boolean shouldShowTimer(boolean verbose, long elapsedMs,
            boolean hasRunningTeammates) {
        return shouldShowTimer(verbose, elapsedMs, hasRunningTeammates, false, 0L);
    }

    static boolean shouldShowTimer(boolean verbose, long elapsedMs,
            boolean hasRunningTeammates, boolean hasStatus, long tokenCount) {
        return verbose || hasRunningTeammates || hasStatus || tokenCount > 0L
            || elapsedMs > SpinnerFrames.SHOW_TOKENS_AFTER_MS;
    }

    static long nextTeammateTurnStartMs(long currentStartMs, long derivedStartMs,
            boolean hasRunningTeammates) {
        if (!hasRunningTeammates || currentStartMs < 0L || derivedStartMs < currentStartMs) {
            return derivedStartMs;
        }
        return currentStartMs;
    }

    static long effectiveElapsedMs(long leaderElapsedMs, long nowMs,
            long teammateTurnStartMs, boolean hasRunningTeammates) {
        if (!hasRunningTeammates || teammateTurnStartMs < 0L) return leaderElapsedMs;
        return Math.max(leaderElapsedMs, Math.max(0L, nowMs - teammateTurnStartMs));
    }

    static long effectiveTokenCount(int displayedResponseLength,
            List<TeammateMetric> runningTeammateMetrics) {
        long total = Math.round(Math.max(0, displayedResponseLength) / 4D);
        for (TeammateMetric metric : runningTeammateMetrics) {
            if (metric != null) total += Math.max(0L, metric.tokens());
        }
        return total;
    }

    static int availableMetricSpace(int columns, String message) {
        int messageWidth = FormatUtils.displayWidth(message) + 2;
        return Math.max(0, columns - messageWidth - 5);
    }

    private List<TeammateMetric> runningTeammateMetrics() {
        Supplier<List<TeammateMetric>> supplier = runningTeammateMetricsSupplier;
        if (supplier == null) return List.of();
        try {
            List<TeammateMetric> metrics = supplier.get();
            return metrics != null ? metrics : List.of();
        } catch (RuntimeException _) {
            return List.of();
        }
    }

    private TeammateMetric foregroundedTeammate(List<TeammateMetric> metrics) {
        String viewedId;
        try {
            viewedId = viewedTeammateIdSupplier.get();
        } catch (RuntimeException _) {
            viewedId = null;
        }
        if (viewedId == null) return null;
        String targetViewedId = viewedId;
        return metrics.stream()
            .filter(metric -> metric != null
                && Strings.CS.equals(targetViewedId, metric.taskId()))
            .findFirst().orElse(null);
    }

    private String buildThinkingText() {
        if (thinkingStatus instanceof String) {
            return "thinking" + effortSuffix;
        } else if (thinkingStatus instanceof Long thinkMs) {
            return "thought for " + Math.max(1, Math.round(thinkMs / 1000.0)) + "s";
        }
        return "";
    }

/**
     * Update response length for token count display.
     */
    public void setResponseLength(int length) {
        this.responseLength.set(Math.max(0, length));
    }


    public void setThinking(boolean thinking) {
        if (thinking && thinkingStartMs < 0) {
            thinkingStartMs = System.currentTimeMillis();
        } else if (!thinking) {
            thinkingStartMs = -1;
        }
        this.thinkingStatus = thinking ? "thinking" : null;
    }

    /**
     * Whether the model is currently in an active thinking stretch — the port of 197's
     * {@code thinkingStartedAt !== null} guard, which gates salvaging thinking on cancel.
     */
    public boolean isThinkingActive() {
        return thinkingStartMs >= 0;
    }


    public void setThinkingDuration(long durationMs) {
        this.thinkingStartMs = -1;
        this.thinkingStatus = durationMs;
    }


    public void setEffortSuffix(String suffix) {
        this.effortSuffix = suffix != null ? suffix : "";
    }

    /**
     * Resolves the verb displayed in the spinner line.
     */
    private String resolveVerb() {
        String om = overrideMessage;
        if (StringUtils.isNotBlank(om)) return om;
        for (TaskBoardPort.TaskItem task : taskSnapshot.tasks()) {
            if (task.status() == TaskBoardPort.Status.IN_PROGRESS) {
                if (StringUtils.isNotBlank(task.activeForm())) return task.activeForm();
                return task.subject();
            }
        }
        return verb.get();
    }

    /** Finds the first pending task not blocked by an unresolved task. */
    private TaskBoardPort.TaskItem findNextPendingTask() {
        List<TaskBoardPort.TaskItem> tasks = taskSnapshot.tasks();
        Set<String> unresolved = tasks.stream()
            .filter(task -> task.status() != TaskBoardPort.Status.COMPLETED)
            .map(TaskBoardPort.TaskItem::id)
            .collect(Collectors.toSet());
        TaskBoardPort.TaskItem fallback = null;
        for (TaskBoardPort.TaskItem task : tasks) {
            if (task.status() != TaskBoardPort.Status.PENDING) continue;
            if (fallback == null) fallback = task;
            if (task.blockedBy().stream().noneMatch(unresolved::contains)) return task;
        }
        return fallback;
    }

    /**
     * Returns elapsed ms with permission-dialog-paused time subtracted.
     */
    private long adjustedElapsedMs() {
        long now = System.currentTimeMillis();
        long paused = totalPausedMs.get();
        long ps = pauseStartMs;
        if (ps >= 0) paused += now - ps;
        long clockStart = turnClockActive ? turnStartMs : startMs;
        return Math.max(0, now - clockStart - paused);
    }

    /** Active turn time for transcript/UI completion, excluding approval pauses. */
    public long adjustedElapsedMsForTranscript() {
        return adjustedElapsedMs();
    }


    public long turnStartMillis() {
        return turnClockActive ? turnStartMs : startMs;
    }

    /** End the submitted-turn clock after its duration and wait anchor are captured. */
    public synchronized void finishTurnClock() {
        turnClockActive = false;
        pauseStartMs = -1;
        totalPausedMs.set(0L);
    }

    /**
     * Freeze the elapsed timer while a permission dialog is open.
     */
    public void pauseTimer() {
        if (pauseStartMs < 0) {
            pauseStartMs = System.currentTimeMillis();
        }
    }

    /**
     * Resume the elapsed timer when the permission dialog closes.
     * Accumulates pause duration into {@code totalPausedMs}. Safe to call from any thread; idempotent.
     */
    public void resumeTimer() {
        long ps = pauseStartMs;
        if (ps >= 0) {
            totalPausedMs.addAndGet(System.currentTimeMillis() - ps);
            pauseStartMs = -1;
        }
    }

    // ── Renderer ──────────────────────────────────────────────────────────

    private class SpinnerRenderer implements InteractableRenderer<SpinnerComponent> {

        @Override
        public TerminalPosition getCursorLocation(SpinnerComponent c) { return null; }

        @Override
        public TerminalSize getPreferredSize(SpinnerComponent c) {
            return c.calculatePreferredSize();
        }

        @Override
        public void drawComponent(TextGUIGraphics g, SpinnerComponent c) {
            if (!c.isVisible()) return;
            // The suffix/tip can shrink without changing the component identity.
            // Clear this component's own cells first so the previous, longer
            // stop-hook label cannot remain in the terminal back buffer.
            g.fill(' ');
            // Update column hint BEFORE buildText so progressive gating uses current terminal width.
            c.lastDrawColumns = g.getSize().getColumns();
            String text = buildText();



            //   Stall starts at 3s, fades to error red over 2s (full red at 5s)
            //   tool-use mode → sinusoidal between claude() and dim (2s period)
            //   normal → claude() brand orange
            TextColor color;
            long now = System.currentTimeMillis();
            TextColor oc = overrideColor;
            if (staticIdleDisplay) {
                color = LanternaTheme.welcomeDim();
            } else if (oc != null) {
                color = oc;
            } else {
                long timeSinceActivity = now - lastActivityMs;
                if (timeSinceActivity > SpinnerFrames.STALL_START_MS) {
                    double stallIntensity = SpinnerFrames.computeStallIntensity(timeSinceActivity, false);
                    // Interpolate between claude() and toolError()
                    color = LanternaTheme.interpolate(
                        LanternaTheme.claude(), LanternaTheme.toolError(), stallIntensity);
                } else if (toolUseMode) {
                    long elapsed = now - startMs;
                    double phase = (elapsed % 2000) / 2000.0 * 2 * Math.PI;
                    double intensity = (Math.sin(phase) + 1) / 2.0;
                    color = intensity > 0.5 ? LanternaTheme.claude() : LanternaTheme.welcomeDim();
                } else {
                    color = LanternaTheme.claude();
                }
            }

            // Shimmer effect — delegates to ShimmerAnimation.compute() so the math (speed / cycle
            // length / lead offset / stalled sentinel) lives in one place with its unit tests,
            // instead of being duplicated as magic numbers here.
            TextColor shimmerColor = overrideShimmerColor != null ? overrideShimmerColor : LanternaTheme.claudeShimmer();
            String verbStr = renderedVerb;
            int verbStart = 2; // icon + space prefix
            int verbLen = verbStr.length();
            long elapsed = System.currentTimeMillis() - startMs;
            boolean shimmerOff = staticIdleDisplay || stalled.get() || verbLen == 0
                || SpinnerFrames.REDUCED_MOTION;
            int verbGlimmer = ShimmerAnimation.compute(
                ShimmerAnimation.Mode.TOOL_USE, verbLen, elapsed, shimmerOff);
            // compute() returns a position within the verb string [0, verbLen);
            // translate to display coordinates by adding verbStart. Preserve the
            // OFF_SCREEN sentinel so the "far from any char" branch below fires.
            int glimmerIdx = (verbGlimmer == ShimmerAnimation.OFF_SCREEN)
                ? ShimmerAnimation.OFF_SCREEN
                : verbStart + verbGlimmer;

            int maxW = g.getSize().getColumns();
            String display = text.length() > maxW ? text.substring(0, maxW) : text;
            int ms = metricStart, me = metricEnd;
            int ts = thinkingTextStart, te = thinkingTextEnd;
            TextColor dimColor = LanternaTheme.welcomeDim();


            TextColor thinkingColor = null;
            if (ts >= 0 && te >= 0 && thinkingStatus instanceof String && thinkingStartMs >= 0) {
                long thinkElapsed = now - thinkingStartMs;
                if (thinkElapsed > THINKING_DELAY_MS) {
                    double elapsedSec = (thinkElapsed - THINKING_DELAY_MS) / 1000.0;
                    double opacity = (Math.sin(elapsedSec * Math.PI * 2 / THINKING_GLOW_PERIOD_S) + 1) / 2.0;
                    int rv = (int) (153 + 32 * opacity); // 153→185
                    thinkingColor = new TextColor.RGB(rv, rv, rv);
                } else {
                    thinkingColor = dimColor; // pre-shimmer: plain dim
                }
            }

            for (int i = 0; i < display.length(); i++) {
                TextColor charColor = color;
                // 1. Metric "(…)" → dim
                if (ms >= 0 && me >= 0 && i >= ms && i < me) {
                    charColor = dimColor;
                }
                // 2. Thinking sub-range → shimmer (overrides dim)
                if (thinkingColor != null && i >= ts && i < te) {
                    charColor = thinkingColor;
                }
                // 3. Verb shimmer → shimmerColor (overrides everything, verb range only)
                if (glimmerIdx > -50 && Math.abs(i - glimmerIdx) <= 1
                        && i >= verbStart && i < verbStart + verbLen) {
                    charColor = shimmerColor;
                }
                g.setCharacter(i, 1,
                    TextCharacter.fromCharacter(display.charAt(i), charColor, TextColor.ANSI.DEFAULT));
            }


            int nextRow = 2;
            long compactStart = compactingStartMs;
            if (compactStart >= 0 && g.getSize().getRows() > nextRow) {
                int barWidth = Math.min(COMPACT_BAR_MAX_WIDTH, maxW - COMPACT_BAR_INDENT - 6);
                if (barWidth >= COMPACT_BAR_MIN_WIDTH) {
                    int percent = Math.max(compactPercentFloor, compactPercent(now - compactStart));
                    compactPercentFloor = percent;
                    String bar = compactBarText(percent, barWidth);
                    String pct = " " + percent + "%";
                    int fillCells = (int) Math.round(percent / 100.0 * barWidth);
                    for (int i = 0; i < bar.length() && COMPACT_BAR_INDENT + i < maxW; i++) {
                        // Fill cells use the terminal default foreground; empty cells are dim.
                        TextColor cellColor = i < fillCells
                            ? TextColor.ANSI.DEFAULT : dimColor;
                        g.setCharacter(COMPACT_BAR_INDENT + i, nextRow,
                            TextCharacter.fromCharacter(bar.charAt(i), cellColor, TextColor.ANSI.DEFAULT));
                    }
                    int pctStart = COMPACT_BAR_INDENT + bar.length();
                    for (int i = 0; i < pct.length() && pctStart + i < maxW; i++) {
                        g.setCharacter(pctStart + i, nextRow,
                            TextCharacter.fromCharacter(pct.charAt(i), dimColor, TextColor.ANSI.DEFAULT));
                    }
                    nextRow++;
                }
            }

            List<TreeRow> treeRows = teammateTreeRows(maxW);
            if (!treeRows.isEmpty()) {
                nextRow++; // TeammateSpinnerTree marginTop={1}
                for (TreeRow treeRow : treeRows) {
                    if (nextRow >= g.getSize().getRows()) break;
                    int x = 0;
                    for (TreeSegment segment : treeRow.segments()) {
                        if (x >= maxW || segment.text().isEmpty()) continue;
                        TextColor segmentColor = segment.color() != null
                            ? segment.color() : TextColor.ANSI.DEFAULT;
                        g.setForegroundColor(segmentColor);
                        if (segment.bold()) g.enableModifiers(SGR.BOLD);
                        String value = FormatUtils.truncate(segment.text(), Math.max(0, maxW - x));
                        g.putString(x, nextRow, value);
                        if (segment.bold()) g.disableModifiers(SGR.BOLD);
                        x += FormatUtils.displayWidth(value);
                    }
                    nextRow++;
                }
                g.setForegroundColor(TextColor.ANSI.DEFAULT);
            }

            // Render effective tip on the next row — auto-selects /btw or /clear tip by elapsed
            // time, falling back to the externally-set spinnerTip.
            String tip = treeRows.isEmpty() ? effectiveTip() : null;
            if (StringUtils.isNotBlank(tip) && g.getSize().getRows() > nextRow) {
                int maxTipW = g.getSize().getColumns() - 2;
                String tipDisplay = tip.length() > maxTipW ? FormatUtils.truncate(tip, maxTipW) : tip;
                tipDisplay = "  " + tipDisplay;
                for (int i = 0; i < tipDisplay.length() && i < g.getSize().getColumns(); i++) {
                    g.setCharacter(i, nextRow,
                        TextCharacter.fromCharacter(tipDisplay.charAt(i),
                            LanternaTheme.welcomeDim(), TextColor.ANSI.DEFAULT));
                }
            }
        }
    }

    /** Sets the optional tip text shown beneath the spinner. Pass null to hide. */
    public void setSpinnerTip(String tip) { this.spinnerTip = tip; invalidate(); }

/**
     * Sets the /btw use count from global config.
     */
    public void setBtwUseCount(int count) { this.btwUseCount = count; }


    public void setTipsEnabled(boolean enabled) { this.tipsEnabled = enabled; }


    public void setVerbose(boolean v) { this.verbose = v; }

    /**
     * Override the spinner color (icon + verb).
     */
    public void setOverrideColor(TextColor color) { this.overrideColor = color; }

    /**
     * Override the shimmer highlight color for the verb glimmer sweep.
     */
    public void setOverrideShimmerColor(TextColor color) { this.overrideShimmerColor = color; }

    /**
     * Override the displayed verb text.
     */
    public void setOverrideMessage(String msg) { this.overrideMessage = msg; }

    /** Replaces the Task* V2 snapshot used for the verb and Next hint. */
    public void setTaskSnapshot(TaskBoardPort.Snapshot snapshot) {
        taskSnapshot = snapshot == null ? TaskBoardPort.Snapshot.EMPTY : snapshot;
        invalidate();
    }




    public void setRunningTeammateMetricsSupplier(Supplier<List<TeammateMetric>> supplier) {
        this.runningTeammateMetricsSupplier = supplier != null ? supplier : List::of;
    }

    public void setViewedTeammateIdSupplier(Supplier<String> supplier) {
        this.viewedTeammateIdSupplier = supplier != null ? supplier : () -> null;
    }

    public void setTeammateSelectionSuppliers(BooleanSupplier selectingSupplier,
                                               Supplier<Integer> selectedIndexSupplier) {
        this.teammateSelectionModeSupplier = selectingSupplier != null
            ? selectingSupplier : () -> false;
        this.teammateSelectedIndexSupplier = selectedIndexSupplier != null
            ? selectedIndexSupplier : () -> -1;
    }

    public void setTeammateTreeExpanded(boolean expanded) {
        this.teammateTreeExpanded = expanded;
        invalidate();
    }

    public boolean isTeammateTreeExpanded() {
        return teammateTreeExpanded;
    }

    public void setTeammateSwarmFinishedListener(Runnable listener) {
        this.teammateSwarmFinishedListener = listener != null ? listener : () -> { };
    }

    /**
     * Computes the effective tip shown below the spinner line.
     */
    private String effectiveTip() {
        long elapsed = adjustedElapsedMs();
        TaskBoardPort.TaskItem nextTask = findNextPendingTask();

        if (tipsEnabled) {
            // Auto-tips suppressed when a next task is queued.
            if (elapsed > CLEAR_TIP_THRESHOLD_MS && nextTask == null) return CLEAR_TIP;
            if (elapsed > BTW_TIP_THRESHOLD_MS && btwUseCount == 0 && nextTask == null) return BTW_TIP;
        }
        if (nextTask != null) return "Next: " + nextTask.subject();
        return spinnerTip;
    }
}
