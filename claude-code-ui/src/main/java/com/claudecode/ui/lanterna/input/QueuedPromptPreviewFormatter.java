package com.claudecode.ui.lanterna.input;

import com.claudecode.commands.XmlConstants;
import com.claudecode.core.constants.Figures;
import com.claudecode.core.message.MessageConstants;
import com.claudecode.core.queue.QueuedCommand;
import com.claudecode.core.text.XmlTagUtils;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

/**
 * Pure text projection for commands shown in the prompt-area queue preview.
 *
 * <p>TS coverage:
 * <ul>
 *   <li>{@code components/PromptInput/PromptInputQueuedCommands.tsx} — each value renders as a
 *       user message, so command-metadata text (a resurrected slash-command breadcrumb) shows as
 *       its {@code /name args} echo and a resurrected {@code <bash-input>} as its {@code !}
 *       command, a resurrected local-command output as its {@code ⎿} rows and a resurrected
 *       interruption as the {@code InterruptedByUser} row (authoritative logic from the 2.1.236
 *       bundle)</li>
 * </ul>
 */
final class QueuedPromptPreviewFormatter {

    private QueuedPromptPreviewFormatter() {}

    static List<String> format(List<QueuedCommand> commands) {
        List<String> lines = new ArrayList<>();
        if (commands != null) {
            for (QueuedCommand command : commands) {
                if (!isVisible(command)) continue;
                List<String> responseRows = responseRows(command.text());
                if (!responseRows.isEmpty()) {
                    lines.addAll(responseRows);
                    continue;
                }
                String display = command.preExpansionValue() != null
                    ? command.preExpansionValue() : commandEcho(command.text());
                if (Strings.CS.equals("bash", command.mode())
                        && !Strings.CS.startsWith(display, "!")) {
                    display = "!" + display;
                }
                String[] commandLines = display.split("\\R", -1);
                for (int i = 0; i < commandLines.length; i++) {
                    lines.add((i == 0 ? "  ❯ " : "    ") + commandLines[i]);
                }
            }
        }
        return List.copyOf(lines);
    }

    /**
     * UserMessageRenderer's echo of command metadata ({@code /name args}) and of a resurrected
     * {@code <bash-input>} ({@code !command}); any other text is returned as is.
     */
    private static String commandEcho(String text) {
        String bash = XmlTagUtils.extractTag(text, XmlConstants.BASH_INPUT_TAG).orElse(null);
        if (bash != null && Strings.CS.startsWith(text, "<" + XmlConstants.BASH_INPUT_TAG + ">")) {
            return "!" + bash;
        }
        String name = XmlTagUtils.extractTag(text, XmlConstants.COMMAND_MESSAGE_TAG).orElse(null);
        if (StringUtils.isBlank(name)) return text;
        String args = XmlTagUtils.extractTag(text, XmlConstants.COMMAND_ARGS_TAG).orElse(null);
        if (Strings.CS.equals("true",
                XmlTagUtils.extractTag(text, XmlConstants.SKILL_FORMAT_TAG).orElse(null))) {
            return "Skill(" + name + ")";
        }
        return "/" + name + (StringUtils.isNotBlank(args) ? " " + args : "");
    }

    /**
     * UserMessageRenderer's {@code ⎿} rows for a resurrected local-command output or
     * interruption; empty for any other text.
     */
    private static List<String> responseRows(String text) {
        if (MessageConstants.INTERRUPT_MESSAGE.equals(text)
                || MessageConstants.INTERRUPT_MESSAGE_FOR_TOOL_USE.equals(text)) {
            return List.of(Figures.RESULT_PREFIX + "Interrupted · What should Claude do instead?");
        }
        if (!Strings.CS.startsWith(text, "<" + XmlConstants.LOCAL_COMMAND_STDOUT_TAG)
                && !Strings.CS.startsWith(text, "<" + XmlConstants.LOCAL_COMMAND_STDERR_TAG)) {
            return List.of();
        }
        List<String> rows = new ArrayList<>();
        for (String tag : List.of(XmlConstants.LOCAL_COMMAND_STDOUT_TAG,
                XmlConstants.LOCAL_COMMAND_STDERR_TAG)) {
            String body = XmlTagUtils.extractTag(text, tag).map(String::strip).orElse("");
            for (String line : body.isEmpty() ? new String[0] : body.split("\\R", -1)) {
                rows.add((rows.isEmpty() ? Figures.RESULT_PREFIX : Figures.RESULT_INDENT) + line);
            }
        }
        return rows.isEmpty() ? List.of(Figures.RESULT_PREFIX + MessageConstants.NO_CONTENT_MESSAGE)
            : List.copyOf(rows);
    }

    private static boolean isVisible(QueuedCommand command) {
        if (command == null) return false;
        // Do not leak raw system/channel XML through the lightweight preview.
        return !command.isMeta()
            && !Strings.CS.equals("task-notification", command.mode());
    }
}
