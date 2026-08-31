package com.claudecode.core.engine;

import java.util.function.Supplier;

/**
 * Factory that builds a {@link MessageCompactor} scoped to a single sub-agent invocation.
 */
public interface SubAgentCompactServiceFactory {

    /**
     * Builds a compact service for one sub-agent invocation.
     */
    MessageCompactor createForSubAgent(String agentId, SessionIdentity sessionIdentity,
                                        String model,
                                        Supplier<FileStateCache> fileStateCacheSupplier);
}
