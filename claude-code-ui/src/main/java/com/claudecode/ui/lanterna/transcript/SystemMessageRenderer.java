package com.claudecode.ui.lanterna.transcript;

import static com.claudecode.ui.lanterna.transcript.SourceCodePainter.dimColor;

import com.claudecode.core.constants.Figures;
import com.claudecode.core.engine.AbortException;
import com.claudecode.core.message.FriendlyApiError;
import com.claudecode.core.message.GoalStatusAttachment;
import com.claudecode.core.message.RefusalFallbackFeature;
import com.claudecode.core.message.RefusalLearnMoreLink;
import com.claudecode.core.message.RefusalErrorMessage;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.message.SystemMessage;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.core.text.FormatUtils;
import com.claudecode.tools.tasks.PendingBackgroundWork;
import com.claudecode.ui.Ansi;
import com.claudecode.ui.lanterna.components.SpinnerVerbs;
import com.claudecode.ui.lanterna.features.settings.UiSettings;
import com.claudecode.ui.lanterna.theme.LanternaTheme;
import com.fasterxml.jackson.databind.JsonNode;
import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TextColor;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Renders the non-conversational rows of a transcript: {@link SDKMessage.System} notices
 * by subtype (api errors, memory saved, hook summaries, refusal fallback banners,
 * informational rows), the turn-duration summary with its budget / hidden-message /
 * background-work suffixes, {@link SDKMessage.Error}, API retries, compact boundaries,
 * attachments (hooks, goal status) and the fixed API status texts an assistant turn can
 * carry instead of prose.
 *
 * <ul>
 *   <li>{@code src/components/messages/SystemTextMessage.tsx} — level filter
 *       ({@code info} only in verbose), {@code ●} gutter for notice/warning rows.</li>
 *   <li>{@code src/components/messages/SystemMemorySavedMessage.tsx},
 *       {@code StopHookSummaryMessage.tsx}, {@code SystemApiErrorMessage.tsx},
 *       {@code AwaySummaryMessage.tsx}, {@code ScheduledTaskFireMessage.tsx} — the
 *       per-subtype rows.</li>
 *   <li>{@code src/components/messages/ModelRefusalFallbackMessage.tsx} — bold banner with
 *       the {@code learn more} hyperlink and the {@code /config} tip.</li>
 *   <li>{@code src/components/TurnDuration.tsx} — {@code ✻ Worked for …} / {@code Waiting
 *       for N background agents…}, token budget usage, hidden-message count.</li>
 *   <li>{@code src/components/messages/AssistantAPIErrorMessage.tsx},
 *       {@code AssistantRateLimitMessage.tsx} — the fixed error strings (prompt too long,
 *       credit balance, invalid key, org disabled, timed out, aborted) and rate-limit
 *       detection.</li>
 *   <li>{@code src/components/messages/HookSystemMessage.tsx}, {@code GoalStatus.tsx} —
 *       attachment bodies; {@code CompactBoundary.tsx} — the compacted marker.</li>
 * </ul>
 */
final class SystemMessageRenderer {

    /** Dispatcher state the system renderer reads. */
    interface Host {
        boolean verbose();
        boolean transcriptMode();
        String expandHint();
        /** {@code local_command} system messages reuse the user text renderer's tag handling. */
        void renderLocalCommand(String content, MessagePanel panel);
    }

    private static final Logger log = LoggerFactory.getLogger(SystemMessageRenderer.class);
    private static final String BLACK_CIRCLE = ToolResultLines.BLACK_CIRCLE;
    /**
     * Help article every refusal announcement ends with. The announcement text and
     * the refusal error line are built in core, so the url lives there too and this
     * is only the reader's name for it.
     */
    static final String REFUSAL_HELP_URL = RefusalErrorMessage.LEARN_MORE_URL;

    private final Host host;

    SystemMessageRenderer(Host host) {
        this.host = host;
    }

    private Supplier<PendingBackgroundWork> pendingBackgroundWorkSupplier;
    private Supplier<String> backgroundTaskSummarySupplier;

    void setTurnSummaryContext(Supplier<PendingBackgroundWork> pendingSupplier,
                                      Supplier<String> summarySupplier) {
        this.pendingBackgroundWorkSupplier = pendingSupplier;
        this.backgroundTaskSummarySupplier = summarySupplier;
    }

    /**
     * Terminal hyperlink capability. Production reads the process environment;
     * tests inject a constant so neither the environment nor {@code Ansi}'s
     * package-private overload has to be widened.
     */
    private BooleanSupplier hyperlinkSupport = Ansi::supportsHyperlinks;

