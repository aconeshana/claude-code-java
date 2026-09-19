package com.claudecode.core.engine;

import java.util.function.Supplier;

/**
 * Factory that builds a {@link MessageCompactor} scoped to a single sub-agent invocation.
 */
public interface SubAgentCompactServiceFactory {

    /**
     * Builds a compact service for one sub-agent invocation.
     *
     * <p>Both suppliers are late-bound against the sub-agent's own session,
     * which does not exist yet when the compactor is attached to its config.
     * The model is not a parameter: compaction forks the sub-agent session, so
     * the model comes from that session's configuration rather than from a
     * value captured here.
     *
     * @param forkBuilder the sub-agent session's cache-sharing fork, which
     *                    carries its system prompt and tool catalog — without
     *                    the catalog a conversation holding {@code tool_use}
     *                    blocks would not be a valid request
     */
    MessageCompactor createForSubAgent(String agentId, SessionIdentity sessionIdentity,
                                        Supplier<FileStateCache> fileStateCacheSupplier,
                                        CacheSharingForkBuilder forkBuilder);
}
