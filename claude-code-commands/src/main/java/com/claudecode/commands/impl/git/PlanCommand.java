package com.claudecode.commands.impl.git;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.commands.AnnotatedCommand;
import com.claudecode.commands.metadata.SlashCommand;
import com.claudecode.core.process.SubprocessEnvironment;
import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * {@code /plan} — enable plan mode or view the current session plan.
 */
@SlashCommand(
    name = "plan",
    description = "Enable plan mode or view the current session plan"
)
public class PlanCommand implements AnnotatedCommand {

    public PlanCommand() { }

    @Override public String argumentHint() { return "[open|<description>]"; }

    @Override
    public CommandResult execute(CommandContext context, String args) {
        String trimmed = args == null ? "" : args.trim();
        boolean inPlanMode = context.application().permissions().isPlanMode();

        if (!inPlanMode) {
            context.application().permissions().enterPlanMode();
            if (!trimmed.isEmpty() && !Strings.CS.equals("open", trimmed)) {

                // fixed stdout text and sets shouldQuery=true. The original
                // /plan input (including its description) is retained by the
                // prompt-command envelope at the UI boundary.
                return CommandResult.forLocalJsxQuery(
                    "plan", trimmed, "Enabled plan mode");
            }
            return CommandResult.localJsx("Enabled plan mode");
        }

        String sessionId = context.session().currentSessionId() != null
            ? context.session().currentSessionId().get() : null;
        if (StringUtils.isBlank(sessionId)) {
            return CommandResult.localJsx("Already in plan mode. No plan written yet.");
        }
        Path planFile = context.application().tooling().plans().planFile(sessionId);

        if (!Files.isRegularFile(planFile)) {
            return CommandResult.localJsx("Already in plan mode. No plan written yet.");
        }

        if (Strings.CS.equals("open", trimmed.split("\\s+", 2)[0])) {
            if (context.presentation().openEditor() != null) {
                context.presentation().openEditor().accept(planFile);
                return CommandResult.localJsx("Opened plan in editor: " + planFile);
            }
            // Headless fallback — no editor channel available.
            return CommandResult.localJsx(
                "Failed to open plan in editor: no external editor available");
        }

        try {
            String content = Files.readString(planFile);
            StringBuilder sb = new StringBuilder();
            sb.append("Current Plan\n").append(planFile).append("\n\n").append(content);
            String editorName = externalEditorName();
            if (editorName != null) {
                sb.append("\n\n\"/plan open\" to edit this plan in ").append(editorName);
            }
            return CommandResult.localJsx(sb.toString());
        } catch (IOException e) {
            return CommandResult.localJsx("Failed to read plan file: " + e.getMessage());
        }
    }


    static String externalEditorName() {
        String editor = SubprocessEnvironment.get("VISUAL");
        if (StringUtils.isBlank(editor)) {
            editor = SubprocessEnvironment.get("EDITOR");
        }
        if (StringUtils.isBlank(editor)) return null;
        String first = editor.trim().split("\\s+")[0];
        int slash = first.lastIndexOf('/');
        return slash >= 0 ? first.substring(slash + 1) : first;
    }

}
