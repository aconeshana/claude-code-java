package com.claudecode.api;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.model.CustomModelConfig;
import com.claudecode.core.model.ModelNames;

import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.Locale;

/**
 * Routes each request to the protocol client configured for its model name.
 *
 * Supports user-defined Anthropic, Chat Completions, and Responses endpoints.
 * An effort-specific 400/422 retries once without effort and caches that
 * endpoint decision. Tagged model IDs retain their client-side context
 * semantics while resolving the base custom endpoint.
 */
@Explanation("Routes requests to user-defined Anthropic, Chat, or Responses clients")
public final class CustomModelRoutingClient implements LlmClient {
    private final LlmClient fallback;
    private final Function<String, Optional<CustomModelConfig>> resolver;
    private final Function<CustomModelConfig, LlmClient> clientFactory;
    private final ConcurrentHashMap<CustomModelConfig, LlmClient> clients = new ConcurrentHashMap<>();
    private final Set<CustomModelConfig> effortUnsupported = ConcurrentHashMap.newKeySet();

    public CustomModelRoutingClient(
            LlmClient fallback,
            Function<String, Optional<CustomModelConfig>> resolver,
            Function<CustomModelConfig, LlmClient> clientFactory) {
        this.fallback = fallback;
        this.resolver = resolver;
        this.clientFactory = clientFactory;
    }

    public static CustomModelRoutingClient standard(
            LlmClient fallback,
            Function<String, Optional<CustomModelConfig>> resolver) {
        return new CustomModelRoutingClient(fallback, resolver, CustomModelRoutingClient::createClient);
    }

    private static LlmClient createClient(CustomModelConfig model) {
        return switch (model.protocol()) {
            case ANTHROPIC -> new AnthropicSdkClient(new ApiConfig.AnthropicConfig(
                model.apiKey(), null, model.modelName(), model.baseUrl(), model.headers()));
            case OPENAI_CHAT, OPENAI_RESPONSES -> LlmClientFactory.create(new ApiConfig(
                ApiConfig.ApiProvider.OPENAI_COMPAT, null,
                new ApiConfig.OpenAiConfig(model.apiKey(), model.modelName(), model.baseUrl(),
                    model.protocol(), model.headers()), null, null));
        };
    }

    private Route route(CreateMessageRequest request) {
        String model = request.model() != null ? request.model() : fallback.getModel();
        Optional<CustomModelConfig> custom = resolveCustomModel(model);
        if (custom.isEmpty()) return new Route(null, fallback);
        CustomModelConfig config = custom.get();
        return new Route(config, clients.computeIfAbsent(config, clientFactory));
    }

    @Override
    public Iterator<StreamEvent> createMessageStream(CreateMessageRequest request) {
        return createMessageStream(request, null);
    }

    @Override
    public Iterator<StreamEvent> createMessageStream(CreateMessageRequest request, Runnable onRequestSubmitted) {
        Route route = route(request);
        CreateMessageRequest effective = effectiveRequest(route.config(), request);
        try {
            return route.client().createMessageStream(effective, onRequestSubmitted);
        } catch (ApiException failure) {
            if (!learnUnsupportedEffort(route.config(), effective, failure)) throw failure;
            return route.client().createMessageStream(withoutEffort(effective), null);
        }
    }

    @Override
    public ApiMessage createMessage(CreateMessageRequest request) {
        Route route = route(request);
        CreateMessageRequest effective = effectiveRequest(route.config(), request);
        try {
            return route.client().createMessage(effective);
        } catch (ApiException failure) {
            if (!learnUnsupportedEffort(route.config(), effective, failure)) throw failure;
            return route.client().createMessage(withoutEffort(effective));
        }
    }

    @Override
    public ApiMessage createMessage(CreateMessageRequest request, long timeoutMillis) {
        Route route = route(request);
        CreateMessageRequest effective = effectiveRequest(route.config(), request);
        try {
            return route.client().createMessage(effective, timeoutMillis);
        } catch (ApiException failure) {
            if (!learnUnsupportedEffort(route.config(), effective, failure)) throw failure;
            return route.client().createMessage(withoutEffort(effective), timeoutMillis);
        }
    }

