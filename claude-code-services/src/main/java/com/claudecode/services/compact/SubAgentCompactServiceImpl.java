package com.claudecode.services.compact;

import java.util.function.Supplier;
import com.claudecode.api.LlmClient;
import com.claudecode.core.engine.FileStateCache;
import com.claudecode.core.engine.MessageCompactor;
import com.claudecode.core.engine.SessionIdentity;
import com.claudecode.core.engine.SubAgentCompactServiceFactory;
import com.claudecode.core.message.TokenEstimator;
import com.claudecode.core.model.ModelNames;


public class SubAgentCompactServiceImpl implements SubAgentCompactServiceFactory {

    private final LlmClient llmClient;
    private final boolean autoCompactEnabled;

    public SubAgentCompactServiceImpl(LlmClient llmClient, boolean autoCompactEnabled) {
        this.llmClient = llmClient;
        this.autoCompactEnabled = autoCompactEnabled;
    }

    @Override
    public MessageCompactor createForSubAgent(String agentId, SessionIdentity sessionIdentity,
                                              String model,
                                              Supplier<FileStateCache> fileStateCacheSupplier) {
        CompactService cs = new CompactService(
            TokenEstimator.getInstance(),
            llmClient != null
                ? new LlmCompactSummarizer(llmClient,
                    () -> ModelNames.parseUserSpecifiedModel(model))
                : null,
            autoCompactEnabled);
        cs.setSessionIdentity(sessionIdentity);
        cs.setAgentId(agentId);
        cs.setFileStateCacheSupplier(fileStateCacheSupplier);
        return cs;
    }
}
