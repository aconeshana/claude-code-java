package com.claudecode.cli;

import com.claudecode.api.ApiException;
import com.claudecode.api.ApiMessage;
import com.claudecode.api.CreateMessageRequest;
import com.claudecode.api.CustomModelJsonStore;
import com.claudecode.api.CustomModelRoutingClient;
import com.claudecode.api.LlmClient;
import com.claudecode.api.StreamEvent;
import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.model.CustomModelCatalog;
import com.claudecode.core.model.CustomModelConfig;
import com.claudecode.core.model.ModelApiProtocol;
import com.claudecode.core.model.ModelContextWindows;
import com.claudecode.gateway.GatewayLanProxyPort;
import com.claudecode.services.config.RuntimeSettings;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;

/**
 * CLI composition-root adapter implementing {@link GatewayLanProxyPort} over the shared
 * {@link CustomModelCatalog} ({@code ~/.claude/model.json}) and, when explicitly opted in by the
 * user, the host's built-in default LLM client.
 */
@Explanation("Bridges ~/.claude/model.json and optional default host credentials to the LAN model proxy")
final class CliLanProxyAdapter implements GatewayLanProxyPort {

    private static final List<String> BUILT_IN_FAMILIES = List.of(
        "claude-sonnet-4-6",
        "claude-opus-4-6",
        "claude-haiku-4-5-20251001");

    private final CustomModelCatalog customCatalog;
    private final LlmClient customOnlyRoutingClient;
    private final LlmClient defaultProviderClient;
    private final String defaultModel;
    private final boolean defaultProviderAvailable;

    CliLanProxyAdapter(
            CustomModelCatalog customCatalog,
            LlmClient defaultProviderClient,
            String defaultModel,
            boolean defaultProviderAvailable) {
        this.customCatalog = customCatalog != null ? customCatalog : new CustomModelJsonStore();
        this.defaultProviderClient = defaultProviderClient;
        this.defaultModel = defaultModel;
        this.defaultProviderAvailable = defaultProviderAvailable && defaultProviderClient != null;
        this.customOnlyRoutingClient = CustomModelRoutingClient.standard(
            new UnconfiguredFallbackLlmClient(),
            this::resolveCustomConfigWithLoopGuard,
            RuntimeSettings::loadImageModelName);
    }

    static CliLanProxyAdapter catalogOnly(CustomModelCatalog catalog) {
        return new CliLanProxyAdapter(catalog, null, null, false);
    }

    @Override
    public List<SharedModel> listModels(boolean includeDefaultProvider) {
        List<SharedModel> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (CustomModelConfig m : customCatalog.list()) {
            if (seen.add(m.modelName())) {
                out.add(new SharedModel(
                    m.modelName(), m.protocol().configValue(), false, m.effectiveContextWindow()));
            }
        }
        if (includeDefaultProvider && defaultProviderAvailable) {
            if (StringUtils.isNotBlank(defaultModel) && seen.add(defaultModel.trim())) {
                String clean = defaultModel.trim();
                out.add(new SharedModel(
                    clean, ModelApiProtocol.ANTHROPIC.configValue(), true,
                    ModelContextWindows.defaultContextWindow(clean)));
            }
            for (String family : BUILT_IN_FAMILIES) {
                if (seen.add(family)) {
                    out.add(new SharedModel(
                        family, ModelApiProtocol.ANTHROPIC.configValue(), true,
                        ModelContextWindows.defaultContextWindow(family)));
                }
            }
        }
        return List.copyOf(out);
    }

    @Override
    public Optional<LlmClient> resolveClient(String modelName, boolean allowDefaultProvider) {
        if (StringUtils.isBlank(modelName)) return Optional.empty();
        if (customCatalog.find(modelName).isPresent()) {
            return Optional.of(customOnlyRoutingClient);
        }
        if (allowDefaultProvider && defaultProviderAvailable) {
            return Optional.of(defaultProviderClient);
        }
        return Optional.empty();
    }

    @Override
    public Optional<ModelApiProtocol> upstreamProtocol(String modelName) {
        Optional<CustomModelConfig> custom = customCatalog.find(modelName);
        return custom.map(value -> Optional.of(value.protocol())).orElseGet(() -> defaultProviderAvailable ? Optional.of(ModelApiProtocol.ANTHROPIC) : Optional.empty());
    }

    private Optional<CustomModelConfig> resolveCustomConfigWithLoopGuard(String modelName) {
        return customCatalog.find(modelName).map(cfg -> {
            String hopId = GatewayLanProxyPort.currentOutboundHopId();
            if (StringUtils.isBlank(hopId)) return cfg;
            Map<String, String> merged = new LinkedHashMap<>();
            if (cfg.headers() != null) merged.putAll(cfg.headers());
            merged.put(LOOP_GUARD_HEADER, hopId);
            return new CustomModelConfig(
                cfg.modelName(), cfg.protocol(), cfg.baseUrl(), cfg.apiKey(),
                merged, cfg.contextWindow(), cfg.multimodal());
        });
    }

    private static final class UnconfiguredFallbackLlmClient implements LlmClient {
        @Override
        public Iterator<StreamEvent> createMessageStream(CreateMessageRequest request) {
            throw new ApiException("Model '" + request.model() + "' is not configured in model.json", 404);
        }

        @Override
        public ApiMessage createMessage(CreateMessageRequest request) {
            throw new ApiException("Model '" + request.model() + "' is not configured in model.json", 404);
        }

        @Override
        public String getModel() {
            return "";
        }
    }
}
