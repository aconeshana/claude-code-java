package com.claudecode.tools.plan;

import org.apache.commons.lang3.StringUtils;
import com.claudecode.core.attachment.PlanModeExitSignal;
import com.claudecode.core.engine.StructuredToolOutput;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.core.message.PlanModeExitInfo;
import com.claudecode.core.plan.PlanCatalogContext;
import com.claudecode.permissions.PermissionDecision;
import com.claudecode.permissions.PermissionGate;
import com.claudecode.permissions.PermissionMode;
import com.claudecode.permissions.ToolPermissionContext;
import com.claudecode.tools.tasks.InProcessTeammateTask;
import com.claudecode.tools.tasks.TaskRegistry;
import com.claudecode.tools.tasks.teammate.TeammateContext;
import com.claudecode.tools.tasks.teammate.TeammateContextHolder;
import com.claudecode.tools.tasks.teammate.AgentTeamsEnabled;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import com.claudecode.tools.AnnotatedTool;
import com.claudecode.tools.BuiltInTool;
import com.claudecode.tools.ToolTexts;
import com.claudecode.tools.InteractiveChannelGate;
import com.claudecode.tools.ValidationResult;

/**
 * ExitPlanModeTool — leave plan mode, read the plan back from disk, and restore the prior
 * permission state.
 */
@BuiltInTool(
    name = "ExitPlanMode",
    shouldDefer = true,
    concurrencySafe = true
)
public class ExitPlanModeTool extends AnnotatedTool<JsonNode, StructuredToolOutput> {


    @Override
    public String searchHint() {
        return "present plan for approval and start coding (plan mode only)";
    }

    private final PermissionGate gate;

    public ExitPlanModeTool() {
        this(null);
    }

    public ExitPlanModeTool(PermissionGate gate) {
        this.gate = gate;
    }

    @Override public String description() {
        return ToolTexts.description("ExitPlanMode");
    }


    @Override
    public String prompt(ToolExecutionContext context) {
        String prompt = ToolTexts.prompt("ExitPlanMode");
        if (!PlanFiles.isMultiPlanEnabled()) return prompt;
        return prompt + "\n\nWhen the current plan explicitly revises one older plan from the "
            + "same session scope, pass that older plan ID as `revisesPlanId`. Omit the field "
            + "for unrelated work or when no prior plan is being superseded.";
    }

    @Override public JsonNode inputSchema() {

// the trailing.passthrough wins, so extra keys are accepted.
        var schema = createObjectSchema();
        ObjectNode properties = (ObjectNode) schema.get("properties");


        ObjectNode allowedPrompts = properties.putObject("allowedPrompts");
        allowedPrompts.put("description",
            "Prompt-based permissions needed to implement the plan. These describe "
                + "categories of actions rather than specific commands.");
        allowedPrompts.put("type", "array");
        ObjectNode item = allowedPrompts.putObject("items");
        item.put("type", "object");
        ObjectNode itemProps = item.putObject("properties");
        ObjectNode toolProp = itemProps.putObject("tool");
        toolProp.put("description", "The tool this prompt applies to");
        toolProp.put("type", "string");
        toolProp.putArray("enum").add("Bash");
        ObjectNode promptProp = itemProps.putObject("prompt");
        promptProp.put("description",
            "Semantic description of the action, e.g. \"run tests\", \"install dependencies\"");
        promptProp.put("type", "string");
        item.putArray("required").add("tool").add("prompt");

        if (PlanFiles.isMultiPlanEnabled()) {
            ObjectNode revisesPlanId = properties.putObject("revisesPlanId");
            revisesPlanId.put("description",
                "Optional ID of one older plan in this session scope that the current plan explicitly revises");
            revisesPlanId.put("type", "string");
        }

        // allowedPrompts item schema, so extra keys are allowed — do NOT set
// additionalProperties:false here. The outer schema is.passthrough
        // (permissive top-level), preserved below.

        schema.putObject("additionalProperties");
        return schema;
    }



    @Override public boolean isEnabled() { return InteractiveChannelGate.terminalInteractionAvailable(); }


    /** Teammates use the leader mailbox; only the main session opens the UI prompt. */
    @Override public boolean requiresUserInteraction() { return currentTeammate() == null; }


    @Override
    public PermissionDecision checkPermissions(JsonNode input, ToolPermissionContext context) {
        if (currentTeammate() != null) {
            return PermissionDecision.allow();
        }
        return new PermissionDecision.Ask(null, input, "Exit plan mode?", null, null);
    }