    @Override
    public long countTokens(String model, List<CreateMessageRequest.RequestMessage> messages,
                            List<CreateMessageRequest.ToolDefinition> tools) {
        LlmClient client = resolveCustomModel(model)
            .map(config -> clients.computeIfAbsent(config, clientFactory))
            .orElse(fallback);
        return client.countTokens(model, messages, tools);
    }

    @Override
    public long countTokensFallback(String model,
                                    List<CreateMessageRequest.RequestMessage> messages,
                                    List<CreateMessageRequest.ToolDefinition> tools,
                                    String sessionId) {
        LlmClient client = resolveCustomModel(model)
            .map(config -> clients.computeIfAbsent(config, clientFactory))
            .orElse(fallback);
        return client.countTokensFallback(model, messages, tools, sessionId);
    }

    @Override
    public String getModel() {
        return fallback.getModel();
    }

    private Optional<CustomModelConfig> resolveCustomModel(String model) {
        if (model == null) return Optional.empty();
        Optional<CustomModelConfig> exact = resolver.apply(model);
        if (exact.isPresent()) return exact;
        String normalized = ModelNames.normalizeModelStringForApi(model);
        return Strings.CS.equals(model, normalized) ? Optional.empty() : resolver.apply(normalized);
    }

    private CreateMessageRequest effectiveRequest(
            CustomModelConfig config, CreateMessageRequest request) {
        return config != null && effortUnsupported.contains(config)
            ? withoutEffort(request) : request;
    }

    private boolean learnUnsupportedEffort(
            CustomModelConfig config, CreateMessageRequest request, ApiException failure) {
        if (config == null || !hasEffort(request) || !isEffortParameterError(failure)) return false;
        effortUnsupported.add(config);
        return true;
    }

    static boolean isEffortParameterError(ApiException failure) {
        if (failure == null || (failure.statusCode() != 400 && failure.statusCode() != 422)) {
            return false;
        }
        String message = failure.getMessage() == null
            ? "" : failure.getMessage().toLowerCase(Locale.ROOT);
        boolean namesEffort = Strings.CS.contains(message, "reasoning_effort")
            || Strings.CS.contains(message, "reasoning.effort")
            || Strings.CS.contains(message, "output_config.effort")
            || Strings.CS.contains(message, "effort parameter")
            || Strings.CS.contains(message, "effort level");
        boolean rejectsParameter = Strings.CS.contains(message, "invalid")
            || Strings.CS.contains(message, "unsupported")
            || Strings.CS.contains(message, "not support")
            || Strings.CS.contains(message, "unknown")
            || Strings.CS.contains(message, "unrecognized")
            || Strings.CS.contains(message, "not permitted");
        return namesEffort && rejectsParameter;
    }

    private static boolean hasEffort(CreateMessageRequest request) {
        return request != null && ((StringUtils.isNotBlank(request.effort()))
            || (request.outputConfig() != null
                && request.outputConfig().effort() != null
                && !StringUtils.isBlank(request.outputConfig().effort())));
    }

    private static CreateMessageRequest withoutEffort(CreateMessageRequest request) {
        CreateMessageRequest.OutputConfig output = request.outputConfig();
        CreateMessageRequest.OutputConfig retainedOutput = output == null ? null
            : new CreateMessageRequest.OutputConfig(null, output.format(), output.taskBudget());
        return CreateMessageRequest.builder()
            .model(request.model())
            .maxTokens(request.maxTokens())
            .systemPrompt(request.systemPrompt())
            .messages(request.messages())
            .tools(request.tools())
            .metadata(request.metadata())
            .stopSequences(request.stopSequences())
            .stream(request.stream())
            .temperature(request.temperature())
            .topP(request.topP())
            .topK(request.topK())
            .thinking(request.thinking())
            .toolChoice(request.toolChoice())
            .outputConfig(retainedOutput)
            .contextManagement(request.contextManagement())
            .skipCacheWrite(request.skipCacheWrite())
            .promptCachingEnabled(request.promptCachingEnabled())
            .querySource(request.querySource())
            .cancellationRegistrar(request.cancellationRegistrar())
            .subagent(request.subagent())
            .build();
    }

    private record Route(CustomModelConfig config, LlmClient client) {}
}
