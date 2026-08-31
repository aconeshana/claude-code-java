package com.claudecode.tools.worktree;

import org.apache.commons.lang3.Strings;

import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.core.engine.ToolResult;
import com.claudecode.session.SessionManager;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.session.SessionStorage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import com.claudecode.tools.AnnotatedTool;
import com.claudecode.tools.BuiltInTool;
import com.claudecode.tools.ToolTexts;
import com.claudecode.tools.ToolCallResult;
import com.claudecode.tools.ValidationResult;

/**
 * ExitWorktree — exits a worktree session created by {@link EnterWorktreeTool} and restores the
 * original working directory.
 */
@BuiltInTool(
    name = "ExitWorktree",
    shouldDefer = true
)
public class ExitWorktreeTool extends AnnotatedTool<JsonNode, String> {


    @Override
    public String searchHint() {
        return "exit a worktree session and return to the original directory";
    }

    private static final JsonNode SCHEMA = buildSchema();
    private record Invocation(String text, ObjectNode payload) {}

    @Override
    public String description() {
        return ToolTexts.description("ExitWorktree");
    }

    @Override
    public JsonNode inputSchema() { return SCHEMA; }


    @Override
    public Object toAutoClassifierInput(JsonNode input) {
        return input == null ? "" : input.path("action").asText("");
    }


    @Override
    public boolean isDestructive(JsonNode input) {
        return input != null &&Strings.CS.equals( "remove", input.path("action").asText());
    }



    @Override
    public ValidationResult validateInput(JsonNode input, ToolExecutionContext context) {
// Scope guard: getCurrentWorktreeSession is null unless EnterWorktree ran
        // in THIS session. Worktrees created by raw `git worktree add`, or by
        // EnterWorktree in a previous session, do not populate it.
        WorktreeSession session = WorktreeService.getCurrentWorktreeSession();
        if (session == null) {
            return ValidationResult.invalid(
                "No-op: there is no active EnterWorktree session to exit. This tool only operates "
                    + "on worktrees created by EnterWorktree in the current session — it will not touch "
                    + "worktrees created manually or in a previous session. No filesystem changes were made.");
        }

        String action = input.hasNonNull("action") ? input.get("action").asText() : "";
        boolean discardChanges = input.path("discard_changes").asBoolean(false);

        if (session.enteredExisting() && Strings.CS.equals("remove", action)) {
            return ValidationResult.invalid(
                "This worktree was entered with `path` and is not owned by this session. "
                    + "Use action: \"keep\" to return to the original directory; "
                    + "ExitWorktree will not remove an existing worktree.");
        }

        if (Strings.CS.equals("remove", action) && !discardChanges) {
            WorktreeService.ChangeSummary summary =
                WorktreeService.changeSummaryOrNull(session.worktreePath(), session.originalHeadCommit());
            if (summary == null) {
                return ValidationResult.invalid("Could not verify worktree state at " + session.worktreePath()
                    + ". Refusing to remove without explicit confirmation. Re-invoke with "
                    + "discard_changes: true to proceed — or use action: \"keep\" to preserve the worktree.");
            }
            if (summary.changedFiles() > 0 || summary.commits() > 0) {
                List<String> parts = new ArrayList<>();
                if (summary.changedFiles() > 0) {
                    parts.add(summary.changedFiles() + (summary.changedFiles() == 1 ? " uncommitted file" : " uncommitted files"));
                }
                if (summary.commits() > 0) {
                    parts.add(summary.commits() + (summary.commits() == 1 ? " commit" : " commits")
                        + " on " + (session.worktreeBranch() != null ? session.worktreeBranch() : "the worktree branch"));
                }
                return ValidationResult.invalid("Worktree has " + String.join(" and ", parts) + ". Removing will discard "
                    + "this work permanently. Confirm with the user, then re-invoke with discard_changes: "
                    + "true — or use action: \"keep\" to preserve the worktree.");
            }
        }
        return ValidationResult.valid();
    }

    @Override
    public String call(JsonNode input, ToolExecutionContext context) {
        return callWithResult(input, context).rawResult();
    }

    @Override
    public ToolCallResult<String> callWithResult(JsonNode input, ToolExecutionContext context) {
        Invocation invocation = invoke(input, context);
        ToolResult mapped = Strings.CS.startsWith(invocation.text(), "Error:")
            ? ToolResult.error(invocation.text())
            : ToolResult.success(invocation.text());
        if (invocation.payload() != null) mapped = mapped.withToolUseResult(invocation.payload());
        return new ToolCallResult<>(invocation.text(), mapped);
    }

