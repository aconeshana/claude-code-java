package com.claudecode.api;

import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.model.ModelApiProtocol;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

/**
 * Adaptive protocol and parameter fallback rules when an upstream endpoint rejects
 * the entry protocol ({@code WrongEndpoint} / {@code APIRefused}), structured output
 * format ({@code refusesFormat}), or {@code thinking: {type: "disabled"}} ({@code refusesThinkingOff}).
 *
 * <p>Replicated from {@code yetone/magpie} ({@code catalog.go:WrongEndpoint},
 * {@code format.go:refusesFormat}, and {@code anthropic.go:refusesThinkingOff}).
 */
@Explanation("Adaptive protocol wrong-endpoint, structured-format, and thinking-disabled fallback")
final class ProtocolFallbackPolicy {

    private static final List<String> WRONG_ENDPOINT_PHRASES = List.of(
        "not a chat model",
        "not supported in the v1/chat/completions",
        "not supported in /v1/chat/completions",
        "not supported in the v1/responses",
        "not supported in /v1/responses",
        "only supported in v1/responses",
        "only supported in /v1/responses",
        "use v1/completions",
        "use /v1/completions",
        "use v1/responses",
        "use /v1/responses",
        "use v1/chat/completions",
        "use /v1/chat/completions",
        "not accessible via the",
        "unsupported_api_for_model",
        "no model endpoints available given user constraints",
        "is not supported for format",
        "does not support this protocol",
        "requires responses",
        "不支持 /v1/chat/completions 接口",
        "改用 /v1/responses",
        "入口协议",
        "does not allow /v1/messages",
        "does not allow /v1/chat/completions",
        "does not allow /v1/responses"
    );

    private static final Pattern FORMAT_REFUSAL = Pattern.compile(
        "(?i)response_format|json_schema|json_object|output_config\\.format|text\\.format"
            + "|structured.output|response_?mime_?type|response_?json_?schema|response_?schema");

    private static final Pattern THINKING_OFF_REFUSAL = Pattern.compile(
        "(?i)(?:thinking|reasoning).*(?:cannot be disabled|must be enabled|disabled.*not supported"
            + "|not support.*disabled|invalid.*disabled)");

    private ProtocolFallbackPolicy() {}

    enum FormatMode {
        NATIVE,
        JSON_OBJECT_WITH_SYSTEM_SCHEMA,
        SYSTEM_PROMPT_ONLY
    }

    static boolean isWrongEndpoint(int status, String errorBody) {
        if (status < 400 || status >= 500 || StringUtils.isBlank(errorBody)) {
            return false;
        }
        String lower = unescapeJsonWords(errorBody).toLowerCase(Locale.ROOT);
        for (String phrase : WRONG_ENDPOINT_PHRASES) {
            if (Strings.CS.contains(lower, phrase)) {
                return true;
            }
        }
        return false;
    }

    static boolean isWrongEndpoint(ApiException failure) {
        return failure != null && isWrongEndpoint(failure.statusCode(), failure.getMessage());
    }

    /**
     * Ordered candidate protocols to retry when {@code current} returned a wrong-endpoint 4xx.
     * Prioritizes any protocol explicitly named in the upstream error message.
     */
    static List<ModelApiProtocol> candidateFallbackProtocols(ModelApiProtocol current, String errorMessage) {
        String lower = errorMessage == null ? "" : unescapeJsonWords(errorMessage).toLowerCase(Locale.ROOT);
        List<ModelApiProtocol> ordered = new ArrayList<>(3);
        if (Strings.CS.contains(lower, "/responses") || Strings.CS.contains(lower, "requires responses")) {
            addIfDifferent(ordered, current, ModelApiProtocol.OPENAI_RESPONSES);
        }
        if (Strings.CS.contains(lower, "chat/completions")) {
            addIfDifferent(ordered, current, ModelApiProtocol.OPENAI_CHAT);
        }
        if (Strings.CS.contains(lower, "/messages")) {
            addIfDifferent(ordered, current, ModelApiProtocol.ANTHROPIC);
        }
        for (ModelApiProtocol p : List.of(
                ModelApiProtocol.OPENAI_RESPONSES,
                ModelApiProtocol.OPENAI_CHAT,
                ModelApiProtocol.ANTHROPIC)) {
            addIfDifferent(ordered, current, p);
        }
        return List.copyOf(ordered);
    }

    private static void addIfDifferent(
            List<ModelApiProtocol> list, ModelApiProtocol current, ModelApiProtocol candidate) {
        if (candidate != current && !list.contains(candidate)) {
            list.add(candidate);
        }
    }

    static boolean isFormatRefusal(int status, String body) {
        if ((status != 400 && status != 422) || StringUtils.isBlank(body)) return false;
        return FORMAT_REFUSAL.matcher(body).find();
    }

    static boolean isFormatRefusal(ApiException failure) {
        return failure != null && isFormatRefusal(failure.statusCode(), failure.getMessage());
    }

