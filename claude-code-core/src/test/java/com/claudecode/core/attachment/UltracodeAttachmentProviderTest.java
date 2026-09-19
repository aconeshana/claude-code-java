package com.claudecode.core.attachment;

import com.claudecode.core.message.*;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cadence and gating for the two ultracode attachments: the one-turn keyword trigger and the
 * standing effort-level reminder.
 */
class UltracodeAttachmentProviderTest {

    // ── Keyword trigger ───────────────────────────────────────────────────

    @Test
    void keywordTriggerFiresWhenBothGatesAreOpen() {
        var provider = new WorkflowKeywordAttachmentProvider(() -> true, () -> true);
        assertInstanceOf(WorkflowKeywordAttachment.class,
            provider.collect(ctx(List.of(), "ultracode this audit")).getFirst());
    }

    @Test
    void keywordTriggerStaysSilentWithoutWorkflows() {
        // Asking the model to use a tool it does not have would be worse than not asking.
        var provider = new WorkflowKeywordAttachmentProvider(() -> false, () -> true);
        assertTrue(provider.collect(ctx(List.of(), "ultracode this audit")).isEmpty());
    }

    @Test
    void keywordTriggerHonoursTheSetting() {
        var provider = new WorkflowKeywordAttachmentProvider(() -> true, () -> false);
        assertTrue(provider.collect(ctx(List.of(), "ultracode this audit")).isEmpty());
    }

    @Test
    void keywordTriggerIgnoresTurnsWithoutTheKeyword() {
        var provider = new WorkflowKeywordAttachmentProvider(() -> true, () -> true);
        assertTrue(provider.collect(ctx(List.of(), "refactor the parser")).isEmpty());
    }

    @Test
    void keywordTriggerLetsAFailingSettingsReadSurface() {
        // A settings read that blows up is a real fault. Swallowing it into "feature off"
        // turned a broken read into a silently missing attachment with nothing logged.
        var provider = new WorkflowKeywordAttachmentProvider(
            () -> true, () -> { throw new IllegalStateException("settings unreadable"); });
        assertThrows(IllegalStateException.class,
            () -> provider.collect(ctx(List.of(), "ultracode this")));
    }

    @Test
    void providersRejectNullSuppliers() {
        // Matches TaskReminderAttachmentProvider / TodoReminderAttachmentProvider: a missing
        // supplier is a wiring bug at construction, not a silently disabled feature.
        assertThrows(NullPointerException.class,
            () -> new WorkflowKeywordAttachmentProvider(null, () -> true));
        assertThrows(NullPointerException.class,
            () -> new WorkflowKeywordAttachmentProvider(() -> true, null));
        assertThrows(NullPointerException.class,
            () -> new UltraEffortAttachmentProvider(null));
    }

    // ── Standing effort reminder ──────────────────────────────────────────

    @Test
    void firstTurnWithUltracodeOnGetsTheFullInstructions() {
        var provider = new UltraEffortAttachmentProvider(() -> true);
        UltraEffortEnterAttachment enter =
            (UltraEffortEnterAttachment) provider.collect(ctx(List.of(), "")).getFirst();
        assertEquals("full", enter.reminderType());
    }

    @Test
    void alreadyAnnouncedTurnsStaySilentUntilTheRefreshInterval() {
        var provider = new UltraEffortAttachmentProvider(() -> true);
        List<Message> history = new ArrayList<>();
        history.add(attachment(new UltraEffortEnterAttachment("full")));
        for (int i = 0; i < 9; i++) history.add(human("u" + i));

        assertTrue(provider.collect(ctx(history, "")).isEmpty());

        history.add(human("u9"));
        UltraEffortEnterAttachment refresh =
            (UltraEffortEnterAttachment) provider.collect(ctx(history, "")).getFirst();
        assertEquals("sparse", refresh.reminderType());
    }

    @Test
    void switchingItOffAnnouncesTheExitExactlyOnce() {
        var provider = new UltraEffortAttachmentProvider(() -> false);
        List<Message> history = new ArrayList<>();
        history.add(attachment(new UltraEffortEnterAttachment("full")));

        assertInstanceOf(UltraEffortExitAttachment.class,
            provider.collect(ctx(history, "")).getFirst());

        // Once the exit is on the record, later turns must not repeat it.
        history.add(attachment(new UltraEffortExitAttachment()));
        assertTrue(provider.collect(ctx(history, "")).isEmpty());
    }

    @Test
    void neverAnnouncesAnExitItNeverEntered() {
        var provider = new UltraEffortAttachmentProvider(() -> false);
        assertTrue(provider.collect(ctx(List.of(), "")).isEmpty());
        assertTrue(provider.collect(ctx(List.of(human("u0")), "")).isEmpty());
    }

    @Test
    void reEnablingAfterAnExitAnnouncesTheFullInstructionsAgain() {
        var provider = new UltraEffortAttachmentProvider(() -> true);
        List<Message> history = List.of(
            attachment(new UltraEffortEnterAttachment("full")),
            attachment(new UltraEffortExitAttachment()));

        UltraEffortEnterAttachment enter =
            (UltraEffortEnterAttachment) provider.collect(ctx(history, "")).getFirst();
        assertEquals("full", enter.reminderType());
    }

    private static UserMessage human(String id) {
        return new UserMessage(id, MessageContent.ofText("prompt"));
    }

    private static AttachmentMessage attachment(
            AttachmentPayload payload) {
        return new AttachmentMessage("a-" + payload.getClass().getSimpleName(), payload);
    }

    private static AttachmentContext ctx(List<Message> messages, String input) {
        return AttachmentContext.builder(".").messages(messages).input(input).build();
    }
}