    @Override
    public ValidationResult validateInput(JsonNode input, ToolExecutionContext context) {
        if (currentTeammate() != null) {
            return ValidationResult.valid();
        }
        boolean inPlanMode = gate != null
            ? gate.currentMode() == PermissionMode.PLAN
            : EnterPlanModeTool.isPlanModeActive();
        if (!inPlanMode) {
            return ValidationResult.invalid(
                "You are not in plan mode. To enter plan mode, call the EnterPlanMode tool first. "
                    + "If your plan was already approved, continue with implementation.");
        }
        if (PlanFiles.isMultiPlanEnabled() && input != null && input.has("revisesPlanId")) {
            if (!input.get("revisesPlanId").isTextual()) {
                return ValidationResult.invalid("revisesPlanId must be a string plan ID.");
            }
            String revisesPlanId = StringUtils.trimToNull(input.get("revisesPlanId").asText());
            if (revisesPlanId != null && context != null) {
                String error = PlanFiles.validateRevisionTarget(
                    context.sessionId(), context.agentId(), revisesPlanId);
                if (error != null) return ValidationResult.invalid(error);
            }
        }
        return ValidationResult.valid();
    }

    @Override
    public StructuredToolOutput call(JsonNode input, ToolExecutionContext context) {
        if (context == null || context.sessionId() == null || StringUtils.isBlank(context.sessionId())) {
            throw new IllegalStateException(
                "ExitPlanMode requires a non-blank session context");
        }
        String sessionId = context.sessionId();
        String agentId = context.agentId();
        PlanCatalogContext planContext = PlanFiles.isMultiPlanEnabled()
            ? PlanFiles.activatePlan(sessionId, agentId)
            : PlanFiles.currentPlanContext(sessionId, agentId);
        Path filePath = Path.of(planContext.planFilePath());
        String revisesPlanId = input != null && input.has("revisesPlanId")
            && input.get("revisesPlanId").isTextual()
            ? StringUtils.trimToNull(input.get("revisesPlanId").asText()) : null;
        String revisionError = PlanFiles.validateRevisionTarget(
            sessionId, agentId, revisesPlanId);
        if (revisionError != null) throw new IllegalArgumentException(revisionError);
        String inputPlan = input != null && input.has("plan") && input.get("plan").isTextual()
            ? input.get("plan").asText() : null;
        if (inputPlan != null) {
            try {
                Files.writeString(filePath, inputPlan,
                    StandardCharsets.UTF_8);
            } catch (IOException _) {

                // in-memory plan. Preserve that non-fatal behavior.
            }
        }
        String plan = inputPlan != null ? inputPlan : PlanFiles.getPlan(sessionId, agentId);
        boolean isAgent = agentId != null;

        boolean planExists = StringUtils.isNotBlank(plan);

        TeammateContext teammate = currentTeammate();
        if (teammate != null && teammate.planMode()) {
            if (StringUtils.isBlank(plan)) {
                throw new IllegalStateException(
                    "No plan file found at " + filePath
                        + ". Please write your plan to this file before calling ExitPlanMode.");
            }
            InProcessTeammateTask handle = TaskRegistry.global()
                .getTeammateHandle(teammate.agentId())
                .orElse(null);
            if (handle == null) {
                throw new IllegalStateException(
                    "Cannot request plan approval: teammate handle is no longer active");
            }
            String requestId = handle.submitPlanApprovalRequest(plan, approval -> {
                if (approval.approved()) {
                    PlanFiles.completePlan(sessionId, agentId, plan, revisesPlanId);
                }
            });
            String waitingText = "Your plan has been submitted to the team lead for approval.\n"
                + "Plan file: " + filePath + "\n"
                + "**What happens next:**\n"
                + "1. Wait for the team lead to review your plan\n"
                + "2. You will receive a message in your inbox with approval/rejection\n"
                + "3. If approved, you can proceed with implementation\n"
                + "4. If rejected, refine your plan based on the feedback\n"
                + "**Important:** Do NOT proceed until you receive approval. Check your inbox for response.\n"
                + "Request ID: " + requestId;
            return enrich(output(waitingText, plan, true, filePath, true, requestId),
                preview(planContext, plan, revisesPlanId));
        }

        if (teammate == null) {
            // Surface the one-shot plan-mode-exit signal for the next request
            // build's plan_mode_exit attachment. Teammates have their own
            // context and must not mutate the leader's process-global signal.
            PlanModeExitSignal.set(new PlanModeExitInfo(filePath.toString(), planExists));
            EnterPlanModeTool.resetPlanMode();
            if (gate != null) {
                gate.markPlanModeExited();
                gate.finishPlanMode();
            }
        } else {
            // Voluntary teammate plan mode is local to the teammate context.
            teammate.setPlanModeRequired(false);
        }

        PlanFiles.PlanCompletion completion = PlanFiles.completePlan(
            sessionId, agentId, plan, revisesPlanId);

        if (StringUtils.isBlank(plan)) {
            return enrich(output(isAgent
                    ? "User has approved the plan. There is nothing else needed from you now. Please respond with \"ok\""
                    : "User has approved exiting plan mode. You can now proceed.",
                null, isAgent, filePath), completion);
        }

        if (isAgent) {
            return enrich(output("User has approved the plan. There is nothing else needed from you now. Please respond with \"ok\"",
                plan, true, filePath), completion);
        }

        boolean planWasEdited = inputPlan != null;
        boolean hasTaskTool = AgentTeamsEnabled.isEnabled()
            && context.enabledTools().stream().anyMatch("Agent"::equals);
        String teammateHint = hasTaskTool
            ? "\nIf this plan can be broken down into multiple independent tasks, consider spawning "
                + "named teammates with the Agent tool (pass a `name`) to parallelize the work."
            : "";
        String text = "User has approved your plan. You can now start coding. Start with updating "
            + "your todo list if applicable\n"
            + "Your plan has been saved to: " + filePath + "\n"
            + "You can refer back to it if needed during implementation."
            + teammateHint + "\n"
            + "## " + (planWasEdited ? "Approved Plan (edited by user)" : "Approved Plan") + ":\n"
            + plan;
        // The isAgent==true path already returned above, so the non-agent text
        // below always carries isAgent=false.
        return enrich(output(text, plan, false, filePath, false, null,
            hasTaskTool, planWasEdited), completion);
    }

