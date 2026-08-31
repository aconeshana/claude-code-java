package com.claudecode.core.attachment;

import java.util.List;

import com.claudecode.core.message.AttachmentPayload;
import com.claudecode.core.message.CompactionReminderAttachment;

/**
 * Reminds the model that auto-compaction is enabled and it has unlimited context through it.
 */
public final class CompactionReminderAttachmentProvider implements AttachmentProvider {

    @Override
    public String name() {
        return "compaction_reminder";
    }

    @Override
    public boolean isEnabled(FeatureFlagRegistry flags) {
        return flags.isEnabled(FeatureFlag.COMPACTION_REMINDERS);
    }

    @Override
    public List<AttachmentPayload> collect(AttachmentContext ctx) {
        if (!ctx.compactionOccurred()) {
            return List.of();
        }
        return List.of(new CompactionReminderAttachment());
    }
}
