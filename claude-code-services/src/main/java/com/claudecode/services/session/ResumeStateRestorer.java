package com.claudecode.services.session;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.core.engine.FileHistoryManager;
import com.claudecode.runtime.query.QuerySession;
import com.claudecode.core.engine.TranscriptSink;
import com.claudecode.core.message.*;
import com.claudecode.core.queue.QueuePriority;
import com.claudecode.core.queue.QueuedCommand;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.core.text.XmlEscaper;
import com.claudecode.core.state.AgentColorStore;
import com.claudecode.core.state.CwdState;
import com.claudecode.services.config.HookSettings;
import com.claudecode.services.config.TrustConfigStore;
import com.claudecode.services.cost.CostTracker;
import com.claudecode.services.hooks.HookEngine;
import com.claudecode.session.SessionStorage;
import com.claudecode.tools.worktree.WorktreeService;
import com.claudecode.tools.worktree.WorktreeSession;
import com.claudecode.tools.tasks.TaskRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Orchestrates the side effect cascade that turns a persisted JSONL into a ready-to-run session on
 * {@code /resume}.
 */
public final class ResumeStateRestorer {

    private static final Logger log = LoggerFactory.getLogger(ResumeStateRestorer.class);
    private static final Pattern TASK_ID_TAG = Pattern.compile(
        "<task(?:-|_)id>([^<]+)</task(?:-|_)id>");
    private static final int ORPHAN_NOTIFICATION_CAP = 20;
    private static final String ORPHAN_SUMMARY_MARKER = "__orphan_summary__:workflow";
    private static final String ORPHAN_LIVE_MARKER_PREFIX = "__orphan_summary_live__:";
    private static final String AGGREGATE_ORPHAN_SUMMARY =
        "Orphaned by a previous Claude Code process exit and reported in an aggregate "
            + "summary (over the per-task notification cap).";

    private final QuerySession engine;
    private final SessionStorage storage;
    private final HookEngine hookEngine;   // may be null in tests
    private final CostTracker costTracker; // may be null in tests
    private final Predicate<String> goalRestoreAllowed;

    public ResumeStateRestorer(QuerySession engine, SessionStorage storage,
                               HookEngine hookEngine, CostTracker costTracker) {
        this(engine, storage, hookEngine, costTracker,
            ResumeStateRestorer::isGoalRestoreAllowed);
    }

    ResumeStateRestorer(QuerySession engine, SessionStorage storage,
                        HookEngine hookEngine, CostTracker costTracker,
                        Predicate<String> goalRestoreAllowed) {
        this.engine = engine;
        this.storage = storage;
        this.hookEngine = hookEngine;
        this.costTracker = costTracker;
        this.goalRestoreAllowed = goalRestoreAllowed;
    }

    /**
     * Best-effort SessionEnd hook fire on the outgoing session.
     */
    public void preSwitch() {
        exitCurrentWorktreeBeforeSwitch();
        if (hookEngine == null) return;
        try {
            hookEngine.dispatchSessionEnd("resume");
        } catch (Exception e) {
            log.warn("SessionEnd hook fire on resume switch failed: {}", e.getMessage());
        }
    }


    void exitCurrentWorktreeBeforeSwitch() {
        WorktreeSession current = WorktreeService.getCurrentWorktreeSession();
        if (current == null) return;
        WorktreeService.restoreWorktreeSession(null);
        WorktreeService.resetLatches();
        try {
            if (Files.isDirectory(Path.of(current.originalCwd()))) {
                System.setProperty("user.dir", current.originalCwd());


                CwdState.setOriginalCwd(Path.of(current.originalCwd()));
            }
        } catch (Exception e) {
            log.warn("Failed to restore original cwd on worktree exit: {}", e.getMessage());
        }
    }

    /**
     * Full post-load restoration. {@code msgs} is the (already
     * deserializeMessages-cleaned) list; {@code sessionFile} is the JSONL
     * the resumed conversation lives in.
     */
    public void postSwitch(Path sessionFile, List<Message> msgs, String cwd) {
        postSwitch(sessionFile, msgs, cwd, true);
    }


    public void postSwitch(Path sessionFile, List<Message> msgs, String cwd,
                           boolean persistRecoveryTranscript) {
        if (persistRecoveryTranscript) {
            persistRecoveredMessages(sessionFile, msgs);
        }
        restoreCost(sessionFile);
        restoreMetadata(sessionFile);
        restoreReadFiles(msgs, cwd);
        restoreBashTools(msgs);
        restoreFileHistory(sessionFile);
        restoreWorktreeState(sessionFile);
        restoreGoal(msgs, cwd);
        restoreOrphanedBackgroundWorkflows(msgs);
        fireSessionStart();
    }


