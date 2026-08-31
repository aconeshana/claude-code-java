package com.claudecode.tools.tasks;

import org.apache.commons.lang3.Strings;

import com.claudecode.core.engine.AbortController;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.UserMessage;
import com.claudecode.permissions.PermissionMode;
import com.claudecode.tools.agent.AgentExecutionResult;
import com.claudecode.tools.agent.NoOpSubAgentFactory;
import com.claudecode.tools.agent.SubAgentFactory;
import com.claudecode.tools.agent.SubAgentRequest;
import com.claudecode.tools.tasks.teammate.Mail;
import com.claudecode.tools.tasks.teammate.MailTypes;
import com.claudecode.tools.tasks.teammate.TeammateContext;
import com.claudecode.tools.tasks.teammate.TeammateMailbox;
import org.junit.jupiter.api.AfterEach;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the REPL teammate-view accessors added to {@link InProcessTeammateTask}
 * (name / permissionMode / status / lastMessagePreview / abortCurrentTurn), which
 * the UI reads while viewing or stepping through a running teammate.
 */
class InProcessTeammateTaskViewTest {

    private final TeammateMailbox mailbox = TeammateMailbox.instance();
    private static final String TEAM = "team-view";

    @AfterEach
    void resetMailbox() {
        mailbox.clearAll();
    }

    private static ToolExecutionContext testContext() {
        return ToolExecutionContext.of(new AbortController(), "test-session");
    }

    private static TeammateContext context(String agentId, String name, PermissionMode mode) {
        return TeammateContext.builder()
            .agentId(agentId)
            .teamId(TEAM)
            .abortController(new AbortController())
            .name(name)
            .permissionMode(mode)
            .build();
    }

    private static SubAgentRequest request() {
        return SubAgentRequest.builder().prompt("explore").parentContext(testContext()).build();
    }

    @Test
    void accessorsReflectContext() {
        TaskStore store = TaskStore.inMemory();
        TaskState task = store.create(TaskType.IN_PROCESS_TEAMMATE, "Teammate");
        InProcessTeammateTask teammate = new InProcessTeammateTask(
            task, store, new NoOpSubAgentFactory(), request(), context(task.id(), "alpha", PermissionMode.PLAN));

        assertEquals("alpha", teammate.name());
        assertEquals(PermissionMode.PLAN, teammate.permissionMode());
        // A freshly created (not-yet-started) task is PENDING in the store.
        assertEquals(TaskStatus.PENDING, teammate.status());
        assertFalse(teammate.isRunning());
    }

    @Test
    void setPermissionModeUpdatesReadBack() {
        TaskStore store = TaskStore.inMemory();
        TaskState task = store.create(TaskType.IN_PROCESS_TEAMMATE, "Teammate");
        InProcessTeammateTask teammate = new InProcessTeammateTask(
            task, store, new NoOpSubAgentFactory(), request(), context(task.id(), "alpha", PermissionMode.DEFAULT));

        assertEquals(PermissionMode.DEFAULT, teammate.permissionMode());
        teammate.setPermissionMode(PermissionMode.BYPASS_PERMISSIONS);
        assertEquals(PermissionMode.BYPASS_PERMISSIONS, teammate.permissionMode(),
            "setPermissionMode must be readable back (mirrors TS Shift+Tab re-read each turn)");
    }

    @Test
    void lastMessagePreviewSkipsInterruptAndTruncates() {
        TaskStore store = TaskStore.inMemory();
        TaskState task = store.create(TaskType.IN_PROCESS_TEAMMATE, "Teammate");
        InProcessTeammateTask teammate = new InProcessTeammateTask(
            task, store, new NoOpSubAgentFactory(), request(), context(task.id(), "alpha", PermissionMode.DEFAULT));

        teammate.appendMessage(Mail.of(MailTypes.USER_MESSAGE, "leader", teammate.getTaskId(), "first result here"));
        teammate.appendMessage(new Mail(MailTypes.INTERRUPT, "", teammate.getTaskId(), teammate.getTaskId(), "interrupted"));

        // The interrupt sentinel is skipped; the last real message wins.
        assertEquals("first result here", teammate.lastMessagePreview(160));

        // Truncation: a long message is cut to the requested length.
        String longMsg = "x".repeat(300);
        teammate.appendMessage(Mail.of(MailTypes.USER_MESSAGE, "leader", teammate.getTaskId(), longMsg));
        assertEquals(160, teammate.lastMessagePreview(160).length());
        assertTrue(Strings.CS.startsWith(teammate.lastMessagePreview(160), "x"));

        // Empty when nothing surfaced.
        InProcessTeammateTask empty = new InProcessTeammateTask(
            store.create(TaskType.IN_PROCESS_TEAMMATE, "Teammate"),
            store, new NoOpSubAgentFactory(), request(), context("other", "beta", PermissionMode.DEFAULT));
        assertEquals("", empty.lastMessagePreview(160));
    }

