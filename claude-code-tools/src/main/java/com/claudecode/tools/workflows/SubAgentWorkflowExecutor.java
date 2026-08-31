package com.claudecode.tools.workflows;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.core.agent.BuiltInAgentDefinitions;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.permissions.PermissionEngine;
import com.claudecode.permissions.PermissionGate;
import com.claudecode.tools.agent.AgentDefinitionLoader;
import com.claudecode.tools.agent.SubAgentFactory;
import com.claudecode.tools.agent.SubAgentRequest;
import com.claudecode.tools.agent.SubAgentResult;

import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Bridges the deterministic workflow DSL's {@code agent} hook to the existing in-process sub-agent
 * runner.
 */
public final class SubAgentWorkflowExecutor implements WorkflowAgentExecutor {

    public static final String TEXT_SYSTEM_PROMPT = """
        You are a subagent spawned by a workflow orchestration script. Use the tools available to complete the task.

        CRITICAL: Your final text response is returned **verbatim** as a string to the calling script — it is your return value, not a message to a human.
        - Output the literal result (data, JSON, text). Do NOT output confirmations like "Done." or "Sent."
        - If asked for JSON, return ONLY the raw JSON — no code fences, no prose, no markdown.
        - Do NOT use SendUserMessage to deliver your answer. Put your answer in your final text response.
        - Be concise. The script will parse your output.
        """.stripTrailing();

    public static final String STRUCTURED_SYSTEM_PROMPT = """
        You are a subagent spawned by a workflow orchestration script. Use the tools available to complete the task.

        CRITICAL: You MUST call the StructuredOutput tool exactly once to return your final answer. The tool's input schema defines the required shape.
        - Do your work (Read files, run commands, etc.), then call StructuredOutput with your answer.
        - Do NOT put your answer in a text response. The script reads ONLY the StructuredOutput tool call.
        - If the schema validation fails, read the error and call StructuredOutput again with a corrected shape.
        - After calling StructuredOutput successfully, end your turn. No acknowledgment needed.
        """.stripTrailing();

    public static final String TEXT_CUSTOM_AGENT_REMINDER = """

        ---

        NOTE: You are running inside a workflow script. Your final text response is returned verbatim as a string to the calling script — it is your return value, not a message to a human. Output the literal result; do not output confirmations like "Done." Be concise — the script will parse your output.""";

    public static final String STRUCTURED_CUSTOM_AGENT_REMINDER = """

        ---

        NOTE: You are running inside a workflow script. You MUST return your final answer by calling the StructuredOutput tool exactly once — the tool's input schema defines the required shape. Do your work, then call StructuredOutput; do NOT put your answer in a text response (the script reads ONLY the tool call). If validation fails, read the error and call StructuredOutput again with a corrected shape.""";

    private final SubAgentFactory subAgentFactory;
    private final PermissionGate permissionGate;

    public SubAgentWorkflowExecutor(SubAgentFactory subAgentFactory) {
        this(subAgentFactory, null);
    }

    public SubAgentWorkflowExecutor(SubAgentFactory subAgentFactory,
                                    PermissionGate permissionGate) {
        this.subAgentFactory = Objects.requireNonNull(subAgentFactory, "subAgentFactory");
        this.permissionGate = permissionGate;
    }

    @Override
    public WorkflowAgentResult execute(WorkflowAgentRequest request) {
        WorkflowAgentOptions options = request.options();
        boolean structured = options.schema() != null && !options.schema().isNull();
        boolean customAgent = StringUtils.isNotBlank(options.agentType());
        if (customAgent) validateAgentType(options.agentType(), request.parentContext());
        SubAgentRequest subAgentRequest = SubAgentRequest.builder()
            .prompt(request.prompt())
            .subagentType(options.agentType())
            .description(displayLabel(request))
            .parentContext(request.parentContext())
            .abortController(request.parentContext() == null
                ? null : request.parentContext().abortController())
            .model(options.model())
            .effort(options.effort())
            .jsonSchema(options.schema())
            .worktreeIsolation(Strings.CS.equals("worktree", options.isolation()))
            .criticalSystemReminder(customAgent
                ? structured ? STRUCTURED_CUSTOM_AGENT_REMINDER : TEXT_CUSTOM_AGENT_REMINDER
                : null)
            .systemPromptOverride(customAgent ? null
                : structured ? STRUCTURED_SYSTEM_PROMPT : TEXT_SYSTEM_PROMPT)
            .agentId(request.agentId())
            .transcriptSubdir(request.transcriptSubdir())
            .progressCallback(request.progressCallback() == null ? null
                : request.progressCallback()::onProgress)
            .build();

        SubAgentResult result = subAgentFactory.runSubAgent(subAgentRequest);
        if (result.isError()) {
            return WorkflowAgentResult.apiError(
                result.error().orElse(result.output()), result.tokensUsed(),
                result.toolUseCount(), result.durationMs(), result.outputTokens());
        }
        return WorkflowAgentResult.of(result.output(), result.tokensUsed(),
            result.toolUseCount(), result.durationMs(), result.outputTokens(),
            result.stopReason(), result.structuredOutputPresent());
    }

    private static String displayLabel(WorkflowAgentRequest request) {
        String label = request.options().label();
        if (StringUtils.isNotBlank(label)) return label;
        String prompt = request.prompt();
        return prompt.length() <= 80 ? prompt : prompt.substring(0, 77) + "...";
    }

    private void validateAgentType(String agentType,
                                   ToolExecutionContext context) {
        if (permissionGate != null && permissionGate.currentContext() != null) {
            PermissionEngine.getDenyRuleForAgent(permissionGate.currentContext(), agentType)
                .ifPresent(rule -> {
                    throw new WorkflowRuntimeException("agent({agentType}): '" + agentType
                        + "' is denied by permission rule '"
                        + PermissionEngine.permissionRuleToString(rule) + "' from "
                        + PermissionEngine.permissionRuleSourceDisplayString(rule.source()) + ".");
                });
        }
        String cwd = context == null || context.workingDirectory() == null
            ? System.getProperty("user.dir") : context.workingDirectory();
        var available = AgentDefinitionLoader.getActive(cwd);
        if (available.stream().noneMatch(agent -> agent.agentType().equals(agentType))) {
            throw new WorkflowRuntimeException("agent({agentType}): agent type '" + agentType
                + "' not found. Available agents: " + available.stream()
                    .map(BuiltInAgentDefinitions.AgentDefinition::agentType).distinct()
                    .collect(Collectors.joining(", ")));
        }
    }
}
