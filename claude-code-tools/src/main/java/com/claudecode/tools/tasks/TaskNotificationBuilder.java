package com.claudecode.tools.tasks;

import com.claudecode.tools.workflows.WorkflowRun;

import java.util.Locale;


/**
 * Builds the model-facing {@code <task-notification>} XML delivered when a background task
 * reaches a terminal state. TS coverage:
 * <ul>
 *   <li>tasks/LocalShellTask/LocalShellTask.tsx — {@code enqueueShellNotification}: bash/monitor
 *       completion shapes and summary wording, plus the stall ("waiting for interactive input")
 *       notification</li>
 *   <li>tasks/LocalAgentTask/LocalAgentTask.tsx — agent completion notification: optional
 *       {@code <result>}/{@code <usage>}/{@code <note>} sections; AGENT_NOTE is verbatim</li>
 *   <li>workflow/remote-task completion shapes (Workflow notifications, RemoteAgentTask)</li>
 * </ul>
 * Tag spellings follow the shipped 2.1.197/2.1.236 binaries (hyphenated
 * {@code task-notification}/{@code task-id}/{@code tool-use-id}/{@code task-type}/
 * {@code output-file}); an earlier port's underscore variants matched neither the official
 * renderer nor its queue drain parser. Two weflow details do NOT match the 197 binary and are
 * intentionally not ported: the {@code feature('MONITOR_TOOL') ? 'next' : 'later'} priority
 * expression, and the {@code <total_tokens>} usage tag (197 parses {@code <subagent_tokens>}).
 * The {@code <worktree>} section appears in neither binary; it is kept for model usefulness
 * with the weflow ({@code worktreePath}) spelling.
 */
public final class TaskNotificationBuilder {

    private static final String AGENT_NOTE = "A task-notification fires each time this agent stops "
        + "with no live background children of its own. The user can send it another message and "
        + "resume it, so the same task-id may notify more than once.";

    private static final String TASK_NOTIFICATION_TAG = "task-notification";
    private static final String TASK_ID_TAG = "task-id";
    private static final String TASK_TYPE_TAG = "task-type";
    private static final String OUTPUT_FILE_TAG = "output-file";
    private static final String STATUS_TAG = "status";
    private static final String SUMMARY_TAG = "summary";
    private static final String TOOL_USE_ID_TAG = "tool-use-id";
    private static final String RESULT_TAG = "result";
    private static final String WORKTREE_TAG = "worktree";
    private static final String WORKTREE_PATH_TAG = "worktreePath";

    private static final String BACKGROUND_BASH_SUMMARY_PREFIX = "Background command ";

    private TaskNotificationBuilder() {}

    /** Builds the {@code <task-notification>} XML for a terminal task. */
    public static String build(TaskState task) {
        boolean monitor = task.type() == TaskType.MONITOR_MCP
            || task.type() == TaskType.MONITOR_WS
            || TaskRegistry.global().isMonitorTask(task.id());
        return build(task, monitor);
    }

    static String build(TaskState task, boolean monitor) {
        return build(task, monitor, null);
    }

    static String build(TaskState task, boolean monitor, WorkflowRun workflowRun) {
        if (monitor) {
            return buildMonitor(task);
        }
        if (task.type() == TaskType.LOCAL_AGENT) {
            return buildAgent(task);
        }
        if (task.type() == TaskType.LOCAL_WORKFLOW) {
            return buildWorkflow(task, workflowRun);
        }
        StringBuilder sb = new StringBuilder();
        sb.append('<').append(TASK_NOTIFICATION_TAG).append('>').append('\n');
        sb.append('<').append(TASK_ID_TAG).append('>')
          .append(escape(task.id())).append("</").append(TASK_ID_TAG).append('>');
        task.toolUseId().ifPresent(id -> sb.append('\n')
          .append('<').append(TOOL_USE_ID_TAG).append('>')
          .append(escape(id)).append("</").append(TOOL_USE_ID_TAG).append('>'));

        switch (task.type()) {
            case LOCAL_BASH, MONITOR_WS, MONITOR_MCP -> buildBash(task, sb);
            case REMOTE_AGENT -> buildRemote(task, sb);
            case LOCAL_WORKFLOW -> throw new IllegalStateException("handled above");
            default -> buildFramework(task, sb);
        }
        sb.append('\n').append("</").append(TASK_NOTIFICATION_TAG).append('>');
        return sb.toString();
    }


