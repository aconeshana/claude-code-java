package com.claudecode.tools.agent;

import com.claudecode.core.attachment.AgentListingDeltaAttachmentProvider;
import com.claudecode.core.attachment.AttachmentContext;
import com.claudecode.core.attachment.AttachmentProvider;
import com.claudecode.core.attachment.FeatureFlagRegistry;
import com.claudecode.core.message.AttachmentPayload;

import java.util.List;


final class SubAgentListingAttachmentProvider implements AttachmentProvider {

    private final AgentListingDeltaAttachmentProvider delegate =
        new AgentListingDeltaAttachmentProvider();

    @Override
    public String name() {
        return delegate.name();
    }

    @Override
    public boolean isEnabled(FeatureFlagRegistry flags) {
        return delegate.isEnabled(flags);
    }

    @Override
    public List<AttachmentPayload> collect(AttachmentContext ctx) {
        return ctx.input() == null ? delegate.collect(ctx) : List.of();
    }
}
