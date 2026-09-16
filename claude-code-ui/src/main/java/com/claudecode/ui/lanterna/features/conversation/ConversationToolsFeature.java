package com.claudecode.ui.lanterna.features.conversation;

import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandRegistry;
import com.claudecode.commands.context.ContextData;
import com.claudecode.commands.diff.DiffData;
import com.claudecode.commands.diff.GitDiffCollector;
import com.claudecode.commands.diff.TurnDiffExtractor;
import com.claudecode.commands.impl.terminal.CopyCommand;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.UserMessage;
import com.claudecode.core.text.FormatUtils;
import com.claudecode.keybindings.UserKeybindingsStore;
import com.claudecode.runtime.query.QuerySession;
import com.claudecode.ui.lanterna.components.LogoPanel;
import com.claudecode.ui.lanterna.components.OSC52Helper;
import com.claudecode.ui.lanterna.dialog.CopyPickerDialog;
import com.claudecode.ui.lanterna.dialog.DiffDialog;
import com.claudecode.ui.lanterna.dialog.ExportDialog;
import com.claudecode.ui.lanterna.dialog.TagRemovalDialog;
import com.claudecode.ui.lanterna.features.help.HelpCommandCatalog;
import com.claudecode.ui.lanterna.features.help.HelpPanel;
import com.claudecode.ui.lanterna.input.InputPanel;
import com.claudecode.ui.lanterna.features.ReplFeature;
import com.claudecode.ui.lanterna.overlay.InlineOverlay;
import com.claudecode.ui.lanterna.repl.ReplCommandUiBridge;
import com.claudecode.ui.lanterna.repl.ReplTranscriptSink;
import com.claudecode.ui.lanterna.theme.LanternaTheme;
import com.claudecode.ui.lanterna.transcript.ContextVisualizationRenderer;
import com.claudecode.ui.lanterna.transcript.MessagePanel;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.gui2.Component;
import com.googlecode.lanterna.gui2.WindowBasedTextGUI;
import java.util.List;
import java.util.Objects;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import org.apache.commons.lang3.StringUtils;

/**
 * Conversation-level utility commands rendered as inline overlays: {@code /help}, {@code /diff},
 * {@code /export}, {@code /copy}, {@code /context}, and the tag-removal confirmation.
 *
 * <p>Each launcher hops to the GUI thread, suppresses the prompt bar while its overlay is open,
 * and echoes a dim dismissal or result line into the transcript when it closes.
 *
 * <p>TS coverage (paths relative to the claude-code repo root):
 * <ul>
 *   <li>{@code commands/help/Help.tsx} — the {@code /help} overlay contents (built-in vs custom
 *       command lists, shortcut labels).</li>
 *   <li>{@code commands/diff/} — {@code /diff} git + per-turn diff collection and overlay.</li>
 *   <li>{@code commands/export/} — {@code /export} clipboard/file picker, default filename
 *       seeded from the first user prompt ({@code extractFirstPrompt}).</li>
 *   <li>{@code commands/copy/} — {@code /copy} code-block picker and OSC 52 clipboard write.</li>
 *   <li>{@code commands/context/} — {@code /context} usage visualization rendering.</li>
 * </ul>
 */
public final class ConversationToolsFeature implements ReplCommandUiBridge.Conversation, ReplFeature {

    private final WindowBasedTextGUI gui;
    private final InputPanel inputPanel;
    private final MessagePanel messagePanel;
    private final ReplTranscriptSink sink;
    private final CommandRegistry commandRegistry;
    private final CommandContext commandContext;
    private final QuerySession queryEngine;
    private final UserKeybindingsStore keybindingsStore;
    private final IntSupplier terminalColumns;

    private final HelpPanel helpPanel;
    private final DiffDialog diffDialog;
    private final ExportDialog exportDialog;
    private final CopyPickerDialog copyPicker;
    private final TagRemovalDialog tagRemovalDialog;

