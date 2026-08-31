package com.claudecode.tools.cron;

import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.core.engine.ToolResult;
import com.claudecode.tools.tasks.teammate.TeammateContext;
import com.claudecode.tools.tasks.teammate.TeammateContextHolder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.function.BooleanSupplier;
import org.apache.commons.lang3.Strings;
import com.claudecode.tools.AnnotatedTool;
import com.claudecode.tools.BuiltInTool;
import com.claudecode.tools.ToolTexts;
import com.claudecode.tools.ValidationResult;

/**
 * CronCreate — schedule a recurring or one-shot cron prompt.
 *
 * <ul>
 *   <li>schema
 *       ({@code cron}, {@code prompt}, {@code recurring}, {@code durable}),
 *       validateInput (5-field expr, nextRun, max 50 jobs, no-durable-teammate),
 *       call (addCronTask, setScheduledTasksEnabled), mapToolResultToToolResultBlockParam</li>
 *   <li>{@code addCronTask}, store model</li>
 *   <li>{@code parseCronExpression}, {@code cronToHuman}</li>
 *   <li>runtime cron/durable
 *       gates and both branches of the model-facing prompt.</li>
 * </ul>
 *
 * The runtime fire-while-idle loop is provided by {@link CronScheduler} and is
 * wired by the interactive REPL composition root.
 */
@BuiltInTool(
    name = "CronCreate",
    shouldDefer = true
)
public class CronCreateTool extends AnnotatedTool<JsonNode, String> {

    private final BooleanSupplier cronEnabled;
    private final BooleanSupplier durableEnabled;

    public CronCreateTool() {
        this(() -> CronFeatureGate.system().cronEnabled(),
            () -> CronFeatureGate.system().durableEnabled());
    }

    CronCreateTool(BooleanSupplier cronEnabled, BooleanSupplier durableEnabled) {
        this.cronEnabled = cronEnabled;
        this.durableEnabled = durableEnabled;
    }

    @Override
    public String description() {
        return ToolTexts.description("CronCreate",
            durableEnabled.getAsBoolean() ? "durable" : "session-only");
    }


