package com.claudecode.core.attachment;

import java.util.List;

import com.claudecode.core.message.AttachmentPayload;
import com.claudecode.core.message.OutputTokenUsageAttachment;
import com.claudecode.core.message.UsageSnapshot;

/**
 * Output-token usage reminder (this turn / session).
 */
public final class OutputTokenUsageAttachmentProvider implements AttachmentProvider {

    @Override
    public String name() {
        return "output_token_usage";
    }

    @Override
    public List<AttachmentPayload> collect(AttachmentContext ctx) {
        if (ctx.agentId() != null) {
            return List.of();
        }
        UsageSnapshot usage = ctx.usage();
        if (usage == null || usage.outputBudget() == null || usage.outputBudget() <= 0) {
            return List.of();
        }
        return List.of(new OutputTokenUsageAttachment(
            usage.outputTurn(), usage.outputBudget(), usage.outputSession()));
    }
}
