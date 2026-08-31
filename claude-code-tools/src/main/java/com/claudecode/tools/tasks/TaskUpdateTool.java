package com.claudecode.tools.tasks;

import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.core.engine.ToolResult;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.permissions.PermissionDecision;
import com.claudecode.permissions.ToolPermissionContext;
import com.claudecode.tools.AnnotatedTool;
import com.claudecode.tools.BuiltInTool;
import com.claudecode.tools.ToolCallResult;
import com.claudecode.tools.ToolTexts;
import com.claudecode.tools.tasks.teammate.AgentTeamsEnabled;
import com.claudecode.tools.tasks.teammate.Mail;
import com.claudecode.tools.tasks.teammate.MailTypes;
import com.claudecode.tools.tasks.teammate.TeammateContext;
import com.claudecode.tools.tasks.teammate.TeammateContextHolder;
import com.claudecode.tools.tasks.teammate.TeammateMailbox;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

/**
 * TaskUpdate — patches a task in the model-facing to-do list ({@link
 * TodoStore}): field edits, status transitions (including the {@code
 * deleted} pseudo-status that removes the task outright), and block/
 * blockedBy relationship additions.
 *
 * <ul>
 *   <li>input schema
 *       (only {@code taskId} required; {@code status} accepts the three
 *       resting states plus the {@code deleted} action through a schema
 *       {@code anyOf}; shared input validation rejects other values before
 *       execution, while the call path retains a defensive check), the
 *       {@code call} flow's exact branch order
 *       (missing task → early fail; per-field diff-and-collect into {@code
 *       updatedFields}; {@code metadata} merge with null-deletes-key
 *       semantics, pushed unconditionally once provided; {@code status ==
 *       'deleted'} short-circuits before any other field is persisted;
 *       {@code TaskCompleted} hook run — and, if it blocks, the entire
 *       update (including unrelated field edits already computed) is
 *       discarded — before a completed-transition is persisted; a single
 *       persist call gated on whether anything actually changed; {@code
 *       addBlocks}/{@code addBlockedBy} applied against the pre-update task's
 *       relationship lists, {@code addBlockedBy} reversing the block
 *       direction), and the
 *       {@code mapToolResultToToolResultBlockParam} result text (failure →
 *       raw error string, success → {@code "Updated task #<id> <fields>"}).
 *       Java applies the same {@code isTodoV2Enabled} catalogue gate. The
 *       auto-owner-assignment-on-in_progress
 *       branch, teammate mailbox notification on ownership change, and the
 *       "completed → call TaskList" / structural-verification-nudge result
 *       suffixes — all gated behind {@code isAgentSwarmsEnabled} /
 *       GrowthBook flags that are permanently off in a non-swarm, x-api-key
 *       Java build.</li>
 * </ul>
 */
@BuiltInTool(
    name = "TaskUpdate",
    shouldDefer = true,
    concurrencySafe = true
)
public class TaskUpdateTool extends AnnotatedTool<JsonNode, String> {


    @Override
    public String searchHint() {
        return "update a task";
    }

    private static final JsonNode SCHEMA = buildSchema();

    private final TaskBoardService taskBoard;
    private record UpdateOutcome(boolean success, String taskId, List<String> updatedFields,
                                 String error, String fromStatus, String toStatus) {}
    private static final JsonNode OUTPUT_SCHEMA = TaskToolOutputSchemas.taskUpdate();

    public TaskUpdateTool(TodoStore todoStore) {
        this(new TaskBoardService(todoStore, () -> null));
    }

    TaskUpdateTool(TaskBoardService taskBoard) {
        this.taskBoard = taskBoard;
    }

    @Override
    public String description() {

        return ToolTexts.description("TaskUpdate");
    }

    @Override
    public String prompt(ToolExecutionContext context) {
        return ToolTexts.prompt("TaskUpdate");
    }

    @Override
    public JsonNode inputSchema() { return SCHEMA; }

