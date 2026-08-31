package com.claudecode.ui.lanterna.input;

import com.claudecode.core.queue.QueuedCommand;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.lang3.Strings;

/**
 * Pure text projection for commands shown in the prompt-area queue preview.
 */
final class QueuedPromptPreviewFormatter {

    private QueuedPromptPreviewFormatter() {}

    static List<String> format(List<QueuedCommand> commands) {
        List<String> lines = new ArrayList<>();
        if (commands != null) {
            for (QueuedCommand command : commands) {
                if (!isVisible(command)) continue;
                String display = command.preExpansionValue() != null
                    ? command.preExpansionValue() : command.text();
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

    private static boolean isVisible(QueuedCommand command) {
        if (command == null) return false;
        // Do not leak raw system/channel XML through the lightweight preview.
        return !command.isMeta()
            && !Strings.CS.equals("task-notification", command.mode());
    }
}
