package com.claudecode.core.attachment;

import java.util.List;

import com.claudecode.core.message.AttachmentPayload;

/**
 * Context-efficiency nudge toward the (unported) HISTORY_SNIP snip tool.
 */
public final class ContextEfficiencyAttachmentProvider implements AttachmentProvider {

    @Override
    public String name() {
        return "context_efficiency";
    }

    @Override
    public boolean isEnabled(FeatureFlagRegistry flags) {
        return flags.isEnabled(FeatureFlag.HISTORY_SNIP);
    }

    @Override
    public List<AttachmentPayload> collect(AttachmentContext ctx) {

// isSnipRuntimeEnabled is false.
        return List.of();
    }
}