    @Override
    public JsonNode outputSchema() { return OUTPUT_SCHEMA; }


    @Override
    public Object toAutoClassifierInput(JsonNode input) {
        if (input == null) return "";
        ArrayList<String> parts = new ArrayList<>();
        String id = input.path("taskId").asText("");
        if (!input.has("taskId")) {
            for (String alias : List.of("id", "task_id")) {
                JsonNode value = input.get(alias);
                if (value != null && value.isTextual()
                        && isJavaScriptNonBlank(value.textValue())) {
                    id = value.textValue();
                    break;
                }
            }
        }
        parts.add(id);
        String status = input.path("status").asText("");
        if (!status.isEmpty()) parts.add(status);
        String subject = input.path("subject").asText("");
        if (!subject.isEmpty()) parts.add(subject);
        return String.join(" ", parts);
    }

    private static boolean isJavaScriptNonBlank(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && isJavaScriptWhitespace(value.charAt(start))) start++;
        while (end > start && isJavaScriptWhitespace(value.charAt(end - 1))) end--;
        return start < end;
    }

    private static boolean isJavaScriptWhitespace(char value) {
        return switch (value) {
            case 0x0009, 0x000A, 0x000B, 0x000C, 0x000D, 0x0020,
                 0x00A0, 0x1680, 0x2028, 0x2029, 0x202F, 0x205F,
                 0x3000, 0xFEFF -> true;
            default -> value >= 0x2000 && value <= 0x200A;
        };
    }

    @Override
    public String call(JsonNode input, ToolExecutionContext context) {
        return callWithResult(input, context).rawResult();
    }

    @Override
    public ToolCallResult<String> callWithResult(JsonNode input, ToolExecutionContext context) {
        UpdateCall call = invoke(input, context);
        return new ToolCallResult<>(call.text(), mapOutcome(call.text(), call.outcome(), context));
    }

    private record UpdateCall(String text, UpdateOutcome outcome) {}

    private UpdateCall invoke(JsonNode input, ToolExecutionContext context) {
        String taskId = input.get("taskId").asText();
        String sessionId = context != null ? context.sessionId() : null;
        taskBoard.publishExpand(sessionId);

        TodoStore activeStore = taskBoard.resolveStore(sessionId);
        activeStore.reload();
        Optional<Task> existingOpt = activeStore.get(taskId);
        if (existingOpt.isEmpty()) {
            UpdateOutcome outcome = new UpdateOutcome(false, taskId, List.of(), "Task not found",
                null, null);
            return new UpdateCall("Task not found", outcome);
        }
        Task existing = existingOpt.get();

        List<String> updatedFields = new ArrayList<>();
        Task next = existing;
        String fromStatus = null;
        String toStatus = null;

        if (input.hasNonNull("subject")) {
            String subject = input.get("subject").asText();
            if (!subject.equals(existing.subject())) {
                next = next.withSubject(subject);
                updatedFields.add("subject");
            }
        }
        if (input.hasNonNull("description")) {
            String description = input.get("description").asText();
            if (!description.equals(existing.description())) {
                next = next.withDescription(description);
                updatedFields.add("description");
            }
        }
        if (input.hasNonNull("activeForm")) {
            String activeForm = input.get("activeForm").asText();
            if (!Objects.equals(activeForm, existing.activeForm().orElse(null))) {
                next = next.withActiveForm(activeForm);
                updatedFields.add("activeForm");
            }
        }
        if (input.hasNonNull("owner")) {
            String owner = input.get("owner").asText();
            if (!Objects.equals(owner, existing.owner().orElse(null))) {
                next = next.withOwner(owner);
                updatedFields.add("owner");
            }
        }


        // in_progress.  Preserve the same gate and do not overwrite an
        // explicit owner supplied by the caller.
        if (AgentTeamsEnabled.isEnabled()
                && input.hasNonNull("status")
                &&Strings.CS.equals( "in_progress", input.get("status").asText())
                && !input.has("owner")
                && existing.owner().orElse("").isEmpty()) {
            String currentName = currentTeammateName();
            if (!StringUtils.isBlank(currentName)) {
                next = next.withOwner(currentName);
                updatedFields.add("owner");
            }
        }
        if (input.has("metadata") && input.get("metadata").isObject()) {
            next = next.withMetadata(mergeMetadata(existing.metadata(), input.get("metadata")));
            updatedFields.add("metadata");
        }

        if (input.hasNonNull("status")) {
            String statusStr = input.get("status").asText();
            if (Strings.CS.equals("deleted", statusStr)) {
                boolean deleted = activeStore.delete(taskId);
                if (!deleted) {
                    UpdateOutcome outcome = new UpdateOutcome(false, taskId, List.of(),
                        "Failed to delete task", null, null);
                    return new UpdateCall("Failed to delete task", outcome);
                }
                taskBoard.publishChanged(sessionId);
                UpdateOutcome outcome = new UpdateOutcome(true, taskId, List.of("deleted"), null,
                    existing.status().wireValue(), "deleted");
                return new UpdateCall("Updated task #" + taskId + " deleted", outcome);
            }

            TodoStatus newStatus = TodoStatusWire.fromWire(statusStr);
            if (newStatus == null) {
                String error = "Error: invalid status '" + statusStr
                    + "'; expected one of pending, in_progress, completed, deleted";
                UpdateOutcome outcome = new UpdateOutcome(false, taskId, List.of(), error,
                    existing.status().wireValue(), null);
                return new UpdateCall(error, outcome);
            }
            if (newStatus != existing.status()) {
                if (newStatus == TodoStatus.COMPLETED) {
                    TaskLifecycleHooks hooks = TodoStore.getTaskLifecycleHooks();
                    if (hooks != null && hooks.hasTaskCompletedHook()) {
                        List<String> blockingErrors = hooks.dispatchTaskCompleted(
                            taskId, existing.subject(), existing.description(),
                            taskBoard.currentTeammateName(), taskBoard.currentTeamName(sessionId));
                        if (!blockingErrors.isEmpty()) {
                            String error = String.join("\n", blockingErrors);
                            UpdateOutcome outcome = new UpdateOutcome(false, taskId, List.of(), error,
                                existing.status().wireValue(), null);
                            return new UpdateCall(error, outcome);
                        }
                    }
                }
                next = next.withStatus(newStatus);
                updatedFields.add("status");
                fromStatus = existing.status().wireValue();
                toStatus = newStatus.wireValue();
            }
        }

        if (!updatedFields.isEmpty()) {
            Task requested = next;
            Set<String> changedFields = Set.copyOf(updatedFields);
            next = activeStore.updateAtomically(taskId,
                current -> applyChangedFields(current, requested, changedFields))
                .orElse(next);
        }

        // Ownership changes are delivered through the same in-process mailbox
        // used by SendMessage/teammate coordination.  The payload intentionally


        if (AgentTeamsEnabled.isEnabled()
                && next.owner().isPresent()
                && StringUtils.isNotEmpty(next.owner().orElse(null))
                && !Objects.equals(next.owner().orElse(null), existing.owner().orElse(null))) {
            notifyTaskOwner(next.owner().orElseThrow(), taskId, existing);
        }

        if (input.has("addBlocks") && input.get("addBlocks").isArray() && !input.get("addBlocks").isEmpty()) {
            boolean any = false;
            for (JsonNode idNode : input.get("addBlocks")) {
                String blockId = idNode.asText();
                if (!existing.blocks().contains(blockId)) {
                    activeStore.block(taskId, blockId);
                    any = true;
                }
            }
            if (any) updatedFields.add("blocks");
        }
        if (input.has("addBlockedBy") && input.get("addBlockedBy").isArray() && !input.get("addBlockedBy").isEmpty()) {
            boolean any = false;
            for (JsonNode idNode : input.get("addBlockedBy")) {
                String blockerId = idNode.asText();
                if (!existing.blockedBy().contains(blockerId)) {
                    activeStore.block(blockerId, taskId);
                    any = true;
                }
            }
            if (any) updatedFields.add("blockedBy");
        }

        if (!updatedFields.isEmpty()) taskBoard.publishChanged(sessionId);
        UpdateOutcome outcome = new UpdateOutcome(true, taskId, List.copyOf(updatedFields), null,
            fromStatus, toStatus);
        return new UpdateCall("Updated task #" + taskId + " "
            + String.join(", ", updatedFields), outcome);
    }

    private static Task applyChangedFields(
            Task current, Task requested, Set<String> changedFields) {
        Task merged = current;
        if (changedFields.contains("subject")) {
            merged = merged.withSubject(requested.subject());
        }
        if (changedFields.contains("description")) {
            merged = merged.withDescription(requested.description());
        }
        if (changedFields.contains("activeForm")) {
            merged = merged.withActiveForm(requested.activeForm().orElse(null));
        }
        if (changedFields.contains("owner")) {
            merged = merged.withOwner(requested.owner().orElse(null));
        }
        if (changedFields.contains("metadata")) {
            merged = merged.withMetadata(requested.metadata());
        }
        if (changedFields.contains("status")) {
            merged = merged.withStatus(requested.status());
        }
        return merged;
    }


    private ToolResult mapOutcome(
            String text, UpdateOutcome outcome, ToolExecutionContext context) {
        String taskId = outcome.taskId();
        boolean success = outcome.success();
        ObjectNode payload = mapper().createObjectNode();
        payload.put("success", success);
        payload.put("taskId", taskId);
        ArrayNode fields = payload.putArray("updatedFields");
        outcome.updatedFields().forEach(fields::add);
        if (outcome.error() != null) payload.put("error", outcome.error());
        if (outcome.fromStatus() != null && outcome.toStatus() != null) {
            ObjectNode change = payload.putObject("statusChange");
            change.put("from", outcome.fromStatus());
            change.put("to", outcome.toStatus());
        }
        String modelText = text;
        if (outcome.success()) {
            if (Strings.CS.equals("completed", outcome.toStatus())
                    && context != null && StringUtils.isNotEmpty(context.agentId())
                    && AgentTeamsEnabled.isEnabled()) {
                modelText += """


                    Task completed. Call TaskList now to find your next available task \
                    or see if your work unblocked others.""";
            }
        }
        return ToolResult.success(modelText).withToolUseResult(payload);
    }

    @Override
    public ToolResult mapUpdatedOutput(
            JsonNode updatedOutput, JsonNode input, ToolExecutionContext context) {
        boolean success = updatedOutput.path("success").asBoolean();
        String taskId = updatedOutput.path("taskId").asText();
        List<String> updatedFields = new ArrayList<>();
        updatedOutput.path("updatedFields").forEach(field -> updatedFields.add(field.asText()));
        String error = updatedOutput.hasNonNull("error")
            ? updatedOutput.path("error").asText() : null;
        JsonNode statusChange = updatedOutput.path("statusChange");
        String fromStatus = statusChange.isObject() ? statusChange.path("from").asText() : null;
        String toStatus = statusChange.isObject() ? statusChange.path("to").asText() : null;
        UpdateOutcome outcome = new UpdateOutcome(
            success, taskId, List.copyOf(updatedFields), error, fromStatus, toStatus);
        String text = success
            ? "Updated task #" + taskId + " " + String.join(", ", updatedFields)
            : error != null ? error : "Task #" + taskId + " not found";
        return mapOutcome(text, outcome, context).withToolUseResult(updatedOutput);
    }

    private static Map<String, Object> mergeMetadata(Map<String, Object> existing, JsonNode patch) {
        Map<String, Object> merged = new LinkedHashMap<>(existing);
        patch.fields().forEachRemaining(entry -> {
            if (entry.getValue().isNull()) {
                merged.remove(entry.getKey());
            } else {
                merged.put(entry.getKey(), JsonUtils.getMapper().convertValue(entry.getValue(), Object.class));
            }
        });
        return merged;
    }

    private static String currentTeammateName() {
        TeammateContext teammate = TeammateContextHolder.get();
        if (teammate != null && teammate.name() != null && !StringUtils.isBlank(teammate.name())) {
            return teammate.name();
        }

        // session.  Do not auto-claim a task as team-lead merely because the
        // agent-teams gate is on; only an actual teammate identity triggers
        // the owner assignment branch.
        return "";
    }

    private static void notifyTaskOwner(String owner, String taskId, Task existing) {
        String sender = currentTeammateName();
        if (StringUtils.isBlank(sender)) sender = TeammateMailbox.TEAM_LEAD;
        ObjectNode payload = mapper().createObjectNode();
        payload.put("type", MailTypes.TASK_ASSIGNMENT);
        payload.put("taskId", taskId);
        payload.put("subject", existing.subject());
        payload.put("description", existing.description());
        payload.put("assignedBy", sender);
        payload.put("timestamp", Instant.now().toString());
        TeammateMailbox mailbox = TeammateMailbox.instance();
        String inbox = mailbox.resolveToInbox(owner);
        mailbox.send(Mail.of(
            MailTypes.TASK_ASSIGNMENT, sender, inbox, payload.toString()));
    }



    @Override public boolean isEnabled() { return TaskToolsGate.isEnabled(); }

    @Override
    public PermissionDecision checkPermissions(JsonNode input, ToolPermissionContext permCtx) {
        return PermissionDecision.allow();
    }


    private static JsonNode buildSchema() {
        ObjectNode schema = mapper().createObjectNode();
        schema.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        props.putObject("taskId")
            .put("description", "The ID of the task to update")
            .put("type", "string");
        props.putObject("subject")
            .put("description", "New subject for the task")
            .put("type", "string");
        props.putObject("description")
            .put("description", "New description for the task")
            .put("type", "string");
        props.putObject("activeForm")
            .put("description", "Present continuous form shown in spinner when in_progress (e.g., \"Running tests\")")
            .put("type", "string");

        ObjectNode statusProp = props.putObject("status");
        statusProp.put("description", "New status for the task");
        ArrayNode anyOf = statusProp.putArray("anyOf");
        ObjectNode statusEnumBranch = anyOf.addObject();
        statusEnumBranch.put("type", "string");
        ArrayNode statusEnum = statusEnumBranch.putArray("enum");
        statusEnum.add("pending").add("in_progress").add("completed");
        ObjectNode statusDeletedBranch = anyOf.addObject();
        statusDeletedBranch.put("type", "string");
        statusDeletedBranch.put("const", "deleted");

        ObjectNode addBlocksProp = props.putObject("addBlocks");
        addBlocksProp.put("description", "Task IDs that this task blocks");
        addBlocksProp.put("type", "array");
        addBlocksProp.putObject("items").put("type", "string");

        ObjectNode addBlockedByProp = props.putObject("addBlockedBy");
        addBlockedByProp.put("description", "Task IDs that block this task");
        addBlockedByProp.put("type", "array");
        addBlockedByProp.putObject("items").put("type", "string");

        props.putObject("owner")
            .put("description", "New owner for the task")
            .put("type", "string");

        ObjectNode metadataProp = props.putObject("metadata");
        metadataProp.put("description", "Metadata keys to merge into the task. Set a key to null to delete it.");
        metadataProp.put("type", "object");
        metadataProp.putObject("propertyNames").put("type", "string");
        metadataProp.putObject("additionalProperties");

        ArrayNode required = schema.putArray("required");
        required.add("taskId");
        schema.put("additionalProperties", false);
        return schema;
    }
}