    private static String buildMonitor(TaskState task) {
        StringBuilder sb = new StringBuilder();
        sb.append('<').append(TASK_NOTIFICATION_TAG).append('>').append('\n')
          .append('<').append(TASK_ID_TAG).append('>').append(escape(task.id()))
          .append("</").append(TASK_ID_TAG).append('>');
        task.toolUseId().ifPresent(id -> sb.append('\n')
          .append('<').append(TOOL_USE_ID_TAG).append('>')
          .append(escape(id)).append("</").append(TOOL_USE_ID_TAG).append('>'));
        sb.append('\n').append('<').append(OUTPUT_FILE_TAG).append('>')
          .append(escape(TaskOutputPaths.outputPath(task.id()).toString()))
          .append("</").append(OUTPUT_FILE_TAG).append('>')
          .append('\n').append('<').append(STATUS_TAG).append('>')
          .append(task.status().name().toLowerCase(Locale.ROOT))
          .append("</").append(STATUS_TAG).append('>')
          .append('\n').append('<').append(SUMMARY_TAG).append('>')
          .append(escape(monitorSummary(task)))
          .append("</").append(SUMMARY_TAG).append('>')
          .append('\n').append("</").append(TASK_NOTIFICATION_TAG).append('>');
        return sb.toString();
    }

    private static String monitorSummary(TaskState task) {
        String base = "Monitor \"" + task.description() + "\"";
        return switch (task.status()) {
            case COMPLETED -> base + " stream ended";
            case FAILED -> base + " script failed"
                + task.exitCode().map(code -> " (exit " + code + ")").orElse("");
            case KILLED -> base + " stopped";
            default -> base + " " + statusText(task.status());
        };
    }

    // ── Per-type shapes ────────────────────────────────────────────────────


    private static String buildAgent(TaskState task) {
        StringBuilder sb = new StringBuilder();
        sb.append('<').append(TASK_NOTIFICATION_TAG).append('>').append('\n')
          .append('<').append(TASK_ID_TAG).append('>').append(escape(task.id()))
          .append("</").append(TASK_ID_TAG).append('>');
        task.toolUseId().ifPresent(id -> sb.append('\n')
          .append('<').append(TOOL_USE_ID_TAG).append('>')
          .append(escape(id)).append("</").append(TOOL_USE_ID_TAG).append('>'));
        sb.append('\n').append('<').append(OUTPUT_FILE_TAG).append('>')
          .append(escape(TaskOutputPaths.outputPath(task.id()).toString()))
          .append("</").append(OUTPUT_FILE_TAG).append('>')
          .append('\n').append('<').append(STATUS_TAG).append('>')
          .append(task.status().name().toLowerCase(Locale.ROOT))
          .append("</").append(STATUS_TAG).append('>')
          .append('\n').append('<').append(SUMMARY_TAG).append('>')
          .append(escape(agentSummary(task)))
          .append("</").append(SUMMARY_TAG).append('>')
          .append("\n<note>").append(AGENT_NOTE).append("</note>");
        task.finalMessage().ifPresent(msg -> sb.append('\n')
          .append('<').append(RESULT_TAG).append('>')
          .append(escape(msg)).append("</").append(RESULT_TAG).append('>'));
        task.usage().ifPresent(u -> sb.append("\n<usage><subagent_tokens>")
          .append(u.totalTokens()).append("</subagent_tokens><tool_uses>")
          .append(u.toolUses()).append("</tool_uses><duration_ms>")
          .append(u.durationMs()).append("</duration_ms></usage>"));
        task.worktreePath().ifPresent(wp -> sb.append('\n')
          .append('<').append(WORKTREE_TAG).append('>')
          .append('<').append(WORKTREE_PATH_TAG).append('>').append(escape(wp))
          .append("</").append(WORKTREE_PATH_TAG).append('>')
          .append("</").append(WORKTREE_TAG).append('>'));
        sb.append('\n').append("</").append(TASK_NOTIFICATION_TAG).append('>');
        return sb.toString();
    }