    void renderSystem(SDKMessage.System msg, MessagePanel panel) {
        if (msg.message() == null) return;
        SystemMessage message = msg.message();
        String subtype = message.subtype() != null ? message.subtype() : "";
        if (Strings.CS.equals("turn_duration", subtype) && message.durationMs() != null) {
            Integer pendingAgents = message.pendingBackgroundAgentCount();
            Integer pendingWorkflows = message.pendingWorkflowCount();
            PendingBackgroundWork current = currentPendingBackgroundWork();
            if (current != null) {
                if (current.pendingAgents() <= 0) pendingAgents = null;
                if (current.pendingWorkflows() <= 0) pendingWorkflows = null;
            }
            renderTurnSummary(panel, message.durationMs(), pendingAgents, pendingWorkflows,
                currentBackgroundTaskSummary(),
                message.budgetTokens(), message.budgetLimit(), message.budgetNudges(),
                message.briefHiddenCount());
            return;
        }
        if (message.content() == null) return;
        String content = message.content();
        String level = message.level() != null ? message.level() : "info";

        // Suppress session-init metadata noise:
        if (Strings.CS.startsWith(content, "Session:") || Strings.CS.contains(content, "| Tools:")) {
            return;
        }
        if (Strings.CS.equals("api_metrics", subtype)) {
            return;
        }

        if (Strings.CS.equals("thinking", subtype)) {
            return;
        }
// A refusal the CLI could not retry on a fallback model is silent.
        if (Strings.CS.equals("model_refusal_no_fallback", subtype)) {
            return;
        }

        // if (!isStopHookSummary && !host.verbose() && message.level === "info") return null;
        // Exception: local_command carries user-visible command echo + result tags

        //  bypassing SystemTextMessage's level filter entirely).
        if (!Strings.CS.equals("stop_hook_summary", subtype) && !Strings.CS.equals("turn_duration", subtype)
                && !Strings.CS.equals("memory_saved", subtype) && !Strings.CS.equals("api_error", subtype)
                && !Strings.CS.equals("compact_boundary", subtype) && !Strings.CS.equals("local_command", subtype)
                && !Strings.CS.equals("scheduled_task_fire", subtype)
                && !host.verbose() && Strings.CS.equals("info", level)) {
            return;
        }

        switch (subtype) {
            case "api_error" -> {
                String truncated = content.length() > 1000
                    ? FormatUtils.truncate(content, 1000) : content;
                panel.appendLine("✗ " + truncated, LanternaTheme.toolError());
            }

            case "memory_saved" ->
                panel.appendMixed(List.of(
                    new MessagePanel.Segment(BLACK_CIRCLE, LanternaTheme.welcomeDim()),
                    new MessagePanel.Segment("Saved " + content, LanternaTheme.inputText())
                ));


            case "permission_retry" ->
                panel.appendMixed(List.of(
                    new MessagePanel.Segment(Figures.TEARDROP_ASTERISK + " ", LanternaTheme.welcomeDim()),
                    new MessagePanel.Segment("Allowed ", TextColor.ANSI.DEFAULT),
                    new MessagePanel.Segment(content, LanternaTheme.inputText())
                ));

            case "bridge_status" ->

                panel.appendLine("ℹ " + content, LanternaTheme.agentCyan());

            case "stop_hook_summary" ->

                panel.appendMixed(List.of(
                    new MessagePanel.Segment(BLACK_CIRCLE, LanternaTheme.welcomeDim()),
                    new MessagePanel.Segment(content, LanternaTheme.welcomeDim())
                ));

            case "turn_duration" ->
                panel.appendLine(
                    Figures.TEARDROP_ASTERISK + " " + SpinnerVerbs.randomCompleted() + " for " + content,
                    LanternaTheme.welcomeDim());

            case "away_summary" ->

                // 236 kyr: dim ※ gutter + "recap:" label (dim bold) + content (dim italic).
                panel.appendMixed(List.of(
                    new MessagePanel.Segment(Figures.REFERENCE_MARK + " ",
                        dimColor(TextColor.ANSI.DEFAULT)),
                    new MessagePanel.Segment("recap: ",
                        dimColor(TextColor.ANSI.DEFAULT), null, null, Set.of(SGR.BOLD)),
                    new MessagePanel.Segment(content,
                        dimColor(TextColor.ANSI.DEFAULT), null, null, Set.of(SGR.ITALIC))
                ));

            case "scheduled_task_fire" -> {

                if (panel.snapshotLineCount() > 0) {
                    panel.appendLine("", TextColor.ANSI.DEFAULT);
                }
                panel.appendLine(Figures.TEARDROP_ASTERISK + " " + content, LanternaTheme.welcomeDim());
            }

            case "informational" -> {
// matches SystemTextMessageInner. Verbose info has no dot and is
                // dim; notice/warning rows have a BLACK_CIRCLE gutter. Only the
                // warning level applies warning color — notice uses terminal text.
                if (Strings.CS.equals("info", level)) {
                    panel.appendLine(content, LanternaTheme.welcomeDim(), 10);
                } else {
                    TextColor color = Strings.CS.equals("warning", level)
                        ? LanternaTheme.statusCost() : TextColor.ANSI.DEFAULT;
                    panel.appendMixed(List.of(
                        new MessagePanel.Segment(BLACK_CIRCLE, color),
                        new MessagePanel.Segment(content, color)
                    ), 10);
                }
            }

            case "agents_killed" ->
                panel.appendLine("⚠ " + content, LanternaTheme.modeAuto());

            case "microcompact_boundary" ->
                panel.appendLine("  ─── microcompact ───", LanternaTheme.welcomeDim());

            case "compact_boundary" ->

                panel.appendLine("  ✻ Conversation compacted (ctrl+o for history)",
                    LanternaTheme.welcomeDim());

// local_command system messages carry one of: (1) <command-name>...
            case "local_command" -> host.renderLocalCommand(content, panel);

            // The announcement that a refused turn was retried on a fallback
            // model. Two rows: a bold warning banner and a dim tip pointing at
            // /config. The banner survives the retraction filter that drops the
            // messages it took back (see RetractedMessages).
            case "model_refusal_fallback" -> {
                if (RefusalFallbackFeature.enabled()) {
                    List<MessagePanel.Segment> banner = new ArrayList<>();
                    banner.add(new MessagePanel.Segment(BLACK_CIRCLE, LanternaTheme.statusCost()));
                    banner.addAll(refusalBodySegments(content));
                    panel.appendMixed(banner);
                    panel.appendLine("  " + Figures.RESULT_BRANCH
                            + "  Tip: You can configure model switch behavior in /config",
                        LanternaTheme.welcomeDim());
                }
            }

            default -> {

                boolean isWarning = Strings.CS.equals("warning", level);
                TextColor msgColor = isWarning ? LanternaTheme.statusCost() : LanternaTheme.toolError();
                panel.appendMixed(List.of(
                    new MessagePanel.Segment(BLACK_CIRCLE, msgColor),
                    new MessagePanel.Segment(content, msgColor)
                ), 10);
            }
        }
    }