    public ConversationToolsFeature(WindowBasedTextGUI gui,
                                    InputPanel inputPanel,
                                    MessagePanel messagePanel,
                                    ReplTranscriptSink sink,
                                    CommandRegistry commandRegistry,
                                    CommandContext commandContext,
                                    QuerySession queryEngine,
                                    UserKeybindingsStore keybindingsStore,
                                    int terminalRows,
                                    IntSupplier terminalColumns) {
        this.gui = Objects.requireNonNull(gui, "gui");
        this.inputPanel = inputPanel;
        this.messagePanel = Objects.requireNonNull(messagePanel, "messagePanel");
        this.sink = Objects.requireNonNull(sink, "sink");
        this.commandRegistry = commandRegistry;
        this.commandContext = Objects.requireNonNull(commandContext, "commandContext");
        this.queryEngine = Objects.requireNonNull(queryEngine, "queryEngine");
        this.keybindingsStore = keybindingsStore;
        this.terminalColumns = Objects.requireNonNull(terminalColumns, "terminalColumns");

        // All inline, zero height until shown.
        this.exportDialog = new ExportDialog();
        exportDialog.setKeybindingsStore(keybindingsStore);
        exportDialog.setGuiInvoker(task -> gui.getGUIThread().invokeLater(task));
        this.copyPicker = new CopyPickerDialog();
        copyPicker.setKeybindingsStore(keybindingsStore);
        this.diffDialog = new DiffDialog(terminalRows);
        diffDialog.setKeybindingsStore(keybindingsStore);
        this.helpPanel = new HelpPanel(terminalRows);
        helpPanel.setTerminalColumnsSupplier(terminalColumns::getAsInt);
        this.tagRemovalDialog = new TagRemovalDialog();
        tagRemovalDialog.setGuiInvoker(task -> gui.getGUIThread().invokeLater(task));
    }

    // ── Views (mounted / registered individually by the scene to keep z-order) ──

    @Override public List<InlineOverlay> overlays() {
        return List.of(exportDialog, copyPicker, diffDialog, helpPanel, tagRemovalDialog);
    }
    public Component exportView() { return exportDialog; }
    public Component copyView() { return copyPicker; }
    public Component diffView() { return diffDialog; }
    public Component helpView() { return helpPanel; }
    public Component tagRemovalView() { return tagRemovalDialog; }

    // ── Launchers ───────────────────────────────────────────────────────────

    @Override
    public void openHelp() {
        HelpCommandCatalog.Catalog catalog = HelpCommandCatalog.build(
            commandRegistry, commandContext);
        var resolver = keybindingsStore.currentResolver();
        var shortcutLabels = HelpPanel.ShortcutLabels.from(
            resolver, keybindingsStore.isEnabled());
        gui.getGUIThread().invokeLater(() -> {
            if (inputPanel != null) inputPanel.setSuppressed(true);
            helpPanel.show(LogoPanel.appVersion(), catalog.builtin(), catalog.custom(),
                shortcutLabels, resolver, () -> {
                if (inputPanel != null) inputPanel.setSuppressed(false);
                sink.line("  Help dialog dismissed", LanternaTheme.welcomeDim());
            });
        });
    }

    @Override
    public void openDiff() {
        Thread.startVirtualThread(() -> {
            DiffData gitDiff = null;
            List<TurnDiffExtractor.TurnDiff> turnDiffs = List.of();
            try {
                gitDiff = new GitDiffCollector(
                    System.getProperty("user.dir")).collect();
            } catch (Exception _) {
                // Not a git repo / git unavailable — dialog shows the empty state.
            }
            try {
                turnDiffs = TurnDiffExtractor.extract(
                    commandContext.session().messagesSupplier().get());
            } catch (Exception _) {
                // Per-turn extraction is best-effort.
            }
            final var fGit = gitDiff;
            final var fTurns = turnDiffs;
            gui.getGUIThread().invokeLater(() -> {
                if (inputPanel != null) inputPanel.setSuppressed(true);
                diffDialog.show(fGit, fTurns, () -> {
                    if (inputPanel != null) inputPanel.setSuppressed(false);
                    sink.line("  Diff dialog dismissed", LanternaTheme.welcomeDim());
                });
            });
        });
    }

    @Override
    public void openExport(String content) {
        if (content == null) return;
        final String firstPrompt = extractFirstPromptFromHistory();
        final String defaultName = ExportDialog.buildDefaultFilename(firstPrompt);
        gui.getGUIThread().invokeLater(() -> {
            if (inputPanel != null) inputPanel.setSuppressed(true);
            exportDialog.show(content, defaultName, System.getProperty("user.dir"),
                (result, _) -> {
                    if (inputPanel != null) inputPanel.setSuppressed(false);
                    handleExportDialogResult(result.message());
                });
        });
    }

    private void handleExportDialogResult(String message) {
        // Same transcript shape as /effort: a grey-bg user-query row matching
        // what the user "typed", followed by the result line. Keeps the
        // history symmetric with a textual /export <filename>.
        sink.breadcrumb("/export");
        if (StringUtils.isNotBlank(message)) {
            sink.line("  ⎿  " + message, LanternaTheme.welcomeDim());
        }
    }

