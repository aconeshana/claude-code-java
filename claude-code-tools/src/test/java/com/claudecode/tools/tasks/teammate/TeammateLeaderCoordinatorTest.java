package com.claudecode.tools.tasks.teammate;

import org.apache.commons.lang3.Strings;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.tools.tasks.TaskRegistry;
import com.claudecode.tools.tasks.TaskState;
import com.claudecode.tools.tasks.TaskStore;
import com.claudecode.tools.tasks.TaskType;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link TeammateLeaderCoordinator} — the leader-side consumer of the
 * {@code team-lead} inbox. Verifies permission/plan asks are serviced (with the
 * headless allow fallback), and that an idle notification clears a teammate's
 * claim so it returns to the available pool.
 */
class TeammateLeaderCoordinatorTest {

    private final TeammateMailbox mailbox = TeammateMailbox.instance();

    @AfterEach
    void reset() {
        TeammateLeaderCoordinator.instance().stop();
        TeammateLeaderCoordinator.instance().resetForTest();
        mailbox.clearAll();
    }

    @Test
    void permissionRequestServicedWithHeadlessAllow() throws Exception {
        TeammateLeaderCoordinator.instance().start();

        String payload = "{\"teammate\":\"t1\",\"toolName\":\"Bash\",\"toolUseId\":\"tu-1\"}";
        mailbox.send(new Mail(MailTypes.PERMISSION_REQUEST, "req-1", "t1", TeammateMailbox.TEAM_LEAD, payload));

        Mail reply = mailbox.receive("t1");
        assertEquals(MailTypes.PERMISSION_RESPONSE, reply.type());
        assertEquals("req-1", reply.requestId(), "reply must preserve the requestId for correlation");
        assertTrue(Strings.CS.contains(reply.payload(), "\"allowed\":true"),
            "headless fallback should allow: " + reply.payload());
    }

    @Test
    void idleNotificationClearsClaim() throws Exception {
        TaskStore store = TaskRegistry.global().store();
        TaskState task = store.create(TaskType.IN_PROCESS_TEAMMATE, "Teammate");
        store.claim(task.id(), TeammateMailbox.TEAM_LEAD);
        assertTrue(store.get(task.id()).flatMap(TaskState::claimedBy).isPresent());

        TeammateLeaderCoordinator.instance().start();
        mailbox.send(Mail.of(MailTypes.IDLE_NOTIFICATION, "t1", TeammateMailbox.TEAM_LEAD,
            "teammate=" + task.id()));

        // Give the coordinator daemon a moment to process the idle mail.
        Thread.sleep(150);
        assertFalse(store.get(task.id()).flatMap(TaskState::claimedBy).isPresent(),
            "idle notification should clear the teammate's claim");
    }

    @Test
    void planApprovalServicedWithHeadlessAllow() throws Exception {
        TeammateLeaderCoordinator.instance().start();

        mailbox.send(new Mail(MailTypes.PLAN_APPROVAL_REQUEST, "req-2", "t1", TeammateMailbox.TEAM_LEAD,
            "plan summary"));

        Mail reply = mailbox.receive("t1");
        assertEquals(MailTypes.PLAN_APPROVAL_RESPONSE, reply.type());
        assertEquals("req-2", reply.requestId());
        assertTrue(Strings.CS.contains(reply.payload(), "\"approved\":true"),
            "headless fallback should approve the plan: " + reply.payload());
    }

    @Test
    void userMessageForwardedToTurnSubmitter() throws Exception {
        AtomicReference<String> submitted = new AtomicReference<>();
        TeammateLeaderCoordinator.instance().setTurnSubmitter(submitted::set);
        TeammateLeaderCoordinator.instance().start();

        mailbox.send(Mail.of(MailTypes.USER_MESSAGE, "t1", TeammateMailbox.TEAM_LEAD,
            "please check the build"));

        // Give the coordinator daemon a moment to process the mail and invoke the submitter.
        Thread.sleep(150);
        String wrapped = submitted.get();
        assertNotNull(wrapped, "teammate user_message should be forwarded to the turn submitter");
        assertTrue(Strings.CS.contains(wrapped, "teammate_id=\"t1\""),
            "wrapped message should carry the sender id: " + wrapped);
        assertTrue(Strings.CS.contains(wrapped, "please check the build"),
            "wrapped message should carry the payload: " + wrapped);
    }

    @Test
    void userMessageLoggedWhenNoTurnSubmitter() throws Exception {
        // headless: no submitter wired → falls back to logging, does not throw.
        TeammateLeaderCoordinator.instance().start();
        mailbox.send(Mail.of(MailTypes.USER_MESSAGE, "t1", TeammateMailbox.TEAM_LEAD,
            "headless message"));
        Thread.sleep(150);
        // No assertion on logging; success == the daemon drained it without error.
    }
}