    private static StructuredToolOutput output(
            String text, String plan, boolean isAgent, Path filePath) {
        return output(text, plan, isAgent, filePath, false, null, false, false);
    }

    private static StructuredToolOutput output(
            String text, String plan, boolean isAgent, Path filePath,
            boolean awaitingLeaderApproval, String requestId) {
        return output(text, plan, isAgent, filePath, awaitingLeaderApproval,
            requestId, false, false);
    }

    private static StructuredToolOutput output(
            String text, String plan, boolean isAgent, Path filePath,
            boolean awaitingLeaderApproval, String requestId,
            boolean hasTaskTool, boolean planWasEdited) {
        ObjectNode data = JsonUtils.getMapper().createObjectNode();
        if (plan == null) {

            data.putNull("plan");
        } else {
            data.put("plan", plan);
        }
        data.put("isAgent", isAgent);
        data.put("filePath", filePath.toString());
        if (awaitingLeaderApproval) {
            data.put("awaitingLeaderApproval", true);
            data.put("requestId", requestId);
        }
        if (hasTaskTool) data.put("hasTaskTool", true);
        if (planWasEdited) data.put("planWasEdited", true);
        return new StructuredToolOutput(text, data);
    }

    private static PlanFiles.PlanCompletion preview(
            PlanCatalogContext context, String plan, String revisesPlanId) {
        if (context == null || context.planId() == null) return null;
        PlanMetadataExtractor.Metadata metadata =
            PlanMetadataExtractor.extract(plan, context.planId());
        return new PlanFiles.PlanCompletion(
            context.planId(), metadata.title(), context.planStatus(), revisesPlanId);
    }

    private static StructuredToolOutput enrich(
            StructuredToolOutput output, PlanFiles.PlanCompletion completion) {
        if (completion == null || completion.planId() == null
                || !(output.toolUseResult() instanceof ObjectNode data)) {
            return output;
        }
        data.put("planId", completion.planId());
        if (completion.title() != null) data.put("title", completion.title());
        if (completion.planStatus() != null) data.put("planStatus", completion.planStatus());
        if (completion.revisesPlanId() != null) {
            data.put("revisesPlanId", completion.revisesPlanId());
        }
        return output;
    }

    private static TeammateContext currentTeammate() {
        return TeammateContextHolder.get();
    }
}