    private static void buildBash(TaskState task, StringBuilder sb) {
        appendOutputFile(sb, task);
        sb.append('\n').append('<').append(STATUS_TAG).append('>')
          .append(task.status().name().toLowerCase(Locale.ROOT)).append("</").append(STATUS_TAG).append('>');
        sb.append('\n').append('<').append(SUMMARY_TAG).append('>')
          .append(escape(bashSummary(task))).append("</").append(SUMMARY_TAG).append('>');
    }


    private static void buildRemote(TaskState task, StringBuilder sb) {
        sb.append('\n').append('<').append(TASK_TYPE_TAG).append('>')
          .append(taskType(task.type())).append("</").append(TASK_TYPE_TAG).append('>');
        appendOutputFile(sb, task);
        sb.append('\n').append('<').append(STATUS_TAG).append('>')
          .append(task.status().name().toLowerCase(Locale.ROOT)).append("</").append(STATUS_TAG).append('>');
        sb.append('\n').append('<').append(SUMMARY_TAG).append('>')
          .append(escape("Remote task \"" + task.description() + "\" " + statusText(task.status())))
          .append("</").append(SUMMARY_TAG).append('>');
    }


    private static String buildWorkflow(TaskState task, WorkflowRun workflowRun) {
        StringBuilder sb = new StringBuilder();
        sb.append('<').append(TASK_NOTIFICATION_TAG).append('>').append('\n')
          .append('<').append(TASK_ID_TAG).append('>').append(escape(task.id()))
          .append("</").append(TASK_ID_TAG).append('>');
        task.toolUseId().ifPresent(id -> sb.append('\n')
          .append('<').append(TOOL_USE_ID_TAG).append('>')
          .append(escape(id)).append("</").append(TOOL_USE_ID_TAG).append('>'));
        sb.append('\n').append('<').append(OUTPUT_FILE_TAG).append('>')
          .append(escape(TaskOutputPaths.outputPath(task.id()).toString()))
          .append("</").append(OUTPUT_FILE_TAG).append('>')
          .append('\n').append('<').append(STATUS_TAG).append('>')
          .append(task.status().name().toLowerCase(Locale.ROOT))
          .append("</").append(STATUS_TAG).append('>')
          .append('\n').append('<').append(SUMMARY_TAG).append('>')
          .append(escape(workflowSummary(task)))
          .append("</").append(SUMMARY_TAG).append('>');
        task.finalMessage().ifPresent(result -> sb.append('\n')
          .append('<').append(RESULT_TAG).append('>')
          .append(escape(result)).append("</").append(RESULT_TAG).append('>'));
        if (workflowRun != null && !workflowRun.failures().isEmpty()) {
            sb.append("\n<failures>")
              .append(escape(String.join("\n", workflowRun.failures())))
              .append("</failures>");
        }
        int agentCount = workflowRun == null ? 0 : workflowRun.agentCount();
        task.usage().ifPresent(u -> sb.append("\n<usage><agent_count>")
          .append(agentCount).append("</agent_count>")
          .append("<subagent_tokens>").append(u.totalTokens()).append("</subagent_tokens>")
          .append("<tool_uses>").append(u.toolUses()).append("</tool_uses>")
          .append("<duration_ms>").append(u.durationMs()).append("</duration_ms></usage>"));
        return sb.append('\n').append("</").append(TASK_NOTIFICATION_TAG).append('>')
            .toString();
    }


    private static void buildFramework(TaskState task, StringBuilder sb) {
        sb.append('\n').append('<').append(TASK_TYPE_TAG).append('>')
          .append(taskType(task.type())).append("</").append(TASK_TYPE_TAG).append('>');
        appendOutputFile(sb, task);
        sb.append('\n').append('<').append(STATUS_TAG).append('>')
          .append(task.status().name().toLowerCase(Locale.ROOT)).append("</").append(STATUS_TAG).append('>');
        sb.append('\n').append('<').append(SUMMARY_TAG).append('>')
          .append(escape("Task \"" + task.description() + "\" " + statusText(task.status())))
          .append("</").append(SUMMARY_TAG).append('>');
    }


