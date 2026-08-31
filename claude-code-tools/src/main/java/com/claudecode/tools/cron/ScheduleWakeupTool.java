package com.claudecode.tools.cron;

import com.claudecode.core.engine.StructuredToolOutput;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.permissions.PermissionDecision;
import com.claudecode.permissions.ToolPermissionContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import com.claudecode.tools.loop.LoopWakeupManager;
import com.claudecode.tools.AnnotatedTool;
import com.claudecode.tools.BuiltInTool;
import com.claudecode.tools.ToolTexts;

/**
 * Dynamic /loop self-pacing tool from Claude Code.
 */
@BuiltInTool(
    name = "ScheduleWakeup",
    shouldDefer = true,
    maxResultSizeChars = 1_000
)
public final class ScheduleWakeupTool extends AnnotatedTool<JsonNode, StructuredToolOutput> {

    private static final String NOT_SCHEDULED =
        "Wakeup not scheduled. Either the /loop dynamic runtime gate is off or the loop reached its maximum duration — the loop has ended; do not re-issue.";

    private final LoopWakeupManager manager;

    public ScheduleWakeupTool() {
        this(LoopWakeupManager.global());
    }

    ScheduleWakeupTool(LoopWakeupManager manager) {
        this.manager = manager;
    }

    @Override
    public String description() {
        return ToolTexts.description("ScheduleWakeup");
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = mapper().createObjectNode();
        schema.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("delaySeconds")
            .put("description", "Seconds from now to wake up. Clamped to [60, 3600] by the runtime.")
            .put("type", "number");
        properties.putObject("reason")
            .put("description", "One short sentence explaining the chosen delay. Goes to telemetry and is shown to the user. Be specific.")
            .put("type", "string");
        properties.putObject("prompt")
            .put("description", "The /loop input to fire on wake-up. Pass the same /loop input verbatim each turn so the next firing re-enters the skill and continues the loop. For autonomous /loop (no user prompt), pass the literal sentinel `<<autonomous-loop-dynamic>>` instead (the dynamic-pacing variant, not the CronCreate-mode `<<autonomous-loop>>`).")
            .put("type", "string");
        schema.putArray("required").add("delaySeconds").add("reason").add("prompt");
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public StructuredToolOutput call(JsonNode input, ToolExecutionContext context) {
        double requested = input.path("delaySeconds").asDouble();
        String reason = input.path("reason").asText("");
        String prompt = input.path("prompt").asText("");
        LoopWakeupManager.ScheduleResult result = manager.schedule(requested, prompt, reason);
        if (result == null) {
            return new StructuredToolOutput(NOT_SCHEDULED, payload(0, 0, false));
        }

        String time = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US)
            .format(Instant.ofEpochMilli(result.scheduledFor()).atZone(manager.zoneId()));
        long remaining = Math.max(0,
            Math.round((result.scheduledFor() - manager.nowMillis()) / 1_000.0));
        String clamped = result.wasClamped()
            ? " (clamped to " + result.clampedDelaySeconds() + "s from your requested value)"
            : "";
        String text = "Next wakeup scheduled for " + time + " (in " + remaining + "s)"
            + clamped + ". Nothing more to do this turn — the harness re-invokes you when "
            + "the wakeup fires or a task-notification arrives.";
        return new StructuredToolOutput(text, payload(result.scheduledFor(),
            result.clampedDelaySeconds(), result.wasClamped()));
    }

    private static Map<String, Object> payload(long scheduledFor, int clampedDelaySeconds,
                                                boolean wasClamped) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("scheduledFor", scheduledFor);
        payload.put("clampedDelaySeconds", clampedDelaySeconds);
        payload.put("wasClamped", wasClamped);
        return payload;
    }

    @Override
    public PermissionDecision checkPermissions(JsonNode input, ToolPermissionContext permCtx) {
        return PermissionDecision.allow();
    }

}
