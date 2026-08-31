package com.claudecode.ui.lanterna.features.memory;

import java.util.Locale;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import com.claudecode.runtime.memory.MemoryCatalog;
import com.claudecode.keybindings.UserKeybindingsStore;
import com.claudecode.tools.agent.AgentDefinitionLoader;
import com.claudecode.ui.lanterna.dialog.MemorySelectorDialog;
import com.claudecode.core.config.ClaudePaths;
import com.googlecode.lanterna.gui2.Component;
import com.googlecode.lanterna.gui2.WindowBasedTextGUI;
import com.googlecode.lanterna.screen.Screen;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import com.claudecode.ui.lanterna.input.ExternalEditorLauncher;
import com.claudecode.ui.lanterna.overlay.InlineOverlay;
import com.claudecode.ui.lanterna.repl.LanternaReplScreen;
import com.claudecode.ui.lanterna.repl.ReplCommandUiBridge;
import com.claudecode.ui.lanterna.repl.ReplTranscriptSink;
import com.claudecode.ui.lanterna.theme.LanternaTheme;

/**
 * Cohesive memory feature: the {@code /memory} inline file picker, agent-memory folder rows, and
 * the pause-Lanterna/resume external-editor boundary shared with {@code /plan open} and the agents
 * wizard.
 */
public final class MemoryFeature implements ReplCommandUiBridge.Memory {

    private static final Logger log = Logger.getLogger(MemoryFeature.class.getName());

    private final WindowBasedTextGUI gui;
    private final Screen screen;
    private final ReplTranscriptSink sink;
    private final MemorySelectorDialog memoryDialog;

    public MemoryFeature(WindowBasedTextGUI gui, Screen screen, MemoryCatalog memoryCatalog,
                  ReplTranscriptSink sink) {
        this(gui, screen, sink, new MemorySelectorDialog(
            memoryCatalog,
            Path.of(System.getProperty("user.dir")),
            Path.of(System.getProperty("user.home")),
            ClaudePaths.CLAUDE_HOME,
            () -> agentMemoryFolders(memoryCatalog)));
    }

    /** Test seam: inject a pre-built dialog and a null GUI. */
    MemoryFeature(MemorySelectorDialog memoryDialog, ReplTranscriptSink sink) {
        this(null, null, sink, memoryDialog);
    }

    private MemoryFeature(WindowBasedTextGUI gui, Screen screen, ReplTranscriptSink sink,
                          MemorySelectorDialog memoryDialog) {
        this.gui = gui;
        this.screen = screen;
        this.sink = sink;
        this.memoryDialog = memoryDialog;
        if (gui != null) memoryDialog.setGuiInvoker(r -> gui.getGUIThread().invokeLater(r));
    }

    public Component view() { return memoryDialog; }

    public InlineOverlay overlay() { return memoryDialog; }

    public void setKeybindingsStore(UserKeybindingsStore store) {
        memoryDialog.setKeybindingsStore(store);
    }

    /**
     * Opens the inline memory file picker (User / Project / Local CLAUDE.md + @-imported
     * children). Selecting a row suspends Lanterna, launches the user's editor via
     * {@link ExternalEditorLauncher}, then restores the UI. No-op when the GUI isn't up yet
     * (headless / bridge modes).
     */
    @Override
    public void openMemoryDialog() {
        if (gui == null || memoryDialog == null) return;
        gui.getGUIThread().invokeLater(() ->
            memoryDialog.show(result -> {
                if (result.cancelled() || result.selectedFile() == null) {
                    sink.line("  Cancelled memory editing", LanternaTheme.welcomeDim());
                    return;
                }

                if (result.openFolder()) {
                    openFolder(result.selectedFile());
                    return;
                }
                editMemoryFile(result.selectedFile());
            }));
    }

    /**
     * Suspend Lanterna, edit {@code file} in the user's editor, resume. Wired to
     * {@code CommandContext.openEditor} so {@code /memory global} (CLI shortcut) uses the same
     * pause/resume path as the dialog picker — spawning vi via a raw ProcessBuilder on top of the
     * alt-screen leaves the terminal state corrupted after {@code :q}.
     */
    void editMemoryFile(Path file) {
        sink.line("  Opened memory file at " + shortenMemoryPath(file), LanternaTheme.welcomeDim());
        openFileInEditor(file);
    }

    /**
     * Generic "pause Lanterna, open external editor, resume" — no caption line (callers own their
     * own transcript copy). Shared by {@code /memory} (path argument), {@code /plan open}, and the
     * agents wizard's save-and-edit path via {@code CommandContext.openEditor}.
     */
    @Override
    public void openFileInEditor(Path file) {
        if (gui == null || screen == null) return;
        // openInEditor never returns null (failure yields an error string), so no null guard —
        // just skip an empty hint.
        String hint = ExternalEditorLauncher.openInEditor(screen, gui, file);
        if (!StringUtils.isBlank(hint)) {
            sink.line("  " + hint, LanternaTheme.welcomeDim());
        }
    }

    private void openFolder(Path folder) {
        try {
            new ProcessBuilder(folderOpenCommand(folder.toString()))
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
            sink.line("  Opened folder: " + shortenMemoryPath(folder), LanternaTheme.welcomeDim());
        } catch (IOException e) {
            sink.line("  Failed to open folder: " + e.getMessage(), LanternaTheme.welcomeDim());
        }
    }

    /**
     * Build the list of "Open X agent memory" folder rows for {@link MemorySelectorDialog}.
     */
    private static List<MemorySelectorDialog.AgentMemoryFolder> agentMemoryFolders(
            MemoryCatalog memoryCatalog) {
        List<MemorySelectorDialog.AgentMemoryFolder> out = new ArrayList<>();
        try {
            String cwdStr = System.getProperty("user.dir");
            Path cwd = Path.of(cwdStr);
            var agents = AgentDefinitionLoader.getAll(cwdStr);
            for (var agent : agents) {
                if (agent.memory() == null) continue;
                Path dir = memoryCatalog.agentMemoryDirectory(
                    agent.agentType(), agent.memory(), cwd);
                if (dir == null) continue;
                out.add(new MemorySelectorDialog.AgentMemoryFolder(
                    "Open " + agent.agentType() + " agent memory",
                    dir,
                    agent.memory() + " scope"));
            }
        } catch (Exception e) {
            log.fine("agentMemoryFolders lookup failed: " + e.getMessage());
        }
        return out;
    }


    private static String[] folderOpenCommand(String path) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (Strings.CS.contains(os, "mac"))  return new String[] {"open", path};
        if (Strings.CS.contains(os, "win"))  return new String[] {"cmd", "/c", "start", "", path};
        return new String[] {"xdg-open", path};
    }

    /**
     * Shorten an absolute path for display: prefer {@code ~/} (HOME) or {@code./} (cwd) if either
     * applies and is shorter than the absolute form.
     */
    static String shortenMemoryPath(Path file) {
        Path abs = file.toAbsolutePath().normalize();
        Path home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
        Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        String s = abs.toString();
        String toHome = abs.startsWith(home) ? formatHomeRelative(home.relativize(abs)) : null;
        String toCwd = abs.startsWith(cwd) ? "./" + cwd.relativize(abs) : null;
        if (toHome != null && toCwd != null) {
            return toHome.length() <= toCwd.length() ? toHome : toCwd;
        }
        return toHome != null ? toHome : (toCwd != null ? toCwd : s);
    }

    private static String formatHomeRelative(Path relative) {
        return relative.toString().isEmpty() ? "~" : "~" + relative.getFileSystem().getSeparator()
            + relative;
    }
}