    private PendingBackgroundWork currentPendingBackgroundWork() {
        Supplier<PendingBackgroundWork> supplier = pendingBackgroundWorkSupplier;
        if (supplier == null) return null;
        try {
            return supplier.get();
        } catch (RuntimeException failure) {
            log.debug("Failed to sample pending background work", failure);
            return PendingBackgroundWork.NONE;
        }
    }

    private String currentBackgroundTaskSummary() {
        Supplier<String> supplier = backgroundTaskSummarySupplier;
        if (supplier == null) return null;
        try {
            return supplier.get();
        } catch (RuntimeException failure) {
            log.debug("Failed to sample background task summary", failure);
            return null;
        }
    }

    /**
     * Splits the trailing {@code learn more: <url>} run out of a refusal
     * announcement body into an underlined hyperlink whose visible label is just
     * {@code learn more}. Falls back to a single bold run when the terminal has
     * no hyperlink support or the marker is absent — see
     * {@link RefusalLearnMoreLink}, which the pause dialog shares.
     */
    private List<MessagePanel.Segment> refusalBodySegments(String content) {
        RefusalLearnMoreLink.Split split =
            RefusalLearnMoreLink.split(content, hyperlinkSupport.getAsBoolean());
        if (!split.linked()) {
            return List.of(new MessagePanel.Segment(content, LanternaTheme.statusCost(),
                null, null, Set.of(SGR.BOLD)));
        }
        List<MessagePanel.Segment> segments = new ArrayList<>(3);
        if (!split.head().isEmpty()) {
            segments.add(new MessagePanel.Segment(split.head(), LanternaTheme.statusCost(),
                null, null, Set.of(SGR.BOLD)));
        }
        segments.add(MessagePanel.Segment.hyperlink(RefusalLearnMoreLink.LINK_TEXT,
            LanternaTheme.statusCost(), split.url(), Set.of(SGR.BOLD, SGR.UNDERLINE)));
        if (!split.tail().isEmpty()) {
            segments.add(new MessagePanel.Segment(split.tail(), LanternaTheme.statusCost(),
                null, null, Set.of(SGR.BOLD)));
        }
        return segments;
    }

