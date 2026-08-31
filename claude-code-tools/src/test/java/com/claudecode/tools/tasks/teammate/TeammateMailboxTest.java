package com.claudecode.tools.tasks.teammate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;


@Timeout(20)
class TeammateMailboxTest {

    private final TeammateMailbox mailbox = TeammateMailbox.instance();

    @AfterEach
    void resetMailbox() {
        mailbox.clearAll();
    }

    @Test
    void sendThenReceiveDeliversPayloadAndSender() throws InterruptedException {
        mailbox.send(Mail.of(MailTypes.USER_MESSAGE, "agent-a", "agent-b", "hello"));
        Mail m = mailbox.receive("agent-b");
        assertEquals("hello", m.payload());
        assertEquals("agent-a", m.from());
        assertEquals("agent-b", m.to());
    }

    @Test
    void receiveBlocksUntilSenderDelivers() throws Exception {
        ExecutorService ex = Executors.newSingleThreadExecutor();
        try {
            Future<?> sent = ex.submit(() ->
                mailbox.send(Mail.of(MailTypes.USER_MESSAGE, "agent-a", "blocked", "ping")));
// receive should block until the sender offers the message.
            Mail m = mailbox.receive("blocked");
            sent.get(2, TimeUnit.SECONDS);
            assertEquals("ping", m.payload());
        } finally {
            ex.shutdownNow();
        }
    }

    @Test
    void pollReturnsNullWhenEmpty() {
        assertNull(mailbox.poll("nobody"));
    }

    @Test
    void pollReturnsNextQueuedMail() {
        mailbox.send(Mail.of(MailTypes.USER_MESSAGE, "agent-a", "r", "first"));
        Mail m = mailbox.poll("r");
        assertNotNull(m);
        assertEquals("first", m.payload());
        // Queue is now drained.
        assertNull(mailbox.poll("r"));
    }

    @Test
    void clearDropsPendingMail() {
        mailbox.send(Mail.of(MailTypes.USER_MESSAGE, "agent-a", "r", "x"));
        mailbox.send(Mail.of(MailTypes.USER_MESSAGE, "agent-a", "r", "y"));
        mailbox.clear("r");
        assertNull(mailbox.poll("r"));
    }

    @Test
    void requestIdRoundTripsViaReply() throws InterruptedException {
        Mail request = Mail.of(MailTypes.PERMISSION_REQUEST, "agent-a", TeammateMailbox.TEAM_LEAD, "ask");
        mailbox.send(request);
        // Leader drains the request and replies, preserving the requestId.
        Mail req = mailbox.receive(TeammateMailbox.TEAM_LEAD);
        assertEquals(MailTypes.PERMISSION_REQUEST, req.type());
        Mail reply = Mail.reply(req, MailTypes.PERMISSION_RESPONSE, "team-lead", "{\"allowed\":true}");
        mailbox.send(reply);

        Mail resp = mailbox.receive("agent-a");
        assertEquals(MailTypes.PERMISSION_RESPONSE, resp.type());
        assertEquals(request.requestId(), resp.requestId());
        assertEquals("{\"allowed\":true}", resp.payload());
    }

    @Test
    void broadcastDeliversToAllExceptSender() {
        // Seed inboxes for b and c so broadcast has recipients to deliver to.
        mailbox.send(Mail.of(MailTypes.USER_MESSAGE, "x", "b", "seed-b"));
        mailbox.send(Mail.of(MailTypes.USER_MESSAGE, "x", "c", "seed-c"));

        mailbox.broadcast(Mail.of(MailTypes.USER_MESSAGE, "a", "*", "hello-all"));

        // Sender 'a' must not receive its own broadcast.
        assertNull(mailbox.poll("a"));

        // b and c each received the broadcast (after their seed message).
        assertEquals("seed-b", mailbox.poll("b").payload());
        assertEquals("hello-all", mailbox.poll("b").payload());
        assertEquals("seed-c", mailbox.poll("c").payload());
        assertEquals("hello-all", mailbox.poll("c").payload());
    }

    @Test
    void shutdownRequestFlow() throws InterruptedException {
        mailbox.send(Mail.of(MailTypes.SHUTDOWN_REQUEST, "leader", "agent-a", "stop"));
        Mail m = mailbox.receive("agent-a");
        assertEquals(MailTypes.SHUTDOWN_REQUEST, m.type());
        assertEquals("stop", m.payload());
    }
}
