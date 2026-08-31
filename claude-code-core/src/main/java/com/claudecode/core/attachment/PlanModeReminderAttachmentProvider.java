package com.claudecode.core.attachment;

import com.claudecode.core.message.AttachmentMessage;
import com.claudecode.core.message.AttachmentPayload;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.PlanModeExitAttachment;
import com.claudecode.core.message.PlanModeReminderAttachment;
import com.claudecode.core.message.PlanModeReentryAttachment;
import com.claudecode.core.message.ToolResultBlock;
import com.claudecode.core.message.UserMessage;
import com.claudecode.core.model.PermissionModeKind;
import com.claudecode.core.plan.PlanCatalogContext;
import java.util.List;
import java.util.ArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.apache.commons.lang3.Strings;

/**
 * Periodic plan-mode reminder injected into ordinary turns.
 *
 * <ul>
 *   <li> —
 *       {@code getPlanModeAttachmentTurnCount},
 *       {@code countPlanModeAttachmentsSinceLastExit}, and
 *       {@code getPlanModeAttachments}.</li>
 *   <li>
 *        sub-agent initial messages are passed directly
 *       into {@code query}, while plan attachments are normally added by the
 *       post-tool {@code getAttachmentMessages} pass; therefore a child skips
 *       this provider on its non-null initial input and receives it on the
 *       subsequent tool-result continuation.</li>
 * </ul>
 */
public final class PlanModeReminderAttachmentProvider implements AttachmentProvider {

    private static final int TURNS_BETWEEN_ATTACHMENTS = 5;
    private static final int FULL_EVERY_N_ATTACHMENTS = 5;

    private final Supplier<PermissionModeKind> modeSupplier;
    private final Supplier<String> planFilePathSupplier;
    private final Supplier<Boolean> planExistsSupplier;
    private final Supplier<PlanCatalogContext> planCatalogSupplier;
    private final BooleanSupplier reentrySupplier;

    public PlanModeReminderAttachmentProvider(
            Supplier<PermissionModeKind> modeSupplier,
            Supplier<String> planFilePathSupplier,
            Supplier<Boolean> planExistsSupplier) {
        this(modeSupplier, planFilePathSupplier, planExistsSupplier, () -> false);
    }

    public PlanModeReminderAttachmentProvider(
            Supplier<PermissionModeKind> modeSupplier,
            Supplier<String> planFilePathSupplier,
            Supplier<Boolean> planExistsSupplier,
            BooleanSupplier reentrySupplier) {
        this.modeSupplier = modeSupplier;
        this.planFilePathSupplier = planFilePathSupplier;
        this.planExistsSupplier = planExistsSupplier;
        this.planCatalogSupplier = null;
        this.reentrySupplier = reentrySupplier;
    }

    public PlanModeReminderAttachmentProvider(
            Supplier<PermissionModeKind> modeSupplier,
            Supplier<PlanCatalogContext> planCatalogSupplier) {
        this(modeSupplier, planCatalogSupplier, () -> false);
    }

    public PlanModeReminderAttachmentProvider(
            Supplier<PermissionModeKind> modeSupplier,
            Supplier<PlanCatalogContext> planCatalogSupplier,
            BooleanSupplier reentrySupplier) {
        this.modeSupplier = modeSupplier;
        this.planFilePathSupplier = null;
        this.planExistsSupplier = null;
        this.planCatalogSupplier = planCatalogSupplier;
        this.reentrySupplier = reentrySupplier;
    }

    @Override
    public String name() {
        return "plan_mode";
    }

    @Override
    public List<AttachmentPayload> collect(AttachmentContext ctx) {
        if (modeSupplier.get() != PermissionModeKind.PLAN) return List.of();
        if (ctx.agentId() != null && ctx.input() != null) return List.of();
        ReminderState state = inspect(ctx.messages());
        if (state.foundPrior() && state.humanTurnsSincePrior() < TURNS_BETWEEN_ATTACHMENTS) {
            return List.of();
        }
        PlanCatalogContext catalog = planCatalogSupplier == null
            ? new PlanCatalogContext(
                null, null, planFilePathSupplier.get(),
                Boolean.TRUE.equals(planExistsSupplier.get()), false, List.of())
            : planCatalogSupplier.get();
        String planFilePath = catalog.planFilePath();
        boolean planExists = catalog.planExists();
        List<AttachmentPayload> attachments = new ArrayList<>(2);
        boolean multiPlan = catalog.planId() != null;
        if (multiPlan) {
            reentrySupplier.getAsBoolean();
        } else if (planExists && reentrySupplier.getAsBoolean()) {
            attachments.add(new PlanModeReentryAttachment(planFilePath));
        }
        int nextCount = state.attachmentsSinceExit() + 1;
        String reminderType = nextCount % FULL_EVERY_N_ATTACHMENTS == 1 ? "full" : "sparse";
        attachments.add(new PlanModeReminderAttachment(
            reminderType,
            ctx.agentId() != null,
            planFilePath,
            planExists,
            catalog.planId(),
            catalog.planStatus(),
            multiPlan && Strings.CS.equals("full", reminderType)
                ? catalog.resumedDraft() : null,
            multiPlan && Strings.CS.equals("full", reminderType)
                ? catalog.recentPlans() : null));
        return List.copyOf(attachments);
    }

    private static ReminderState inspect(List<Message> messages) {
        int humanTurns = 0;
        boolean foundPrior = false;
        int attachmentsSinceExit = 0;
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message message = messages.get(i);
            if (message instanceof AttachmentMessage attachment) {
                if (attachment.payload() instanceof PlanModeExitAttachment) break;
                if (attachment.payload() instanceof PlanModeReminderAttachment) {
                    attachmentsSinceExit++;
                    if (!foundPrior) foundPrior = true;
                } else if (attachment.payload() instanceof PlanModeReentryAttachment
                        && !foundPrior) {
                    foundPrior = true;
                }
            } else if (!foundPrior && isHumanTurn(message)) {
                humanTurns++;
            }
        }
        return new ReminderState(humanTurns, foundPrior, attachmentsSinceExit);
    }

    static boolean isHumanTurn(Message message) {
        if (!(message instanceof UserMessage user) || user.isMeta() || user.message() == null) return false;
        return user.message().blocks() == null
            || user.message().blocks().stream().noneMatch(ToolResultBlock.class::isInstance);
    }

    private record ReminderState(
        int humanTurnsSincePrior, boolean foundPrior, int attachmentsSinceExit) {}
}