    /** Test seam; production keeps the environment probe. */
    void setHyperlinkSupport(BooleanSupplier support) {
        this.hyperlinkSupport = support;
    }

    void renderError(SDKMessage.Error msg, MessagePanel panel) {


        // it never produces an Error message, it just resets loading state.
        if (msg.exception() instanceof AbortException) {
            return;
        }
        String friendly = msg.exception() instanceof FriendlyApiError fae ? fae.friendlyMessage() : null;
        String text = friendly != null
            ? "✗ " + friendly
            : msg.exception() != null
                ? "✗ Error: " + msg.exception().getMessage()
                : "✗ Unknown error";
        panel.appendLine(text, LanternaTheme.toolError());
    }

    /**
     * Turn-summary line that also reports what the turn is still waiting on.
     */
    void renderTurnSummary(MessagePanel panel, long elapsedMs,
                                  Integer pendingAgentCount, Integer pendingWorkflowCount,
                                  String backgroundTaskSummary) {
        renderTurnSummary(panel, elapsedMs, pendingAgentCount, pendingWorkflowCount,
            backgroundTaskSummary, null, null, null, null);
    }

    void renderTurnSummary(MessagePanel panel, long elapsedMs,
                                  Integer pendingAgentCount, Integer pendingWorkflowCount,
                                  String backgroundTaskSummary,
                                  Long budgetTokens, Long budgetLimit,
                                  Integer budgetNudges, Integer briefHiddenCount) {
        renderTurnSummaryWithVisibility(panel, elapsedMs, pendingAgentCount,
            pendingWorkflowCount, backgroundTaskSummary, budgetTokens, budgetLimit,
            budgetNudges, briefHiddenCount,
            UiSettings.readGlobalBoolean("showTurnDuration", true));
    }

    void renderTurnSummaryWithVisibility(MessagePanel panel, long elapsedMs,
                                  Integer pendingAgentCount, Integer pendingWorkflowCount,
                                  String backgroundTaskSummary,
                                  Long budgetTokens, Long budgetLimit,
                                  Integer budgetNudges, Integer briefHiddenCount,
                                  boolean showDuration) {
        boolean hasBudget = budgetLimit != null;
        int hiddenCount = briefHiddenCount == null ? 0 : briefHiddenCount;
        if (!showDuration && !hasBudget && hiddenCount <= 0) return;
        int agents    = pendingAgentCount    == null ? 0 : pendingAgentCount;
        int workflows = pendingWorkflowCount == null ? 0 : pendingWorkflowCount;
        boolean waiting = agents > 0 || workflows > 0;
        TextColor dim = LanternaTheme.welcomeDim();
        List<MessagePanel.Segment> line = new ArrayList<>();
        line.add(new MessagePanel.Segment("✻ ", dim));
        if (showDuration && waiting) {
            line.add(new MessagePanel.Segment("Waiting for", dim));
            if (agents > 0) {
                line.add(new MessagePanel.Segment(" ", dim));
                line.add(new MessagePanel.Segment(String.valueOf(agents), dim, null, null,
                    Set.of(SGR.BOLD)));
                line.add(new MessagePanel.Segment(
                    agents == 1 ? " background agent" : " background agents", dim));
            }
            if (agents > 0 && workflows > 0) {
                line.add(new MessagePanel.Segment(" and", dim));
            }
            if (workflows > 0) {
                line.add(new MessagePanel.Segment(" ", dim));
                line.add(new MessagePanel.Segment(String.valueOf(workflows), dim, null, null,
                    Set.of(SGR.BOLD)));
                line.add(new MessagePanel.Segment(
                    workflows == 1 ? " dynamic workflow" : " dynamic workflows", dim));
            }
            line.add(new MessagePanel.Segment(" to finish", dim));
        } else if (showDuration) {
            String verb    = SpinnerVerbs.randomCompleted();
            String elapsed = FormatUtils.formatDuration(elapsedMs);
            line.add(new MessagePanel.Segment(verb + " for " + elapsed, dim));
        }
        if (hasBudget) {
            long tokens = budgetTokens == null ? 0L : budgetTokens;
            long limit = budgetLimit;
            String usage = tokens >= limit
                ? FormatUtils.formatNumber(tokens) + " used ("
                    + FormatUtils.formatNumber(limit) + " min ✔)"
                : FormatUtils.formatNumber(tokens) + " / "
                    + FormatUtils.formatNumber(limit) + " ("
                    + Math.round(limit == 0L ? 0D : tokens * 100D / limit) + "%)";
            String separator = showDuration ? " · " : "";
            line.add(new MessagePanel.Segment(separator + usage, dim));
            int nudges = budgetNudges == null ? 0 : budgetNudges;
            if (nudges > 0) {
                line.add(new MessagePanel.Segment(" · " + nudges + " "
                    + (nudges == 1 ? "nudge" : "nudges"), dim));
            }
        }
        if (hiddenCount > 0) {
            String separator = showDuration || hasBudget ? " · " : "";
            line.add(new MessagePanel.Segment(separator + hiddenCount + " "
                + (hiddenCount == 1 ? "message" : "messages")
                + " hidden (/focus to show)", dim));
        }
        if (!(showDuration && waiting) && StringUtils.isNotBlank(backgroundTaskSummary)) {
            line.add(new MessagePanel.Segment(" · " + backgroundTaskSummary + " still running", dim));
        }
        panel.appendLine("", dim);                                                        // spacer
        panel.appendMixed(line);
    }

