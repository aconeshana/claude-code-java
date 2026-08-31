package com.claudecode.core.attachment;

import java.util.List;

import com.claudecode.core.message.AttachmentPayload;
import com.claudecode.core.message.TokenUsageAttachment;
import com.claudecode.core.message.UsageSnapshot;

/**
 * Conversation token-usage reminder.
 */
public final class TokenUsageAttachmentProvider implements AttachmentProvider {

    private final boolean enabled;

    /** Provider-level default used by focused attachment tests. */
    public TokenUsageAttachmentProvider() {
        this(true);
    }

    /** CLI wiring passes the environment gate explicitly. */
    public TokenUsageAttachmentProvider(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public String name() {
        return "token_usage";
    }

    @Override
    public List<AttachmentPayload> collect(AttachmentContext ctx) {
        if (!enabled) {
            return List.of();
        }
        if (ctx.agentId() != null) {
            return List.of();
        }
        UsageSnapshot usage = ctx.usage();
        if (usage == null) {
            return List.of();
        }
        return List.of(new TokenUsageAttachment(
            usage.tokenUsed(), usage.tokenTotal(), usage.tokenRemaining()));
    }
}
