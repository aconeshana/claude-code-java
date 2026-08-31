package com.claudecode.core.queue;

import com.claudecode.core.engine.OrphanedPermission;
import com.claudecode.core.message.PastedContent;

import java.util.Map;

/**
 * A command held in the session's queue, waiting for the current turn to finish before being
 * dispatched.
 */
public record QueuedCommand(

        String text,
        /** Paste-chip attachments keyed by chip index; null for non-UI-originated commands. */
        Map<Integer, PastedContent> pastedContents,
        /** Input mode: "bash" | "prompt" | "orphaned-permission" | "task-notification". */
        String mode,
        /**
         * Processing priority.
         */
        QueuePriority priority,
        /** True for system-generated messages hidden in the transcript UI but visible to the model. */
        boolean isMeta,
        /** Provenance tag: "channel" when injected from an MCP channel notification; null = human keyboard. */
        String originKind,
        /** Skip slash-command routing — send text directly to the model even if it starts with '/'. */
        boolean skipSlashCommands,

        boolean bridgeOrigin,
        /** Input text before paste-placeholder expansion; null falls back to {@code text}. */
        String preExpansionValue,
        /** Billing-header workload tag threaded from the cron scheduler. */
        String workload,
        /** Target agent ID; null = main thread. */
        String agentId,
        /** Orphaned SDK control_response payload; non-null only when mode="orphaned-permission". */
        OrphanedPermission orphanedPermission,
        /**
         * Background task whose terminal transition produced this notification; non-null only when
         * mode="task-notification".
         */
        String taskId,
        /** True for model-scheduled cron/loop prompts; suppresses the ordinary attachment pass. */
        boolean modelScheduledOrigin
) {
    public QueuedCommand {
        if (text == null) throw new IllegalArgumentException("text must not be null");
        // NOTE: priority is intentionally left nullable (may be null = "unset"),

        // side coerces it: enqueue() → NEXT, enqueuePendingNotification() →

        // null as NEXT for safety. Do NOT re-add a `null → NEXT` coercion here
        // — it would mask an unset priority and defeat enqueuePendingNotification's
        // `?? 'later'` default (the value would already be NEXT).
        if (mode == null) mode = "prompt";
    }

    // ── Convenience factories ─────────────────────────────────────────────

    /** MCP channel notification — wraps pre-built {@code <channel>} XML. Server name is embedded in the XML. */
    public static QueuedCommand channel(String xmlText) {
        return new QueuedCommand(xmlText, null, "task-notification", QueuePriority.NEXT,
                true, "channel", true, false, null, null, null, null);
    }

    /** Task/notification injection at low priority. */
    public static QueuedCommand notification(String text) {
        return new QueuedCommand(text, null, "task-notification", QueuePriority.LATER,
                true, null, false, false, null, null, null, null);
    }

    /** Normal user-initiated prompt. */
    public static QueuedCommand prompt(String text) {
        return new QueuedCommand(text, null, "prompt", QueuePriority.NEXT,
                false, null, false, false, null, null, null, null);
    }


    public static QueuedCommand modelScheduled(String text, String preExpansionValue,
                                               String workload, String agentId) {
        return new QueuedCommand(text, null, "prompt", QueuePriority.LATER,
            true, null, true, false, preExpansionValue, workload, agentId,
            null, null, true);
    }

    /** Orphaned SDK permission response to replay; processed next as a hidden meta command. */
    public static QueuedCommand orphanedPermission(OrphanedPermission payload) {
        return new QueuedCommand("", null, "orphaned-permission", QueuePriority.NEXT,
                true, null, false, false, null, null, null, payload);
    }

    // ── Backward-compatible constructors ──────────────────────────────────

    /** UI input with paste chips; defaults: mode="prompt", priority=NEXT, all flags false. */
    public QueuedCommand(String text, Map<Integer, PastedContent> pastedContents) {
        this(text, pastedContents, "prompt", QueuePriority.NEXT,
                false, null, false, false, null, null, null, null);
    }

    /**
     * Backward-compatible 11-arg constructor (pre-dates {@code orphanedPermission}).
     * Keeps every existing call site working now that the record gained a 12th field;
     * {@code orphanedPermission} defaults to {@code null}.
     */
    public QueuedCommand(String text, Map<Integer, PastedContent> pastedContents, String mode,
            QueuePriority priority, boolean isMeta, String originKind, boolean skipSlashCommands,
            boolean bridgeOrigin, String preExpansionValue, String workload, String agentId) {
        this(text, pastedContents, mode, priority, isMeta, originKind, skipSlashCommands,
                bridgeOrigin, preExpansionValue, workload, agentId, null);
    }

    /**
     * Backward-compatible 12-arg constructor (pre-dates {@code taskId}). Only
     * task-notification enqueues carry a task id, so every other call site keeps
     * its shape and gets {@code null}.
     */
    public QueuedCommand(String text, Map<Integer, PastedContent> pastedContents, String mode,
            QueuePriority priority, boolean isMeta, String originKind, boolean skipSlashCommands,
            boolean bridgeOrigin, String preExpansionValue, String workload, String agentId,
            OrphanedPermission orphanedPermission) {
        this(text, pastedContents, mode, priority, isMeta, originKind, skipSlashCommands,
                bridgeOrigin, preExpansionValue, workload, agentId, orphanedPermission, null, false);
    }

    /** Backward-compatible canonical shape from before modelScheduledOrigin. */
    public QueuedCommand(String text, Map<Integer, PastedContent> pastedContents, String mode,
            QueuePriority priority, boolean isMeta, String originKind, boolean skipSlashCommands,
            boolean bridgeOrigin, String preExpansionValue, String workload, String agentId,
            OrphanedPermission orphanedPermission, String taskId) {
        this(text, pastedContents, mode, priority, isMeta, originKind, skipSlashCommands,
                bridgeOrigin, preExpansionValue, workload, agentId, orphanedPermission, taskId, false);
    }
}
