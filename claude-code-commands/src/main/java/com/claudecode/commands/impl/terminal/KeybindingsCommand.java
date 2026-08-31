package com.claudecode.commands.impl.terminal;

import org.apache.commons.lang3.StringUtils;

import com.claudecode.commands.AnnotatedCommand;
import com.claudecode.commands.metadata.SlashCommand;
import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandResult;
import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.process.SubprocessEnvironment;
import com.claudecode.keybindings.KeybindingValidator;
import com.claudecode.keybindings.KeybindingsTemplate;
import com.claudecode.core.config.ClaudePaths;
import com.claudecode.core.io.FileUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * /keybindings — open the user keybindings template in the editor.
 */
@SlashCommand(
    name = "keybindings",
    description = "Open or create your keybindings configuration file"
)
public class KeybindingsCommand implements AnnotatedCommand {

    private final Path bindingsPath;

    public KeybindingsCommand() {
        this(ClaudePaths.KEYBINDINGS_JSON);
    }

    KeybindingsCommand(Path bindingsPath) {
        this.bindingsPath = bindingsPath;
    }

    @Override
    public boolean isAvailable(CommandContext context) {
        return isEnabled();
    }

    @Override
    public CommandResult execute(CommandContext context, String args) {
        boolean existed = Files.isRegularFile(bindingsPath);
        try {
            if (!existed) {
                FileUtils.writeString(bindingsPath, KeybindingsTemplate.generate(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW);
            }
        } catch (IOException e) {
            return CommandResult.of("Failed to create keybindings file: " + e.getMessage());
        }

        // Interactive REPL: suspend Lanterna and open the file in the user's
        // editor (wired via CommandContext.openEditor → ExternalEditorLauncher,
        // the same path /memory uses). The file is written above first so the
        // launcher doesn't overwrite it with an empty one. openEditor is null
        // in headless mode, where we fall back to a manual-open instruction.
        if (context.presentation().openEditor() != null) {
            context.presentation().openEditor().accept(bindingsPath);
        }

        // Validate the (possibly edited) file and surface any warnings.
        String content;
        try {
            content = Files.readString(bindingsPath, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return CommandResult.of("Failed to read keybindings file: " + e.getMessage());
        }
        List<KeybindingValidator.KeybindingWarning> warnings = KeybindingValidator.validateFile(content);
        String warningText = KeybindingValidator.formatWarnings(warnings);

        String verb = existed ? "Opened" : "Created";
        if (context.presentation().openEditor() != null) {
            String msg = verb + " " + bindingsPath + " in your editor.";
            if (!warningText.isEmpty()) msg += "\n\n" + warningText;
            return CommandResult.of(msg);
        }
        // Headless fallback.
        String editor = SubprocessEnvironment.get("VISUAL");
        if (StringUtils.isBlank(editor)) {
            editor = SubprocessEnvironment.get("EDITOR");
        }
        if (StringUtils.isBlank(editor)) editor = "$EDITOR";
        String msg = verb + " " + bindingsPath + ".\n"
            + "Open it manually: " + editor + " " + bindingsPath + "\n"
            + "(reload the REPL after editing for changes to take effect).";
        if (!warningText.isEmpty()) msg += "\n\n" + warningText;
        return CommandResult.of(msg);
    }

    @Explanation("Claude Code 2.1.197 exposes /keybindings without the former preview gate")
    static boolean isEnabled() {
        return true;
    }


}