    void restoreOrphanedBackgroundWorkflows(List<Message> messages) {
        if (messages == null || messages.isEmpty()) return;
        Map<String, WorkflowLaunch> workflows = new LinkedHashMap<>();
        Set<String> notifiedTaskIds = new HashSet<>();
        Set<String> stoppedTaskIds = new HashSet<>();

        for (Message message : messages) {
            notificationText(message).ifPresent(text -> collectTaskIds(text, notifiedTaskIds));
            if (!(message instanceof UserMessage user) || user.toolUseResult() == null) continue;
            JsonNode result = user.toolUseResult() instanceof JsonNode json
                ? json : JsonUtils.getMapper().valueToTree(user.toolUseResult());
            if (result.hasNonNull("task_id") && result.hasNonNull("task_type")) {
                stoppedTaskIds.add(result.path("task_id").asText());
            }
            if (!Strings.CS.equals(
                        "async_launched", result.path("status").asText())
                    || !Strings.CS.equals(
                        "local_workflow", result.path("taskType").asText())
                    || !result.hasNonNull("taskId") || result.hasNonNull("error")) {
                continue;
            }
            String toolUseId = successfulToolUseId(user);
            if (toolUseId == null) continue;
            String taskId = result.path("taskId").asText();
            workflows.put(taskId, new WorkflowLaunch(
                taskId, toolUseId, text(result, "workflowName"), text(result, "runId")));
        }

        Set<String> liveExclusions = new HashSet<>();
        for (String id : notifiedTaskIds) {
            if (Strings.CS.startsWith(
                    id, ORPHAN_LIVE_MARKER_PREFIX)) {
                liveExclusions.add(id.substring(ORPHAN_LIVE_MARKER_PREFIX.length()));
            }
        }
        if (notifiedTaskIds.contains(ORPHAN_SUMMARY_MARKER)) {
            for (String taskId : workflows.keySet()) {
                if (!liveExclusions.contains(taskId)) notifiedTaskIds.add(taskId);
            }
        }

        List<WorkflowLaunch> orphaned = new ArrayList<>();
        List<String> live = new ArrayList<>();
        for (WorkflowLaunch workflow : workflows.values()) {
            if (notifiedTaskIds.contains(workflow.taskId())
                    || stoppedTaskIds.contains(workflow.taskId())) {
                continue;
            }
            if (TaskRegistry.global().store().get(workflow.taskId()).isPresent()) {
                live.add(workflow.taskId());
            } else {
                orphaned.add(workflow);
            }
        }
        if (orphaned.isEmpty()) return;
        if (orphaned.size() > ORPHAN_NOTIFICATION_CAP) {
            enqueueAggregateWorkflowOrphans(orphaned, live);
            return;
        }
        for (WorkflowLaunch workflow : orphaned) {
            String quotedName = workflow.workflowName() == null
                ? "" : " \"" + workflow.workflowName() + "\"";
            String resume = workflow.runId() == null ? ""
                : " To pick up where it left off, relaunch with Workflow({scriptPath, "
                    + "resumeFromRunId: \"" + workflow.runId()
                    + "\"}) — completed agent() calls return cached.";
            String summary = "No completion record was found for background workflow"
                + quotedName
                + " from the previous session. It may have been stopped (via the UI or "
                + "TaskStop — these leave no transcript marker), or it may have been running "
                + "when the previous Claude Code process exited." + resume;
            String xml = "<task-notification>\n"
                + "<task-id>" + XmlEscaper.escapeText(workflow.taskId()) + "</task-id>\n"
                + "<tool-use-id>" + XmlEscaper.escapeText(workflow.toolUseId())
                + "</tool-use-id>\n"
                + "<status>stopped</status>\n"
                + "<summary>" + XmlEscaper.escapeText(summary) + "</summary>\n"
                + "</task-notification>";
            engine.conversation().getMessageQueue().enqueuePendingNotification(
                new QueuedCommand(xml, null, "task-notification", QueuePriority.NEXT,
                    true, null, false, false, null, null, null, null,
                    workflow.taskId()));
            engine.conversation().getMessageQueue().enqueueSdkEvent(
                new SDKMessage.TaskNotification(workflow.taskId(), workflow.toolUseId(),
                    "stopped", null, summary));
        }
    }

