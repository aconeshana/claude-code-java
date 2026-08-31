package com.claudecode.commands.impl.session;

import java.util.Locale;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import com.claudecode.core.text.FormatUtils;

import com.claudecode.commands.AnnotatedCommand;
import com.claudecode.commands.metadata.SlashCommand;
import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandResult;
import com.claudecode.core.message.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * /export — export the current conversation to a plain-text file.
 */
@SlashCommand(
    name = "export",
    description = "Export the current conversation to a file or clipboard"
)
public class ExportCommand implements AnnotatedCommand {

    @Override
    public String argumentHint() { return "[filename]"; }

    @Override
    public CommandResult execute(CommandContext context, String args) {
        List<Message> messages = context.session().messagesSupplier().get();

        // transcript the same as any other (the old Java guard was invented).
        String filename = args != null ? args.trim() : "";


        String content = renderToPlainText(messages);

        // No args + dialog launcher wired → hand off to interactive picker

        // entire output: emitting the success / failure transcript line and
        // writing to disk or clipboard. EnsureCommandResult.skip so the REPL
        // doesn't echo "/export" before the dialog appears.
        if (filename.isEmpty() && context.presentation().exportDialogLauncher() != null) {
            context.presentation().exportDialogLauncher().accept(content);
            return CommandResult.skip();
        }


        if (filename.isEmpty()) {
            String timestamp = FormatUtils.formatExportTimestamp(Instant.now());
            String firstPrompt = extractFirstPrompt(messages);
            if (!firstPrompt.isEmpty()) {
                String sanitized = sanitizeFilename(firstPrompt);
                filename = !sanitized.isEmpty()
                    ? timestamp + "-" + sanitized + ".txt"
                    : "conversation-" + timestamp + ".txt";
            } else {
                filename = "conversation-" + timestamp + ".txt";
            }
        } else if (!Strings.CS.endsWith(filename, ".txt")) {
            filename = filename.replaceAll("\\.[^.]+$", "") + ".txt";
        }

        String cwd = context.session().workingDirectory() != null ? context.session().workingDirectory() : System.getProperty("user.dir");
        Path path = Path.of(filename).isAbsolute() ? Path.of(filename) : Path.of(cwd, filename);

        try {

            // on a missing directory and surfaces the error verbatim.
            Files.writeString(path, content);
        } catch (IOException e) {
            return CommandResult.of("Failed to export conversation: " + e.getMessage());
        }

        return CommandResult.of("Conversation exported to: " + path.toAbsolutePath());
    }


    static String renderToPlainText(List<Message> messages) {
        StringBuilder sb = new StringBuilder();
        for (Message msg : messages) {
            switch (msg) {
                case UserMessage um -> {
                    if (um.message() == null) break;
                    if (um.message().text() != null) {
                        sb.append("> ").append(um.message().text()).append("\n\n");
                    } else if (um.message().blocks() != null) {
                        for (var block : um.message().blocks()) {
                            if (block instanceof TextBlock tb && tb.text() != null
                                    && !StringUtils.isBlank(tb.text())) {
                                sb.append("> ").append(tb.text()).append("\n\n");
                            } else if (block instanceof ToolResultBlock trb) {
                                appendToolResult(sb, trb);
                            }
                        }
                    }
                }
                case AssistantMessage am when !am.isApiErrorMessage() -> {
                    if (am.message() == null || am.message().content() == null) break;
                    for (var block : am.message().content()) {
                        if (block instanceof TextBlock tb && tb.text() != null
                                && !StringUtils.isBlank(tb.text())) {
                            sb.append("● ").append(tb.text()).append("\n\n");
                        } else if (block instanceof ToolUseBlock tub) {
                            sb.append("● ").append(tub.name())
                                .append(summarizeToolInput(tub)).append("\n");
                        }
                    }
                }
                default -> {}
            }
        }
        return sb.toString();
    }

    private static void appendToolResult(StringBuilder sb, ToolResultBlock trb) {
        StringBuilder text = new StringBuilder();
        if (trb.content() != null) {
            for (var inner : trb.content()) {
                if (inner instanceof TextBlock tb && tb.text() != null) {
                    text.append(tb.text());
                }
            }
        }
        String[] lines = text.toString().split("\n");
        for (int i = 0; i < lines.length; i++) {
            sb.append(i == 0 ? "  ⎿  " : "     ").append(lines[i]).append('\n');
        }
        sb.append('\n');
    }

    /** Dim header args — first string field of the tool input, truncated. */
    private static String summarizeToolInput(ToolUseBlock tub) {
        if (tub.input() == null || !tub.input().fields().hasNext()) return "";
        var field = tub.input().fields().next();
        if (!field.getValue().isTextual()) return "";
        String value = field.getValue().asText();
        if (value.length() > 80) value = FormatUtils.truncate(value, 80);
        return "(" + value.replace('\n', ' ') + ")";
    }


    static String extractFirstPrompt(List<Message> messages) {
        UserMessage first = null;
        for (Message msg : messages) {
            if (msg instanceof UserMessage um) {
                first = um;
                break;
            }
        }
        if (first == null || first.message() == null) return "";
        String text = "";
        if (first.message().text() != null) {
            text = first.message().text().trim();
        } else if (first.message().blocks() != null) {
            for (var block : first.message().blocks()) {
                if (block instanceof TextBlock tb) {
                    text = tb.text() != null ? tb.text().trim() : "";
                    break;
                }
            }
        }
        String firstLine = text.split("\n")[0];
        if (firstLine.length() > 50) firstLine = FormatUtils.truncate(firstLine, 50);
        return firstLine;
    }


    static String sanitizeFilename(String text) {
        return text.toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9\\s-]", "")
            .replaceAll("\\s+", "-")
            .replaceAll("-+", "-")
            .replaceAll("^-|-$", "");
    }
}
