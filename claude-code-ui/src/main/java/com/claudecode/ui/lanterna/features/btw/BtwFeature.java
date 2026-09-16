package com.claudecode.ui.lanterna.features.btw;

import com.claudecode.commands.CommandContext;
import com.claudecode.commands.impl.git.BranchCommand;
import com.claudecode.core.config.EnvUtils;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.core.message.AssistantContent;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.MessageOrigin;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.UserMessage;
import com.claudecode.core.process.SubprocessEnvironment;
import com.claudecode.core.text.FormatUtils;
import com.claudecode.runtime.query.QuerySession;
import com.claudecode.tools.Tool;
import com.claudecode.tools.ToolRegistry;
import com.claudecode.tools.agent.AgentTool;
import com.claudecode.ui.lanterna.dialog.BtwSideQuestionDialog;
import com.claudecode.ui.lanterna.input.InputPanel;
import com.claudecode.ui.lanterna.features.ReplFeature;
import com.claudecode.ui.lanterna.overlay.InlineOverlay;
import com.claudecode.ui.lanterna.repl.ReplCommandUiBridge;
import com.claudecode.ui.lanterna.repl.ReplTranscriptSink;
import com.claudecode.ui.lanterna.features.settings.UiSettings;
import com.claudecode.ui.lanterna.theme.LanternaTheme;
import com.googlecode.lanterna.gui2.Component;
import com.googlecode.lanterna.gui2.WindowBasedTextGUI;
import com.googlecode.lanterna.screen.Screen;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

/**
 * The {@code /btw} inline side-question dialog and its two exchange-handling paths: forking a
 * subagent from the question/response pair ({@link AgentTool}), or — in coordinator mode —
 * branching a new session from it ({@link BranchCommand}). Extracted from
 * {@code LanternaReplScreen}.
 */
public final class BtwFeature implements ReplCommandUiBridge.Btw, ReplFeature {

    private final WindowBasedTextGUI gui;
    private final Screen screen;
    private final InputPanel inputPanel;
    private final ReplTranscriptSink sink;
    private final ToolRegistry toolRegistry;
    private final QuerySession queryEngine;
    private final CommandContext commandContext;
    private final Supplier<ToolExecutionContext> agentToolExecutionContext;
    private final BtwSideQuestionDialog dialog;

    public BtwFeature(
            WindowBasedTextGUI gui,
            Screen screen,
            InputPanel inputPanel,
            ReplTranscriptSink sink,
            ToolRegistry toolRegistry,
            QuerySession queryEngine,
            CommandContext commandContext,
            Supplier<ToolExecutionContext> agentToolExecutionContext) {
        this.gui = gui;
        this.screen = screen;
        this.inputPanel = inputPanel;
        this.sink = sink;
        this.toolRegistry = toolRegistry;
        this.queryEngine = queryEngine;
        this.commandContext = commandContext;
        this.agentToolExecutionContext = agentToolExecutionContext;
        this.dialog = new BtwSideQuestionDialog();
        this.dialog.setGuiInvoker(task -> gui.getGUIThread().invokeLater(task));
    }

    @Override public List<InlineOverlay> overlays() {
        return List.of(dialog);
    }

    public Component view() {
        return dialog;
    }

    public int readUseCount() {
        return UiSettings.readGlobalInt("btwUseCount", 0);
    }

    @Override
    public void open(String question, Function<String, String> sideQuestionRunner) {
        if (gui == null || dialog == null || sideQuestionRunner == null) return;
        gui.getGUIThread().invokeLater(() -> {
            if (inputPanel != null) inputPanel.setSuppressed(true);
            int rows = 24;
            try { rows = screen.getTerminalSize().getRows(); } catch (Exception _) { }
            dialog.show(question, rows, sideQuestionRunner,
                this::forkBtwExchange, () -> {
                if (inputPanel != null) {
                    inputPanel.setSuppressed(false);
                    inputPanel.takeFocus();
                }
            });
        });
    }

