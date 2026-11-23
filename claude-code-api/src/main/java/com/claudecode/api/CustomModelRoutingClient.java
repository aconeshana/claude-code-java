package com.claudecode.api;

import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.model.CustomModelConfig;
import com.claudecode.core.model.ModelApiProtocol;
import com.claudecode.core.model.ModelNames;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

/**
 * Routes each request to the protocol client configured for its model name.
 * <p>
 * Supports user-defined Anthropic, Chat Completions, Responses, and Google Gemini
 * endpoints. Learns and caches adaptive fallbacks per endpoint when an upstream
 * rejects {@code effort}, {@code thinking: disabled}, structured output {@code format},
 * or the configured entry protocol ({@code WrongEndpoint}).
 */
@Explanation("Routes requests to user-defined Anthropic, Chat, Responses, or Gemini clients with adaptive fallback")
public final class CustomModelRoutingClient implements LlmClient {
    private final LlmClient fallback;
    private final Function<String, Optional<CustomModelConfig>> resolver;
    private final Function<CustomModelConfig, LlmClient> clientFactory;
    private final Supplier<String> imageModelName;
    private final ConcurrentHashMap<CustomModelConfig, LlmClient> clients = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<CustomModelConfig, ModelApiProtocol> learnedProtocol =
        new ConcurrentHashMap<>();
    private final Set<CustomModelConfig> effortUnsupported = ConcurrentHashMap.newKeySet();
    private final Set<CustomModelConfig> thinkingOffUnsupported = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<CustomModelConfig, ProtocolFallbackPolicy.FormatMode> learnedFormatMode =
        new ConcurrentHashMap<>();
    private final ImageCaptionCache captionCache = new ImageCaptionCache();

    public CustomModelRoutingClient(
            LlmClient fallback,
            Function<String, Optional<CustomModelConfig>> resolver,
            Function<CustomModelConfig, LlmClient> clientFactory) {
        this(fallback, resolver, clientFactory, () -> null);
    }

    public CustomModelRoutingClient(
            LlmClient fallback,
            Function<String, Optional<CustomModelConfig>> resolver,
            Function<CustomModelConfig, LlmClient> clientFactory,
            Supplier<String> imageModelName) {
        this.fallback = fallback;
        this.resolver = resolver;
        this.clientFactory = clientFactory;
        this.imageModelName = imageModelName != null ? imageModelName : () -> null;
    }

    public static CustomModelRoutingClient standard(
            LlmClient fallback,
            Function<String, Optional<CustomModelConfig>> resolver) {
        return new CustomModelRoutingClient(
            fallback, resolver, CustomModelRoutingClient::createClient, () -> null);
    }

    public static CustomModelRoutingClient standard(
            LlmClient fallback,
            Function<String, Optional<CustomModelConfig>> resolver,
            Supplier<String> imageModelName) {
        return new CustomModelRoutingClient(
            fallback, resolver, CustomModelRoutingClient::createClient, imageModelName);
    }

    private static LlmClient createClient(CustomModelConfig model) {
        return switch (model.protocol()) {
            case ANTHROPIC -> new AnthropicSdkClient(new ApiConfig.AnthropicConfig(
                model.apiKey(), null, model.modelName(), model.baseUrl(), model.headers()));
            case OPENAI_CHAT, OPENAI_RESPONSES, GEMINI -> LlmClientFactory.create(new ApiConfig(
                ApiConfig.ApiProvider.OPENAI_COMPAT, null,
                new ApiConfig.OpenAiConfig(model.apiKey(), model.modelName(), model.baseUrl(),
                    model.protocol(), model.headers()), null, null));
        };
    }

    private Route route(CreateMessageRequest request) {
        String model = request.model() != null ? request.model() : fallback.getModel();
        Optional<CustomModelConfig> custom = resolveCustomModel(model);
        if (custom.isEmpty()) return new Route(null, null, fallback);
        CustomModelConfig baseConfig = custom.get();
        ModelApiProtocol effectiveProtocol = learnedProtocol.getOrDefault(baseConfig, baseConfig.protocol());
        CustomModelConfig effectiveConfig = effectiveProtocol == baseConfig.protocol()
            ? baseConfig
            : withProtocol(baseConfig, effectiveProtocol);
        return new Route(baseConfig, effectiveConfig, clients.computeIfAbsent(effectiveConfig, clientFactory));
    }