    private void enqueueAggregateWorkflowOrphans(
            List<WorkflowLaunch> orphaned, List<String> live) {
        for (WorkflowLaunch workflow : orphaned) {
            engine.conversation().getMessageQueue().enqueueSdkEvent(
                new SDKMessage.TaskNotification(workflow.taskId(), workflow.toolUseId(),
                    "stopped", null, AGGREGATE_ORPHAN_SUMMARY));
        }
        List<String> ids = new ArrayList<>();
        orphaned.stream().limit(ORPHAN_NOTIFICATION_CAP)
            .map(WorkflowLaunch::taskId).forEach(ids::add);
        ids.add(ORPHAN_SUMMARY_MARKER);
        live.stream().map(id -> ORPHAN_LIVE_MARKER_PREFIX + id).forEach(ids::add);
        StringBuilder xml = new StringBuilder("<task-notification>\n");
        for (String id : ids) {
            xml.append("<task-id>").append(XmlEscaper.escapeText(id))
                .append("</task-id>\n");
        }
        xml.append("<status>stopped</status>\n<summary>")
            .append(orphaned.size())
            .append(" background workflow task(s) from the previous session have no completion "
                + "record. They may have been stopped (via the UI, Monitor timeout, or agent "
                + "teardown — these leave no transcript marker), or they may have been running "
                + "when the previous Claude Code process exited. They have been marked stopped. "
                + "First 20 task ids: ")
            .append(String.join(", ", orphaned.stream().limit(ORPHAN_NOTIFICATION_CAP)
                .map(WorkflowLaunch::taskId).toList()))
            .append("""
                . Task ids in this notification beginning with "__orphan_summary" are \
                internal scan markers, not tasks.</summary>
                </task-notification>""");
        engine.conversation().getMessageQueue().enqueuePendingNotification(
            new QueuedCommand(xml.toString(), null, "task-notification", QueuePriority.LATER,
                true, null, false, false, null, null, null, null, null));
    }

    private static Optional<String> notificationText(Message message) {
        if (message instanceof UserMessage user && user.message() != null) {
            if (user.message().text() != null) {
                return Optional.of(user.message().text());
            }
            if (user.message().blocks() != null) {
                StringBuilder text = new StringBuilder();
                for (var block : user.message().blocks()) {
                    if (block instanceof TextBlock value) {
                        if (!text.isEmpty()) text.append('\n');
                        text.append(value.text());
                    }
                }
                return Optional.of(text.toString());
            }
        }
        if (message instanceof AttachmentMessage attachment
                && attachment.payload() instanceof QueuedCommandAttachment queued) {
            return Optional.ofNullable(queued.text());
        }
        return Optional.empty();
    }

    private static void collectTaskIds(String text, Set<String> target) {
        if (text == null
                || !(Strings.CS.contains(text, "<task-notification>")
                    || Strings.CS.contains(text, "<task_notification>"))
                || !Strings.CS.contains(text, "<status>")) {
            return;
        }
        var matcher = TASK_ID_TAG.matcher(text);
        while (matcher.find()) target.add(matcher.group(1));
    }

    private static String successfulToolUseId(UserMessage user) {
        if (user.message() == null || user.message().blocks() == null) return null;
        for (var block : user.message().blocks()) {
            if (block instanceof ToolResultBlock result && !result.isError()) {
                return result.toolUseId();
            }
        }
        return null;
    }

