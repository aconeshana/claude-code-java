package com.claudecode.core.config;

import org.apache.commons.lang3.StringUtils;
import com.claudecode.core.annotation.Explanation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.function.Predicate;

/**
 * Canonical path constants for the {@code ~/.claude/} configuration directory.
 */
public final class ClaudePaths {

    private static final String ENV_CLAUDE_CONFIG_DIR = System.getenv("CLAUDE_CONFIG_DIR");
    private static final Path   USER_HOME             = Path.of(System.getProperty("user.home"));

    /**
     * The {@code ~/.claude/} directory (or {@code $CLAUDE_CONFIG_DIR} when overridden).
     */
    public static final Path CLAUDE_HOME = resolveClaudeHome(ENV_CLAUDE_CONFIG_DIR, USER_HOME);


    @Deprecated(since = "0.1.0")
    public static final Path CONFIG_JSON = CLAUDE_HOME.resolve(".config.json");

    /**
     * Normally, next to the {@code.claude/} directory.
     */
    public static final Path GLOBAL_JSON = resolveGlobalJson(
        ENV_CLAUDE_CONFIG_DIR, USER_HOME, Files::exists);

    // ── well-known files ────────────────────────────────────────────────────

    public static final Path SETTINGS_JSON       = CLAUDE_HOME.resolve("settings.json");

    public static final Path KEYBINDINGS_JSON    = CLAUDE_HOME.resolve("keybindings.json");
    /** Custom model endpoint catalogue. */
    @Explanation("Defines the model.json custom-model endpoint catalogue path")
    public static final Path MODEL_JSON          = CLAUDE_HOME.resolve("model.json");

    public static final Path HISTORY_JSONL       = CLAUDE_HOME.resolve("history.jsonl");
    /** {@code ~/.claude/CLAUDE.md} */
    public static final Path CLAUDE_MD           = CLAUDE_HOME.resolve("CLAUDE.md");

    // ── well-known directories ───────────────────────────────────────────────
    /** {@code ~/.claude/agents/} */
    public static final Path AGENTS_DIR       = CLAUDE_HOME.resolve("agents");
    /** {@code ~/.claude/projects/} */
    public static final Path PROJECTS_DIR     = CLAUDE_HOME.resolve("projects");
    /** {@code ~/.claude/tasks/} */
    public static final Path TASKS_DIR        = CLAUDE_HOME.resolve("tasks");
    /**
     * {@code ~/.claude/teams/} — per-team config directories written by {@code TeamCreate}/{@code
     * TeamDelete} (each team's lives at ).
     */
    public static final Path TEAMS_DIR        = CLAUDE_HOME.resolve("teams");
    /** {@code ~/.claude/skills/} */
    public static final Path SKILLS_DIR       = CLAUDE_HOME.resolve("skills");
    /** {@code ~/.claude/workflows/} — user-authored dynamic workflow scripts. */
    public static final Path WORKFLOWS_DIR    = CLAUDE_HOME.resolve("workflows");
    /**
     * {@code ~/.claude/cache/}.
     */
    public static final Path CACHE_DIR        = CLAUDE_HOME.resolve("cache");
    /** {@code ~/.claude/paste-cache/} */
    public static final Path PASTE_STORE_DIR  = CLAUDE_HOME.resolve("paste-cache");







    public static final Path PROMPT_DUMPS_DIR = CLAUDE_HOME.resolve("dump-prompts");

    // ── dynamic path helpers ─────────────────────────────────────────────────

    static Path resolveClaudeHome(String configDir, Path userHome) {
        Path selected = configDir != null ? Path.of(configDir) : userHome.resolve(".claude");
        return Path.of(Normalizer.normalize(selected.toString(), Normalizer.Form.NFC));
    }


    static Path resolveGlobalJson(String configDir, Path userHome, Predicate<Path> exists) {
        Path configHome = resolveClaudeHome(configDir, userHome);
        Path legacy = configHome.resolve(".config.json");
        if (exists.test(legacy)) return legacy;
        Path base = StringUtils.isNotEmpty(configDir) ? Path.of(configDir) : userHome;
        return Path.of(Normalizer.normalize(base.resolve(".claude.json").toString(),
            Normalizer.Form.NFC));
    }

    /** Call-time variant for components whose tests redirect {@code user.home}. */
    public static Path currentClaudeHome() {
        return resolveClaudeHome(System.getenv("CLAUDE_CONFIG_DIR"),
            Path.of(System.getProperty("user.home")));
    }


    public static Path managedRoot() {
        return SettingsPathResolver.policySettingsDirectory();
    }

    private ClaudePaths() {}
}
