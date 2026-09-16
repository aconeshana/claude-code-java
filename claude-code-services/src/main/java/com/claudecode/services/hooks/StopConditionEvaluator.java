package com.claudecode.services.hooks;

import com.claudecode.api.ApiException;
import com.claudecode.api.CreateMessageRequest;
import com.claudecode.api.PromptTooLongException;
import com.claudecode.core.engine.ApiMessageFormatter;
import com.claudecode.core.engine.RequestMessageNormalizer;
import com.claudecode.core.engine.StreamingClient;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.TokenEstimator;
import com.claudecode.core.message.Usage;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.services.model.ModelOutputTokens;
import com.claudecode.services.model.SideQuery;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Evaluates a Stop / SubagentStop prompt hook (the /goal condition included)
 * against the live transcript with a one-shot structured side query, budgeting
 * the transcript to a fraction of the evaluator model's context window and
 * retrying at half budget on {@link PromptTooLongException}.
 *
 * <ul>
 *   <li>{@code src/utils/hooks/execPromptHook.ts} — Stop-hook evaluator system
 *       prompt, {@code {ok, reason, impossible}} json_schema, schema
 *       validation, and ConditionMet / NotMet / Impossible mapping.</li>
 *   <li>{@code src/utils/hooks/execPromptHook.ts} — transcript truncation by
 *       assistant-response groups with the omitted-prefix notice and the
 *       prompt-too-long retry.</li>
 *   <li>{@code src/utils/hooks/apiQueryHookHelper.ts} — evaluator request
 *       parameters (effort, tools, metadata, disabled thinking, no caching).</li>
 * </ul>
 */
final class StopConditionEvaluator {

    private static final Logger LOG = LoggerFactory.getLogger(StopConditionEvaluator.class);
    private static final double TRANSCRIPT_FRACTION = 0.5;

    private static final String SYSTEM_PROMPT = """
        You are evaluating a stop-condition hook in Claude Code. Read the conversation transcript carefully, then judge whether the user-provided condition is satisfied.

        Your response must be a JSON object with one of these shapes:
        - {"ok": true, "reason": "<quote evidence from the transcript that satisfies the condition>"}
        - {"ok": false, "reason": "<quote what is missing or what blocks the condition>"}
        - {"ok": false, "impossible": true, "reason": "<explain why the condition can never be satisfied>"}

        Always include a "reason" field, quoting specific text from the transcript whenever possible. If the transcript does not contain clear evidence that the condition is satisfied, return {"ok": false, "reason": "insufficient evidence in transcript"}.

        Only use {"ok": false, "impossible": true} when the condition is genuinely unachievable in this session — for example: the condition is self-contradictory, it depends on a resource or capability that is unavailable, or the assistant has explicitly tried, exhausted reasonable approaches, and stated it cannot be done. Apply your own judgment when deciding this — the assistant claiming the goal is impossible is evidence, not proof; independently confirm the condition is genuinely unachievable rather than deferring to the assistant's self-assessment. Do not use it just because the goal has not been reached yet or because progress is slow. When in doubt, return {"ok": false} without "impossible".\
        """;

    private final HookLlmBindings llm;
    private final GoalEvaluatorBindings goal;
    private final HookSessionContext context;
    private final HookEffects effects;
    private final HookOutputParser parser;

    StopConditionEvaluator(HookLlmBindings llm, GoalEvaluatorBindings goal,
                           HookSessionContext context, HookEffects effects,
                           HookOutputParser parser) {
        this.llm = llm;
        this.goal = goal;
        this.context = context;
        this.effects = effects;
        this.parser = parser;
    }

    /** Effort reported in Stop-hook ARGUMENTS, derived from the same bindings the evaluator uses. */
    String currentEffort() {
        return goal.effort(llm.currentModel());
    }

