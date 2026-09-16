package com.claudecode.services.hooks;

import com.claudecode.core.engine.AbortController;
import com.claudecode.permissions.PermissionMode;
import com.claudecode.tools.agent.SubAgentFactory;
import com.claudecode.tools.agent.SubAgentRequest;
import com.claudecode.tools.agent.SubAgentResult;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs {@code type:"agent"} hooks as a bounded verifier sub-agent that inspects
 * the transcript and workspace with tools and reports through the structured
 * output schema. Falls back to {@link PromptHookExecutor}'s LLM-only
 * verification when no sub-agent runtime is installed.
 *
 * <ul>
 *   <li>{@code src/utils/hooks/execAgentHook.ts} — verifier agent request
 *       (hook-agent type, disallowed tools, max turns, json_schema, system
 *       prompt), timeout abort, and result parsing.</li>
 * </ul>
 */
final class AgentHookExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(AgentHookExecutor.class);

    private final HookLlmBindings llm;
    private final HookSessionContext context;
    private final PromptHookExecutor prompts;

    AgentHookExecutor(HookLlmBindings llm, HookSessionContext context, PromptHookExecutor prompts) {
        this.llm = llm;
        this.context = context;
        this.prompts = prompts;
    }

    HookResult execute(AgentHook cmd, HookInput input, long defaultTimeoutMillis) {
        String resolvedPrompt = cmd.prompt().replace("$ARGUMENTS", input.toJson());
        SubAgentFactory factory = llm.agentFactory();
        if (factory == null) {
            return prompts.evaluateAgentFallback(cmd, input, resolvedPrompt, defaultTimeoutMillis);
        }

        long timeoutMillis = cmd.timeoutSeconds()
            .map(seconds -> seconds * 1000L)
            .orElse(defaultTimeoutMillis > 0 ? defaultTimeoutMillis : 60_000L);
        String transcriptPath = String.valueOf(
            input.extra().getOrDefault("transcript_path", ""));
        String event = input.event().displayName();
        String systemPrompt = (input.event() == HookEvent.STOP
                || input.event() == HookEvent.SUBAGENT_STOP
            ? "You are verifying a stop condition in Claude Code. Your task is to verify "
                + "that the agent completed the given plan."
            : "You are evaluating a " + event + " hook in Claude Code. Your task is to "
                + "evaluate the condition described in the user message.")
            + " The conversation transcript is available at: " + transcriptPath + "\n"
            + "Use the available tools to inspect the transcript and workspace as needed. "
            + "Return the verification result exactly once through the structured-output tool.";
        AbortController abort = new AbortController();
        JsonNode schema = PromptHookExecutor.outputFormat().path("schema").deepCopy();
        SubAgentRequest request = SubAgentRequest.builder()
            .prompt(resolvedPrompt)
            .subagentType("hook-agent")
            .disallowedTools(List.of("Agent", "ExitPlanMode"))
            .model(cmd.model().orElse(llm.currentModel()))
            .permissionMode(PermissionMode.DONT_ASK)
            .cwd(context.cwd())
            .maxTurns(50)
            .jsonSchema(schema)
            .systemPromptOverride(systemPrompt)
            .abortController(abort)
            .description("Verify hook condition")
            .build();
        CompletableFuture<SubAgentResult> future = CompletableFuture.supplyAsync(
            () -> factory.runSubAgent(request),
            runnable -> Thread.ofVirtual().start(runnable));
        try {
            SubAgentResult result = future.get(timeoutMillis, TimeUnit.MILLISECONDS);
            if (result == null || result.isError() || StringUtils.isBlank(result.output())) {
                return HookResult.skip();
            }
            return prompts.parseDecision(result.output(), cmd, input);
        } catch (TimeoutException _) {
            abort.abort("Agent hook timed out");
            future.cancel(true);
            return HookResult.skip();
        } catch (Exception failure) {
            abort.abort("Agent hook failed");
            LOG.warn("AgentHook verifier failed: {}", failure.getMessage());
            return HookResult.skip();
        }
    }
}
