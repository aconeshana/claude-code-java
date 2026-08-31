package com.claudecode.tools.cron;

import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.core.engine.ToolResult;
import com.claudecode.tools.tasks.teammate.TeammateContext;
import com.claudecode.tools.tasks.teammate.TeammateContextHolder;
import com.claudecode.core.text.FormatUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import com.claudecode.tools.AnnotatedTool;
import com.claudecode.tools.BuiltInTool;
import com.claudecode.tools.ToolTexts;

/**
 * CronList — list all active scheduled cron jobs.
 *
 * <ul>
 *   <li>schema ({}),
 *       call (listAllCronTasks → filter by agentId), mapToolResultToToolResultBlockParam</li>
 *   <li>{@code listAllCronTasks}</li>
 *   <li>{@code cronToHuman}</li>
 *   <li>{@code truncate(prompt, 80, true)}</li>
 *   <li>cron/durable gates and
 *       the durable/session-only prompt branches.</li>
 * </ul>
 */
@BuiltInTool(
    name = "CronList",
    shouldDefer = true,
    readOnly = true,
    concurrencySafe = true
)
public class CronListTool extends AnnotatedTool<JsonNode, String> {

    private final BooleanSupplier cronEnabled;
    private final BooleanSupplier durableEnabled;

    public CronListTool() {
        this(() -> CronFeatureGate.system().cronEnabled(),
            () -> CronFeatureGate.system().durableEnabled());
    }

    CronListTool(BooleanSupplier cronEnabled, BooleanSupplier durableEnabled) {
        this.cronEnabled = cronEnabled;
        this.durableEnabled = durableEnabled;
    }

    @Override
    public String description() {
        return ToolTexts.description("CronList");
    }

    @Override
    public JsonNode inputSchema() {

        ObjectNode schema = createObjectSchema();
        // z.strictObject({}) rejects any (even unknown) keys — set strict.
        schema.put("additionalProperties", false);
        return schema;
    }




    @Override public boolean isEnabled() { return cronEnabled.getAsBoolean(); }


    @Override public String searchHint() { return "list active cron jobs"; }

    @Override public String prompt(ToolExecutionContext context) {
        return ToolTexts.prompt("CronList",
            durableEnabled.getAsBoolean() ? "durable" : "session-only");
    }


    @Override
    public ToolResult mapResult(Object rawResult, JsonNode input, ToolExecutionContext context) {
        if (!(rawResult instanceof String)) return null;
        List<CronStore.CronJob> jobs = CronStore.list();
        TeammateContext teammate = TeammateContextHolder.get();
        if (teammate != null) {
            jobs = jobs.stream().filter(job -> Objects.equals(job.agentId(), teammate.agentId())).toList();
        }
        ArrayNode array = mapper().createArrayNode();
        for (CronStore.CronJob job : jobs) {
            ObjectNode item = array.addObject();
            item.put("id", job.id());
            item.put("cron", job.cron());
            item.put("humanSchedule", CronUtils.toHuman(job.cron()));
            item.put("prompt", job.prompt());
            if (job.recurring()) item.put("recurring", true);
            if (!job.durable()) item.put("durable", false);
        }
        ObjectNode data = mapper().createObjectNode();
        data.set("jobs", array);
        return ToolResult.success((String) rawResult).withToolUseResult(data);
    }

    @Override
    public String call(JsonNode input, ToolExecutionContext context) {
        List<CronStore.CronJob> jobs = CronStore.list();
        TeammateContext teammate = TeammateContextHolder.get();
        if (teammate != null) {
            jobs = jobs.stream()
                .filter(job -> Objects.equals(job.agentId(), teammate.agentId()))
                .toList();
        }
        if (jobs.isEmpty()) return "No scheduled jobs.";


        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < jobs.size(); i++) {
            CronStore.CronJob j = jobs.get(i);
            if (i > 0) sb.append('\n');
            sb.append(j.id());
            sb.append(" — ");
            sb.append(CronUtils.toHuman(j.cron()));
            sb.append(j.recurring() ? " (recurring)" : " (one-shot)");
            if (!j.durable()) sb.append(" [session-only]");
            sb.append(": ");
            sb.append(FormatUtils.truncateSingleLine(j.prompt(), 80));
        }
        return sb.toString();
    }

}
