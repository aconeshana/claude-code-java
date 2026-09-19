package com.claudecode.services.compact;

import java.util.function.Supplier;
import com.claudecode.core.engine.CacheSharingForkBuilder;
import com.claudecode.core.engine.FileStateCache;
import com.claudecode.core.engine.MessageCompactor;
import com.claudecode.core.engine.SessionIdentity;
import com.claudecode.core.engine.StreamingClient;
import com.claudecode.core.engine.SubAgentCompactServiceFactory;
import com.claudecode.core.message.TokenEstimator;


/**
 * Builds the per-invocation compact service for a sub-agent.
 *
 * <p>Sub-agents run the same query loop as the main session, so they auto- and
 * micro-compact too, and their compaction forks their own session: the request
 * carries that session's system prompt and tool catalog. Summarizing through a
 * plain client instead would drop the catalog, and a sub-agent that had already
 * called a tool would compact into a request the Messages API rejects —
 * {@code tool_use} blocks with no declared tools.
 */
public class SubAgentCompactServiceImpl implements SubAgentCompactServiceFactory {

    private final StreamingClient streamingClient;
    private final boolean autoCompactEnabled;

    public SubAgentCompactServiceImpl(StreamingClient streamingClient, boolean autoCompactEnabled) {
        this.streamingClient = streamingClient;
        this.autoCompactEnabled = autoCompactEnabled;
    }

    @Override
    public MessageCompactor createForSubAgent(String agentId, SessionIdentity sessionIdentity,
                                              Supplier<FileStateCache> fileStateCacheSupplier,
                                              CacheSharingForkBuilder forkBuilder) {
        CompactService cs = new CompactService(
            TokenEstimator.getInstance(),
            streamingClient != null && forkBuilder != null
                ? new LlmCompactSummarizer(streamingClient, forkBuilder)
                : null,
            autoCompactEnabled);
        cs.setSessionIdentity(sessionIdentity);
        cs.setAgentId(agentId);
        cs.setFileStateCacheSupplier(fileStateCacheSupplier);
        return cs;
    }
}
