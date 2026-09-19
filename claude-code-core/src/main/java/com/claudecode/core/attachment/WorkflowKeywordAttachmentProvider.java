package com.claudecode.core.attachment;

import com.claudecode.core.message.AttachmentPayload;
import com.claudecode.core.message.WorkflowKeywordAttachment;

import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Opts a single turn into multi-agent orchestration when the user typed {@code ultracode}.
 *
 * <p>Unlike the {@code ultracode} effort level, which stays on for the session, this fires for
 * one turn only and leaves no standing instruction behind.
 */
public final class WorkflowKeywordAttachmentProvider implements AttachmentProvider {

    private final BooleanSupplier workflowsEnabled;
    private final BooleanSupplier keywordTriggerEnabled;

    /**
     * @param workflowsEnabled      whether dynamic-workflow orchestration is available at all
     * @param keywordTriggerEnabled the {@code workflowKeywordTriggerEnabled} setting, which
     *                              defaults to on
     */
    public WorkflowKeywordAttachmentProvider(
            BooleanSupplier workflowsEnabled, BooleanSupplier keywordTriggerEnabled) {
        this.workflowsEnabled = workflowsEnabled;
        this.keywordTriggerEnabled = keywordTriggerEnabled;
    }

    @Override
    public String name() {
        return "workflow_keyword_request";
    }

    @Override
    public List<AttachmentPayload> collect(AttachmentContext ctx) {
        if (!enabled(workflowsEnabled) || !enabled(keywordTriggerEnabled)) return List.of();
        if (!UltracodeKeyword.mentionedIn(ctx.input())) return List.of();
        return List.of(new WorkflowKeywordAttachment());
    }

    private static boolean enabled(BooleanSupplier supplier) {
        if (supplier == null) return false;
        try {
            return supplier.getAsBoolean();
        } catch (RuntimeException _) {
            // A settings read that blows up must not take the turn down with it.
            return false;
        }
    }
}
