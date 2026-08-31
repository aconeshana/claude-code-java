package com.claudecode.tools.worktree;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.core.engine.ToolResult;
import com.claudecode.core.prompt.SystemPromptSectionResolver;
import com.claudecode.session.SessionManager;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.session.SessionStorage;
import com.claudecode.core.state.CwdState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Optional;
import com.claudecode.tools.AnnotatedTool;
import com.claudecode.tools.BuiltInTool;
import com.claudecode.tools.ToolTexts;
import com.claudecode.tools.ToolCallResult;
import com.claudecode.tools.ValidationResult;

/**
 * EnterWorktree — creates (or resumes) an isolated git worktree and switches the session into it.
 */
@BuiltInTool(
    name = "EnterWorktree",
    shouldDefer = true
)
public class EnterWorktreeTool extends AnnotatedTool<JsonNode, String> {


    @Override
    public String searchHint() {
        return "create an isolated git worktree and switch into it";
    }

    private static final JsonNode SCHEMA = buildSchema();
    private static final SecureRandom RANDOM = new SecureRandom();
    private record Invocation(String text, ObjectNode payload) {}

    @Override public String description() {
        return ToolTexts.description("EnterWorktree");
    }

    @Override public JsonNode inputSchema() { return SCHEMA; }


    @Override
    public Object toAutoClassifierInput(JsonNode input) {
        return input == null ? "" : input.path("name").asText(input.path("path").asText(""));
    }