    @Test
    void abortCurrentTurnIsSafeBeforeRunStarts() {
        TaskStore store = TaskStore.inMemory();
        TaskState task = store.create(TaskType.IN_PROCESS_TEAMMATE, "Teammate");
        InProcessTeammateTask teammate = new InProcessTeammateTask(
            task, store, new NoOpSubAgentFactory(), request(), context(task.id(), "alpha", PermissionMode.DEFAULT));

        // No per-turn controller exists yet — must be a no-op, not an NPE.
        assertDoesNotThrow(teammate::abortCurrentTurn);
    }

    @Test
    void messagesReturnsThreadSafeCopy() {
        TaskStore store = TaskStore.inMemory();
        TaskState task = store.create(TaskType.IN_PROCESS_TEAMMATE, "Teammate");
        InProcessTeammateTask teammate = new InProcessTeammateTask(
            task, store, new NoOpSubAgentFactory(), request(), context(task.id(), "alpha", PermissionMode.DEFAULT));

        teammate.appendMessage(Mail.of(MailTypes.USER_MESSAGE, "leader", teammate.getTaskId(), "hello"));
        List<Mail> msgs = teammate.messages();
        assertEquals(1, msgs.size());
        // Mutating the returned copy must not affect the internal log.
        msgs.clear();
        assertEquals(1, teammate.messages().size());
    }

    @Test
    void displayTranscriptStartsWithInitialPromptFromConversation() throws Exception {
        TaskStore store = TaskStore.inMemory();
        TaskState task = store.create(TaskType.IN_PROCESS_TEAMMATE, "Teammate");
        String prompt = "explore";
        // The sub-agent's conversation already begins with the initial prompt
// (matches the real DefaultSubAgentFactory, whose engine appends the
        // prompt as the first user message). The display transcript must NOT
        // seed a second copy — that would duplicate it.
        List<Message> conv = List.of(
            new UserMessage(UUID.randomUUID().toString(), MessageContent.ofText(prompt)),
            new UserMessage(UUID.randomUUID().toString(), MessageContent.ofText("follow-up")));
        SubAgentFactory factory = _ -> AgentExecutionResult.builder("done")
            .conversationMessages(conv).build();
        InProcessTeammateTask teammate = new InProcessTeammateTask(
            task, store, factory,
            SubAgentRequest.builder().prompt(prompt).parentContext(testContext()).build(),
            context(task.id(), "alpha", PermissionMode.DEFAULT));
        teammate.start();
        mailbox.receive(TeammateMailbox.TEAM_LEAD); // consume idle after turn 1

        List<Message> transcript = teammate.displayTranscript();
        // The prompt appears exactly ONCE, as the conversation's first message.
        long promptCount = transcript.stream()
            .filter(m -> m instanceof UserMessage um && prompt.equals(um.message().text()))
            .count();
        assertEquals(1, promptCount, "initial prompt must appear exactly once (no seed duplication)");
        assertInstanceOf(UserMessage.class, transcript.getFirst(),
          "conversation starts with a user message");
        assertEquals(prompt, ((UserMessage) transcript.getFirst()).message().text(),
            "first message is the initial prompt");
        // The whole conversation (2 messages) is present.
        assertEquals(2, transcript.size());

        teammate.stop();
    }

    @Test
    void displayTranscriptAccumulatesConversation() throws Exception {
        TaskStore store = TaskStore.inMemory();
        TaskState task = store.create(TaskType.IN_PROCESS_TEAMMATE, "Teammate");
        List<Message> conv = List.of(
            new UserMessage(UUID.randomUUID().toString(), MessageContent.ofText("first")),
            new UserMessage(UUID.randomUUID().toString(), MessageContent.ofText("second")));
        SubAgentFactory factory = _ -> AgentExecutionResult.builder("done")
            .conversationMessages(conv).build();
        InProcessTeammateTask teammate = new InProcessTeammateTask(
            task, store, factory, request(), context(task.id(), "alpha", PermissionMode.DEFAULT));
        teammate.start();
        mailbox.receive(TeammateMailbox.TEAM_LEAD); // consume idle after turn 1

        List<Message> transcript = teammate.displayTranscript();
        // No separate seed: the transcript is exactly the conversation (2 messages).
        assertEquals(2, transcript.size());
        String joined = transcript.stream()
            .filter(UserMessage.class::isInstance)
            .map(m -> ((UserMessage) m).message().text())
            .reduce("", (a, b) -> a + "|" + b);
        assertTrue(Strings.CS.contains(joined, "first"), "conversation message surfaced: " + joined);
        assertTrue(Strings.CS.contains(joined, "second"), "conversation message surfaced: " + joined);

        teammate.stop();
    }
}