    public static String buildStallNotification(TaskState task, String tail) {
        StringBuilder sb = new StringBuilder();
        sb.append('<').append(TASK_NOTIFICATION_TAG).append('>').append('\n');
        sb.append('<').append(TASK_ID_TAG).append('>')
          .append(escape(task.id())).append("</").append(TASK_ID_TAG).append('>');
        task.toolUseId().ifPresent(id -> sb.append('\n')
          .append('<').append(TOOL_USE_ID_TAG).append('>')
          .append(escape(id)).append("</").append(TOOL_USE_ID_TAG).append('>'));
        sb.append('\n').append('<').append(OUTPUT_FILE_TAG).append('>')
          .append(escape(TaskOutputPaths.outputPath(task.id()).toString()))
          .append("</").append(OUTPUT_FILE_TAG).append('>');
        sb.append('\n').append('<').append(SUMMARY_TAG).append('>')
          .append(escape(BACKGROUND_BASH_SUMMARY_PREFIX + "\"" + task.description()
              + "\" appears to be waiting for interactive input"))
          .append("</").append(SUMMARY_TAG).append('>');
        sb.append('\n').append("</").append(TASK_NOTIFICATION_TAG).append('>');
        sb.append("\nLast output:\n").append(tail == null ? "" : trimEnd(tail))
          .append("""


              The command is likely blocked on an interactive prompt. \
              Kill this task and re-run with piped input (e.g., `echo y | command`) \
              or a non-interactive flag if one exists.""");
        return sb.toString();
    }

    private static String trimEnd(String s) {
        int end = s.length();
        while (end > 0 && Character.isWhitespace(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(0, end);
    }

    private static void appendOutputFile(StringBuilder sb, TaskState task) {
        String outputPath = TaskOutputPaths.outputPath(task.id()).toString();
        sb.append('\n').append('<').append(OUTPUT_FILE_TAG).append('>')
          .append(escape(outputPath)).append("</").append(OUTPUT_FILE_TAG).append('>');
    }

    // ── Summary / status text ───────────────────────────────────────────────


    private static String agentSummary(TaskState task) {
        return switch (task.status()) {
            case COMPLETED -> "Agent \"" + task.description() + "\" finished";
            case FAILED -> "Agent \"" + task.description() + "\" failed: "
                + task.errorMessage().orElse("Unknown error");
            case KILLED -> "Agent \"" + task.description() + "\" was stopped";
            default -> "Agent \"" + task.description() + "\" " + statusText(task.status());
        };
    }


    private static String bashSummary(TaskState task) {
        String base = BACKGROUND_BASH_SUMMARY_PREFIX + "\"" + task.description() + "\"";
        return switch (task.status()) {
            case COMPLETED -> base + exitSuffix(task, "completed", " (exit code ");
            case FAILED -> base + exitSuffix(task, "failed", " (with exit code ");
            case KILLED -> base + " was stopped";
            default -> base + " " + statusText(task.status());
        };
    }

    private static String workflowSummary(TaskState task) {
        String base = "Dynamic workflow \"" + task.description() + "\"";
        return switch (task.status()) {
            case COMPLETED -> base + " completed";
            case FAILED -> base + " failed: "
                + task.errorMessage().orElse("Unknown error");
            case KILLED -> base + " was stopped";
            default -> base + " " + statusText(task.status());
        };
    }

    private static String exitSuffix(TaskState task, String verb, String prefix) {
        return task.exitCode()
            .map(code -> " " + verb + prefix + code + ")")
            .orElse(" " + verb);
    }


    private static String statusText(TaskStatus status) {
        return switch (status) {
            case COMPLETED -> "completed successfully";
            case FAILED -> "failed";
            case KILLED -> "was stopped";
            case RUNNING -> "is running";
            case PENDING -> "is pending";
            case PAUSED -> "is paused";
        };
    }


    private static String taskType(TaskType type) {
        return switch (type) {
            case LOCAL_BASH -> "local_bash";
            case LOCAL_AGENT -> "local_agent";
            case REMOTE_AGENT -> "remote_agent";
            case IN_PROCESS_TEAMMATE -> "in_process_teammate";
            case LOCAL_WORKFLOW -> "local_workflow";
            case MONITOR_MCP -> "monitor_mcp";
            case MONITOR_WS -> "monitor_ws";
            case DREAM -> "dream";
            case WEB_SESSION -> "web_session";
        };
    }

    /** Minimal XML text escaping for description / id / path / result values. */
    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
