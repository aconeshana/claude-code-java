package com.claudecode.tools.tasks.teammate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link TeammateMailbox} name→inbox resolution and inbox existence
 * checks used by leader→teammate dispatch and peer addressing.
 */
class TeammateMailboxNameResolutionTest {

    private final TeammateMailbox mailbox = TeammateMailbox.instance();

    @AfterEach
    void reset() {
        mailbox.clearAll();
    }

    @Test
    void resolveToInboxPassesThroughTeamLead() {
        assertEquals(TeammateMailbox.TEAM_LEAD, mailbox.resolveToInbox(TeammateMailbox.TEAM_LEAD));
    }

    @Test
    void registeredNameResolvesAndUnregisterFallsBack() {
        mailbox.registerName("researcher", "task-123");
        assertEquals("task-123", mailbox.resolveToInbox("researcher"));

        mailbox.unregisterName("researcher");
        assertEquals("researcher", mailbox.resolveToInbox("researcher"));
    }

    @Test
    void unknownRecipientFallsBackToItself() {
        assertEquals("ghost", mailbox.resolveToInbox("ghost"));
    }

    @Test
    void hasInboxReflectsLiveInbox() {
        assertFalse(mailbox.hasInbox("task-new"));
        // A send lazily creates the recipient's inbox.
        mailbox.send(Mail.of(MailTypes.USER_MESSAGE, TeammateMailbox.TEAM_LEAD, "task-new", "hi"));
        assertTrue(mailbox.hasInbox("task-new"));
    }

    @Test
    void registeringNameMovesMailQueuedBeforeTheTeammateStarted() {
        mailbox.send(Mail.of(
            MailTypes.TASK_ASSIGNMENT, TeammateMailbox.TEAM_LEAD, "researcher", "assignment"));

        mailbox.registerName("researcher", "task-123");

        Mail delivered = mailbox.poll("task-123");
        assertNotNull(delivered);
        assertEquals("assignment", delivered.payload());
        assertEquals("task-123", delivered.to());
        assertNull(mailbox.poll("researcher"));
    }
}
