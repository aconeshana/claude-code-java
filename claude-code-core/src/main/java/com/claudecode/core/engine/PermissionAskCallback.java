package com.claudecode.core.engine;

import org.apache.commons.lang3.StringUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.claudecode.core.message.ContentBlock;
import java.util.List;

/**
 * Callback interface for interactive permission prompts when a tool requires ASK-level approval.
 */
@FunctionalInterface
public interface PermissionAskCallback {
    /**
     * Prompts the user to allow or deny execution of the named tool.
     * Must not be called from the Lanterna GUI thread.
     */
    Result ask(PermissionAskContext context);

    /**
     * User response to a permission prompt.
     */
    record Result(boolean allowed, String feedback, JsonNode updatedInput,
                  boolean directDenial, List<PermissionUpdate> updatedPermissions,
                  List<ContentBlock> feedbackContentBlocks) {
        /** Backward-compatible constructor before image-bearing permission feedback. */
        public Result(boolean allowed, String feedback, JsonNode updatedInput,
                      boolean directDenial, List<PermissionUpdate> updatedPermissions) {
            this(allowed, feedback, updatedInput, directDenial, updatedPermissions, List.of());
        }

        /** Backward-compatible constructor for interactive/UI callers. */
        public Result(boolean allowed, String feedback, JsonNode updatedInput) {
            this(allowed, feedback, updatedInput, false, List.of(), List.of());
        }

        /** Backward-compatible constructor predating hook permission updates. */
        public Result(boolean allowed, String feedback, JsonNode updatedInput,
                      boolean directDenial) {
            this(allowed, feedback, updatedInput, directDenial, List.of(), List.of());
        }

        public Result {
            if (feedback != null && StringUtils.isBlank(feedback)) feedback = null;
            updatedPermissions = List.copyOf(
                updatedPermissions == null ? List.of() : updatedPermissions);
            feedbackContentBlocks = List.copyOf(
                feedbackContentBlocks == null ? List.of() : feedbackContentBlocks);
        }

        /** Plain allow — no Tab amend. */
        public static Result allow() { return new Result(true, null, null); }

        /** Allow + extra instruction injected as text content block after tool_result. */
        public static Result allowWithFeedback(String feedback) { return new Result(true, feedback, null); }

        /** Allow + rewritten input (interactive tools collect answers during the prompt). */
        public static Result allowWithInput(JsonNode updatedInput) { return new Result(true, null, updatedInput); }

        /** Allow with hook/SDK supplied input and validated permission updates. */
        public static Result allowWithInputAndPermissions(
                JsonNode updatedInput, List<PermissionUpdate> updates) {
            return new Result(true, null, updatedInput, false, updates);
        }

    /**
     * Allow + rewritten input + extra instruction injected as text content block.
     */
    public static Result allowWithInputAndFeedback(JsonNode updatedInput, String feedback) {
        return new Result(true, feedback, updatedInput);
    }
        /** Plain deny — aborts the query. */
        public static Result deny() { return new Result(false, null, null); }
        /** Deny + feedback — model sees REJECT_MESSAGE_WITH_REASON_PREFIX+feedback, no abort. */
        public static Result denyWithFeedback(String feedback) { return new Result(false, feedback, null); }

/**
         * Deny + feedback/images — used by ExitPlanMode's.
         */
        public static Result denyWithFeedback(String feedback, List<ContentBlock> contentBlocks) {
            return new Result(false, feedback, null, false, List.of(), contentBlocks);
        }

        /**
         * Deny from an SDK permission controller. The supplied text is already
         * the model-facing tool_result message; unlike a terminal "No + Tab"
         * response it must not receive {@code REJECT_MESSAGE_WITH_REASON_PREFIX}.
         */
        public static Result denyWithDirectMessage(String message) {
            return new Result(false, message, null, true);
        }
    }
}
