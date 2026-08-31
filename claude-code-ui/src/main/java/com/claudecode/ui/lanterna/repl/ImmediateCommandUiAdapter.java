package com.claudecode.ui.lanterna.repl;

import org.apache.commons.lang3.StringUtils;
import com.claudecode.commands.Command;
import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandResult;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.claudecode.ui.lanterna.input.InputPanel;
import com.claudecode.ui.lanterna.theme.LanternaTheme;
import com.claudecode.ui.lanterna.transcript.MessagePanel;

/**
 * Immediate-command bypass for the REPL input loop.
 */
public final class ImmediateCommandUiAdapter {

    private static final Logger log = LoggerFactory.getLogger(ImmediateCommandUiAdapter.class);

    private final InputPanel   inputPanel;
    private final MessagePanel messagePanel;
    private final Consumer<Runnable> onGuiThread;

    /**
     * Constructs the adapter with its two UI panel dependencies.
     *
     * @param inputPanel   the REPL input box (stash / restore target)
     * @param messagePanel the message output panel (for displaying results)
     * @param onGuiThread  marshals Lanterna component mutation onto the GUI thread;
     *                     {@code null} runs inline, which only headless tests may do
     */
    public ImmediateCommandUiAdapter(InputPanel inputPanel, MessagePanel messagePanel,
                                     Consumer<Runnable> onGuiThread) {
        this.inputPanel   = inputPanel;
        this.messagePanel = messagePanel;
        this.onGuiThread  = onGuiThread != null ? onGuiThread : Runnable::run;
    }

    // ──────────────────────────────────────────────────────────────────────
    // Public API
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Attempt to dispatch {@code cmd} via the immediate-bypass path.
     */
    public boolean tryDispatchImmediate(
            Command cmd,
            String args,
            boolean fromKeybinding,
            boolean queryActive,
            CommandContext ctx) {

        if (!shouldTreatAsImmediate(cmd, fromKeybinding, queryActive)) {
            return false;
        }

// Clear the input box.

        inputPanel.setText("");

// Execute on a virtual thread — command.execute may do I/O (file reads, etc.)
        // and must not block the Lanterna GUI event thread.
        final String commandName = cmd.name();
        Thread.ofVirtual()
              .name("immediate-cmd-" + commandName)
              .start(() -> executeAndNotify(cmd, args, ctx, commandName));




//        1. Datadog — trackDatadogEvent，走 Statsig feature gate tengu_log_datadog_events 控制开关；发送前用 stripProtoFields
//        剥掉所有 _PROTO_* 字段（防 PII 泄漏）
//        2. 1P BigQuery — logEventTo1P，通过 firstPartyEventLoggingExporter，保留完整 payload（包括 _PROTO_* 字段，这些路由到
//        BigQuery protected columns）

//        两条通道都是 Anthropic 内部基础设施，和 api.anthropic.com 完全无关——用户的对话内容通过 API
//        发，遥测事件走单独的内部通道。

//        另外有个 isSinkKilled 开关可以紧急关闭各个 sink，Statsig killswitch 控制。
//        Datadog
//        https://http-intake.logs.us5.datadoghq.com/api/v2/logs
//        用的是硬编码的 public client token，批量发送（最多 100 条，每 15 秒 flush 一次）。只限白名单里 ~40 种事件名，_PROTO_*
//            字段发前剥掉。

//        1P (firstParty)
//        https://api.anthropic.com/api/event_logging/batch
//        这确实发到 api.anthropic.com，但走的是 /api/event_logging/batch 这个专用内部端点，不是 AI 对话接口。事件序列化成
//        ClaudeCodeInternalEvent proto 格式，用用户的 OAuth/API key 作为鉴权（401 时降级为无鉴权）。失败事件持久化到
//        ~/.claude/telemetry/，二次方退避重试，最多 8 次。
// TODO(analytics): replace with AnalyticsService.recordEvent once wired.
        log.debug("[analytics] tengu_immediate_command_executed commandName={} fromKeybinding={}",
                  commandName, fromKeybinding);

        return true;
    }

    // ──────────────────────────────────────────────────────────────────────
    // Private helpers
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Pure decision function — separated for unit testability.
     */
    static boolean shouldTreatAsImmediate(Command cmd, boolean fromKeybinding, boolean queryActive) {
        return queryActive && (cmd.isImmediate() || fromKeybinding);
    }

    /**
     * Run the command and append the result to the message panel.
     */
    private void executeAndNotify(
            Command cmd,
            String args,
            CommandContext ctx,
            String commandName) {
        try {
            CommandResult result = cmd.execute(ctx, args);


            if (!result.silent() && result.output() != null && !StringUtils.isBlank(result.output())) {
                addNotification(commandName, result.output());
            }

            // Handle side-effects from the result.
            if (result.shouldExit()) {
                // Exit is also an immediate-safe side-effect; signal handled outside this class.
// The caller in LanternaReplScreen must check result.shouldExit if needed.
                // Here we only log — the REPL exit flag is owned by LanternaReplScreen.
                log.debug("[immediate] command {} requested exit — not propagated from adapter", commandName);
            }
            // /rename: update the prompt-bar banner.

            // Marshalled: this runs on the immediate-cmd virtual thread, and a Lanterna
            // component mutated off the GUI thread can lock itself and then walk the
// parent chain for its theme while updateScreen holds those parents and
            // descends — a silent, intermittent TUI freeze (see SessionController).
            if (result.newSessionName() != null) {
                String newName = result.newSessionName();
                onGuiThread.accept(() -> inputPanel.setAgentName(newName));
            }
        } catch (Exception e) {
            log.warn("[immediate] command /{} threw: {}", commandName, e.getMessage(), e);
            addNotification(commandName, "Error: " + e.getMessage());
        }
    }

    /**
     * Append a notification line to the message panel.
     */
    private void addNotification(String commandName, String text) {
        String prefix = "  [/" + commandName + "] ";
        messagePanel.appendLine(prefix + text, LanternaTheme.welcomeDim());
    }
}
