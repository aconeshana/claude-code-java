package com.claudecode.core.attachment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.core.engine.FileStateCache;
import com.claudecode.core.message.AttachmentMessage;
import com.claudecode.core.message.AutoModeReminderAttachment;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.PlanModeReminderAttachment;
import com.claudecode.core.message.PlanModeReentryAttachment;
import com.claudecode.core.message.UserMessage;
import com.claudecode.core.model.PermissionModeKind;
import com.claudecode.core.plan.PlanCatalogContext;
import com.claudecode.core.plan.PlanHistoryEntry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * Permission-mode reminder cadence tests.
 *
 * <ul>
 *   <li> —
 *       {@code getPlanModeAttachments}/{@code getAutoModeAttachments}, including
 *       the five-human-turn throttle and full/sparse reminder cycle.</li>
 *   <li>—
 *       sub-agent plan reminders begin on the post-tool attachment pass, not
 *       on the initial authored prompt.</li>
 * </ul>
 */
class PermissionModeAttachmentProviderTest {

    @Test
    void planAlwaysEmitsFullReminderOnFirstTurn() {
        var provider = new PlanModeReminderAttachmentProvider(
            () -> PermissionModeKind.PLAN, () -> "/plans/session.md", () -> false);

        var out = provider.collect(ctx(List.of()));

        assertEquals(1, out.size());
        PlanModeReminderAttachment reminder = (PlanModeReminderAttachment) out.getFirst();
        assertEquals("full", reminder.reminderType());
        assertEquals("/plans/session.md", reminder.planFilePath());
    }

    @Test
    void planThrottlesForFiveHumanTurnsThenEmitsSparse() {
        var provider = new PlanModeReminderAttachmentProvider(
            () -> PermissionModeKind.PLAN, () -> "/plans/session.md", () -> true);
        List<Message> messages = new ArrayList<>();
        messages.add(new AttachmentMessage("a1",
            new PlanModeReminderAttachment("full", false, "/plans/session.md", false)));
        for (int i = 0; i < 4; i++) messages.add(human("u" + i));
        assertTrue(provider.collect(ctx(messages)).isEmpty());
        messages.add(human("u4"));
        PlanModeReminderAttachment reminder = (PlanModeReminderAttachment)
            provider.collect(ctx(messages)).getFirst();
        assertEquals("sparse", reminder.reminderType());
    }

    @Test
    void planReentryPrecedesTheRegularReminderAndIsConsumedOnce() {
        AtomicBoolean reentry = new AtomicBoolean(true);
        var provider = new PlanModeReminderAttachmentProvider(
            () -> PermissionModeKind.PLAN,
            () -> "/plans/calm-building-harbor.md",
            () -> true,
            () -> reentry.getAndSet(false));

        var first = provider.collect(ctx(List.of()));

        assertEquals(2, first.size());
        assertTrue(first.getFirst() instanceof PlanModeReentryAttachment);
        assertTrue(first.get(1) instanceof PlanModeReminderAttachment);
        assertTrue(provider.collect(ctx(List.of())).getFirst()
            instanceof PlanModeReminderAttachment);
    }

    @Test
    void subAgentSkipsInitialInputButEmitsOnToolContinuation() {
        var provider = new PlanModeReminderAttachmentProvider(
            () -> PermissionModeKind.PLAN, () -> "/plans/session-agent-a1.md", () -> false);

        assertTrue(provider.collect(ctx(List.of(), "prompt", "a1")).isEmpty());
        PlanModeReminderAttachment reminder = (PlanModeReminderAttachment)
            provider.collect(ctx(List.of(), null, "a1")).getFirst();

        assertTrue(reminder.isSubAgent());
        assertEquals("/plans/session-agent-a1.md", reminder.planFilePath());
    }

    @Test
    void multiPlanFullReminderCarriesCatalogAndSuppressesLegacyReentry() {
        AtomicBoolean reentry = new AtomicBoolean(true);
        PlanCatalogContext catalog = new PlanCatalogContext(
            "P002", "DRAFT", "/plans/session-p002.md", false, false,
            List.of(new PlanHistoryEntry(
                "P001", "APPROVED", "First plan", "Original scope.",
                "/plans/session.md")));
        var provider = new PlanModeReminderAttachmentProvider(
            () -> PermissionModeKind.PLAN, () -> catalog,
            () -> reentry.getAndSet(false));

        var out = provider.collect(ctx(List.of()));

        assertEquals(1, out.size());
        PlanModeReminderAttachment reminder = (PlanModeReminderAttachment) out.getFirst();
        assertEquals("P002", reminder.planId());
        assertEquals("DRAFT", reminder.planStatus());
        assertEquals(Boolean.FALSE, reminder.resumedDraft());
        assertEquals(catalog.recentPlans(), reminder.recentPlans());
        assertFalse(reentry.get(), "the released reentry marker must be consumed");
    }

    @Test
    void multiPlanSparseReminderOmitsHistoricalCatalog() {
        PlanCatalogContext catalog = new PlanCatalogContext(
            "P006", "DRAFT", "/plans/session-p006.md", true, true,
            List.of(new PlanHistoryEntry(
                "P005", "APPROVED", "Previous", "Previous scope.",
                "/plans/session-p005.md")));
        var provider = new PlanModeReminderAttachmentProvider(
            () -> PermissionModeKind.PLAN, () -> catalog);
        List<Message> messages = new ArrayList<>();
        messages.add(new AttachmentMessage("a1",
            new PlanModeReminderAttachment("full", false, "/plans/session-p006.md", true)));
        for (int i = 0; i < 5; i++) messages.add(human("u" + i));

        PlanModeReminderAttachment reminder = (PlanModeReminderAttachment)
            provider.collect(ctx(messages)).getFirst();

        assertEquals("sparse", reminder.reminderType());
        assertEquals("P006", reminder.planId());
        assertEquals("DRAFT", reminder.planStatus());
        assertNull(reminder.resumedDraft());
        assertNull(reminder.recentPlans());
    }

    @Test
    void autoEmitsOnlyWhenAutoModeIsSelected() {
        var off = new AutoModeReminderAttachmentProvider(() -> PermissionModeKind.DEFAULT);
        assertTrue(off.collect(ctx(List.of())).isEmpty());

        var on = new AutoModeReminderAttachmentProvider(() -> PermissionModeKind.AUTO);
        AutoModeReminderAttachment reminder =
            (AutoModeReminderAttachment) on.collect(ctx(List.of())).getFirst();
        assertEquals("full", reminder.reminderType());
    }

    private static UserMessage human(String id) {
        return new UserMessage(id, MessageContent.ofText("prompt"));
    }

    private static AttachmentContext ctx(List<Message> messages) {
        return ctx(messages, "", null);
    }

    private static AttachmentContext ctx(List<Message> messages, String input, String agentId) {
        return AttachmentContext.builder(".")
            .messages(messages)
            .input(input)
            .fileStateCache(new FileStateCache())
            .agentId(agentId)
            .querySource("main")
            .build();
    }
}
