package com.claudecode.runtime.query;

import com.claudecode.core.engine.OrphanedPermission;
import com.claudecode.core.engine.PermissionAskCallback;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.core.message.AssistantContent;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.ContentBlock;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.message.ToolUseBlock;
import com.claudecode.core.message.Usage;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Replays a single tool_use whose permission decision arrived "orphaned" — the original SDK {@code
 * control_request} context was lost (e.g.
 */
final class OrphanedPermissionExecutor {

    private OrphanedPermissionExecutor() {}

    public static void execute(OrphanedPermission orphaned, DefaultQuerySession engine, Consumer<SDKMessage> emit) {
        JsonNode permissionResult = orphaned.permissionResult();
        if (permissionResult == null || permissionResult.isMissingNode()) return;

        String toolUseId = permissionResult.path("toolUseID").asText(null);
        if (StringUtils.isBlank(toolUseId)) return;

        // Recover the original tool_use from the transcript (it was never resolved).
        Optional<AssistantMessage> maybeMsg = engine.findUnresolvedToolUse(toolUseId);
        if (maybeMsg.isEmpty()) return; // unknown id or already resolved — nothing to replay
        AssistantMessage assistantMessage = maybeMsg.get();

        ToolUseBlock toolUseBlock = null;
        AssistantContent ac = assistantMessage.message();
        if (ac != null && ac.content() != null) {
            for (ContentBlock b : ac.content()) {
                if (b instanceof ToolUseBlock tu && toolUseId.equals(tu.id())) {
                    toolUseBlock = tu;
                    break;
                }
            }
        }
        if (toolUseBlock == null) return;

        String behavior = permissionResult.path("behavior").asText(null);

        // the abort fires while converting the decision at replay time, i.e. AFTER the
        // rejection tool_result is emitted. Capture it here and honor it post-step.
        boolean interrupt = permissionResult.path("interrupt").asBoolean(false);

// Apply updatedInput up-front (allow only).
        ToolUseBlock finalBlock = toolUseBlock;
        JsonNode updatedInput = permissionResult.path("updatedInput");

        // permission hosts, so it must not replace the recovered tool input.
        boolean hasUpdatedInput = updatedInput != null && !updatedInput.isMissingNode()
            && !updatedInput.isNull()
            && !(updatedInput.isObject() && updatedInput.isEmpty());
        if (Strings.CS.equals("allow", behavior) && hasUpdatedInput) {
            finalBlock = new ToolUseBlock(toolUseBlock.id(), toolUseBlock.name(), updatedInput);
        }

        // Temporary callback that returns the recorded decision for this tool_use id,
        // and defers to the original callback for any other tool (safety; normally none).
        PermissionAskCallback original = engine.getPermissionAskCallback();
        PermissionAskCallback replayCallback = ctx -> {
            if (!toolUseId.equals(ctx.toolUseId())) {
                return original != null ? original.ask(ctx) : PermissionAskCallback.Result.deny();
            }
            if (Strings.CS.equals("allow", behavior)) {
                return PermissionAskCallback.Result.allowWithInput(hasUpdatedInput ? updatedInput : null);
            }
            // deny — keep the query alive (SDK controller already answered).
            JsonNode messageNode = permissionResult.path("message");
            String reason = (messageNode != null && !messageNode.isMissingNode() && !messageNode.isNull())
                ? messageNode.asText() : null;
            if (StringUtils.isBlank(reason)) {
                reason = "Permission denied (orphaned control_response)";
            }
            return PermissionAskCallback.Result.denyWithFeedback(reason);
        };

        // De-duplicate the assistant message by tool_use id (CCR resume may already hold it).
        boolean alreadyPresent = engine.getMutableMessages().stream()
            .anyMatch(m -> m instanceof AssistantMessage am
                && am.message() != null && am.message().content() != null
                && am.message().content().stream()
                    .anyMatch(b -> b instanceof ToolUseBlock tu && toolUseId.equals(tu.id())));
        if (!alreadyPresent) {
            engine.getMutableMessages().add(assistantMessage);
            QueryHelpers.recordTranscript(engine, assistantMessage);
        }
        Usage usage = ac.usage();
        emit.accept(new SDKMessage.Assistant(assistantMessage, usage));

        // Run the tool with the replay callback installed; restore the original afterwards.
        PermissionAskCallback prev = engine.getPermissionAskCallback();
        engine.setPermissionAskCallback(replayCallback);
        try {
            ToolExecution.step(finalBlock, engine, emit, assistantMessage.uuid());
        } finally {
            engine.setPermissionAskCallback(prev);
        }
        // deny+interrupt: abort only now that the rejection tool_result has been emitted

        // when the orphaned control_response was first received). allow+interrupt does not abort.
        if (interrupt && Strings.CS.equals("deny", behavior)) {
            engine.interrupt();
        }
    }
}