    private static CustomModelConfig withProtocol(CustomModelConfig base, ModelApiProtocol protocol) {
        return new CustomModelConfig(
            base.modelName(),
            protocol,
            base.baseUrl(),
            base.apiKey(),
            base.headers(),
            base.contextWindow(),
            base.multimodal());
    }

    @Override
    public Iterator<StreamEvent> createMessageStream(CreateMessageRequest request) {
        return createMessageStream(request, null);
    }

    @Override
    public Iterator<StreamEvent> createMessageStream(
            CreateMessageRequest request, Runnable onRequestSubmitted) {
        return executeWithAdaptiveFallback(
            request,
            (client, effective, firstTry) ->
                client.createMessageStream(effective, firstTry ? onRequestSubmitted : null));
    }

    @Override
    public ApiMessage createMessage(CreateMessageRequest request) {
        return executeWithAdaptiveFallback(request, (client, effective, _) -> client.createMessage(effective));
    }

    @Override
    public ApiMessage createMessage(CreateMessageRequest request, long timeoutMillis) {
        return executeWithAdaptiveFallback(
            request, (client, effective, _) -> client.createMessage(effective, timeoutMillis));
    }

    @FunctionalInterface
    private interface ClientCall<T> {
        T invoke(LlmClient client, CreateMessageRequest request, boolean firstAttempt);
    }

    private <T> T executeWithAdaptiveFallback(CreateMessageRequest original, ClientCall<T> call) {
        Route route = route(original);
        CreateMessageRequest current = effectiveRequest(route, original);
        boolean firstAttempt = true;

        for (int attempt = 0; attempt < 4; attempt++) {
            try {
                return call.invoke(route.client(), current, firstAttempt);
            } catch (ApiException failure) {
                firstAttempt = false;
                CustomModelConfig key = route.baseConfig();
                if (key == null) throw failure;

                // 1. Wrong-endpoint protocol fallback (e.g. /v1/messages or /v1/chat/completions -> /v1/responses)
                if (ProtocolFallbackPolicy.isWrongEndpoint(failure)) {
                    T recovered = retryAcrossProtocols(key, route.effectiveConfig().protocol(),
                        failure, original, call);
                    if (recovered != null) return recovered;
                }

                // 2. Effort / thinkingConfig rejection retry
                if (learnUnsupportedEffort(key, current, failure)) {
                    current = withoutEffort(current);
                    continue;
                }

                // 3. thinking: {type: "disabled"} rejection retry
                if (ProtocolFallbackPolicy.isThinkingOffRefusal(failure, current)) {
                    thinkingOffUnsupported.add(key);
                    current = ProtocolFallbackPolicy.withoutDisabledThinking(current);
                    continue;
                }

                // 4. Structured output format degradation (json_schema -> json_object -> system prompt)
                if (ProtocolFallbackPolicy.isFormatRefusal(failure)
                        && ProtocolFallbackPolicy.hasStructuredFormat(original)) {
                    ProtocolFallbackPolicy.FormatMode prevMode = learnedFormatMode.getOrDefault(
                        key, ProtocolFallbackPolicy.FormatMode.NATIVE);
                    ProtocolFallbackPolicy.FormatMode nextMode = ProtocolFallbackPolicy.nextFormatFallback(
                        route.effectiveConfig().protocol(), original, prevMode);
                    if (nextMode != null) {
                        learnedFormatMode.put(key, nextMode);
                        current = effectiveRequest(route, original);
                        continue;
                    }
                }
                throw failure;
            }
        }
        return call.invoke(route.client(), current, false);
    }

    private <T> T retryAcrossProtocols(
            CustomModelConfig baseConfig,
            ModelApiProtocol failedProtocol,
            ApiException initialFailure,
            CreateMessageRequest original,
            ClientCall<T> call) {
        ApiException lastFailure = initialFailure;
        for (ModelApiProtocol candidate :
                ProtocolFallbackPolicy.candidateFallbackProtocols(failedProtocol, lastFailure.getMessage())) {
            CustomModelConfig altConfig = withProtocol(baseConfig, candidate);
            LlmClient altClient = clients.computeIfAbsent(altConfig, clientFactory);
            Route altRoute = new Route(baseConfig, altConfig, altClient);
            try {
                T result = call.invoke(altClient, effectiveRequest(altRoute, original), false);
                learnedProtocol.put(baseConfig, candidate);
                return result;
            } catch (ApiException nextFailure) {
                lastFailure = nextFailure;
                if (!ProtocolFallbackPolicy.isWrongEndpoint(nextFailure)) {
                    throw nextFailure;
                }
            }
        }
        throw lastFailure;
    }