    private Invocation invoke(JsonNode input, ToolExecutionContext context) {
// validateInput guards this, but getCurrentWorktreeSession is a mutable
        // singleton — defend against a race between validation and execution

        WorktreeSession session = WorktreeService.getCurrentWorktreeSession();
        if (session == null) {
            return new Invocation("Error: Not in a worktree session", null);
        }

        String action = input.hasNonNull("action") ? input.get("action").asText() : "";
        if (!Strings.CS.equals("keep", action) && !Strings.CS.equals("remove", action)) {
            return new Invocation("Error: \"action\" must be \"keep\" or \"remove\"", null);
        }
        if (session.enteredExisting() && Strings.CS.equals("remove", action)) {
            return new Invocation("Error: worktrees entered with `path` are not removed; use action: \"keep\"", null);
        }

        // Capture before keepWorktree/cleanupWorktree null out the current session.
        String originalCwd = session.originalCwd();
        String worktreePath = session.worktreePath();
        String worktreeBranch = session.worktreeBranch();

        // Re-count at execution time for the result message — the worktree state may
        // have changed since the check above (or discardChanges skipped it entirely).
        WorktreeService.ChangeSummary finalSummary =
            WorktreeService.changeSummaryOrNull(worktreePath, session.originalHeadCommit());
        int changedFiles = finalSummary != null ? finalSummary.changedFiles() : 0;
        int commits = finalSummary != null ? finalSummary.commits() : 0;

        String message;
        if (Strings.CS.equals("keep", action)) {
            WorktreeService.keepWorktree();
            message = "Exited worktree. Your work is preserved at " + worktreePath
                + (worktreeBranch != null ? " on branch " + worktreeBranch : "")
                + ". Session is now back in " + originalCwd + ".";
            if (session.hasTmuxSession()) {
                message += " Tmux session " + session.tmuxSessionName()
                    + " is still running; reattach with `tmux attach -t "
                    + session.tmuxSessionName() + "`.";
            }
        } else {
            if (session.hasTmuxSession()) {
                WorktreeService.killTmuxSession(session.tmuxSessionName());
            }
            WorktreeService.cleanupWorktree();
            List<String> discardParts = new ArrayList<>();
            if (commits > 0) discardParts.add(commits + (commits == 1 ? " commit" : " commits"));
            if (changedFiles > 0) discardParts.add(changedFiles + (changedFiles == 1 ? " uncommitted file" : " uncommitted files"));
            String discardNote = discardParts.isEmpty() ? "" : " Discarded " + String.join(" and ", discardParts) + ".";
            message = "Exited and removed worktree at " + worktreePath + "." + discardNote
                + " Session is now back in " + originalCwd + ".";
        }

        SessionStorage storage = new SessionStorage(JsonUtils.getMapper());
        WorktreeService.persistWorktreeState(storage,
            new SessionManager(originalCwd).getSessionFile(context.sessionId()),
            context.sessionId(), null);

        ObjectNode payload = mapper().createObjectNode();
        payload.put("action", action);
        payload.put("originalCwd", originalCwd);
        payload.put("worktreePath", worktreePath);
        if (worktreeBranch == null) payload.putNull("worktreeBranch");
        else payload.put("worktreeBranch", worktreeBranch);
        if (Strings.CS.equals("keep", action) && session.tmuxSessionName() != null) {
            payload.put("tmuxSessionName", session.tmuxSessionName());
        }
        if (Strings.CS.equals("remove", action)) {
            payload.put("discardedFiles", changedFiles);
            payload.put("discardedCommits", commits);
        }
        payload.put("message", message);
        return new Invocation(message, payload);
    }

    private static JsonNode buildSchema() {
        ObjectNode schema = mapper().createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        ObjectNode actionProp = props.putObject("action");
        actionProp.put("type", "string");
        ArrayNode actionEnum = actionProp.putArray("enum");
        actionEnum.add("keep");
        actionEnum.add("remove");
        actionProp.put("description",
            "\"keep\" leaves the worktree and branch on disk; \"remove\" deletes both.");

        ObjectNode discardProp = props.putObject("discard_changes");
        discardProp.put("type", "boolean");
        discardProp.put("description",
            "Required true when action is \"remove\" and the worktree has uncommitted files or "
                + "unmerged commits. The tool will refuse and list them otherwise.");

        ArrayNode required = schema.putArray("required");
        required.add("action");


// reject unknown keys. createObjectSchema is permissive; set strict.
        schema.put("additionalProperties", false);
        return schema;
    }
}