    static boolean isThinkingOffRefusal(ApiException failure, CreateMessageRequest request) {
        if (failure == null || (failure.statusCode() != 400 && failure.statusCode() != 422)) {
            return false;
        }
        if (request == null || request.thinking() == null
                || !Strings.CS.equals("disabled", request.thinking().type())) {
            return false;
        }
        String msg = failure.getMessage();
        return StringUtils.isNotBlank(msg) && THINKING_OFF_REFUSAL.matcher(msg).find();
    }

    static boolean hasStructuredFormat(CreateMessageRequest request) {
        return request != null
            && request.outputConfig() != null
            && request.outputConfig().format() != null
            && !request.outputConfig().format().isNull();
    }

    static FormatMode nextFormatFallback(
            ModelApiProtocol protocol, CreateMessageRequest request, FormatMode current) {
        if (!hasStructuredFormat(request)) return null;
        JsonNode fmt = request.outputConfig().format();
        boolean isSchema = Strings.CS.equals("json_schema", fmt.path("type").asText(""));
        if (current == FormatMode.NATIVE) {
            if (isSchema && (protocol == ModelApiProtocol.OPENAI_CHAT
                    || protocol == ModelApiProtocol.OPENAI_RESPONSES)) {
                return FormatMode.JSON_OBJECT_WITH_SYSTEM_SCHEMA;
            }
            return FormatMode.SYSTEM_PROMPT_ONLY;
        }
        if (current == FormatMode.JSON_OBJECT_WITH_SYSTEM_SCHEMA) {
            return FormatMode.SYSTEM_PROMPT_ONLY;
        }
        return null;
    }

    static CreateMessageRequest applyFormatMode(CreateMessageRequest request, FormatMode mode) {
        if (mode == null || mode == FormatMode.NATIVE || !hasStructuredFormat(request)) {
            return request;
        }
        JsonNode fmt = request.outputConfig().format();
        String instruction = formatInstruction(fmt);
        String combinedSystem = StringUtils.isBlank(request.systemPrompt())
            ? instruction
            : request.systemPrompt() + "\n\n" + instruction;

        CreateMessageRequest.OutputConfig prevOut = request.outputConfig();
        CreateMessageRequest.OutputConfig newOut;
        if (mode == FormatMode.JSON_OBJECT_WITH_SYSTEM_SCHEMA) {
            ObjectNode jsonObjFmt = JsonUtils.getMapper().createObjectNode().put("type", "json_object");
            newOut = new CreateMessageRequest.OutputConfig(prevOut.effort(), jsonObjFmt, prevOut.taskBudget());
        } else {
            newOut = (prevOut.effort() == null && prevOut.taskBudget() == null)
                ? null
                : new CreateMessageRequest.OutputConfig(prevOut.effort(), null, prevOut.taskBudget());
        }
        return rebuildRequest(request, combinedSystem, newOut, request.thinking());
    }

    static CreateMessageRequest withoutDisabledThinking(CreateMessageRequest request) {
        if (request == null || request.thinking() == null) return request;
        return rebuildRequest(request, request.systemPrompt(), request.outputConfig(), null);
    }

    static String formatInstruction(JsonNode fmt) {
        if (fmt == null || fmt.isNull()) return "";
        JsonNode schema = fmt.path("schema");
        if (schema.isMissingNode() || schema.isNull()) {
            schema = fmt.path("json_schema").path("schema");
        }
        if (schema.isObject()) {
            return "Respond with a single JSON object matching this JSON schema and nothing else:\n"
                + schema;
        }
        return "Respond with a single valid JSON object and nothing else.";
    }

    private static CreateMessageRequest rebuildRequest(
            CreateMessageRequest request,
            String systemPrompt,
            CreateMessageRequest.OutputConfig outputConfig,
            CreateMessageRequest.ThinkingConfig thinking) {
        return CreateMessageRequest.builder()
            .model(request.model())
            .maxTokens(request.maxTokens())
            .systemPrompt(systemPrompt)
            .messages(request.messages())
            .tools(request.tools())
            .metadata(request.metadata())
            .stopSequences(request.stopSequences())
            .stream(request.stream())
            .temperature(request.temperature())
            .topP(request.topP())
            .topK(request.topK())
            .thinking(thinking)
            .effort(request.effort())
            .toolChoice(request.toolChoice())
            .outputConfig(outputConfig)
            .speed(request.speed())
            .contextManagement(request.contextManagement())
            .skipCacheWrite(request.skipCacheWrite())
            .promptCachingEnabled(request.promptCachingEnabled())
            .promptCacheTtl(request.promptCacheTtl())
            .querySource(request.querySource())
            .cancellationRegistrar(request.cancellationRegistrar())
            .subagent(request.subagent())
            .streamConnectTimeout(request.streamConnectTimeout())
            .build();
    }

    private static String unescapeJsonWords(String raw) {
        return raw.replace("\\/", "/").replace("\\\"", "\"");
    }
}
