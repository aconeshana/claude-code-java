package com.claudecode.core.attachment;

import com.claudecode.core.message.AttachmentMessage;
import com.claudecode.core.message.AttachmentPayload;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.UltraEffortEnterAttachment;
import com.claudecode.core.message.UltraEffortExitAttachment;

import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Keeps the model aware of whether {@code ultracode} effort is on.
 *
 * <p>Announces it in full the first time, restates it briefly every
 * {@value #TURNS_BETWEEN_REMINDERS} user turns while it stays on, and says so once when it is
 * switched off. Without this the level would be indistinguishable from plain {@code xhigh} —
 * the standing instruction to orchestrate workflows is the whole difference.
 */
public final class UltraEffortAttachmentProvider implements AttachmentProvider {

    private static final int TURNS_BETWEEN_REMINDERS = 10;

    private final BooleanSupplier ultracodeActive;

    public UltraEffortAttachmentProvider(BooleanSupplier ultracodeActive) {
        this.ultracodeActive = Objects.requireNonNull(ultracodeActive, "ultracodeActive");
    }

    @Override
    public String name() {
        return "ultra_effort_enter";
    }

    @Override
    public List<AttachmentPayload> collect(AttachmentContext ctx) {
        // Read directly, like the other providers in this package: a settings read that throws
        // is a real fault and must surface, not be swallowed into "the level is off".
        boolean active = ultracodeActive.getAsBoolean();
        boolean announced = false;
        boolean seenPrior = false;
        int humanTurns = 0;
        for (int i = ctx.messages().size() - 1; i >= 0; i--) {
            Message message = ctx.messages().get(i);
            if (message instanceof AttachmentMessage attachment) {
                if (attachment.payload() instanceof UltraEffortEnterAttachment) {
                    announced = true;
                    seenPrior = true;
                    break;
                }
                if (attachment.payload() instanceof UltraEffortExitAttachment) {
                    seenPrior = true;
                    break;
                }
            } else if (PlanModeReminderAttachmentProvider.isHumanTurn(message)) {
                humanTurns++;
            }
        }
        if (active) {
            if (!announced) return List.of(new UltraEffortEnterAttachment("full"));
            if (humanTurns >= TURNS_BETWEEN_REMINDERS) {
                return List.of(new UltraEffortEnterAttachment("sparse"));
            }
            return List.of();
        }
        // Only worth saying "off" when the model was previously told it was on.
        if (seenPrior && announced) return List.of(new UltraEffortExitAttachment());
        return List.of();
    }
}
