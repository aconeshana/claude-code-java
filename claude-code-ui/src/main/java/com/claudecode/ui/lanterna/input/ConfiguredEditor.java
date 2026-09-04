package com.claudecode.ui.lanterna.input;

import com.claudecode.core.process.ExternalEditorDefaults;
import com.claudecode.core.process.SubprocessEnvironment;
import java.util.Locale;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;

/**
 * The editor the {@code ctrl+g} hand-off would launch, and the name to show for it.
 *
 * <p>Authority is the {@code 2.1.236} bundle.
 *
 * <ul>
 *   <li>Covers: {@code cne()} — which command is configured. The bundle reads the settings
 *       {@code editor} field first and falls back to a detected editor; this port keeps
 *       {@link ExternalEditorLauncher}'s order ({@code $VISUAL}, {@code $EDITOR}, platform default)
 *       so the name shown and the command launched cannot disagree. See {@link #command()}.</li>
 *   <li>Covers: {@code z1(e)} and its {@code Vxp} table — the display name shown in
 *       {@code edit in <editor>}. See {@link #displayName(String)}.</li>
 * </ul>
 *
 * <p>Deviation: {@code z1} consults the IDE registry {@code ijr} before {@code Vxp}. That registry
 * is keyed by IDE identifiers reported over the IDE connection ({@code vscode}, {@code fleet},
 * {@code androidstudio}), not by editor commands, and this port has no such connection, so the
 * lookup is omitted rather than faked.
 */
public final class ConfiguredEditor {

    /** {@code z1}'s fallback when nothing is configured. */
    public static final String UNKNOWN_EDITOR = "IDE";

    /** {@code Vxp} — commands whose display name is not simply their capitalized basename. */
    private static final Map<String, String> DISPLAY_NAMES = Map.ofEntries(
        Map.entry("code", "VS Code"),
        Map.entry("cursor", "Cursor"),
        Map.entry("windsurf", "Devin Desktop"),
        Map.entry("antigravity", "Antigravity"),
        Map.entry("vi", "Vim"),
        Map.entry("vim", "Vim"),
        Map.entry("nano", "nano"),
        Map.entry("notepad", "Notepad"),
        Map.entry("start /wait notepad", "Notepad"),
        Map.entry("emacs", "Emacs"),
        Map.entry("subl", "Sublime Text"),
        Map.entry("atom", "Atom"));

    private ConfiguredEditor() {}

    /** The configured editor command, or {@code null} when none can be resolved. */
    public static String command() {
        String editor = SubprocessEnvironment.get("VISUAL");
        if (StringUtils.isBlank(editor)) editor = SubprocessEnvironment.get("EDITOR");
        if (StringUtils.isBlank(editor)) editor = ExternalEditorDefaults.defaultCommand();
        return StringUtils.isBlank(editor) ? null : editor;
    }

    /**
     * {@code z1} — the whole command, then its first token's basename, are looked up in the display
     * table; anything unrecognized falls back to that basename capitalized.
     */
    public static String displayName(String command) {
        if (StringUtils.isBlank(command)) return UNKNOWN_EDITOR;
        String known = DISPLAY_NAMES.get(command.toLowerCase(Locale.ROOT).trim());
        if (known != null) return known;

        String firstToken = StringUtils.substringBefore(command, " ");
        String basename = basename(firstToken).toLowerCase(Locale.ROOT);
        if (basename.isEmpty()) return capitalize(command);
        return DISPLAY_NAMES.getOrDefault(basename, capitalize(basename));
    }

    private static String basename(String path) {
        int separator = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return separator >= 0 ? path.substring(separator + 1) : path;
    }

    /** Lodash {@code capitalize}: lower-case the whole string, then upper-case the first letter. */
    private static String capitalize(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.isEmpty() ? lower
            : Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }
}
