package com.claudecode.ui.lanterna.input;

import com.claudecode.permissions.PermissionMode;
import com.claudecode.ui.lanterna.status.StatusLineComponent;
import com.claudecode.ui.lanterna.theme.LanternaTheme;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.gui2.Label;
import java.util.Locale;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.apache.commons.lang3.StringUtils;

/**
 * The prompt's hint row (main + suffix labels) and the status line above it.
 *
 * <p>The hint row shows exactly one thing at a time, chosen by a fixed
 * priority: an active temporary notification → paste in progress → history
 * search status → message-actions overlay → selected workflow row → teammate
 * navigation → the leader's permission-mode chip. Callers never write the
 * labels directly; they supply a {@link Context} and let {@link #render} pick.
 *
 * <p>The status line keeps a persistent custom/native HUD and a transient
 * progress/view text; transient text is shown only while no persistent HUD is
 * visible and never destroys it.
 *
 * <ul>
 *   <li>{@code src/components/PromptInput/PromptInputFooterLeftSide.tsx} — the
 *       {@code modePart} permission chip and {@code ? for shortcuts} /
 *       {@code (shift+tab to cycle)} hints.</li>
 *   <li>{@code src/components/PromptInput/Notifications.tsx} — timed
 *       notifications ({@code Esc again to clear}, teammate mode changes) that
 *       temporarily replace the hint.</li>
 *   <li>{@code src/components/PromptInput/HistorySearchInput.tsx} — the
 *       {@code search prompts: } / {@code no matching prompt: } status.</li>
 *   <li>{@code src/components/StatusLine.tsx} — the custom status-line HUD.</li>
 * </ul>
 */
final class PromptHintBar {

    /** Prompt state the hint depends on but does not own. */
    record Context(
        boolean pasting,
        boolean messageActionsActive,
        String messageActionsHint,
        String permissionMode,
        boolean loading,
        boolean agentsHintEnabled
    ) {}

    private final Label mainLabel = new Label("");
    private final Label suffixLabel = new Label("");
    private final StatusLineComponent statusLine = new StatusLineComponent();
    private final ScheduledExecutorService scheduler;
    private final Runnable refreshHint;

    private ScheduledFuture<?> temporaryTimer;
    private String historySearchStatus;
    /** Persistent custom/native HUD state; transient progress must never destroy it. */
    private String persistentStatusText;
    private int persistentStatusPadding;
    private boolean persistentStatusVisible;
    /** Short-lived progress/view text, used only while no persistent HUD is visible. */
    private String transientStatusText;
    private int transientStatusPadding;

    /**
     * @param scheduler runs temporary-hint expiry
     * @param refreshHint recomputes the whole hint row (the owner's full refresh,
     *     including footer pills and vim label), invoked after status changes and
     *     when a temporary hint expires
     */
    PromptHintBar(ScheduledExecutorService scheduler, Runnable refreshHint) {
        this.scheduler = scheduler;
        this.refreshHint = refreshHint;
    }

    Label mainLabel() { return mainLabel; }

    Label suffixLabel() { return suffixLabel; }

    StatusLineComponent statusLine() { return statusLine; }

    String mainText() { return mainLabel.getText(); }

    /** Main and suffix joined the way they read on screen. */
    String combinedText() {
        String main = mainLabel.getText();
        String suffix = suffixLabel.getText();
        if (!main.isEmpty() && !suffix.isEmpty()) return main + " " + suffix;
        return main + suffix;
    }

    // ── Status line ─────────────────────────────────────────────────────────

    /** Renders {@code text} (possibly ANSI-colored, multi-line) as the custom status line. */
    void setStatusLine(String text, int padding) {
        persistentStatusText = text;
        persistentStatusPadding = padding;
        renderEffectiveStatusLine();
    }

    void clearStatusLine() {
        persistentStatusText = null;
        persistentStatusPadding = 0;
        renderEffectiveStatusLine();
    }

    /** Shows progress/navigation text without replacing the persistent HUD. */
    void setTransientStatusLine(String text, int padding) {
        transientStatusText = text;
        transientStatusPadding = padding;
        renderEffectiveStatusLine();
    }

    void clearTransientStatusLine() {
        transientStatusText = null;
        transientStatusPadding = 0;
        renderEffectiveStatusLine();
    }

