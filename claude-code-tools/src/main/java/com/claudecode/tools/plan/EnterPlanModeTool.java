package com.claudecode.tools.plan;

import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.core.engine.ToolResult;
import com.claudecode.permissions.PermissionGate;
import com.claudecode.permissions.PermissionMode;
import com.claudecode.permissions.PermissionDecision;
import com.claudecode.permissions.ToolPermissionContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.concurrent.atomic.AtomicBoolean;
import com.claudecode.tools.AnnotatedTool;
import com.claudecode.tools.BuiltInTool;
import com.claudecode.tools.InteractiveChannelGate;
import com.claudecode.tools.ToolTexts;

/**
 * EnterPlanModeTool — switch to plan mode (read-only).
 */
@BuiltInTool(
    name = "EnterPlanMode",
    shouldDefer = true,
    readOnly = true,
    concurrencySafe = true
)
public class EnterPlanModeTool extends AnnotatedTool<JsonNode, String> {


    @Override
    public String searchHint() {
        return "switch to plan mode to design an approach before coding";
    }

    private static final AtomicBoolean PLAN_MODE_ACTIVE = new AtomicBoolean(false);

    private final PermissionGate gate;

    /** Backwards-compatible constructor — no gate, flag-only behavior. */
    public EnterPlanModeTool() {
        this(null);
    }

    /** Wires this tool to the live PermissionGate. Preferred. */
    public EnterPlanModeTool(PermissionGate gate) {
        this.gate = gate;
    }

    @Override public String description() {
        return ToolTexts.description("EnterPlanMode");
    }

    @Override public String prompt(ToolExecutionContext context) {
        return ToolTexts.prompt("EnterPlanMode");
    }

    @Override public JsonNode inputSchema() {

// extra keys are rejected. createObjectSchema alone is permissive,
        // so set additionalProperties:false to match z.strictObject.
        ObjectNode schema = createObjectSchema();
        schema.put("additionalProperties", false);
        return schema;
    }

/** established tool uses buildTool's read-only default allow decision. */
    @Override
    public PermissionDecision checkPermissions(JsonNode input, ToolPermissionContext context) {
        return PermissionDecision.allow();
    }




    @Override public boolean isEnabled() { return InteractiveChannelGate.terminalInteractionAvailable(); }


    @Override
    public String call(JsonNode input, ToolExecutionContext context) {
        if (context != null && context.agentId() != null) {
            throw new IllegalStateException("EnterPlanMode tool cannot be used in agent contexts");
        }
        PLAN_MODE_ACTIVE.set(true);
        if (gate != null) {
            gate.setMode(PermissionMode.PLAN);
        }
        return enteredPlanModeMessage();
    }


    private static String enteredPlanModeMessage() {
        return """
            Entered plan mode. You should now focus on exploring the codebase and designing an implementation approach.

            In plan mode, you should:
            1. Thoroughly explore the codebase to understand existing patterns
            2. Identify similar features and architectural approaches
            3. Consider multiple approaches and their trade-offs
            4. Use AskUserQuestion if you need to clarify the approach
            5. Design a concrete implementation strategy
            6. When ready, use ExitPlanMode to present your plan for approval
            Remember: DO NOT write or edit any files yet. This is a read-only exploration and planning phase.""";
    }


    @Override
    public ToolResult mapResult(Object rawResult, JsonNode input, ToolExecutionContext context) {
        if (!(rawResult instanceof String text)) return null;
        ObjectNode data = mapper().createObjectNode();
        data.put("message", "Entered plan mode. You should now focus on exploring the codebase and designing an implementation approach.");
        return ToolResult.success(text).withToolUseResult(data);
    }

    /** Check if plan mode is currently active. */
    public static boolean isPlanModeActive() {
        return PLAN_MODE_ACTIVE.get();
    }

    /** Reset the compatibility flag used by gate-less embedders/tests. */
    static void resetPlanMode() {
        PLAN_MODE_ACTIVE.set(false);
    }
}
