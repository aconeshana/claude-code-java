package com.claudecode.services.cache;

import com.claudecode.core.engine.SubAgentLifecycleListener;

/**
 * Services-side implementation of {@link SubAgentLifecycleListener} that drops the
 * prompt-cache-break tracker's per-agent entry when a sub-agent finishes.
 */
public class PromptCacheBreakCleanup implements SubAgentLifecycleListener {

    @Override
    public void onSubAgentComplete(String agentId) {
        PromptCacheBreakDetection.cleanupAgentTracking(agentId);
    }
}