    @Override
    public String prompt(ToolExecutionContext context) {
        boolean durable = durableEnabled.getAsBoolean();
        return ToolTexts.prompt("CronCreate",
            durable ? "durable" : "session-only");
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = createObjectSchema();
        ObjectNode props = (ObjectNode) schema.get("properties");

        ObjectNode cronProp = mapper().createObjectNode();
        cronProp.put("type", "string");
        cronProp.put("description",
            "Standard 5-field cron expression in local time: \"M H DoM Mon DoW\" "
            + "(e.g. \"*/5 * * * *\" = every 5 minutes, \"30 14 28 2 *\" = Feb 28 at 2:30pm local once).");
        props.set("cron", cronProp);

        ObjectNode promptProp = mapper().createObjectNode();
        promptProp.put("type", "string");
        promptProp.put("description", "The prompt to enqueue at each fire time.");
        props.set("prompt", promptProp);

        ObjectNode recurProp = mapper().createObjectNode();
        recurProp.put("type", "boolean");
        recurProp.put("description",
            "true (default) = fire on every cron match until deleted or auto-expired after "
            + CronStore.DEFAULT_MAX_AGE_DAYS + " days. false = fire once at the next match, "
            + "then auto-delete. Use false for \"remind me at X\" one-shot requests with "
            + "pinned minute/hour/dom/month.");
        props.set("recurring", recurProp);

        ObjectNode durProp = mapper().createObjectNode();
        durProp.put("type", "boolean");
        durProp.put("description",
            "true = persist to .claude/scheduled_tasks.json and survive restarts. "
            + "false (default) = in-memory only, dies when this Claude session ends. "
            + "Use true only when the user asks the task to survive across sessions.");
        props.set("durable", durProp);

        schema.set("required", mapper().createArrayNode().add("cron").add("prompt"));


// keys. createObjectSchema is permissive; set strict.
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public ValidationResult validateInput(JsonNode input, ToolExecutionContext context) {

        // expression / next-run / MAX_JOBS checks are returned as is_error
        // (errorCodes 1/2/3) via ValidationResult.invalid, NOT as a plain-text
// "Error: …" from call (which would look like a successful result).
        String cron = input.path("cron").asText("");
        if (!CronUtils.isValid(cron)) {
            return ValidationResult.invalid(
                "Invalid cron expression '" + cron + "'. Expected 5 fields: M H DoM Mon DoW.");
        }
        if (CronUtils.nextRunMs(cron) == null) {
            return ValidationResult.invalid(
                "Cron expression '" + cron + "' does not match any calendar date in the next year.");
        }
        if (CronStore.list().size() >= CronStore.MAX_JOBS) {
            return ValidationResult.invalid(
                "Too many scheduled jobs (max " + CronStore.MAX_JOBS + "). Cancel one first.");
        }
        if (input.path("durable").asBoolean(false) && TeammateContextHolder.get() != null) {
            return ValidationResult.invalid(
                "durable crons are not supported for teammates "
                    + "(teammates do not persist across sessions)");
        }
        return ValidationResult.valid();
    }

    @Override
    public String call(JsonNode input, ToolExecutionContext context) {

        // its own whitespace normalization for validation, but the persisted
        // cron/prompt values are not silently trimmed.
        String cron     = input.path("cron").asText("");
        String prompt   = input.path("prompt").asText("");
        boolean recurring = !input.has("recurring") || input.get("recurring").asBoolean(true);
        boolean durable = input.has("durable") && input.get("durable").asBoolean(false)
            && durableEnabled.getAsBoolean();

        // Validation (cron expression / next-run / MAX_JOBS) now lives in



        TeammateContext teammate = TeammateContextHolder.get();
        String id = CronStore.add(cron, prompt, recurring, durable,
            teammate != null ? teammate.agentId() : null,
            context == null ? null : context.sessionId());
        String humanSchedule = CronUtils.toHuman(cron);


        String where = durable
            ? "Persisted to .claude/scheduled_tasks.json"
            : "Session-only (not written to disk, dies when Claude exits)";
        if (recurring) {
            return "Scheduled recurring job " + id + " (" + humanSchedule + "). "
                + where + ". Auto-expires after " + CronStore.DEFAULT_MAX_AGE_DAYS
                + " days. Use CronDelete to cancel sooner.";
        } else {
            return "Scheduled one-shot task " + id + " (" + humanSchedule + "). "
                + where + ". It will fire once then auto-delete.";
        }
    }


    @Override public boolean isEnabled() { return cronEnabled.getAsBoolean(); }


    @Override public String searchHint() { return "schedule a recurring or one-shot prompt"; }

    @Override public Object toAutoClassifierInput(JsonNode input) {
        if (input == null) return "";
        return input.path("cron").asText("") + ": " + input.path("prompt").asText("");
    }


    @Override
    public ToolResult mapResult(Object rawResult, JsonNode input, ToolExecutionContext context) {
        if (!(rawResult instanceof String output)) return null;
        String[] words = output.split("\\s+");
        if (words.length < 4 || !(Strings.CS.startsWith(output, "Scheduled recurring job")
                ||Strings.CS.startsWith( output, "Scheduled one-shot task"))) return null;
        String id = words[3];
        CronStore.CronJob job = CronStore.list().stream()
            .filter(candidate ->Strings.CS.equals( candidate.id(), id)).findFirst().orElse(null);
        if (job == null) return null;
        ObjectNode data = mapper().createObjectNode();
        data.put("id", job.id());
        data.put("humanSchedule", CronUtils.toHuman(job.cron()));
        data.put("recurring", job.recurring());
        data.put("durable", job.durable());
        return ToolResult.success(output).withToolUseResult(data);
    }
}
