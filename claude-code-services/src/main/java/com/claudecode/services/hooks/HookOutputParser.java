package com.claudecode.services.hooks;

import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.core.text.StringUtils;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Locale;
import java.util.Optional;
import org.apache.commons.lang3.Strings;

/**
 * Stateless parser for generic and strict prompt/agent hook output.
 */
final class HookOutputParser {

    HookResult parse(String output) {
        return parse(output, null);
    }

    HookResult parse(String output, HookEvent expectedEvent) {
        try {
            ParsedJson parsed = readJsonWithPlainText(output);
            JsonNode node = parsed.node();
            if (node == null) {
                return new HookResult.Allow(output);
            }

            HookResult result = parseDecision(node, expectedEvent);
            if (result instanceof HookResult.Skip) return result;
            HookResult.Effects effects = parseEffects(node, parsed.plainText(), expectedEvent);
            return effects.isEmpty() ? result : new HookResult.Decorated(result, effects);
        } catch (Exception _) {
            return new HookResult.Allow(output);
        }
    }

    private static HookResult parseDecision(JsonNode node, HookEvent expectedEvent) {

        JsonNode continueNode = node.get("continue");
        if (continueNode != null && continueNode.isBoolean() && !continueNode.asBoolean()) {
            String stopReason = node.hasNonNull("stopReason")
                ? node.get("stopReason").asText() : null;
            return new HookResult.PreventContinuation(stopReason);
        }

        String decision = node.has("decision") ? node.get("decision").asText("") : "";
        String reason = node.has("reason") ? node.get("reason").asText("") : "";
        String context = extractAdditionalContext(node);
        JsonNode specific = node.get("hookSpecificOutput");
        if (specific != null && specific.isObject()
                && specific.path("hookEventName").isTextual()) {
            String actualEvent = specific.path("hookEventName").asText();
            if (expectedEvent == null) {
                return context != null ? new HookResult.Allow(context) : HookResult.allow();
            }
            if (!Strings.CS.equals(expectedEvent.displayName(), actualEvent)) {
                return HookResult.skip();
            }
            if (Strings.CS.equals("PreToolUse", actualEvent)
                    && Strings.CS.equals("deny",
                        specific.path("permissionDecision").asText())) {
                String denyReason = specific.path("permissionDecisionReason").asText(reason);
                return new HookResult.Block(
                    denyReason.isEmpty() ? "Blocked by hook" : denyReason);
            }
            return new HookResult.Structured(specific,
                Optional.ofNullable(context).filter(c -> !c.isEmpty()));
        }
        return switch (decision.toLowerCase(Locale.ROOT)) {
            case "block" -> new HookResult.Block(reason.isEmpty() ? "Blocked by hook" : reason);
            case "message" -> new HookResult.Message(reason);
            default -> context != null ? new HookResult.Allow(context) : HookResult.allow();
        };
    }

    private static HookResult.Effects parseEffects(
            JsonNode node, String plainText, HookEvent expectedEvent) {
        Optional<String> systemMessage = Optional.empty();
        Optional<String> terminalSequence = Optional.empty();
        Optional<String> successOutput = Optional.empty();
        boolean suppressOutput = false;
        String validationError = null;

        JsonNode system = node.get("systemMessage");
        if (system != null) {
            if (system.isTextual()) systemMessage = Optional.of(system.asText());
            else validationError = "systemMessage must be a string";
        }
        JsonNode suppress = node.get("suppressOutput");
        if (suppress != null) {
            if (suppress.isBoolean()) suppressOutput = suppress.asBoolean();
            else validationError = appendValidation(validationError,
                "suppressOutput must be a boolean");
        }
        JsonNode terminal = node.get("terminalSequence");
        if (terminal != null) {
            if (!terminal.isTextual()) {
                validationError = appendValidation(validationError,
                    "terminalSequence must be a string");
            } else {
                String rejection = terminalSequenceRejection(terminal.asText());
                if (rejection == null) terminalSequence = Optional.of(terminal.asText());
                else validationError = appendValidation(validationError,
                    "terminalSequence " + rejection);
            }
        }
        if (!suppressOutput && org.apache.commons.lang3.StringUtils.isNotBlank(plainText)) {
            successOutput = Optional.of(plainText.trim());
        }
        validationError = validateSpecificEffects(node.get("hookSpecificOutput"),
            expectedEvent, validationError);
        return new HookResult.Effects(
            systemMessage, terminalSequence, successOutput, suppressOutput, validationError);
    }

    private static String validateSpecificEffects(
            JsonNode specific, HookEvent expectedEvent, String validationError) {
        if (specific == null || !specific.isObject() || expectedEvent == null) {
            return validationError;
        }
        if (expectedEvent == HookEvent.SESSION_START) {
            JsonNode title = specific.get("sessionTitle");
            if (title != null && !title.isTextual()) {
                validationError = appendValidation(validationError,
                    "sessionTitle must be a string");
            }
            JsonNode reload = specific.get("reloadSkills");
            if (reload != null && !reload.isBoolean()) {
                validationError = appendValidation(validationError,
                    "reloadSkills must be a boolean");
            }
        }
        if (expectedEvent == HookEvent.SESSION_START
                || expectedEvent == HookEvent.CWD_CHANGED
                || expectedEvent == HookEvent.FILE_CHANGED) {
            JsonNode paths = specific.get("watchPaths");
            if (paths != null && !paths.isArray()) {
                validationError = appendValidation(validationError,
                    "watchPaths must be an array");
            } else if (paths != null) {
                for (JsonNode path : paths) {
                    if (!path.isTextual()) {
                        validationError = appendValidation(validationError,
                            "watchPaths entries must be strings");
                        break;
                    }
                }
            }
        }
        return validationError;
    }