    HookResult evaluate(PromptHook cmd, String condition, HookInput input,
                        boolean impossibleIsTerminal) {
        SideQuery sideQuery = llm.sideQuery();
        if (sideQuery == null) {
            LOG.info("[goal] executeStopConditionPromptHook: sideQuery is null — goal evaluator SKIPPED (fail-open); advanced goal condition \"{}\" will NOT block stop",
                condition);
            return HookResult.skip();
        }
        LOG.info("[goal] executeStopConditionPromptHook: evaluating condition \"{}\" via side-query (model={})",
            condition, cmd.model().orElse(null));
        long startedAt = System.currentTimeMillis();
        String toolUseId = input.toolUseId().orElseGet(() -> UUID.randomUUID().toString());
        String model = cmd.model().orElse(llm.evaluatorModel());
        List<Message> transcript = context.transcript();
        JsonNode format = outputFormat();
        long timeoutMillis = cmd.timeoutSeconds().orElse(30) * 1000L;
        String response;
        try {
            List<CreateMessageRequest.RequestMessage> requestMessages =
                transcriptMessages(transcript, model, TRANSCRIPT_FRACTION);
            response = query(sideQuery, model, condition, requestMessages, format, timeoutMillis);
        } catch (PromptTooLongException _) {
            List<CreateMessageRequest.RequestMessage> retryMessages =
                transcriptMessages(transcript, model, TRANSCRIPT_FRACTION / 2);
            LOG.debug("Stop PromptHook: prompt too long; retrying with {} messages",
                retryMessages.size());
            try {
                response = query(sideQuery, model, condition, retryMessages, format, timeoutMillis);
            } catch (ApiException apiError) {
                enqueueError(cmd, input, toolUseId, startedAt,
                    "Hook evaluator API error: " + apiError.getMessage(), "");
                return HookResult.skip();
            } catch (RuntimeException failure) {
                enqueueError(cmd, input, toolUseId, startedAt,
                    "Error executing prompt hook: " + failure.getMessage(), "");
                return HookResult.skip();
            }
        } catch (ApiException apiError) {
            enqueueError(cmd, input, toolUseId, startedAt,
                "Hook evaluator API error: " + apiError.getMessage(), "");
            return HookResult.skip();
        } catch (RuntimeException failure) {
            enqueueError(cmd, input, toolUseId, startedAt,
                "Error executing prompt hook: " + failure.getMessage(), "");
            return HookResult.skip();
        }

        if (StringUtils.isBlank(response)) {
            enqueueError(cmd, input, toolUseId, startedAt,
                "JSON validation failed", response == null ? "" : response);
            return HookResult.skip();
        }
        JsonNode result = parser.readJson(response);
        if (result == null) {
            enqueueError(cmd, input, toolUseId, startedAt, "JSON validation failed", response);
            return HookResult.skip();
        }
        String schemaFailure = schemaFailure(result);
        if (schemaFailure != null) {
            enqueueError(cmd, input, toolUseId, startedAt,
                "Schema validation failed: " + schemaFailure, response);
            return HookResult.skip();
        }
        String reason = result.path("reason").asText();
        LOG.info("[goal] evaluator response ok={} impossible={} reason=\"{}\" durationMs={}",
            result.path("ok").asBoolean(),
            result.path("impossible").asBoolean(false),
            reason,
            System.currentTimeMillis() - startedAt);
        if (result.path("ok").asBoolean()) {
            return new HookResult.ConditionMet(reason);
        }
        if (impossibleIsTerminal && result.path("impossible").asBoolean(false)) {
            return new HookResult.ConditionImpossible(reason);
        }
        // The evaluator sees the resolved prompt plus ARGUMENTS, but blocking
        // feedback and /goal state keep the user-authored condition itself.
        return new HookResult.ConditionNotMet(cmd.prompt(), reason);
    }

    private static JsonNode outputFormat() {
        var schema = JsonUtils.getMapper().createObjectNode();
        schema.put("type", "object");
        var properties = schema.putObject("properties");
        properties.putObject("ok").put("type", "boolean");
        properties.putObject("reason").put("type", "string");
        properties.putObject("impossible").put("type", "boolean");
        schema.putArray("required").add("ok").add("reason");
        schema.put("additionalProperties", false);
        var format = JsonUtils.getMapper().createObjectNode();
        format.put("type", "json_schema");
        format.set("schema", schema);
        return format;
    }

    private static String schemaFailure(JsonNode result) {
        if (!result.isObject()) return "expected an object";
        if (!result.path("ok").isBoolean()) return "ok must be a boolean";
        if (!result.path("reason").isTextual()) return "reason must be a string";
        if (result.has("impossible") && !result.path("impossible").isBoolean()) {
            return "impossible must be a boolean";
        }
        return null;
    }

    private void enqueueError(PromptHook cmd, HookInput input, String toolUseId, long startedAt,
                              String stderr, String stdout) {
        effects.nonBlockingError(HookOutcomes.hookEventName(input), stderr, stdout, toolUseId,
            input.event().displayName(), cmd.prompt(),
            Math.max(0L, System.currentTimeMillis() - startedAt));
        if (StringUtils.isNotBlank(stderr)) {
            LOG.warn("Stop PromptHook execution failed: {}", stderr);
        }
    }

