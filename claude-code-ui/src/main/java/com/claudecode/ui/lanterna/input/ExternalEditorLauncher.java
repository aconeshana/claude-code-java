package com.claudecode.ui.lanterna.input;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.core.process.SubprocessEnvironment;
import com.claudecode.core.process.ExternalEditorDefaults;
import com.googlecode.lanterna.gui2.WindowBasedTextGUI;
import com.googlecode.lanterna.screen.Screen;

import java.nio.file.FileAlreadyExistsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Suspends the Lanterna alt-screen, launches an interactive terminal editor ({@code $VISUAL} /
 * {@code $EDITOR} / {@code vi}) via a parsed argv command with {@code inheritIO}, then restores
 * Lanterna and forces one full redraw.
 */
public final class ExternalEditorLauncher {

    private static final Logger log = LoggerFactory.getLogger(ExternalEditorLauncher.class);

    private ExternalEditorLauncher() {}

    /**
     * Ensure {@code file} exists (with parents), then open it in the user's editor.
     */
    public static String openInEditor(Screen screen, WindowBasedTextGUI gui, Path file) {
        try {
            ensureFileExists(file);
        } catch (IOException e) {
            log.warn("[MEMORY] Failed to create memory file {}: {}", file, e.getMessage());
            return "Failed to create memory file: " + file + " — " + e.getMessage();
        }


// selection order.
        String editor = SubprocessEnvironment.get("VISUAL");
        String source = "$VISUAL";
        if (isBlank(editor)) {
            editor = SubprocessEnvironment.get("EDITOR");
            source = "$EDITOR";
        }
        if (isBlank(editor)) {
            editor = ExternalEditorDefaults.defaultCommand();
            source = "default";
        }

        final ExternalEditorCommand command;
        try {
            command = ExternalEditorCommand.resolve(editor);
        } catch (IllegalArgumentException invalidCommand) {
            return "Invalid editor command: " + invalidCommand.getMessage();
        }


        // restore off the handoff mode (editFileInEditor's useAlternateScreen);
        // Java has a single handoff path, so it keys off completion instead.
        boolean screenStopped = false;
        try {
            // 1. Let go of the alt buffer so the editor takes over the terminal.
            screen.stopScreen();
            screenStopped = true;
            // 2. Run the editor in-place (blocking; child inherits our stdin/stdout/stderr).
// The resolver supplies the GUI editor wait flag before waitFor.
            int rc = new ProcessBuilder(command.argvFor(file))
                .inheritIO()
                .start()
                .waitFor();
            if (rc != 0) {
                log.info("[MEMORY] Editor '{}' exited with code {}", editor, rc);
            }
        } catch (IOException e) {
            log.warn("[MEMORY] Failed to launch editor '{}': {}", editor, e.getMessage());
            return "Failed to open editor (" + editor + "): " + e.getMessage();
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return "Editor interrupted.";
        } finally {
            if (screenStopped) safeRestore(screen, gui);
        }


        return Strings.CS.equals("default", source)
            ? "> To use a different editor, set the $EDITOR or $VISUAL environment variable."
            : "> Using " + source + "=\"" + editor
              + "\". To change editor, set $EDITOR or $VISUAL environment variable.";
    }

    private static void ensureFileExists(Path file) throws IOException {
        Path parent = file.getParent();
        if (parent != null) Files.createDirectories(parent);
        if (!Files.exists(file)) {

            try {
                Files.write(file, new byte[0], StandardOpenOption.CREATE_NEW);
            } catch (FileAlreadyExistsException _) {
                // Someone else created it between the exists check and the write. Fine.
            }
        }
    }

    /**
     * Restore Lanterna after the editor exits. Any failure here is logged and
     * swallowed — the caller has already returned the editor's exit path, so
     * bubbling a restore failure would just leave the REPL in a worse state.
     */
    private static void safeRestore(Screen screen, WindowBasedTextGUI gui) {
        try {
            screen.startScreen();
            // Force one full frame so the memory panel + spinner re-render
            // instead of showing whatever bytes the editor left on the buffer.
            gui.getGUIThread().invokeLater(() -> {
                try {
                    gui.updateScreen();
                    screen.refresh(Screen.RefreshType.COMPLETE);
                } catch (IOException e) {
                    log.debug("[MEMORY] post-editor refresh failed: {}", e.getMessage());
                }
            });
        } catch (IOException e) {
            log.warn("[MEMORY] Failed to restore Lanterna after editor: {}", e.getMessage());
        }
    }

    private static boolean isBlank(String s) {
        return StringUtils.isBlank(s);
    }
}