    void renderRetry(SDKMessage.ApiRetry retry, MessagePanel panel) {
        panel.appendLine(
            "⟳ Retry: " + retry.error() + " (attempt " + retry.attempt() + ")",
            LanternaTheme.modeAuto());
    }

    void renderCompactBoundary(MessagePanel panel) {

        panel.appendLine("", LanternaTheme.welcomeDim()); // top margin
        panel.appendLine("✻ Conversation compacted (ctrl+o for history)",
            LanternaTheme.welcomeDim());
        panel.appendLine("", LanternaTheme.welcomeDim()); // bottom margin
    }

    void renderAttachment(SDKMessage.Attachment attachment, MessagePanel panel) {
        switch (attachment.attachmentType()) {
            case "max_turns_reached" ->
                panel.appendLine("⚠ Maximum turns reached — conversation ended.", LanternaTheme.modeAuto());
            case "hook_success", "hook_output" ->
                panel.appendLine("  [hook] " + FormatUtils.truncate(attachment.content(), 120), LanternaTheme.welcomeDim());
            case "hook_error" ->
                panel.appendLine("✗ [hook error] " + FormatUtils.truncate(attachment.content(), 120), LanternaTheme.toolError());
            case "hook_system_message" -> renderHookSystemMessage(attachment.content(), panel);
            case "queued_command" ->
                panel.appendLine("➳ Queued: " + FormatUtils.truncate(attachment.content(), 80), LanternaTheme.agentCyan());
            case "goal_status" -> renderGoalStatus(attachment.content(), panel);
            case "structured_output", "hook_non_blocking_error" -> {
                /* transcript metadata — not displayed inline */
            }
            default -> {
                // Show unknown attachments as dim metadata
                if (StringUtils.isNotBlank(attachment.content())) {
                    panel.appendLine(
                        "[" + attachment.attachmentType() + "] " + FormatUtils.truncate(attachment.content(), 100),
                        LanternaTheme.welcomeDim());
                }
            }
        }
    }

    private void renderHookSystemMessage(String content, MessagePanel panel) {
        try {
            JsonNode payload = JsonUtils.getMapper().readTree(content);
            String hookName = payload.path("hookName").asText("Hook");
            String message = payload.path("content").asText("");
            if (StringUtils.isNotBlank(message)) {
                panel.appendLine(hookName + " says: " + message, LanternaTheme.inputText());
            }
        } catch (Exception _) {
            if (StringUtils.isNotBlank(content)) {
                panel.appendLine(content, LanternaTheme.inputText());
            }
        }
    }