    private void forkBtwExchange(String question, String response) {
        if (!EnvUtils.isEnvTruthy(SubprocessEnvironment.get("CLAUDE_CODE_COORDINATOR_MODE"))) {
            spawnBtwForkAgent(question, response);
            return;
        }
        branchBtwExchange(question, response);
    }

    private void spawnBtwForkAgent(String question, String response) {
        try {
            Tool<?, ?> registered = toolRegistry.get("Agent").orElse(null);
            if (!(registered instanceof AgentTool agentTool)) {
                throw new IllegalStateException("Agent tool is unavailable");
            }
            List<Message> current = queryEngine.conversation().getMessages();
            String parent = current == null || current.isEmpty() ? null : current.getLast().uuid();
            String userUuid = UUID.randomUUID().toString();
            String sessionId = queryEngine.conversation().getSessionId();
            UserMessage user = new UserMessage(
                userUuid, MessageContent.ofText(question), false, false, null,
                MessageOrigin.USER, parent, Instant.now(),
                null, null, sessionId);
            AssistantMessage assistant = new AssistantMessage(
                UUID.randomUUID().toString(),
                AssistantContent.of(List.of(new TextBlock(response))),
                false, userUuid, Instant.now());
            ToolExecutionContext context = agentToolExecutionContext.get();
            AgentTool.SpawnedFork spawned = agentTool.spawnForkFromDirective(
                question, List.of(user, assistant), context);
            gui.getGUIThread().invokeLater(() -> {
                dialog.hide();
                if (spawned == null) {
                    sink.line("  Cannot fork before the first conversation turn",
                        LanternaTheme.welcomeDim());
                } else {
                    String suffix = spawned.agentId().length() <= 4 ? spawned.agentId()
                        : spawned.agentId().substring(spawned.agentId().length() - 4);
                    sink.line("  ✻ forked " + spawned.name() + " (" + suffix + ")",
                        LanternaTheme.welcomeDim());
                }
            });
        } catch (Exception exception) {
            gui.getGUIThread().invokeLater(() -> {
                dialog.hide();
                sink.line("  Failed to fork: " + rootMessage(exception),
                    LanternaTheme.toolError());
            });
        }
    }

    private void branchBtwExchange(String question, String response) {
        try {
            List<Message> current = commandContext.session().messagesSupplier().get();
            String parent = current == null || current.isEmpty() ? null : current.getLast().uuid();
            String userUuid = UUID.randomUUID().toString();
            String sessionId = commandContext.session().currentSessionId() == null
                ? null : commandContext.session().currentSessionId().get();
            UserMessage user = new UserMessage(
                userUuid, MessageContent.ofText(question), false, false, null,
                MessageOrigin.USER, parent, Instant.now(),
                null, null, sessionId);
            AssistantMessage assistant = new AssistantMessage(
                UUID.randomUUID().toString(),
                AssistantContent.of(List.of(new TextBlock(response))),
                false, userUuid, Instant.now());
            String normalized = question.replaceAll("\\s+", " ").trim();
            String title = FormatUtils.truncate("btw: " + normalized, 80);
            var result = new BranchCommand().executeWithAdditionalMessages(
                commandContext, title, List.of(user, assistant));
            gui.getGUIThread().invokeLater(() -> {
                if (StringUtils.isNotBlank(result.output())) {
                    for (String line : result.output().split("\\R", -1)) {
                        sink.line("  " + line, LanternaTheme.welcomeDim());
                    }
                }
                if (Strings.CS.startsWith(result.output(), "Branched conversation")) {
                    dialog.hide();
                    if (result.newSessionName() != null && inputPanel != null) {
                        inputPanel.setAgentName(result.newSessionName());
                    }
                } else {
                    dialog.finishFork();
                }
            });
        } catch (Exception exception) {
            gui.getGUIThread().invokeLater(() -> {
                dialog.hide();
                sink.line("  Failed to branch /btw response: "
                    + rootMessage(exception), LanternaTheme.toolError());
            });
        }
    }

    private static String rootMessage(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null) cause = cause.getCause();
        return cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
    }
}