    private void renderEffectiveStatusLine() {
        if (StringUtils.isNotBlank(persistentStatusText)) {
            statusLine.setStatusText(persistentStatusText, persistentStatusPadding);
            persistentStatusVisible = true;
        } else if (StringUtils.isNotBlank(transientStatusText)) {
            statusLine.setStatusText(transientStatusText, transientStatusPadding);
            persistentStatusVisible = false;
        } else {
            statusLine.clear();
            persistentStatusVisible = false;
        }
        refreshHint.run();
    }

    // ── Temporary notifications ─────────────────────────────────────────────

    /** Replaces the hint with {@code text} for {@code timeoutMs}, then restores the normal hint. */
    void showTemporary(String text, TextColor color, long timeoutMs) {
        cancelTemporary();
        setLabel(mainLabel, "  " + text);
        mainLabel.setForegroundColor(color);
        setLabel(suffixLabel, "");
        temporaryTimer = scheduler.schedule(() -> {
            temporaryTimer = null;
            refreshHint.run();
        }, timeoutMs, TimeUnit.MILLISECONDS);
    }

    /** Drops a pending temporary notification without repainting. */
    void cancelTemporary() {
        ScheduledFuture<?> timer = temporaryTimer;
        if (timer != null) {
            timer.cancel(false);
            temporaryTimer = null;
        }
    }

    boolean temporaryActive() { return temporaryTimer != null; }

    /** History-search status overrides everything but a temporary notification. */
    void setHistorySearchStatus(String query, boolean failedMatch) {
        if (query != null) cancelTemporary();
        historySearchStatus = query == null ? null
            : (failedMatch ? "no matching prompt: " : "search prompts: ") + query;
        refreshHint.run();
    }

    /** The queued-input reminder shown instead of the leader hint while commands wait. */
    void showQueuedHint() {
        setLabel(mainLabel, "");
        setLabel(suffixLabel, "Press up to edit queued messages");
        suffixLabel.setForegroundColor(LanternaTheme.queuedText());
    }

    // ── Rendering ───────────────────────────────────────────────────────────

    /** Repaints the hint row by priority; a live temporary notification is left untouched. */
    void render(Context ctx, PromptFooter footer) {
        if (temporaryActive()) return;
        if (ctx.pasting()) {
            show("  Pasting text…", LanternaTheme.welcomeDim(), "", LanternaTheme.welcomeDim());
            return;
        }
        if (historySearchStatus != null) {
            show("  " + historySearchStatus, LanternaTheme.welcomeDim(), "",
                LanternaTheme.welcomeDim());
            return;
        }
        if (ctx.messageActionsActive()) {
            show("  " + ctx.messageActionsHint(), LanternaTheme.inputText(),
                " · ↑↓ navigate · esc back", LanternaTheme.welcomeDim());
            return;
        }
        if (footer.isWorkflowSelected()) {
            PromptFooter.WorkflowHint hint = footer.workflowHint();
            if (hint != null) {
                show(hint.main(), LanternaTheme.welcomeDim(), hint.suffix(),
                    LanternaTheme.welcomeDim());
                return;
            }
        } else if (footer.taskNavigation().isActive()) {
            // Teammate-view mode shows navigation/status instead of the leader's hint.
            PromptTaskNavigationController.TeammateHint hint = footer.teammateHint();
            if (hint != null) {
                show(hint.main(),
                    hint.accent() ? LanternaTheme.claude() : LanternaTheme.welcomeDim(),
                    hint.suffix(), LanternaTheme.welcomeDim());
                return;
            }
        }
        renderLeaderHint(ctx);
    }

    /** Leader-only hint banner (permission-mode chip + shift+tab hint). */
    private void renderLeaderHint(Context ctx) {
        PermissionMode mode = PermissionMode.fromString(ctx.permissionMode());
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
        boolean showAgentsHint = !ctx.loading() && ctx.agentsHintEnabled();
        String agents = showAgentsHint ? " · ← for agents" : "";
        String suffix = mode == PermissionMode.DEFAULT
            ? (persistentStatusVisible ? "" : "  ? for shortcuts" + agents)
            : "(shift+tab to cycle)" + agents;
        show(mainText, mainColor, suffix, LanternaTheme.welcomeDim());
    }

    private void show(String main, TextColor mainColor, String suffix, TextColor suffixColor) {
        setLabel(mainLabel, main);
        mainLabel.setForegroundColor(mainColor);
        setLabel(suffixLabel, suffix);
        suffixLabel.setForegroundColor(suffixColor);
    }

    private static void setLabel(Label label, String text) {
        String value = text == null ? "" : text;
        label.setText(value);
        label.setVisible(!value.isEmpty());
    }
}