    private static String appendValidation(String existing, String next) {
        return existing == null ? next : existing + "; " + next;
    }


    static String terminalSequenceRejection(String sequence) {
        if (org.apache.commons.lang3.StringUtils.isEmpty(sequence)) return "is empty";
        int offset = 0;
        while (offset < sequence.length()) {
            char current = sequence.charAt(offset);
            if (current == '\u0007') {
                offset++;
                continue;
            }
            if (current != '\u001b' || offset + 2 >= sequence.length()
                    || sequence.charAt(offset + 1) != ']') {
                return "contains non-allowlisted text or control characters";
            }
            int bodyStart = offset + 2;
            int end = -1;
            int terminatorLength = 0;
            for (int i = bodyStart; i < sequence.length(); i++) {
                char ch = sequence.charAt(i);
                if (ch == '\u0007') {
                    end = i;
                    terminatorLength = 1;
                    break;
                }
                if (ch == '\u001b' && i + 1 < sequence.length()
                        && sequence.charAt(i + 1) == '\\') {
                    end = i;
                    terminatorLength = 2;
                    break;
                }
                if (Character.isISOControl(ch)) {
                    return "contains a nested or unsafe control character";
                }
            }
            if (end < 0) return "is incomplete";
            String body = sequence.substring(bodyStart, end);
            int separator = body.indexOf(';');
            String code = separator < 0 ? body : body.substring(0, separator);
            String payload = separator < 0 ? "" : body.substring(separator + 1);
            if (!Strings.CS.equalsAny(code, "0", "1", "2", "9", "99", "777")) {
                return "uses non-allowlisted OSC " + code;
            }
            if (Strings.CS.equals(code, "9") && !payload.isEmpty()
                    && Character.isDigit(payload.charAt(0))
                    && !payload.matches("4(?:;[0-4](?:;(?:100|[0-9]{1,2}))?)?")) {
                return "uses an unsafe numeric OSC 9 body";
            }
            offset = end + terminatorLength;
        }
        return null;
    }

    PromptDecision parsePromptDecision(String output) {
        JsonNode node = readJson(output);
        if (node == null) {
            return PromptDecision.invalid("JSON validation failed");
        }
        String schemaFailure = promptSchemaFailure(node);
        if (schemaFailure != null) {
            return PromptDecision.invalid("Schema validation failed: " + schemaFailure);
        }
        if (!node.path("ok").asBoolean()) {
            String reason = node.has("reason") && node.get("reason").isTextual()
                ? node.get("reason").asText() : "Prompt hook condition was not met";
            return new PromptDecision(true, false, reason, null);
        }
        return new PromptDecision(true, true, null, null);
    }


    JsonNode readJson(String output) {
        return readJsonWithPlainText(output).node();
    }

    private ParsedJson readJsonWithPlainText(String output) {
        String normalized = stripMarkdownFence(output);
        try {
            return new ParsedJson(readOneJsonValue(normalized), "");
        } catch (Exception _) {
            // Full output can include normal trailing command output.
        }
        String firstLine = StringUtils.firstLineOf(normalized);
        try {
            JsonNode node = readOneJsonValue(firstLine);
            String remainder = normalized.length() > firstLine.length()
                ? normalized.substring(firstLine.length()).stripLeading() : "";
            return new ParsedJson(node, remainder);
        } catch (Exception _) {
            return new ParsedJson(null, "");
        }
    }

    private record ParsedJson(JsonNode node, String plainText) { }

    private static JsonNode readOneJsonValue(String value) throws Exception {
        try (JsonParser parser = JsonUtils.getMapper().createParser(value)) {
            JsonNode node = JsonUtils.getMapper().readTree(parser);
            if (parser.nextToken() != null) {
                throw new IllegalArgumentException("multiple JSON values");
            }
            return node;
        }
    }

    private static String stripMarkdownFence(String output) {
        if (output == null) return null;
        return output.trim()
            .replaceFirst("^```[a-zA-Z]*\\s*", "")
            .replaceFirst("\\s*```$", "")
            .trim();
    }

    private static String promptSchemaFailure(JsonNode node) {
        if (!node.isObject()) return "expected an object";
        if (!node.has("ok") || !node.get("ok").isBoolean()) return "ok must be a boolean";
        if (node.has("reason") && !node.get("reason").isTextual()) {
            return "reason must be a string";
        }
        var fields = node.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            if (!Strings.CS.equals(field, "ok") && !Strings.CS.equals(field, "reason")) {
                return "unexpected field: " + field;
            }
        }
        return null;
    }

    private static String extractAdditionalContext(JsonNode node) {
        JsonNode specific = node.path("hookSpecificOutput").path("additionalContext");
        if (specific.isTextual() && !specific.asText().isEmpty()) {
            return specific.asText();
        }
        JsonNode flat = node.path("additionalContext");
        return flat.isTextual() ? flat.asText() : null;
    }

    record PromptDecision(boolean valid, boolean allowed, String reason, String failure) {
        private static PromptDecision invalid(String failure) {
            return new PromptDecision(false, false, null, failure);
        }
    }
}
