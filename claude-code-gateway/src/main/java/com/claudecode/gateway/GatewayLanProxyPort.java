package com.claudecode.gateway;

import com.claudecode.api.LlmClient;
import com.claudecode.core.model.ModelApiProtocol;
import java.util.List;
import java.util.Optional;

/**
 * Port bridging the stateless LAN model gateway to the host's configured {@code CustomModelCatalog}
 * and (when explicitly enabled) its built-in default LLM provider.
 */
public interface GatewayLanProxyPort {

    String LOOP_GUARD_HEADER = LanModelProxyHandler.LOOP_GUARD_HEADER;

    /** Outbound loop-guard hop ID bound on the current virtual thread during a LAN proxy call, or {@code null}. */
    static String currentOutboundHopId() {
        return LanModelProxyHandler.currentOutboundHopId();
    }

    /** Summary of one model exposed to LAN callers via {@code /v1/models} and {@code /v1beta/models}. */
    record SharedModel(String id, String protocol, boolean builtIn, Long contextWindow) {}

    /** Lists models currently available on the host. */
    default List<SharedModel> listModels(boolean includeDefaultProvider) {
        return List.of();
    }

    /**
     * Resolves an {@link LlmClient} capable of serving {@code modelName}, or {@code Optional.empty()}
     * when the model is neither in {@code model.json} nor permitted via {@code allowDefaultProvider}.
     */
    default Optional<LlmClient> resolveClient(String modelName, boolean allowDefaultProvider) {
        return Optional.empty();
    }

    /** Returns the configured upstream wire protocol of {@code modelName} for call telemetry. */
    default Optional<ModelApiProtocol> upstreamProtocol(String modelName) {
        return Optional.empty();
    }
}