    @Override
    public ValidationResult validateInput(JsonNode input, ToolExecutionContext context) {
        boolean hasName = input.hasNonNull("name") && !StringUtils.isBlank(input.get("name").asText(""));
        boolean hasPath = input.hasNonNull("path") && !StringUtils.isBlank(input.get("path").asText(""));
        if (hasName && hasPath) {
            return ValidationResult.invalid("`name` and `path` are mutually exclusive");
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
        String text = invocation.text();
        if (Strings.CS.startsWith(text, "Error:")) {
            String modelText = text.substring("Error:".length()).stripLeading();
            return new ToolCallResult<>(text,
                ToolResult.error(modelText).withToolUseResult(text));
        }
        ToolResult result = ToolResult.success(text);
        return new ToolCallResult<>(text, invocation.payload() == null
            ? result : result.withToolUseResult(invocation.payload()));
    }

    private Invocation invoke(JsonNode input, ToolExecutionContext context) {
        boolean hasPath = input.hasNonNull("path") && !StringUtils.isBlank(input.get("path").asText(""));
        WorktreeSession active = WorktreeService.getCurrentWorktreeSession();
        if (!hasPath && active != null) {
            return new Invocation("Error: Already in a worktree session", null);
        }

        // Capture the session's real cwd BEFORE any switch below — this is also
// what the resulting WorktreeSession.originalCwd anchors on, so the
        // session's JSONL file (keyed by this cwd via SessionManager) stays the
        // one the conversation has always lived in, not a path derived from the
        // worktree we're about to enter.
        String originalCwd = active != null ? active.originalCwd() : context.workingDirectory();

        // Main repo root for the git path (null when not a git repo — createSessionWorktree

        // branch that runs before git-root resolution).
        String repoRoot = WorktreeService.findCanonicalGitRoot(originalCwd);

        if (hasPath) {
            Optional<WorktreeService.RegisteredWorktree> registered =
                WorktreeService.findRegisteredWorktree(
                    context.workingDirectory(), input.get("path").asText());
            if (registered.isEmpty()) {
                return new Invocation("Error: path must be an existing registered worktree of the current repository", null);
            }
            WorktreeService.RegisteredWorktree target = registered.get();
            String anchorCwd = active != null ? active.originalCwd() : repoRoot;
            if (anchorCwd == null) anchorCwd = context.workingDirectory();
            String worktreeName = Path.of(target.path()).getFileName().toString();
            WorktreeSession session = new WorktreeSession(
                anchorCwd, target.path(), worktreeName, target.branch(),
                active != null ? active.originalBranch() : null,
                target.headCommit(), context.sessionId(), null, false, 0L, false,
                false, true);
            WorktreeService.replaceCurrentWorktreeSession(session);
            switchSessionCwd(session, anchorCwd, context.sessionId());
            String branchInfo = target.branch() == null ? "" : " on branch " + target.branch();
            String message = "Entered existing worktree at " + target.path() + branchInfo
                + ". The session is now working in the worktree. Use ExitWorktree with "
                + "action: \"keep\" to return to the original directory; this worktree will not be removed.";
            return new Invocation(message, worktreePayload(target.path(), target.branch(), message));
        }

        String slug = (input.hasNonNull("name") && !StringUtils.isBlank(input.get("name").asText("")))
            ? input.get("name").asText()
            : generateSlug();

        try {
            WorktreeService.validateWorktreeSlug(slug);
        } catch (IllegalArgumentException e) {
            return new Invocation("Error: " + e.getMessage(), null);
        }

        WorktreeService.WorktreeCreateResult created;
        try {
            created = WorktreeService.createSessionWorktree(slug, originalCwd);
        } catch (WorktreeException e) {
            return new Invocation("Error: " + e.getMessage(), null);
        }

        // Anchor the session's originalCwd + JSONL on the repo root for git worktrees
        // (return there on exit), or the real session cwd for hook-based ones (no repo root).
        String anchorCwd = created.hookBased() ? originalCwd : repoRoot;

        WorktreeSession session = new WorktreeSession(
            anchorCwd, created.worktreePath(), slug, created.worktreeBranch(),
            created.originalBranch(), created.originalHeadCommit(),
            context.sessionId(), null, created.hookBased(), 0L, false);

        if (!WorktreeService.tryClaim(session)) {
            return new Invocation("Error: Already in a worktree session", null);
        }

        switchSessionCwd(session, anchorCwd, context.sessionId());

        String branchInfo = session.worktreeBranch() != null ? " on branch " + session.worktreeBranch() : "";
        String message = "Created worktree at " + session.worktreePath() + branchInfo
            + ". The session is now working in the worktree. Use ExitWorktree to leave mid-session, "
            + "or exit the session to be prompted.";
        return new Invocation(message, worktreePayload(
            session.worktreePath(), session.worktreeBranch(), message));
    }

    private static ObjectNode worktreePayload(String path, String branch, String message) {
        ObjectNode payload = JsonUtils.getMapper().createObjectNode();
        payload.put("worktreePath", path);
        if (branch == null) payload.putNull("worktreeBranch");
        else payload.put("worktreeBranch", branch);
        payload.put("message", message);
        return payload;
    }

    private static void switchSessionCwd(
            WorktreeSession session, String anchorCwd, String sessionId) {
        System.setProperty("user.dir", session.worktreePath());
        CwdState.setOriginalCwd(Path.of(session.worktreePath()));
        WorktreeService.resetLatches();
        SystemPromptSectionResolver.clearAll();

        SessionStorage storage = new SessionStorage(JsonUtils.getMapper());
        WorktreeService.persistWorktreeState(storage,
            new SessionManager(anchorCwd).getSessionFile(sessionId), sessionId, session);
    }

    private static String generateSlug() {
        byte[] bytes = new byte[4];
        RANDOM.nextBytes(bytes);
        return "session-" + HexFormat.of().formatHex(bytes);
    }

    private static JsonNode buildSchema() {
        ObjectNode schema = mapper().createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");

        ObjectNode nameProp = properties.putObject("name");
        nameProp.put("type", "string");
        nameProp.put("description",
            "Optional name for a new worktree. Each \"/\"-separated segment may contain only letters, "
                + "digits, dots, underscores, and dashes; max 64 chars total. "
                + "A random name is generated if not provided. Mutually exclusive with `path`.");

        ObjectNode pathProp = properties.putObject("path");
        pathProp.put("type", "string");
        pathProp.put("description",
            "Path to an existing worktree of the current repository to switch into instead of "
                + "creating a new one. Must appear in `git worktree list` for the current repo. "
                + "Mutually exclusive with `name`.");

        schema.put("additionalProperties", false);
        return schema;
    }
}