    private CreateMessageRequest effectiveRequest(Route route, CreateMessageRequest request) {
        CreateMessageRequest current = routeImagesFor(route, request);
        CustomModelConfig key = route.baseConfig();
        if (key == null) return current;
        if (effortUnsupported.contains(key)) {
            current = withoutEffort(current);
        }
        if (thinkingOffUnsupported.contains(key)
                && current.thinking() != null
                && Strings.CS.equals("disabled", current.thinking().type())) {
            current = ProtocolFallbackPolicy.withoutDisabledThinking(current);
        }
        ProtocolFallbackPolicy.FormatMode fmtMode = learnedFormatMode.get(key);
        if (fmtMode != null && fmtMode != ProtocolFallbackPolicy.FormatMode.NATIVE) {
            current = ProtocolFallbackPolicy.applyFormatMode(current, fmtMode);
        }
        return current;
    }

    private CreateMessageRequest routeImagesFor(Route route, CreateMessageRequest request) {
        if (route.baseConfig() == null || !route.baseConfig().isTextOnly()) return request;
        String imageModel;
        try {
            imageModel = imageModelName.get();
        } catch (RuntimeException _) {
            return request;
        }
        if (StringUtils.isBlank(imageModel)) return request;
        List<CreateMessageRequest.RequestMessage> rewritten = ImageContentRouter.routeImages(
            request.messages(), false, resolveImageEndpoint(imageModel), imageModel, captionCache);
        if (rewritten == request.messages()) return request;
        return CreateMessageRequest.builder()
            .model(request.model())
            .maxTokens(request.maxTokens())
            .systemPrompt(request.systemPrompt())
            .messages(rewritten)
            .tools(request.tools())
            .metadata(request.metadata())
            .stopSequences(request.stopSequences())
            .stream(request.stream())
            .temperature(request.temperature())
            .topP(request.topP())
            .topK(request.topK())
            .thinking(request.thinking())
            .effort(request.effort())
            .toolChoice(request.toolChoice())
            .outputConfig(request.outputConfig())
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

    private ImageContentRouter.ImageEndpoint resolveImageEndpoint(String imageModel) {
        Optional<CustomModelConfig> custom = resolveCustomModel(imageModel);
        if (custom.isEmpty()) {
            return new ImageContentRouter.ImageEndpoint(fallback, true);
        }
        CustomModelConfig config = custom.get();
        return new ImageContentRouter.ImageEndpoint(
            clients.computeIfAbsent(config, clientFactory), config.acceptsImages());
    }

    @Override
    public long countTokens(
            String model,
            List<CreateMessageRequest.RequestMessage> messages,
            List<CreateMessageRequest.ToolDefinition> tools) {
        LlmClient client = resolveCustomModel(model)
            .map(config -> clients.computeIfAbsent(
                withProtocol(config, learnedProtocol.getOrDefault(config, config.protocol())),
                clientFactory))
            .orElse(fallback);
        return client.countTokens(model, messages, tools);
    }

    @Override
    public long countTokensFallback(
            String model,
            List<CreateMessageRequest.RequestMessage> messages,
            List<CreateMessageRequest.ToolDefinition> tools,
            String sessionId) {
        LlmClient client = resolveCustomModel(model)
            .map(config -> clients.computeIfAbsent(
                withProtocol(config, learnedProtocol.getOrDefault(config, config.protocol())),
                clientFactory))
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
            || Strings.CS.contains(message, "effort level")
            || Strings.CS.contains(message, "thinkingconfig")
            || Strings.CS.contains(message, "thinking_config")
            || Strings.CS.contains(message, "thinkinglevel")
            || Strings.CS.contains(message, "thinking_level")
            || Strings.CS.contains(message, "thinkingbudget")
            || Strings.CS.contains(message, "thinking_budget");
        boolean rejectsParameter = Strings.CS.contains(message, "invalid")
            || Strings.CS.contains(message, "unsupported")
            || Strings.CS.contains(message, "not support")
            || Strings.CS.contains(message, "unknown")
            || Strings.CS.contains(message, "unrecognized")
            || Strings.CS.contains(message, "not permitted");
        return namesEffort && rejectsParameter;
    }

    private static boolean hasEffort(CreateMessageRequest request) {
        return request != null && (StringUtils.isNotBlank(request.effort())
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

    private record Route(CustomModelConfig baseConfig, CustomModelConfig effectiveConfig, LlmClient client) {}
}