    /**
     * matches {@code ExportCommand.extractFirstPrompt} — first user message
     * text, first line, max 50 chars. Used to seed the default filename in the
     * picker. Returns empty string if no usable prompt found.
     */
    private String extractFirstPromptFromHistory() {
        var messages = queryEngine.conversation().getMessages();
        for (var msg : messages) {
            if (!(msg instanceof UserMessage um)) continue;
            if (um.message() == null) continue;
            String text = null;
            if (um.message().text() != null) {
                text = um.message().text().trim();
            } else if (um.message().blocks() != null) {
                for (var block : um.message().blocks()) {
                    if (block instanceof TextBlock(String text1)) {
                        text = text1.trim();
                        break;
                    }
                }
            }
            if (StringUtils.isEmpty(text)) continue;
            String firstLine = text.split("\n")[0];
            if (firstLine.length() > 50) firstLine = FormatUtils.truncate(firstLine, 50);
            return firstLine;
        }
        return "";
    }

    @Override
    public void openCopyPicker(String fullText,
                               List<CopyCommand.CodeBlock> codeBlocks,
                               boolean skipPicker) {
        gui.getGUIThread().invokeLater(() -> {
            if (skipPicker) {
                handleCopyDialogResult(fullText, codeBlocks,
                    new CopyPickerDialog.CopySelection(-1, false, false));
                return;
            }
            if (inputPanel != null) inputPanel.setSuppressed(true);
            copyPicker.show(fullText, codeBlocks, selection -> {
                if (inputPanel != null) inputPanel.setSuppressed(false);
                handleCopyDialogResult(fullText, codeBlocks, selection);
            });
        });
    }

    /** Executes a {@code /copy} selection and echoes the result. */
    private void handleCopyDialogResult(
            String fullText,
            List<CopyCommand.CodeBlock> codeBlocks,
            CopyPickerDialog.CopySelection selection) {
        sink.breadcrumb("/copy");
        if (selection == null) {
            sink.line("  ⎿  Copy cancelled", LanternaTheme.welcomeDim());
            return;
        }
        String text;
        String filename;
        if (selection.blockIndex() >= 0 && selection.blockIndex() < codeBlocks.size()) {
            var block = codeBlocks.get(selection.blockIndex());
            text = block.code();
            filename = "copy" + CopyCommand.fileExtension(block.lang());
        } else {
            text = fullText;
            filename = CopyCommand.RESPONSE_FILENAME;
        }
        if (!selection.writeOnly()) {
            OSC52Helper.copyToClipboard(text);
        }
        Thread.startVirtualThread(() -> {
            String result = commandContext.presentation().copyApplyFromDialog() != null
                ? commandContext.presentation().copyApplyFromDialog().apply(
                    text, filename, selection.always(), selection.writeOnly())
                : null;
            gui.getGUIThread().invokeLater(() -> {
                if (StringUtils.isNotBlank(result)) {
                    String[] lines = result.split("\n");
                    for (int i = 0; i < lines.length; i++) {
                        sink.line((i == 0 ? "  ⎿  " : "     ") + lines[i], LanternaTheme.welcomeDim());
                    }
                }
            });
        });
    }

    /** Renders the {@code /context} usage chart from the command context's collector. */
    @Override
    public void showContextVisualization() {
        Supplier<ContextData> collector = commandContext.session().contextDataCollector();
        if (collector == null) return;
        Thread.startVirtualThread(() -> {
            try {
                var data = collector.get();
                var lines = ContextVisualizationRenderer.render(data, terminalColumns.getAsInt());
                gui.getGUIThread().invokeLater(() -> {
                    sink.line("", TextColor.ANSI.DEFAULT);
                    for (var segments : lines) {
                        messagePanel.appendMixed(segments);
                    }
                });
            } catch (Exception e) {
                gui.getGUIThread().invokeLater(() ->
                    sink.line("  /context failed: " + e.getMessage(), LanternaTheme.toolError()));
            }
        });
    }

    @Override
    public void openTagRemoval(CommandContext.TagRemovalRequest request) {
        if (request == null) return;
        gui.getGUIThread().invokeLater(() -> tagRemovalDialog.show(request, result -> {
            if (result != null && result.output() != null && !StringUtils.isBlank(result.output())) {
                sink.system(result.output());
            }
            inputPanel.takeFocus();
        }));
    }
}
