package com.claudecode.services.config;

import com.claudecode.services.config.SettingsSchema.FieldError;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static com.claudecode.services.config.SettingsSchema.checkBoolean;
import static com.claudecode.services.config.SettingsSchema.checkRequiredString;
import static com.claudecode.services.config.SettingsSchema.checkString;
import static com.claudecode.services.config.SettingsSchema.checkStringArray;
import static com.claudecode.services.config.SettingsSchema.enumMsg;
import static com.claudecode.services.config.SettingsSchema.err;
import static com.claudecode.services.config.SettingsSchema.expectedMsg;
import static com.claudecode.services.config.SettingsSchema.isValidUrl;
import static com.claudecode.services.config.SettingsSchema.present;

/**
 * Validates the {@code hooks} settings structure for {@link SettingsSchema}.
 */
final class HooksSchemaValidator {

    private HooksSchemaValidator() {}


    private static final List<String> HOOK_EVENTS = List.of(
        "PreToolUse", "PostToolUse", "PostToolUseFailure", "PostToolBatch", "Notification",
        "UserPromptSubmit", "UserPromptExpansion", "SessionStart", "SessionEnd", "Stop", "StopFailure",
        "SubagentStart", "SubagentStop", "PreCompact", "PostCompact",
        "PermissionRequest", "PermissionDenied", "Setup", "TeammateIdle",
        "TaskCreated", "TaskCompleted", "Elicitation", "ElicitationResult",
        "ConfigChange", "WorktreeCreate", "WorktreeRemove", "InstructionsLoaded",
        "CwdChanged", "FileChanged", "MessageDisplay");


    private static final List<String> SHELL_TYPES = List.of("bash", "powershell");

    static void validate(JsonNode hooks, List<FieldError> errors) {
// Root error-path prefix: hooks settings only ever live under the top-level "hooks" key.
        String path = "hooks";
        if (!present(hooks)) return;
        if (!hooks.isObject()) {
            err(errors, path, expectedMsg("record", hooks));
            return;
        }
        Iterator<Map.Entry<String, JsonNode>> events = hooks.fields();
        while (events.hasNext()) {
            Map.Entry<String, JsonNode> event = events.next();
            String eventPath = path + "." + event.getKey();
            if (!HOOK_EVENTS.contains(event.getKey())) {
                err(errors, eventPath, "Invalid key in record");
                continue;
            }
            validateMatcherArray(event.getValue(), eventPath, errors);
        }
    }

    private static void validateMatcherArray(JsonNode matchers, String path, List<FieldError> errors) {
        if (!matchers.isArray()) {
            err(errors, path, expectedMsg("array", matchers));
            return;
        }
        for (int i = 0; i < matchers.size(); i++) {
            JsonNode matcher = matchers.get(i);
            String matcherPath = path + "." + i;
            if (!matcher.isObject()) {
                err(errors, matcherPath, expectedMsg("object", matcher));
                continue;
            }
            checkString(matcher, matcherPath, "matcher", errors);
            JsonNode hookList = matcher.get("hooks");
            if (hookList == null || !hookList.isArray()) {
                err(errors, matcherPath + ".hooks", expectedMsg("array", hookList));
                continue;
            }
            for (int j = 0; j < hookList.size(); j++) {
                validateHookCommand(hookList.get(j), matcherPath + ".hooks." + j, errors);
            }
        }
    }


    private static void validateHookCommand(JsonNode hook, String path, List<FieldError> errors) {
        if (!hook.isObject()) {
            err(errors, path, expectedMsg("object", hook));
            return;
        }
        JsonNode type = hook.get("type");
        String discriminator = type != null && type.isTextual() ? type.asText() : null;
        switch (discriminator == null ? "" : discriminator) {
            case "command" -> {
                checkRequiredString(hook, path, "command", errors);
                checkCommonHookFields(hook, path, errors);
                JsonNode shell = hook.get("shell");
                if (present(shell) && (!shell.isTextual() || !SHELL_TYPES.contains(shell.asText()))) {
                    err(errors, path + ".shell", enumMsg(SHELL_TYPES));
                }
                checkBoolean(hook, path, "async", errors);
                checkBoolean(hook, path, "asyncRewake", errors);
            }
            case "prompt", "agent" -> {
                checkRequiredString(hook, path, "prompt", errors);
                checkCommonHookFields(hook, path, errors);
                checkString(hook, path, "model", errors);
            }
            case "http" -> {
                JsonNode url = hook.get("url");
                if (url == null || !url.isTextual()) {
                    err(errors, path + ".url", expectedMsg("string", url));
                } else if (!isValidUrl(url.asText())) {
                    // zod invalid_format keeps its own message through formatZodError.
                    err(errors, path + ".url", "Invalid URL");
                }
                checkCommonHookFields(hook, path, errors);
                JsonNode headers = hook.get("headers");
                if (present(headers)) {
                    if (!headers.isObject()) {
                        err(errors, path + ".headers", expectedMsg("record", headers));
                    } else {
                        Iterator<Map.Entry<String, JsonNode>> fields = headers.fields();
                        while (fields.hasNext()) {
                            Map.Entry<String, JsonNode> field = fields.next();
                            if (!field.getValue().isTextual()) {
                                err(errors, path + ".headers." + field.getKey(),
                                    expectedMsg("string", field.getValue()));
                            }
                        }
                    }
                }
                checkStringArray(hook, "allowedEnvVars", path + ".allowedEnvVars", errors);
            }
            // Unmatched (or missing) discriminator: zod invalid_union at the
            // discriminator path, default message.
            default -> err(errors, path + ".type", "Invalid input");
        }
    }

    /** {@code if?} / {@code timeout?} / {@code statusMessage?} / {@code once?} shared by all arms. */
    private static void checkCommonHookFields(JsonNode hook, String path, List<FieldError> errors) {
        checkString(hook, path, "if", errors);
        JsonNode timeout = hook.get("timeout");
        if (present(timeout)) {
            if (!timeout.isNumber()) {
                err(errors, path + ".timeout", expectedMsg("number", timeout));
            } else if (timeout.asDouble() <= 0) {
                err(errors, path + ".timeout", "Number must be greater than or equal to 0");
            }
        }
        checkString(hook, path, "statusMessage", errors);
        checkBoolean(hook, path, "once", errors);
    }
}
