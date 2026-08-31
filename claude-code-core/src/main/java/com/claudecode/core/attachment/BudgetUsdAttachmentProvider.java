package com.claudecode.core.attachment;

import java.util.List;

import com.claudecode.core.message.AttachmentPayload;
import com.claudecode.core.message.BudgetUsdAttachment;
import com.claudecode.core.message.UsageSnapshot;

/**
 * USD-budget usage reminder.
 */
public final class BudgetUsdAttachmentProvider implements AttachmentProvider {

    @Override
    public String name() {
        return "budget_usd";
    }

    @Override
    public List<AttachmentPayload> collect(AttachmentContext ctx) {
        if (ctx.agentId() != null) {
            return List.of();
        }
        UsageSnapshot usage = ctx.usage();
        if (usage == null || usage.budgetTotal() <= 0) {
            return List.of();
        }
        return List.of(new BudgetUsdAttachment(
            usage.budgetUsed(), usage.budgetTotal(), usage.budgetRemaining()));
    }
}
