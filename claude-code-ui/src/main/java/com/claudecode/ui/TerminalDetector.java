package com.claudecode.ui;

import org.apache.commons.lang3.Strings;

import com.claudecode.core.platform.Platform;
import com.claudecode.core.process.ProcessUtils;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Terminal detection from environment and bounded parent-process inspection.
 *
 * <ul>
 *   <li>{@code JETBRAINS_IDES} and
 *       {@code detectTerminal}.</li>
 *   <li>JetBrains parent-process detection
 *       and synchronous initialized terminal value.</li>
 * </ul>
 */
public final class TerminalDetector {

    private TerminalDetector() {}

    /** Detected terminal name (null if unknown). */
    private static final List<String> JETBRAINS_IDES = List.of(
        "pycharm", "intellij", "webstorm", "phpstorm", "rubymine", "clion",
        "goland", "rider", "datagrip", "appcode", "dataspell", "aqua",
        "gateway", "fleet", "jetbrains", "androidstudio");
    private static final String TERMINAL = detect(System.getenv());

    public static String getTerminal() { return TERMINAL; }

    static String detect(Map<String, String> env) {
        // Cursor
        if (env.get("CURSOR_TRACE_ID") != null) return "cursor";

        String vscodeGit = env.get("VSCODE_GIT_ASKPASS_MAIN");
        if (vscodeGit != null) {
            if (Strings.CS.contains(vscodeGit, "cursor")) return "cursor";
            if (Strings.CS.contains(vscodeGit, "windsurf")) return "windsurf";
            if (Strings.CS.contains(vscodeGit, "antigravity")) return "antigravity";
        }

        // macOS bundle ID
        String bundleId = env.get("__CFBundleIdentifier");
        if (bundleId != null) {
            String lower = bundleId.toLowerCase(Locale.ROOT);
            if (Strings.CS.contains(lower, "vscodium")) return "codium";
            if (Strings.CS.contains(lower, "windsurf")) return "windsurf";
            if (Strings.CS.contains(lower, "com.google.android.studio")) return "androidstudio";
            // JetBrains IDEs
            for (String ide : JETBRAINS_IDES) {
                if (Strings.CS.contains(lower, ide)) return ide;
            }
        }

        // Visual Studio (not VS Code)
        if (env.get("VisualStudioVersion") != null) return "visualstudio";

        // JetBrains on Linux/Windows
        if (Strings.CS.equals("JetBrains-JediTerm", env.get("TERMINAL_EMULATOR"))) {
            if (!Platform.IS_DARWIN) {
                for (String command : ProcessUtils.ancestorCommands(ProcessHandle.current().pid(), 10)) {
                    String lower = command.toLowerCase(Locale.ROOT);
                    for (String ide : JETBRAINS_IDES) if (Strings.CS.contains(lower, ide)) return ide;
                }
            }
            return "pycharm";
        }

        // Check TERM before TERM_PROGRAM
        String term = env.get("TERM");
        if (Strings.CS.equals("xterm-ghostty", term)) return "ghostty";
        if (term != null && Strings.CS.contains(term, "kitty")) return "kitty";

        // TERM_PROGRAM
        String termProgram = env.get("TERM_PROGRAM");
        if (termProgram != null) return termProgram;

        // tmux
        if (env.get("TMUX") != null) return "tmux";
        if (env.get("STY") != null) return "screen";
        if (env.get("KONSOLE_VERSION") != null) return "konsole";
        if (env.get("GNOME_TERMINAL_SERVICE") != null) return "gnome-terminal";
        if (env.get("XTERM_VERSION") != null) return "xterm";
        if (env.get("VTE_VERSION") != null) return "vte-based";
        if (env.get("TERMINATOR_UUID") != null) return "terminator";
        if (env.get("KITTY_WINDOW_ID") != null) return "kitty";
        if (env.get("ALACRITTY_LOG") != null) return "alacritty";
        if (env.get("TILIX_ID") != null) return "tilix";
        if (env.get("WT_SESSION") != null) return "windows-terminal";
        if (env.get("SESSIONNAME") != null && Strings.CS.equals("cygwin", term)) return "cygwin";
        if (env.get("MSYSTEM") != null) return env.get("MSYSTEM").toLowerCase(Locale.ROOT);
        if (env.get("ConEmuANSI") != null || env.get("ConEmuPID") != null
                || env.get("ConEmuTask") != null) return "conemu";
        if (env.get("WSL_DISTRO_NAME") != null) return "wsl-" + env.get("WSL_DISTRO_NAME");
        if (env.get("SSH_CONNECTION") != null || env.get("SSH_CLIENT") != null
                || env.get("SSH_TTY") != null) return "ssh-session";
        if (term != null) {
            if (Strings.CS.contains(term, "alacritty")) return "alacritty";
            if (Strings.CS.contains(term, "rxvt")) return "rxvt";
            if (Strings.CS.contains(term, "termite")) return "termite";
            return term;
        }

        return null;
    }
}