    private void renderGoalStatus(String content, MessagePanel panel) {
        if (StringUtils.isBlank(content)) return;
        try {
            GoalStatusAttachment status = JsonUtils.getMapper()
                .readValue(content, GoalStatusAttachment.class);
            if (status.hasSentinelMarker()) return;
            panel.appendLine("", TextColor.ANSI.DEFAULT);

            if (!status.met() && !status.hasFailedMarker()) {
                panel.appendLine("✶ Goal not yet met… continuing", LanternaTheme.welcomeDim());
                if (host.verbose() || host.transcriptMode()) {
                    if (StringUtils.isNotBlank(status.condition())) {
                        panel.appendLine("  Goal: " + status.condition(), LanternaTheme.welcomeDim());
                    }
                    if (StringUtils.isNotBlank(status.reason())) {
                        panel.appendLine("  Reason: " + status.reason(), LanternaTheme.welcomeDim());
                    }
                }
                return;
            }

            String stats = goalStats(status);
            if (status.hasFailedMarker()) {
                panel.appendLine("✗ Goal could not be achieved" + stats, LanternaTheme.toolError());
                if (StringUtils.isNotBlank(status.reason())) {
                    panel.appendLine("  " + status.reason(), LanternaTheme.welcomeDim());
                }
            } else {
                panel.appendLine("✓ Goal achieved" + stats, LanternaTheme.toolSuccess());
            }

            if (host.verbose() || host.transcriptMode()) {
                if (StringUtils.isNotBlank(status.condition())) {
                    panel.appendLine("  Goal: " + status.condition(), LanternaTheme.welcomeDim());
                }
                if (!status.hasFailedMarker() && status.reason() != null && !StringUtils.isBlank(status.reason())) {
                    panel.appendLine("  Reason: " + status.reason(), LanternaTheme.welcomeDim());
                }
            }

        } catch (Exception e) {
            log.warn("Unable to render goal_status attachment: {}", e.getMessage());
        }
    }

    private static String goalStats(GoalStatusAttachment status) {
        if (status.durationMs() == null && status.iterations() == null && status.tokens() == null) {
            return "";
        }
        long duration = status.durationMs() != null ? status.durationMs() : 0L;
        int turns = status.iterations() != null ? status.iterations() : 0;
        long tokens = status.tokens() != null ? status.tokens() : 0L;
        return " (" + FormatUtils.formatDuration(duration, true, true)
            + " · " + turns + " " + (turns == 1 ? "turn" : "turns")
            + " · " + FormatUtils.formatTokens(tokens) + " tokens)";
    }

    /**
     * Handle special assistant error/status text.
     */
    boolean renderSpecialAssistantText(String text, MessagePanel panel) {

        if (Strings.CS.equals("No response requested.", text)) return true;

        TextColor err = LanternaTheme.toolError();
        switch (text) {
            case "Prompt is too long" -> {
                panel.appendLine("Context limit reached · /compact or /clear to continue", err);
                return true;
            }
            case "Credit balance is too low" -> {
                panel.appendLine(
                    "Credit balance too low · Add funds: https://platform.claude.com/settings/billing", err);
                return true;
            }
// INVALID_API_KEY (both variants).
            case "Not logged in · Please run /login",
                 "Invalid API key · Fix external API key" -> {
                panel.appendLine(text, err);
                return true;
            }
            // ORG_DISABLED (both variants)
            case "Your ANTHROPIC_API_KEY belongs to a disabled organization · Unset the environment variable to use your subscription instead",
                 "Your ANTHROPIC_API_KEY belongs to a disabled organization · Update or unset the environment variable" -> {
                panel.appendLine(text, err);
                return true;
            }
            case "OAuth token revoked · Please run /login" -> {
                panel.appendLine(text, err);
                return true;
            }
            case "Request timed out" -> {
                panel.appendLine(text, err);
                return true;
            }
            case "Opus is experiencing high load, please use /model to switch to Sonnet" -> {
                panel.appendLine(text, err);
                return true;
            }

            case "API Error: Request was aborted." -> {
                panel.appendLine("Interrupted by user", LanternaTheme.welcomeDim());
                return true;
            }
        }

        if (Strings.CS.startsWith(text, "API Error") || Strings.CS.startsWith(text, "Please run /login · API Error")) {
            if (!host.verbose() && !host.transcriptMode() && text.length() > 1000) {
                panel.appendLine(FormatUtils.truncate(text, 1000), err);
                panel.appendMixed(List.of(
                    new MessagePanel.Segment("  " + host.expandHint(), LanternaTheme.welcomeDim())
                ));
            } else {
                panel.appendLine(text, err);
            }
            return true;
        }
        return false;
    }

    static boolean isRateLimitError(String text) {
        if (StringUtils.isEmpty(text)) return false;
        String[] prefixes = {
            "You've hit your",
            "You've used",
            "You're now using extra usage",
            "You're close to",
            "You're out of extra usage"
        };
        for (String prefix : prefixes) {
            if (Strings.CS.startsWith(text, prefix)) return true;
        }
        return false;
    }
}