    private String query(SideQuery sideQuery, String model, String condition,
                         List<CreateMessageRequest.RequestMessage> transcript,
                         JsonNode format, long timeoutMillis) {
        List<CreateMessageRequest.RequestMessage> messages = new ArrayList<>(transcript);
        messages.add(new CreateMessageRequest.RequestMessage("user",
            "Based on the conversation transcript above, has the following stopping "
                + "condition been satisfied? Answer based on transcript evidence only.\n\n"
                + "Condition: " + condition));
        String identity = goal.identity();
        String systemPrompt = StringUtils.isBlank(identity)
            ? SYSTEM_PROMPT
            : identity + "\n\n" + SYSTEM_PROMPT;
        return sideQuery.queryTextOrThrow(new SideQuery.Request()
            .model(model)
            .systemPrompt(systemPrompt)
            .messages(messages)
            .maxTokens(Math.toIntExact(ModelOutputTokens.getMaxOutputTokensForModel(model)))
            // tier: ONE_SHOT (see promptCachingEnabled below)
            .timeoutMillis(timeoutMillis)
            .thinking(CreateMessageRequest.ThinkingConfig.disabled())
            .outputConfig(new CreateMessageRequest.OutputConfig(currentEffort(), format))
            .metadata(goal.metadata())
            .tools(goal.tools())
            .temperature(1.0)
            // One-shot goal-condition evaluation over a frozen transcript;
            // the prefix is never replayed, so the cache marker is pure premium.
            .promptCachingEnabled(false)
            .streaming(true)
            .querySource("hook_prompt"));
    }

    private List<CreateMessageRequest.RequestMessage> transcriptMessages(
            List<Message> messages, String model, double fraction) {
        if (messages == null || messages.isEmpty()) return List.of();
        long budget = (long) Math.floor(goal.contextWindow(model) * fraction);
        List<Message> retained = truncate(messages, model, budget);
        List<StreamingClient.StreamRequest.RequestMessage> wire = new ArrayList<>();
        int omitted = messages.size() - retained.size();
        if (omitted > 0) {
            wire.add(new StreamingClient.StreamRequest.RequestMessage("user",
                "[Earlier conversation truncated to fit the hook evaluator's context window — "
                    + omitted + " earlier messages omitted. Evaluate the condition against the "
                    + "recent transcript below; if the required evidence may be in the omitted "
                    + "prefix, return {\"ok\": false, \"reason\": \"insufficient evidence in "
                    + "transcript\"}.]"));
            LOG.debug("Hooks: truncated Stop transcript {}→{} messages (budget {}, model {})",
                messages.size(), retained.size(), budget, model);
        }
        wire.addAll(ApiMessageFormatter.toRequestMessages(retained, false));
        List<StreamingClient.StreamRequest.RequestMessage> normalized =
            RequestMessageNormalizer.mergeConsecutiveRequestMessages(wire);
        List<CreateMessageRequest.RequestMessage> result = new ArrayList<>();
        for (StreamingClient.StreamRequest.RequestMessage message : normalized) {
            if (!Strings.CS.equals("user", message.role()) && !Strings.CS.equals("assistant", message.role())) continue;
            result.add(new CreateMessageRequest.RequestMessage(message.role(), message.content()));
        }
        return List.copyOf(result);
    }

    private static List<Message> truncate(List<Message> messages, String model, long budget) {
        if (latestAssistantUsage(messages, model) <= budget) return List.copyOf(messages);
        List<List<Message>> groups = groupAssistantResponses(messages);
        long tokens = 0L;
        int start = groups.size();
        for (int i = groups.size() - 1; i >= 0; i--) {
            long groupTokens = estimateGroupTokens(groups.get(i));
            if (start < groups.size() && tokens + groupTokens > budget) break;
            tokens += groupTokens;
            start = i;
        }
        List<Message> retained = new ArrayList<>();
        for (int i = start; i < groups.size(); i++) retained.addAll(groups.get(i));
        return List.copyOf(retained);
    }

    private static long latestAssistantUsage(List<Message> messages, String model) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof AssistantMessage assistant
                    && assistant.message() != null && assistant.message().usage() != null) {
                Usage usage = assistant.message().usage();
                return TokenEstimator.contextTokens(usage, model);
            }
        }
        return 0L;
    }

    private static List<List<Message>> groupAssistantResponses(List<Message> messages) {
        List<List<Message>> groups = new ArrayList<>();
        List<Message> current = new ArrayList<>();
        String previousAssistantId = null;
        for (Message message : messages) {
            if (message instanceof AssistantMessage assistant) {
                String id = assistant.message() != null ? assistant.message().id() : null;
                if (!Objects.equals(id, previousAssistantId) && !current.isEmpty()) {
                    groups.add(List.copyOf(current));
                    current.clear();
                }
                previousAssistantId = id;
            }
            current.add(message);
        }
        if (!current.isEmpty()) groups.add(List.copyOf(current));
        return List.copyOf(groups);
    }

    private static long estimateGroupTokens(List<Message> group) {
        TokenEstimator estimator = TokenEstimator.getInstance();
        long characters = 0L;
        for (Message message : group) {
            long messageChars = estimator.estimateMessageChars(message);
            if (messageChars == 0L) {
                try {
                    messageChars = JsonUtils.getMapper().writeValueAsString(message).length();
                } catch (Exception _) {
                    messageChars = String.valueOf(message).length();
                }
            }
            characters += messageChars;
        }
        return Math.max(1L, Math.round(characters / 4.0));
    }
}
