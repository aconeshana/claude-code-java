package com.claudecode.services.hooks;

import com.claudecode.core.engine.HookDispatcher;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

/**
 * Pure aggregation of per-hook results into the {@link HookDispatcher.HookOutcome}
 * contract consumed by the query loop.
 *
 * <ul>
 *   <li>{@code src/utils/hooks.ts} — outcome aggregation across parallel hook
 *       results (blocking errors, additional context, prevent-continuation,
 *       hook-specific output).</li>
 *   <li>{@code src/utils/hooks.ts} — PreCompact / PostCompact success-message
 *       shaping from individual hook stdout.</li>
 *   <li>{@code src/utils/hooks/hookHelpers.ts} — hook display-name derivation
 *       ({@code hookEvent:matchQuery}).</li>
 * </ul>
 */
final class HookOutcomes {

    private HookOutcomes() {}

    static HookDispatcher.HookOutcome aggregate(List<HookResult> results) {
        List<String> blocking = new ArrayList<>();
        List<String> contexts = new ArrayList<>();
        boolean preventContinuation = false;
        String stopReason = null;
        List<HookDispatcher.HookSpecificOutput> specificOutputs = new ArrayList<>();
        for (HookResult r : results) {
            r = baseResult(r);
            if (r instanceof HookResult.Block block) {
                blocking.add(block.reason());
            } else if (r instanceof HookResult.ConditionNotMet(String condition, String reason)) {
                blocking.add("[" + condition + "]: " + reason);
            } else if (r instanceof HookResult.Allow(Optional<String> additionalContext)
                && additionalContext.isPresent()) {
                String c = additionalContext.get();
                if (!StringUtils.isBlank(c)) {
                    contexts.add(c);
                }
            } else if (r instanceof HookResult.PreventContinuation(Optional<String> reason)) {
                preventContinuation = true;
                if (reason.isPresent()) {
                    stopReason = reason.get();
                }
            } else if (r instanceof HookResult.Structured(JsonNode output,
                    Optional<String> additionalContext)) {
                if (output != null && output.path("hookEventName").isTextual()) {
                    specificOutputs.add(new HookDispatcher.HookSpecificOutput(
                        output.path("hookEventName").asText(), output));
                }
                additionalContext.filter(c -> !StringUtils.isBlank(c))
                    .ifPresent(contexts::add);
            }
        }
        return new HookDispatcher.HookOutcome(
            blocking.isEmpty() && !preventContinuation,
            contexts.isEmpty() ? null : String.join("\n", contexts),
            List.copyOf(blocking),
            preventContinuation,
            stopReason,
            null,
            List.copyOf(contexts),
            List.copyOf(specificOutputs));
    }

    static HookDispatcher.HookOutcome aggregateCompact(
            String eventName, List<HookExecution> executions, String additionalSeparator) {
        StringBuilder additional = new StringBuilder();
        List<String> display = new ArrayList<>();
        for (HookExecution execution : executions) {
            HookResult result = baseResult(execution.result());
            String command = commandText(execution.command());
            boolean succeeded = result instanceof HookResult.Allow
                || result instanceof HookResult.Message
                || execution.backgrounded();
            String output = execution.backgrounded()
                    && execution.initialOutput() != null
                    && !StringUtils.isBlank(execution.initialOutput())
                ? execution.initialOutput().trim()
                : compactHookOutput(result, command);
            if (succeeded && !StringUtils.isBlank(output)) {
                if (!additional.isEmpty()) additional.append(additionalSeparator);
                additional.append(output);
            }
            String status = eventName + " [" + command + "] "
                + (succeeded ? "completed successfully" : "failed");
            display.add(StringUtils.isBlank(output) ? status : status + ": " + output);
        }
        return new HookDispatcher.HookOutcome(
            true,
            additional.isEmpty() ? null : additional.toString(),
            List.of(), false, null,
            display.isEmpty() ? null : String.join("\n", display));
    }

    static String commandText(HookCommand command) {
        if (command == null) return "unknown";
        return HooksConfigManager.getRawHookContent(command);
    }

    private static String compactHookOutput(HookResult result, String command) {
        result = baseResult(result);
        return switch (result) {
            case HookResult.Allow allow -> allow.additionalContext().orElse("").trim();
            case HookResult.Message message -> message.content() != null ? message.content().trim() : "";
            case HookResult.Block block -> stripCommandPrefix(block.reason(), command);
            case HookResult.PreventContinuation stopped -> stopped.stopReason().orElse("").trim();
            case HookResult.Structured structured ->
                structured.additionalContext().orElse("").trim();
            case HookResult.Decorated _ -> throw new IllegalStateException(
                "decorated hook result must be unwrapped");
            case HookResult.Skip _ -> "";
            case HookResult.ConditionMet met -> met.reason() != null ? met.reason().trim() : "";
            case HookResult.ConditionNotMet notMet -> notMet.reason() != null ? notMet.reason().trim() : "";
            case HookResult.ConditionImpossible impossible ->
                impossible.reason() != null ? impossible.reason().trim() : "";
        };
    }

    private static String stripCommandPrefix(String output, String command) {
        if (output == null) return "";
        String trimmed = output.trim();
        String prefix = "[" + command + "]: ";
        return Strings.CS.startsWith(trimmed, prefix) ? trimmed.substring(prefix.length()).trim() : trimmed;
    }

    static HookResult baseResult(HookResult result) {
        return result instanceof HookResult.Decorated decorated
            ? decorated.result() : result;
    }

    /**
     * Hook identifier used in blocking-error messages, matching {@code hookName =
     * matchQuery ? `${hookEvent}:${matchQuery}` : hookEvent}.
     */
    static String hookEventName(HookInput input) {
        String base = input.event().displayName();
        return input.toolName().map(t -> base + ":" + t).orElse(base);
    }
}
