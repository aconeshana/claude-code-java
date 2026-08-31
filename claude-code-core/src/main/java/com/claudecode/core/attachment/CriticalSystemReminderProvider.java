package com.claudecode.core.attachment;

import java.util.List;

import com.claudecode.core.message.AttachmentPayload;
import org.apache.commons.lang3.StringUtils;
import com.claudecode.core.message.TextReminderAttachment;

/**
 * Re-injects a critical instruction the parent set on a sub-agent, surfaced as a verbatim
 * system-reminder.
 */
public final class CriticalSystemReminderProvider implements AttachmentProvider {

    @Override
    public String name() {
        return "critical_system_reminder";
    }

    @Override
    public List<AttachmentPayload> collect(AttachmentContext ctx) {
        String reminder = ctx.criticalSystemReminder();
        if (StringUtils.isBlank(reminder)) {
            return List.of();
        }
        return List.of(new TextReminderAttachment(reminder));
    }
}
