package com.claudecode.core.attachment;

import com.claudecode.core.message.AttachmentMessage;
import com.claudecode.core.message.AttachmentPayload;
import com.claudecode.core.message.AutoModeReminderAttachment;
import com.claudecode.core.message.Message;
import com.claudecode.core.model.PermissionModeKind;
import java.util.List;
import java.util.function.Supplier;

/**
 * Periodic auto-mode reminder injected into ordinary turns.
 */
public final class AutoModeReminderAttachmentProvider implements AttachmentProvider {

    private static final int TURNS_BETWEEN_ATTACHMENTS = 5;
    private static final int FULL_EVERY_N_ATTACHMENTS = 5;

    private final Supplier<PermissionModeKind> modeSupplier;

    public AutoModeReminderAttachmentProvider(Supplier<PermissionModeKind> modeSupplier) {
        this.modeSupplier = modeSupplier;
    }

    @Override
    public String name() {
        return "auto_mode";
    }

    @Override
    public List<AttachmentPayload> collect(AttachmentContext ctx) {
        if (modeSupplier.get() != PermissionModeKind.AUTO) return List.of();
        int humanTurns = 0;
        int attachmentCount = 0;
        boolean foundPrior = false;
        for (int i = ctx.messages().size() - 1; i >= 0; i--) {
            Message message = ctx.messages().get(i);
            if (message instanceof AttachmentMessage attachment
                    && attachment.payload() instanceof AutoModeReminderAttachment) {
                attachmentCount++;
                if (!foundPrior) foundPrior = true;
            } else if (!foundPrior && PlanModeReminderAttachmentProvider.isHumanTurn(message)) {
                humanTurns++;
            }
        }
        if (foundPrior && humanTurns < TURNS_BETWEEN_ATTACHMENTS) return List.of();
        int nextCount = attachmentCount + 1;
        String reminderType = nextCount % FULL_EVERY_N_ATTACHMENTS == 1 ? "full" : "sparse";
        return List.of(new AutoModeReminderAttachment(reminderType));
    }
}