    private static String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.path(field).asText() : null;
    }

    private record WorkflowLaunch(
        String taskId, String toolUseId, String workflowName, String runId) {}

    /**
     * Persists only messages that the recovery pipeline synthesized in memory.
     */
    public void persistRecoveredMessages(Path sessionFile, List<Message> msgs) {
        if (sessionFile == null || msgs == null || msgs.isEmpty()) return;
        TranscriptSink sink = engine.execution().getTranscriptSink();
        if (sink == null) return;
        try {
            Set<String> persistedUuids = new HashSet<>();
            for (Message stored : storage.readMessages(sessionFile)) {
                if (stored.uuid() != null) persistedUuids.add(stored.uuid());
            }
            for (Message recovered : msgs) {
                String uuid = recovered.uuid();
                if (uuid == null || !persistedUuids.add(uuid)) continue;
                sink.record(engine.conversation().getSessionId(), recovered);
            }
        } catch (Exception e) {
            log.warn("Recovery transcript persistence failed for {}: {}",
                sessionFile, e.getMessage());
        }
    }

    // ── Individual steps ─────────────────────────────────────────────────

    void restoreGoal(List<Message> msgs, String cwd) {
        if (hookEngine == null) return;
        try {
            if (!goalRestoreAllowed.test(cwd)) {
                hookEngine.clearGoal();
                return;
            }
            Usage usage = engine.execution().getTotalUsage();
            long tokens = usage == null ? 0L
                : usage.inputTokens() + usage.outputTokens()
                    + usage.cacheCreationInputTokens() + usage.cacheReadInputTokens();
            hookEngine.restoreGoalFromTranscript(msgs, tokens);
        } catch (Exception e) {
            log.warn("Goal restore failed: {}", e.getMessage());
        }
    }

    private static boolean isGoalRestoreAllowed(String cwd) {
        if (HookSettings.areGoalHooksRestricted()) return false;
        String resolved = StringUtils.isBlank(cwd)
            ? System.getProperty("user.dir") : cwd;
        return TrustConfigStore.isTrustAccepted(Path.of(resolved));
    }

    void restoreCost(Path sessionFile) {
        if (sessionFile == null) return;
        try {
            Usage summed = storage.sumAssistantUsage(sessionFile);
            // Seed the engine's running total — Java tracks cumulative usage
            // directly on QuerySession (no separate CostTracker in the CLI wiring).
            // Keeping the CostTracker branch below so services that DO wire one
            // stay consistent.
            engine.execution().setTotalUsage(summed);
            if (costTracker != null) {
                costTracker.reset();
                costTracker.addUsage(summed);
            }
        } catch (Exception e) {
            log.warn("Cost restore failed: {}", e.getMessage());
        }
    }

    void restoreMetadata(Path sessionFile) {
        if (sessionFile == null) return;
        try {
            SessionStorage.MetadataSnapshot snap = storage.scanMetadata(sessionFile);
            // Session-level metadata store is per-agent; clear stale entries
            // then apply the resumed snapshot so a fresh session doesn't leak
            // colour/name bindings from the previous run.
            AgentColorStore.resetAll();
            snap.agentColor().ifPresent(color -> {
                String agent = snap.agentName().orElse("default");
                AgentColorStore.set(agent, color);
            });
            var permissionModeRestorer = engine.configuration().getConfig().permissionModeRestorer();
            if (permissionModeRestorer != null) {
                snap.permissionMode().ifPresent(permissionModeRestorer);
            }
            // custom-title / tag / mode remain consumed from JSONL metadata.
            // permissionMode is behavioral state and must also update the live
            // gate before an interactive local command or model turn runs.
        } catch (Exception e) {
            log.warn("Metadata restore failed: {}", e.getMessage());
        }
    }

    void restoreReadFiles(List<Message> msgs, String cwd) {
        try {
            Set<String> paths = MessageExtractor.extractReadFilePaths(msgs, cwd);
            engine.conversation().putReadFilePaths(paths);
        } catch (Exception e) {
            log.warn("readFileState restore failed: {}", e.getMessage());
        }
        try {
            engine.forks().getFileStateCache().mergeFrom(MessageExtractor.extractReadFileState(msgs, cwd));
        } catch (Exception e) {
            log.warn("fileStateCache restore failed: {}", e.getMessage());
        }
    }

    void restoreBashTools(List<Message> msgs) {
        try {
            Set<String> tools = MessageExtractor.extractBashTools(msgs);
            engine.conversation().putBashTools(tools);
        } catch (Exception e) {
            log.warn("bashTools restore failed: {}", e.getMessage());
        }
    }

    /**
     * Rebuilds the {@code /rewind} "Restore code" checkpoint chain from the transcript's {@code
     * file-history-snapshot} entries.
     */
    void restoreFileHistory(Path sessionFile) {
        if (sessionFile == null) return;
        FileHistoryManager fileHistoryManager = engine.conversation().getFileHistoryManager();
        if (fileHistoryManager == null) return;
        try {
            List<SessionStorage.FileHistorySnapshotEntry> raw = storage.scanFileHistorySnapshots(sessionFile);
            List<FileHistoryManager.Snapshot> chain = FileHistorySnapshotCodec.buildChain(raw);
            fileHistoryManager.restoreFromSnapshots(chain);
        } catch (Exception e) {
            log.warn("File history restore failed: {}", e.getMessage());
        }
    }


    void restoreWorktreeState(Path sessionFile) {
        if (sessionFile == null) return;
        try {
            WorktreeSession fresh = WorktreeService.getCurrentWorktreeSession();
            if (fresh != null) {
                WorktreeService.persistWorktreeState(storage, sessionFile, engine.conversation().getSessionId(), fresh);
                return;
            }
            WorktreeSession persisted = WorktreeService.readPersistedWorktreeState(storage, sessionFile);
            if (persisted == null) return;
            if (!Files.isDirectory(Path.of(persisted.worktreePath()))) {
                WorktreeService.persistWorktreeState(storage, sessionFile, engine.conversation().getSessionId(), null);
                return;
            }
            System.setProperty("user.dir", persisted.worktreePath());

            // setOriginalCwd(getCwd)).
            CwdState.setOriginalCwd(Path.of(persisted.worktreePath()));
            WorktreeService.restoreWorktreeSession(persisted);
            WorktreeService.resetLatches();
        } catch (Exception e) {
            log.warn("Worktree state restore failed: {}", e.getMessage());
        }
    }

    void fireSessionStart() {
        if (hookEngine == null) return;
        try {
            engine.conversation().injectSystemReminder(hookEngine.dispatchSessionStartWithOutcome("resume").additionalContext());
        } catch (Exception e) {
            log.warn("SessionStart hook fire on resume failed: {}", e.getMessage());
        }
    }
}
