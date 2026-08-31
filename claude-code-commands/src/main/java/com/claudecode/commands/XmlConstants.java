package com.claudecode.commands;

import java.util.List;

/**
 * XML tag name constants used to mark command/skill metadata and terminal output in conversation
 * messages.
 */
public final class XmlConstants {

    private XmlConstants() {
    }

    // ── Slash-command / skill metadata tags ──────────────────────────────────

    /** {@code <command-name>} — the raw slash-command name without leading {@code /}. */
    public static final String COMMAND_NAME_TAG    = "command-name";

    /** {@code <command-message>} — display name for the command loading spinner. */
    public static final String COMMAND_MESSAGE_TAG = "command-message";

    /** {@code <command-args>} — arguments passed to the command. */
    public static final String COMMAND_ARGS_TAG    = "command-args";

    /**
     * {@code <skill-format>} — sentinel set to {@code "true"} when the command message represents a
     * skill invocation rather than a built-in slash command.
     */
    public static final String SKILL_FORMAT_TAG    = "skill-format";

    // ── Terminal / bash output tags ───────────────────────────────────────────

    /** {@code <bash-input>} — bash command text in a user message. */
    public static final String BASH_INPUT_TAG             = "bash-input";

    /** {@code <bash-stdout>} — stdout from a bash execution. */
    public static final String BASH_STDOUT_TAG            = "bash-stdout";

    /** {@code <bash-stderr>} — stderr from a bash execution. */
    public static final String BASH_STDERR_TAG            = "bash-stderr";

    /** {@code <local-command-stdout>} — stdout of a local slash command. */
    public static final String LOCAL_COMMAND_STDOUT_TAG   = "local-command-stdout";

    /** {@code <local-command-stderr>} — stderr of a local slash command. */
    public static final String LOCAL_COMMAND_STDERR_TAG   = "local-command-stderr";

    /** {@code <local-command-caveat>} — caveat/warning from a local slash command. */
    public static final String LOCAL_COMMAND_CAVEAT_TAG   = "local-command-caveat";

    





    public static final List<String> TERMINAL_OUTPUT_TAGS = List.of(
            BASH_INPUT_TAG,
            BASH_STDOUT_TAG,
            BASH_STDERR_TAG,
            LOCAL_COMMAND_STDOUT_TAG,
            LOCAL_COMMAND_STDERR_TAG,
            LOCAL_COMMAND_CAVEAT_TAG
    );

    // ── Clock / tick ─────────────────────────────────────────────────────────

    /** {@code <tick>} — heartbeat/clock tick tag. */
    public static final String TICK_TAG = "tick";

    // ── Background task notification tags ────────────────────────────────────

    /** {@code <task-notification>} — wraps a background task completion notification. */
    public static final String TASK_NOTIFICATION_TAG      = "task-notification";

    /** {@code <task-id>} — the task identifier. */
    public static final String TASK_ID_TAG                = "task-id";

    /** {@code <tool-use-id>} — the tool use ID linked to the task. */
    public static final String TOOL_USE_ID_TAG            = "tool-use-id";

    /** {@code <task-type>} — the type of the background task. */
    public static final String TASK_TYPE_TAG              = "task-type";

    /** {@code <output-file>} — path to the task's output file. */
    public static final String OUTPUT_FILE_TAG            = "output-file";

    /** {@code <status>} — task or hook execution status. */
    public static final String STATUS_TAG                 = "status";

    /** {@code <summary>} — a short summary of the task result. */
    public static final String SUMMARY_TAG                = "summary";

    /** {@code <reason>} — reason associated with a status or decision. */
    public static final String REASON_TAG                 = "reason";

    // ── Worktree tags ────────────────────────────────────────────────────────

    /** {@code <worktree>} — top-level worktree info block. */
    public static final String WORKTREE_TAG               = "worktree";


    public static final String WORKTREE_PATH_TAG          = "worktreePath";


    public static final String WORKTREE_BRANCH_TAG        = "worktreeBranch";

    // ── Ultraplan / remote review ────────────────────────────────────────────

    /** {@code <ultraplan>} — wraps a remote parallel planning session result. */
    public static final String ULTRAPLAN_TAG              = "ultraplan";

    /** {@code <remote-review>} — wraps the final output from a remote /review session. */
    public static final String REMOTE_REVIEW_TAG          = "remote-review";

    /** {@code <remote-review-progress>} — heartbeat progress from the remote review orchestrator. */
    public static final String REMOTE_REVIEW_PROGRESS_TAG = "remote-review-progress";

    // ── Swarm / inter-agent / cross-session tags ─────────────────────────────

    /** {@code <teammate-message>} — swarm inter-agent message. */
    public static final String TEAMMATE_MESSAGE_TAG       = "teammate-message";

    /** {@code <channel-message>} — message on an external channel. */
    public static final String CHANNEL_MESSAGE_TAG        = "channel-message";

    /** {@code <channel>} — channel identifier. */
    public static final String CHANNEL_TAG                = "channel";

    /** {@code <cross-session-message>} — message from another Claude session's inbox (UDS). */
    public static final String CROSS_SESSION_MESSAGE_TAG  = "cross-session-message";

    // ── Fork boilerplate ─────────────────────────────────────────────────────

    /** {@code <fork-boilerplate>} — wraps rules/format boilerplate in a fork child's first message. */
    public static final String FORK_BOILERPLATE_TAG       = "fork-boilerplate";

    




    public static final String FORK_DIRECTIVE_PREFIX      = "Your directive: ";

    // ── Slash command argument conventions ───────────────────────────────────

    



    public static final List<String> COMMON_HELP_ARGS = List.of("help", "-h", "--help");

    



    public static final List<String> COMMON_INFO_ARGS = List.of(
            "list", "show", "display", "current", "view", "get",
            "check", "describe", "print", "version", "about", "status", "?"
    );
}
