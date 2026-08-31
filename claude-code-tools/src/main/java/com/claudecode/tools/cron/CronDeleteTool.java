package com.claudecode.tools.cron;

import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.core.engine.ToolResult;
import com.claudecode.tools.tasks.teammate.TeammateContext;
import com.claudecode.tools.tasks.teammate.TeammateContextHolder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import com.claudecode.tools.AnnotatedTool;
import com.claudecode.tools.BuiltInTool;
import com.claudecode.tools.ToolTexts;
import com.claudecode.tools.ValidationResult;

/**
 * CronDelete — cancel a scheduled cron job by ID.
 *
 * <ul>
 *   <li>schema ({@code id}),
 *       validateInput (must exist), call (removeCronTasks), result message</li>
 *   <li>{@code removeCronTasks}</li>
 *   <li>cron/durable gates and
 *       the durable/session-only prompt branches.</li>
 * </ul>
 */
@BuiltInTool(
    name = "CronDelete",
    shouldDefer = true
)
public class CronDeleteTool extends AnnotatedTool<JsonNode, String> {

    private final BooleanSupplier cronEnabled;
    private final BooleanSupplier durableEnabled;

    public CronDeleteTool() {
        this(() -> CronFeatureGate.system().cronEnabled(),
            () -> CronFeatureGate.system().durableEnabled());
    }

    CronDeleteTool(BooleanSupplier cronEnabled, BooleanSupplier durableEnabled) {
        this.cronEnabled = cronEnabled;
        this.durableEnabled = durableEnabled;
    }

    @Override
    public String description() {
        return ToolTexts.description("CronDelete");
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = createObjectSchema();
        ObjectNode props  = (ObjectNode) schema.get("properties");

        ObjectNode idProp = mapper().createObjectNode();
        idProp.put("type", "string");
        idProp.put("description", "Job ID returned by CronCreate.");
        props.set("id", idProp);

        schema.set("required", mapper().createArrayNode().add("id"));


// keys. createObjectSchema is permissive; set strict.
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public ValidationResult validateInput(JsonNode input, ToolExecutionContext context) {

        // is_error (errorCode 1), not a plain-text "Error: …" returned from
// call. Teammates may delete only jobs carrying their own runtime
        // agentId; the leader (no teammate context) may delete any job.
        String id = input.path("id").asText("");
        if (id.isEmpty()) {
            return ValidationResult.invalid("id is required.");
        }
        CronStore.CronJob job = CronStore.list().stream()
            .filter(j -> j.id().equals(id))
            .findFirst().orElse(null);
        if (job == null) {
            return ValidationResult.invalid("No scheduled job with id '" + id + "'");
        }
        TeammateContext teammate = TeammateContextHolder.get();
        if (teammate != null && !Objects.equals(job.agentId(), teammate.agentId())) {
            return ValidationResult.invalid(
                "Cannot delete cron job '" + id + "': owned by another agent");
        }
        return ValidationResult.valid();
    }

    @Override
    public String call(JsonNode input, ToolExecutionContext context) {
        String id = input.path("id").asText("");
        // Existence is guaranteed by validateInput; remove is a no-op otherwise.
        CronStore.remove(id);

        return "Cancelled job " + id + ".";
    }


    @Override public boolean isEnabled() { return cronEnabled.getAsBoolean(); }


    @Override public String searchHint() { return "cancel a scheduled cron job"; }

    @Override public Object toAutoClassifierInput(JsonNode input) {
        return input == null ? "" : input.path("id").asText("");
    }

    @Override public String prompt(ToolExecutionContext context) {
        return ToolTexts.prompt("CronDelete",
            durableEnabled.getAsBoolean() ? "durable" : "session-only");
    }


    @Override
    public ToolResult mapResult(Object rawResult, JsonNode input, ToolExecutionContext context) {
        if (!(rawResult instanceof String output) || input == null) return null;
        ObjectNode data = mapper().createObjectNode();
        data.put("id", input.path("id").asText(""));
        return ToolResult.success(output).withToolUseResult(data);
    }
}
