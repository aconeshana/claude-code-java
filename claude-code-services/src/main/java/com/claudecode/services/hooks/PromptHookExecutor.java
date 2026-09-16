package com.claudecode.services.hooks;

import com.claudecode.api.CreateMessageRequest;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.services.model.SideQuery;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs {@code type:"prompt"} hooks through a one-shot structured side query
 * with the strict {@code {ok, reason}} contract, and provides the same LLM-only
 * evaluation as the fallback for {@code type:"agent"} hooks when no sub-agent
 * runtime is installed. Stop-family prompt hooks are routed to
 * {@link StopConditionEvaluator}.
 *
 * <ul>
 *   <li>{@code src/utils/hooks/execPromptHook.ts} — {@code $ARGUMENTS}
 *       substitution, evaluator system prompt, json_schema output format,
 *       decision parsing, non-blocking error attachment on invalid output.</li>
 *   <li>{@code src/utils/hooks/execAgentHook.ts} — LLM-only verification
 *       fallback when the agent runtime is unavailable.</li>
 * </ul>
 */
final class PromptHookExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(PromptHookExecutor.class);

    private static final String EVALUATOR_SYSTEM_PROMPT = """
        You are evaluating a hook in Claude Code.

        Your response must be a JSON object with one of these shapes:
        - {"ok": true}
        - {"ok": false, "reason": "Reason for why it is not met"}
        Return only the JSON object.
        """;

    private final HookLlmBindings llm;
    private final HookEffects effects;
    private final HookOutputParser parser;
    private final StopConditionEvaluator stopConditions;

    PromptHookExecutor(HookLlmBindings llm, HookEffects effects, HookOutputParser parser,
                       StopConditionEvaluator stopConditions) {
        this.llm = llm;
        this.effects = effects;
        this.parser = parser;
        this.stopConditions = stopConditions;
    }

    HookResult execute(PromptHook cmd, HookInput input, long defaultTimeoutMillis) {
        String arguments = input.toJson();
        String resolvedPrompt = Strings.CS.contains(cmd.prompt(), "$ARGUMENTS")
            ? cmd.prompt().replace("$ARGUMENTS", arguments)
            : cmd.prompt() + "\n\nARGUMENTS: " + arguments;

        if (input.event() == HookEvent.STOP || input.event() == HookEvent.SUBAGENT_STOP) {
            return stopConditions.evaluate(cmd, resolvedPrompt, input,
                input.event() == HookEvent.STOP);
        }

        if (llm.sideQuery() == null) {
            LOG.debug("PromptHook: LLM client not configured, returning Allow with prompt");
            return new HookResult.Allow("PromptHook: " + resolvedPrompt);
        }

        try {
            String model = cmd.model().orElse(llm.evaluatorModel());
            String promptText = buildPromptEvaluationPrompt(resolvedPrompt, input);
            return evaluate(cmd, cmd.prompt(), promptText, model, input,
                cmd.timeoutSeconds().map(seconds -> seconds * 1000L).orElse(defaultTimeoutMillis));
        } catch (Exception e) {
            LOG.warn("PromptHook execution failed: {}", e.getMessage());
            return HookResult.skip();
        }
    }

    /** LLM-only verification used when no sub-agent runtime is installed for agent hooks. */
    HookResult evaluateAgentFallback(AgentHook cmd, HookInput input, String resolvedPrompt,
                                     long defaultTimeoutMillis) {
        if (llm.sideQuery() == null) {
            LOG.debug("AgentHook: LLM client not configured, returning Allow with prompt");
            return new HookResult.Allow("AgentHook: " + resolvedPrompt);
        }
        try {
            String model = cmd.model().orElse(llm.agentFallbackModel());
            String verificationPrompt = buildAgentVerificationPrompt(resolvedPrompt, input);
            return evaluate(cmd, cmd.prompt(), verificationPrompt, model, input,
                cmd.timeoutSeconds().map(seconds -> seconds * 1000L).orElse(defaultTimeoutMillis));
        } catch (Exception e) {
            LOG.warn("AgentHook execution failed: {}", e.getMessage());
            return HookResult.skip();
        }
    }

    private HookResult evaluate(HookCommand command, String commandText, String promptText,
                                String model, HookInput input, long timeoutMillis) {
        String response = callStructuredHookLlm(promptText, model, timeoutMillis);
        if (StringUtils.isBlank(response)) {
            enqueueError(HookOutcomes.hookEventName(input), input, commandText,
                "JSON validation failed", response == null ? "" : response,
                System.currentTimeMillis());
            return HookResult.skip();
        }
        return parseDecision(response, command, input);
    }

    /** Parses the strict ordinary Prompt/Agent Hook {@code {ok,reason}} contract. */
    HookResult parseDecision(String output, HookCommand command, HookInput input) {
        long startedAt = System.currentTimeMillis();
        String hookName = HookOutcomes.hookEventName(input);
        String commandText = command instanceof PromptHook p ? p.prompt()
            : command instanceof AgentHook a ? a.prompt() : command.toString();
        HookOutputParser.PromptDecision decision = parser.parsePromptDecision(output);
        if (!decision.valid()) {
            enqueueError(hookName, input, commandText, decision.failure(), output, startedAt);
            return HookResult.skip();
        }
        if (!decision.allowed()) {
            return new HookResult.Block(decision.reason());
        }
        return HookResult.allow();
    }

    private void enqueueError(String hookName, HookInput input, String command,
                              String stderr, String stdout, long startedAt) {
        effects.nonBlockingError(hookName, stderr, stdout,
            input.toolUseId().orElseGet(() -> UUID.randomUUID().toString()),
            input.event().displayName(), command,
            Math.max(0L, System.currentTimeMillis() - startedAt));
    }

    private String callStructuredHookLlm(String prompt, String model, long timeoutMillis) {
        SideQuery sideQuery = llm.sideQuery();
        if (sideQuery == null) return null;
        return sideQuery.queryTextOrThrow(new SideQuery.Request()
            .model(model)
            .systemPrompt(EVALUATOR_SYSTEM_PROMPT)
            .userPrompt(prompt)
            .maxTokens(1024)
            .timeoutMillis(timeoutMillis)
            .thinking(CreateMessageRequest.ThinkingConfig.disabled())
            .outputConfig(new CreateMessageRequest.OutputConfig(null, outputFormat()))
            // tier: ONE_SHOT — per-hook one-shot prompt; no stable prefix to cache.
            .promptCachingEnabled(false)
            .querySource("hook_prompt"));
    }

    /** The {@code {ok: boolean, reason?: string}} json_schema output format. */
    static JsonNode outputFormat() {
        var schema = JsonUtils.getMapper().createObjectNode();
        schema.put("type", "object");
        var properties = schema.putObject("properties");
        properties.putObject("ok").put("type", "boolean");
        properties.putObject("reason").put("type", "string");
        schema.putArray("required").add("ok");
        schema.put("additionalProperties", false);
        var format = JsonUtils.getMapper().createObjectNode();
        format.put("type", "json_schema");
        format.set("schema", schema);
        return format;
    }

    private static String buildPromptEvaluationPrompt(String hookPrompt, HookInput input) {
        return
            "Evaluate the following hook prompt and determine if the operation should be allowed or blocked.\n\n"
                + "Hook Prompt:\n" + hookPrompt + "\n\n"
                + "Context:\n" + input.toJson() + "\n\n"
                + "Return only a JSON object with:\n"
                + "- ok: true if the condition is satisfied, false otherwise\n"
                + "- reason: optional explanation when ok is false\n\n"
                + "Example: {\"ok\":true}";
    }

    private static String buildAgentVerificationPrompt(String agentPrompt, HookInput input) {
        return "You are verifying an action taken by Claude Code.\n\n"
            + "Verification Task:\n" + agentPrompt + "\n\n"
            + "Action Context:\n" + input.toJson() + "\n\n"
            + "Verify whether the action was performed correctly and safely.\n"
            + "Return only a JSON object with:\n"
            + "- ok: true if the action is verified, false if it is not\n"
            + "- reason: optional explanation when ok is false\n\n"
            + "Example: {\"ok\":true}";
    }
}
