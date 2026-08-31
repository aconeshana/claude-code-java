package com.claudecode.core.attachment;

import java.util.List;

import com.claudecode.core.message.AttachmentPayload;
import com.claudecode.core.message.PlanModeExitAttachment;

/**
 * One-time notice that plan mode was just exited.
 */
public final class PlanModeExitAttachmentProvider implements AttachmentProvider {

    @Override
    public String name() {
        return "plan_mode_exit";
    }

    @Override
    public List<AttachmentPayload> collect(AttachmentContext ctx) {
        var exit = ctx.planModeExit();
        if (exit == null) {
            return List.of();
        }
        return List.of(new PlanModeExitAttachment(exit.planFilePath(), exit.planExists()));
    }
}
