package com.claudecode.ui.lanterna.input;

import com.claudecode.core.io.FileUtils;
import com.claudecode.core.process.ExternalEditorDefaults;
import com.claudecode.core.process.SubprocessEnvironment;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.screen.Screen.RefreshType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Consumer;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ctrl+G on the prompt: hands the terminal to {@code $VISUAL} / {@code $EDITOR} (platform fallback otherwise)
 * to compose a longer prompt, then restores the TUI and loads the edited text into the prompt.
 *
 * <p>The Lanterna screen is stopped <em>synchronously</em> before the child launches — a queued
 * stop plus a fixed sleep can start the editor while Lanterna still owns the terminal. Mouse
 * reporting is disabled across the handoff through the injected terminal hooks so the editor
 * never receives SGR mouse sequences.
 *
 * <p>Distinct from {@link ExternalEditorLauncher}, which edits an arbitrary file (memory /
 * plan) and returns a hint string; this one round-trips the prompt text itself.
 *
 * <p>TS coverage (paths relative to the claude-code repo root):
 * <ul>
 *   <li>{@code utils/externalEditor.ts} — editor resolution ($VISUAL → $EDITOR → default),
 *       temp-file round trip, trailing-newline strip.</li>
 *   <li>{@code components/PromptInput/} — Ctrl+G external-editor keybinding action.</li>
 * </ul>
 */
public final class PromptExternalEditor {

    private static final Logger log = LoggerFactory.getLogger(PromptExternalEditor.class);

    /** Terminal-level hooks around the handoff, owned by the screen's terminal lifecycle. */
    public interface TerminalHandoff {
        void beforeHandoff();
        void afterHandoff();
    }

    private final Screen screen;
    private final Consumer<Runnable> guiInvoker;
    private final InputPanel inputPanel;
    private final TerminalHandoff handoff;

    public PromptExternalEditor(Screen screen, Consumer<Runnable> guiInvoker,
                                  InputPanel inputPanel, TerminalHandoff handoff) {
        this.screen = Objects.requireNonNull(screen, "screen");
        this.guiInvoker = Objects.requireNonNull(guiInvoker, "guiInvoker");
        this.inputPanel = Objects.requireNonNull(inputPanel, "inputPanel");
        this.handoff = Objects.requireNonNull(handoff, "handoff");
    }

    /** Runs the editor on a virtual thread; returns immediately. */
    public void open() {
        Thread.ofVirtual().name("external-editor").start(this::run);
    }

    private void run() {
        Path tmpFile = null;
        boolean screenStopped = false;
        String editedContent = null;
        try {
            String editor = SubprocessEnvironment.get("VISUAL");
            if (StringUtils.isEmpty(editor)) editor = SubprocessEnvironment.get("EDITOR");
            if (StringUtils.isEmpty(editor)) editor = ExternalEditorDefaults.defaultCommand();
            ExternalEditorCommand command = ExternalEditorCommand.resolve(editor);

            tmpFile = FileUtils.createTempFile("claude-code-input", ".md");
            Files.writeString(tmpFile, inputPanel.getText());

            handoff.beforeHandoff();
            screen.stopScreen();
            screenStopped = true;

            Process p = new ProcessBuilder(command.argvFor(tmpFile)).inheritIO().start();
            int exitCode = p.waitFor();
            if (exitCode != 0) {
                log.info("[LANTERNA] External editor '{}' exited with code {}", editor, exitCode);
            }

            editedContent = Files.readString(tmpFile);
            // Editors usually append a trailing newline; the prompt should not inherit it.
            if (Strings.CS.endsWith(editedContent, "\n")) {
                editedContent = editedContent.substring(0, editedContent.length() - 1);
            }
        } catch (Exception e) {
            log.warn("[LANTERNA] External editor failed", e);
        } finally {
            try {
                if (tmpFile != null) Files.deleteIfExists(tmpFile);
            } catch (IOException cleanupFailure) {
                log.debug("[LANTERNA] External editor temp-file cleanup failed: {}",
                    cleanupFailure.getMessage());
            }
            if (screenStopped) restore(editedContent);
        }
    }

    private void restore(String editedContent) {
        try {
            guiInvoker.accept(() -> {
                try {
                    screen.startScreen();
                    handoff.afterHandoff();
                    if (editedContent != null) inputPanel.setText(editedContent);
                    screen.refresh(RefreshType.COMPLETE);
                } catch (Exception restoreFailure) {
                    log.warn("[LANTERNA] Failed to restore screen after external editor",
                        restoreFailure);
                }
            });
        } catch (RuntimeException schedulingFailure) {
            log.warn("[LANTERNA] Could not schedule screen restore", schedulingFailure);
            try {
                screen.startScreen();
                handoff.afterHandoff();
            } catch (Exception _) { /* best-effort */ }
        }
    }
}
