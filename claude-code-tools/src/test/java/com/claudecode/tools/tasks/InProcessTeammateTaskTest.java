package com.claudecode.tools.tasks;

import org.apache.commons.lang3.Strings;
import com.claudecode.core.engine.AbortController;
import com.claudecode.core.engine.HookDispatcher;
import com.claudecode.core.engine.PermissionAskCallback;
import com.claudecode.core.engine.PermissionAskContext;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.core.message.AssistantContent;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.ToolUseBlock;
import com.claudecode.core.message.Usage;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.tools.agent.NoOpSubAgentFactory;
import com.claudecode.tools.agent.SubAgentFactory;
import com.claudecode.tools.agent.SubAgentRequest;
import com.claudecode.tools.agent.SubAgentResult;
import com.claudecode.tools.tasks.teammate.Mail;
import com.claudecode.tools.tasks.teammate.MailTypes;
import com.claudecode.tools.tasks.teammate.AgentTeamsEnabled;
import com.claudecode.tools.tasks.teammate.TeammateContext;
import com.claudecode.tools.tasks.teammate.TeammateMailbox;
import com.claudecode.tools.tasks.teammate.TeammatePermissionAskCallback;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link InProcessTeammateTask} — the live handle that drives a real
 * sub-agent run on a virtual thread, isolated by a {@link TeammateContext} and
 * communicating with the leader over the in-JVM {@link TeammateMailbox}.
 *
 * <p>Covers the lifecycle (start → run → complete), cooperative shutdown
 * (abort + thread termination), the mailbox-backed permission bridge
 * (leader→teammate request/response), and {@code kill}.
 */
@Timeout(20)
class InProcessTeammateTaskTest {

    private final TeammateMailbox mailbox = TeammateMailbox.instance();

    @AfterEach
    void resetMailbox() {
        mailbox.clearAll();
        AgentTeamsEnabled.resetForTest();
    }

    private static ToolExecutionContext testContext() {
        return ToolExecutionContext.of(new AbortController(), "test-session");
    }

    private static TeammateContext teammateContext(String agentId) {
        return TeammateContext.builder()
            .agentId(agentId)
            .teamId("team-1")
            .abortController(new AbortController())
            .build();
    }

    private static SubAgentRequest teammateRequest() {
        return SubAgentRequest.builder().prompt("explore").parentContext(testContext()).build();
    }

    /** A factory that blocks forever (until interrupted) so shutdown can be observed. */
    private static final class BlockingFactory implements SubAgentFactory {
        @Override
        public SubAgentResult runSubAgent(SubAgentRequest request) {
            try {
                new ArrayBlockingQueue<Object>(1).take(); // blocks until interrupted
                return SubAgentResult.of("done");
            } catch (InterruptedException _) {
                // A cooperative shutdown aborts the run; surface it as a failure
// (matches how a real sub-agent query loop unwinds on abort).
                Thread.currentThread().interrupt();
                throw new RuntimeException("interrupted by shutdown");
            }
        }
    }

    /** A factory that asks the leader for permission and reports the decision. */
    private static final class PermissionAskingFactory implements SubAgentFactory {
        @Override
        public SubAgentResult runSubAgent(SubAgentRequest request) {
            PermissionAskCallback cb = request.parentContext().permissionAskCallback();
            PermissionAskContext ctx = PermissionAskContext.simple("Bash", null, "tu-1");
            PermissionAskCallback.Result r = cb.ask(ctx);
            return SubAgentResult.of("decision=" + r.allowed());
        }
    }

    private static final class UsageReportingFactory implements SubAgentFactory {
        final CountDownLatch reported = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        @Override
        public SubAgentResult runSubAgent(SubAgentRequest request) {
            request.progressCallback().onProgress("Reading build.gradle.kts", 0.5);
            request.progressCallback().onAgentUsage("assistant-1",
                new Usage(100, 20, 10, 5));
            reported.countDown();
            try {
                release.await();
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("interrupted");
            }
            return SubAgentResult.of("done", 135, 0.0);
        }
    }

    private static final class MultiTurnUsageReportingFactory implements SubAgentFactory {
        final AtomicInteger turns = new AtomicInteger();
        final CountDownLatch firstReported = new CountDownLatch(1);
        final CountDownLatch firstRelease = new CountDownLatch(1);
        final CountDownLatch secondStarted = new CountDownLatch(1);
        final CountDownLatch secondUsageRelease = new CountDownLatch(1);
        final CountDownLatch secondReported = new CountDownLatch(1);
        final CountDownLatch secondRelease = new CountDownLatch(1);

        @Override
        public SubAgentResult runSubAgent(SubAgentRequest request) {
            int turn = turns.incrementAndGet();
            if (turn == 2) {
                secondStarted.countDown();
                try {
                    secondUsageRelease.await();
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("interrupted");
                }
            }
            request.progressCallback().onAgentUsage("assistant-" + turn,
                turn == 1 ? new Usage(100, 20, 0, 0) : new Usage(200, 30, 0, 0));
            CountDownLatch reported = turn == 1 ? firstReported : secondReported;
            CountDownLatch release = turn == 1 ? firstRelease : secondRelease;
            reported.countDown();
            try {
                release.await();
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("interrupted");
            }
            // The second result deliberately represents a whole-conversation
            // recomputation. The live per-turn tracker must remain authoritative.
            return SubAgentResult.of("done-" + turn, turn == 1 ? 120 : 250, 0.0);
        }
    }

    private static final class ActivityReportingFactory implements SubAgentFactory {
        final CountDownLatch reported = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        @Override
        public SubAgentResult runSubAgent(SubAgentRequest request) {
            request.progressCallback().onProgress("Using tool...", 0.5);
            request.progressCallback().onAgentMessage(new AssistantMessage(
                "assistant-activities",
                AssistantContent.of(List.of(
                    new ToolUseBlock("grep-1", "Grep",
                        JsonUtils.getMapper().createObjectNode().put("pattern", "Task")),
                    new ToolUseBlock("read-1", "Read",
                        JsonUtils.getMapper().createObjectNode()
                            .put("file_path", "TaskBoardProjection.java"))))),
                "agent-1");
            reported.countDown();
            try {
                release.await();
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("interrupted");
            }
            return SubAgentResult.of("done");
        }
    }

    private static final class InternalActivityReportingFactory implements SubAgentFactory {
        final CountDownLatch reported = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        @Override
        public SubAgentResult runSubAgent(SubAgentRequest request) {
            request.progressCallback().onAgentMessage(new AssistantMessage(
                "assistant-internal-activities",
                AssistantContent.of(List.of(
                    new ToolUseBlock("grep-1", "Grep",
                        JsonUtils.getMapper().createObjectNode().put("pattern", "Task")),
                    new ToolUseBlock("output-1", "StructuredOutput",
                        JsonUtils.getMapper().createObjectNode().put("result", "ok")),
                    new ToolUseBlock("repl-1", "REPL",
                        JsonUtils.getMapper().createObjectNode().put("command", "1 + 1")),
                    new ToolUseBlock("read-1", "Read",
                        JsonUtils.getMapper().createObjectNode()
                            .put("file_path", "TaskBoardProjection.java"))))),
                "agent-1");
            reported.countDown();
            try {
                release.await();
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("interrupted");
            }
            return SubAgentResult.of("done");
        }
    }

    private static final class PowerShellActivityReportingFactory implements SubAgentFactory {
        final CountDownLatch reported = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        @Override
        public SubAgentResult runSubAgent(SubAgentRequest request) {
            request.progressCallback().onAgentMessage(new AssistantMessage(
                "assistant-powershell-activities",
                AssistantContent.of(List.of(
                    new ToolUseBlock("search-1", "PowerShell",
                        JsonUtils.getMapper().createObjectNode()
                            .put("command", "Select-String Task files.txt")),
                    new ToolUseBlock("read-1", "PowerShell",
                        JsonUtils.getMapper().createObjectNode()
                            .put("command", "Get-Content files.txt"))))),
                "agent-1");
            reported.countDown();
            try {
                release.await();
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("interrupted");
            }
            return SubAgentResult.of("done");
        }
    }

    private static final class ProgressOnlyReportingFactory implements SubAgentFactory {
        final CountDownLatch reported = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        @Override
        public SubAgentResult runSubAgent(SubAgentRequest request) {
            request.progressCallback().onProgress("streaming shell output", 0.5);
            reported.countDown();
            try {
                release.await();
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("interrupted");
            }
            return SubAgentResult.of("done");
        }
    }

    private static final class WriteActivityReportingFactory implements SubAgentFactory {
        final CountDownLatch reported = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        @Override
        public SubAgentResult runSubAgent(SubAgentRequest request) {
            request.progressCallback().onAgentMessage(new AssistantMessage(
                "assistant-write-activity",
                AssistantContent.of(List.of(new ToolUseBlock("write-1", "Write",
                    JsonUtils.getMapper().createObjectNode()
                        .put("file_path", request.parentContext().workingDirectory()
                            + "/src/App.java"))))),
                "agent-1");
            reported.countDown();
            try {
                release.await();
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("interrupted");
            }
            return SubAgentResult.of("done");
        }
    }

    /** A factory that records every prompt it is asked to run (multi-turn check). */
    private static final class PromptCapturingFactory implements SubAgentFactory {
        final List<String> prompts = new ArrayList<>();
        final AtomicInteger turns = new AtomicInteger(0);
        @Override
        public SubAgentResult runSubAgent(SubAgentRequest request) {
            prompts.add(request.prompt());
            int n = turns.incrementAndGet();
            return SubAgentResult.of("ok-" + n);
        }
    }

    private static final class MessagePriorityFactory implements SubAgentFactory {
        final List<String> prompts = new CopyOnWriteArrayList<>();
        final CountDownLatch firstStarted = new CountDownLatch(1);
        final CountDownLatch firstRelease = new CountDownLatch(1);
        final CountDownLatch secondStarted = new CountDownLatch(1);

        @Override
        public SubAgentResult runSubAgent(SubAgentRequest request) {
            prompts.add(request.prompt());
            if (prompts.size() == 1) {
                firstStarted.countDown();
                try {
                    firstRelease.await();
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("interrupted");
                }
                return SubAgentResult.of("first done");
            }
            secondStarted.countDown();
            try {
                new ArrayBlockingQueue<Object>(1).take();
                return SubAgentResult.of("unexpected");
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("interrupted");
            }
        }
    }

    private static void waitForStatus(TaskStore store, String id, TaskStatus status, long timeoutMs)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < deadline) {
            if (Boolean.TRUE.equals(store.get(id).map(t -> t.status() == status).orElse(false))) {
                return;
            }
            Thread.sleep(10);
        }
        fail("task " + id + " did not reach " + status + " within " + timeoutMs + "ms (status="
            + store.get(id).map(t -> t.status().name()).orElse("absent") + ")");
    }

    @Test
    void startRunsNoOpFactoryThenIdlesAndStops() throws Exception {
        TaskStore store = TaskStore.inMemory();
        TaskState task = store.create(TaskType.IN_PROCESS_TEAMMATE, "Teammate");
        InProcessTeammateTask teammate = new InProcessTeammateTask(
            task, store, new NoOpSubAgentFactory(), teammateRequest(), teammateContext(task.id()));

        assertFalse(teammate.isActive());
        teammate.start();
        assertTrue(teammate.isActive());

        // Turn 1 runs; the multi-turn loop then signals idle and waits for the
        // next prompt (it does not self-terminate). Observe the idle signal.
        Mail idle = mailbox.receive(TeammateMailbox.TEAM_LEAD);
        assertEquals(MailTypes.IDLE_NOTIFICATION, idle.type());
        // The turn's output is recorded so the leader can see it.
        assertTrue(store.get(task.id()).get().finalMessage().isPresent());

        // Cooperatively shut the teammate down (the loop only ends on stop/kill).
        teammate.stop();
        waitForStatus(store, task.id(), TaskStatus.KILLED, 2000);
        assertFalse(teammate.isActive(), "teammate should be inactive after stop");
    }

    @Test
    void stopAbortsControllerAndTerminatesRunner() throws Exception {
        TaskStore store = TaskStore.inMemory();
        TaskState task = store.create(TaskType.IN_PROCESS_TEAMMATE, "Teammate");
        AbortController controller = new AbortController();
        TeammateContext ctx = TeammateContext.builder()
            .agentId(task.id()).teamId("team-1").abortController(controller).build();
        InProcessTeammateTask teammate = new InProcessTeammateTask(
            task, store, new BlockingFactory(), teammateRequest(), ctx);

        teammate.start();
        assertTrue(teammate.isActive());

        // Give the runner a moment to enter the blocked factory call.
        Thread.sleep(50);
        teammate.stop();

        // Cooperative abort: the shared AbortController is signalled.
        assertTrue(controller.isAborted(), "stop() must abort the shared controller");
        // The runner thread must have been interrupted and terminated.
        Thread runner = teammate.runnerThreadForTest();
        if (runner != null) {
            runner.join(2000);
            assertFalse(runner.isAlive());
        }
        assertFalse(teammate.isActive());
        // Cooperative shutdown transitions the teammate to a terminal state.
        waitForStatus(store, task.id(), TaskStatus.KILLED, 2000);
    }

    @Test
    void stoppingTeammateUnassignsItsOpenTasksLikeReleased197() throws Exception {
        TaskStore store = TaskStore.inMemory();
        TaskState teammateState = store.create(TaskType.IN_PROCESS_TEAMMATE, "Teammate");
        TodoStore todos = TodoStore.inMemory();
        Task byId = todos.create("Owned by id", "", null, null);
        Task byName = todos.create("Owned by name", "", null, null);
        Task completed = todos.create("Already complete", "", null, null);
        todos.update(byId.id(), byId.withOwner(teammateState.id())
            .withStatus(TodoStatus.IN_PROGRESS));
        todos.update(byName.id(), byName.withOwner("reviewer")
            .withStatus(TodoStatus.IN_PROGRESS));
        todos.update(completed.id(), completed.withOwner("reviewer")
            .withStatus(TodoStatus.COMPLETED));
        TeammateContext context = TeammateContext.builder()
            .agentId(teammateState.id())
            .teamId("team-1")
            .name("reviewer")
            .abortController(new AbortController())
            .build();
        InProcessTeammateTask teammate = new InProcessTeammateTask(
            teammateState, store, new BlockingFactory(), teammateRequest(), context, todos);

        teammate.start();
        teammate.stop();
        waitForStatus(store, teammateState.id(), TaskStatus.KILLED, 2000);

        Task releasedById = todos.get(byId.id()).orElseThrow();
        Task releasedByName = todos.get(byName.id()).orElseThrow();
        Task stillCompleted = todos.get(completed.id()).orElseThrow();
        assertEquals(TodoStatus.PENDING, releasedById.status());
        assertTrue(releasedById.owner().isEmpty());
        assertEquals(TodoStatus.PENDING, releasedByName.status());
        assertTrue(releasedByName.owner().isEmpty());
        assertEquals(TodoStatus.COMPLETED, stillCompleted.status());
        assertEquals("reviewer", stillCompleted.owner().orElseThrow());
    }

    @Test
    void permissionBridgeForwardsToLeaderAndReturnsDecision() throws Exception {
        TaskStore store = TaskStore.inMemory();
        TaskState task = store.create(TaskType.IN_PROCESS_TEAMMATE, "Teammate");

        // The teammate's sub-agent ask must route through the mailbox bridge,
        // so its request carries a TeammatePermissionAskCallback resolving to
        // this handle. (The handle is referenced lazily by the supplier, which
        // is only invoked during the run — after the handle is assigned.)
        // Use an array holder so the supplier resolves the handle at call time
        // while keeping the captured reference effectively final.
        final InProcessTeammateTask[] teammateRef = new InProcessTeammateTask[1];
        ToolExecutionContext leaderCtx = testContext()
            .withPermissionAskCallback(new TeammatePermissionAskCallback(() -> teammateRef[0]));
        SubAgentRequest bridgedRequest = teammateRequest().withParentContext(leaderCtx);

        InProcessTeammateTask teammate = new InProcessTeammateTask(
            task, store, new PermissionAskingFactory(), bridgedRequest, teammateContext(task.id()));
        teammateRef[0] = teammate;
        teammate.start();

        // Service the leader inbox: the teammate's requestPermission forwarded
        // a PERMISSION_REQUEST here and is blocked on the response.
        Mail request = mailbox.receive(TeammateMailbox.TEAM_LEAD);
        assertEquals(MailTypes.PERMISSION_REQUEST, request.type());

        Thread.sleep(40);

        mailbox.send(Mail.reply(request, MailTypes.PERMISSION_RESPONSE, TeammateMailbox.TEAM_LEAD,
            InProcessTeammateTask.encodeDecision(PermissionAskCallback.Result.allowWithFeedback("go"))));

        // The allow decision flows back; the teammate finishes turn 1, records
        // its output, then signals idle (the multi-turn loop continues).
        Mail idle = mailbox.receive(TeammateMailbox.TEAM_LEAD);
        assertEquals(MailTypes.IDLE_NOTIFICATION, idle.type());
        assertTrue(Strings.CS.contains(store.get(task.id()).get().finalMessage().orElse(""), "decision=true"));
        assertTrue(teammate.totalPausedMillis() >= 30L,
            "permission wait must be excluded from teammate elapsed time");

        teammate.stop();
    }

    @Test
    void liveUsageIsAvailableToTheParentSpinnerBeforeTheTurnFinishes() throws Exception {
        TaskStore store = TaskStore.inMemory();
        TaskState task = store.create(TaskType.IN_PROCESS_TEAMMATE, "Teammate");
        UsageReportingFactory factory = new UsageReportingFactory();
        InProcessTeammateTask teammate = new InProcessTeammateTask(
            task, store, factory, teammateRequest(), teammateContext(task.id()));

        teammate.start();
        assertTrue(factory.reported.await(2, TimeUnit.SECONDS));
        assertEquals(135L, teammate.progressTokens());
        assertEquals("Reading build.gradle.kts", teammate.progressActivity());

        factory.release.countDown();
        assertEquals(MailTypes.IDLE_NOTIFICATION,
            mailbox.receive(TeammateMailbox.TEAM_LEAD).type());
        teammate.stop();
    }

    @Test
    void liveUsageResetsForEachTeammateTurnLikeReleasedTracker() throws Exception {
        TaskStore store = TaskStore.inMemory();
        TaskState task = store.create(TaskType.IN_PROCESS_TEAMMATE, "Teammate");
        MultiTurnUsageReportingFactory factory = new MultiTurnUsageReportingFactory();
        InProcessTeammateTask teammate = new InProcessTeammateTask(
            task, store, factory, teammateRequest(), teammateContext(task.id()));

        teammate.start();
        assertTrue(factory.firstReported.await(2, TimeUnit.SECONDS));
        assertEquals(120L, teammate.progressTokens());
        factory.firstRelease.countDown();
        assertEquals(MailTypes.IDLE_NOTIFICATION,
            mailbox.receive(TeammateMailbox.TEAM_LEAD).type());

        mailbox.send(Mail.of(MailTypes.USER_MESSAGE,
            TeammateMailbox.TEAM_LEAD, task.id(), "continue"));
        assertTrue(factory.secondStarted.await(2, TimeUnit.SECONDS));
        assertEquals(0L, teammate.progressTokens(),
            "released progress tracker starts each prompt with zero tokens");
        factory.secondUsageRelease.countDown();
        assertTrue(factory.secondReported.await(2, TimeUnit.SECONDS));
        assertEquals(230L, teammate.progressTokens(),
            "released in-process runner creates a fresh progress tracker per prompt");

        factory.secondRelease.countDown();
        assertEquals(MailTypes.IDLE_NOTIFICATION,
            mailbox.receive(TeammateMailbox.TEAM_LEAD).type());
        assertEquals(230L, teammate.progressTokens());
        teammate.stop();
    }

    @Test
    void consecutiveSearchAndReadActivitiesUseReleased197Aggregation() throws Exception {
        TaskStore store = TaskStore.inMemory();
        TaskState task = store.create(TaskType.IN_PROCESS_TEAMMATE, "Teammate");
        ActivityReportingFactory factory = new ActivityReportingFactory();
        InProcessTeammateTask teammate = new InProcessTeammateTask(
            task, store, factory, teammateRequest(), teammateContext(task.id()));

        teammate.start();
        assertTrue(factory.reported.await(2, TimeUnit.SECONDS));

        assertEquals(2, teammate.progressToolUses());
        assertEquals("Searching for 1 pattern, reading 1 file…", teammate.progressActivity());

        factory.release.countDown();
        assertEquals(MailTypes.IDLE_NOTIFICATION,
            mailbox.receive(TeammateMailbox.TEAM_LEAD).type());
        teammate.stop();
    }

    @Test
    void released197InternalToolsCountButDoNotBreakActivityAggregation() throws Exception {
        TaskStore store = TaskStore.inMemory();
        TaskState task = store.create(TaskType.IN_PROCESS_TEAMMATE, "Teammate");
        InternalActivityReportingFactory factory = new InternalActivityReportingFactory();
        InProcessTeammateTask teammate = new InProcessTeammateTask(
            task, store, factory, teammateRequest(), teammateContext(task.id()));

        teammate.start();
        assertTrue(factory.reported.await(2, TimeUnit.SECONDS));

        assertEquals(4, teammate.progressToolUses());
        assertEquals("Searching for 1 pattern, reading 1 file…", teammate.taskBoardActivity());

        factory.release.countDown();
        assertEquals(MailTypes.IDLE_NOTIFICATION,
            mailbox.receive(TeammateMailbox.TEAM_LEAD).type());
        teammate.stop();
    }

    @Test
    void released197AutoClaimUsesFalsyEmptyOwnerAndLiteralPromptWhitespace() {
        Task available = new Task("7", "Ship it", "   ", null, Optional.of(""),
            TodoStatus.PENDING, List.of(), List.of(), null);

        assertSame(available,
            InProcessTeammateTask.findAvailableTask(List.of(available)));
        assertEquals("Complete all open tasks. Start with task #7: \n\n Ship it\n\n   ",
            InProcessTeammateTask.formatTaskAsPrompt(available));
    }

    @Test
    void teammateClaimsAvailableTaskBeforeItsInitialTurnLikeReleased197() throws Exception {
        TaskStore store = TaskStore.inMemory();
        TaskState teammateState = store.create(TaskType.IN_PROCESS_TEAMMATE, "Teammate");
        TodoStore todos = TodoStore.inMemory();
        Task available = todos.create("Claim immediately", "", null, null);
        MessagePriorityFactory factory = new MessagePriorityFactory();
        TeammateContext context = TeammateContext.builder()
            .agentId(teammateState.id())
            .teamId("team-1")
            .name("reviewer")
            .abortController(new AbortController())
            .build();
        InProcessTeammateTask teammate = new InProcessTeammateTask(
            teammateState, store, factory, teammateRequest(), context, todos);

        teammate.start();
        assertTrue(factory.firstStarted.await(2, TimeUnit.SECONDS));

        Task claimed = todos.get(available.id()).orElseThrow();
        assertEquals(TodoStatus.IN_PROGRESS, claimed.status());
        assertEquals("reviewer", claimed.owner().orElseThrow());
        factory.firstRelease.countDown();
        teammate.stop();
    }

    @Test
    void queuedLeaderMessageWinsOverAutoClaimLikeReleased197() throws Exception {
        TaskStore store = TaskStore.inMemory();
        TaskState teammateState = store.create(TaskType.IN_PROCESS_TEAMMATE, "Teammate");
        TodoStore todos = TodoStore.inMemory();
        Task claimedAtSpawn = todos.create("Claim at spawn", "", null, null);
        Task available = todos.create("Claim later", "", null, null);
        MessagePriorityFactory factory = new MessagePriorityFactory();
        TeammateContext context = TeammateContext.builder()
            .agentId(teammateState.id())
            .teamId("team-1")
            .name("reviewer")
            .abortController(new AbortController())
            .build();
        InProcessTeammateTask teammate = new InProcessTeammateTask(
            teammateState, store, factory, teammateRequest(), context, todos);

        teammate.start();
        assertTrue(factory.firstStarted.await(2, TimeUnit.SECONDS));
        assertEquals(TodoStatus.IN_PROGRESS,
            todos.get(claimedAtSpawn.id()).orElseThrow().status());
        assertEquals("reviewer",
            todos.get(claimedAtSpawn.id()).orElseThrow().owner().orElseThrow());
        mailbox.send(Mail.of(MailTypes.USER_MESSAGE,
            TeammateMailbox.TEAM_LEAD, teammateState.id(), "urgent leader work"));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (teammate.messages().stream()
                .noneMatch(mail -> "urgent leader work".equals(mail.payload()))
                && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        factory.firstRelease.countDown();

        assertTrue(factory.secondStarted.await(2, TimeUnit.SECONDS));
        assertEquals("urgent leader work", factory.prompts.get(1));
        Task notYetClaimed = todos.get(available.id()).orElseThrow();
        assertEquals(TodoStatus.PENDING, notYetClaimed.status());
        assertTrue(notYetClaimed.owner().isEmpty());
        teammate.stop();
    }

    @Test
    void taskAssignmentStartsTheAssignedTeammatesNextTurnLikeReleased197() throws Exception {
        AgentTeamsEnabled.setEnabledForTest(true);
        TaskStore store = TaskStore.inMemory();
        TaskState teammateState = store.create(TaskType.IN_PROCESS_TEAMMATE, "Teammate");
        TodoStore todos = TodoStore.inMemory();
        MessagePriorityFactory factory = new MessagePriorityFactory();
        TeammateContext context = TeammateContext.builder()
            .agentId(teammateState.id())
            .teamId("team-1")
            .name("reviewer")
            .abortController(new AbortController())
            .build();
        InProcessTeammateTask teammate = new InProcessTeammateTask(
            teammateState, store, factory, teammateRequest(), context, todos);
        teammate.start();
        assertTrue(factory.firstStarted.await(2, TimeUnit.SECONDS));

        Task assigned = todos.create("Ship", "finish it", null, null);
        var update = JsonUtils.getMapper().createObjectNode();
        update.put("taskId", assigned.id());
        update.put("owner", "reviewer");
        new TaskUpdateTool(todos).call(update, testContext());
        factory.firstRelease.countDown();

        assertTrue(factory.secondStarted.await(2, TimeUnit.SECONDS));
        String prompt = factory.prompts.get(1);
        assertTrue(prompt.startsWith(
            "<teammate-message teammate_id=\"team-lead\">\n"));
        assertTrue(prompt.endsWith("\n</teammate-message>"));
        String assignmentJson = prompt.substring(
            prompt.indexOf('\n') + 1, prompt.lastIndexOf("\n</teammate-message>"));
        JsonNode assignment = JsonUtils.getMapper().readTree(assignmentJson);
        assertEquals("task_assignment", assignment.path("type").asText());
        assertEquals(assigned.id(), assignment.path("taskId").asText());
        assertEquals("Ship", assignment.path("subject").asText());
        assertEquals("finish it", assignment.path("description").asText());
        assertEquals("team-lead", assignment.path("assignedBy").asText());
        teammate.stop();
    }

    @Test
    void powershellSearchAndReadActivitiesUseReleased197Aggregation() throws Exception {
        TaskStore store = TaskStore.inMemory();
        TaskState task = store.create(TaskType.IN_PROCESS_TEAMMATE, "Teammate");
        PowerShellActivityReportingFactory factory = new PowerShellActivityReportingFactory();
        InProcessTeammateTask teammate = new InProcessTeammateTask(
            task, store, factory, teammateRequest(), teammateContext(task.id()));

        teammate.start();
        assertTrue(factory.reported.await(2, TimeUnit.SECONDS));

        assertEquals("Searching for 1 pattern, reading 1 file…",
            teammate.taskBoardActivity());

        factory.release.countDown();
        assertEquals(MailTypes.IDLE_NOTIFICATION,
            mailbox.receive(TeammateMailbox.TEAM_LEAD).type());
        teammate.stop();
    }

    @Test
    void taskBoardActivityIgnoresGenericProgressStreamLikeReleased197() throws Exception {
        TaskStore store = TaskStore.inMemory();
        TaskState task = store.create(TaskType.IN_PROCESS_TEAMMATE, "Teammate");
        ProgressOnlyReportingFactory factory = new ProgressOnlyReportingFactory();
        InProcessTeammateTask teammate = new InProcessTeammateTask(
            task, store, factory, teammateRequest(), teammateContext(task.id()));

        teammate.start();
        assertTrue(factory.reported.await(2, TimeUnit.SECONDS));

        assertEquals("streaming shell output", teammate.progressActivity());
        assertNull(teammate.taskBoardActivity());

        factory.release.countDown();
        assertEquals(MailTypes.IDLE_NOTIFICATION,
            mailbox.receive(TeammateMailbox.TEAM_LEAD).type());
        teammate.stop();
    }

    @Test
    void taskBoardUsesReleased197ToolActivityDescriptions() throws Exception {
        TaskStore store = TaskStore.inMemory();
        TaskState task = store.create(TaskType.IN_PROCESS_TEAMMATE, "Teammate");
        WriteActivityReportingFactory factory = new WriteActivityReportingFactory();
        InProcessTeammateTask teammate = new InProcessTeammateTask(
            task, store, factory, teammateRequest(), teammateContext(task.id()));

        teammate.start();
        assertTrue(factory.reported.await(2, TimeUnit.SECONDS));

        assertEquals("Writing src/App.java", teammate.taskBoardActivity());

        factory.release.countDown();
        assertEquals(MailTypes.IDLE_NOTIFICATION,
            mailbox.receive(TeammateMailbox.TEAM_LEAD).type());
        teammate.stop();
    }

    @Test
    void multiTurnDeliversInjectedPrompt() throws Exception {
        TaskStore store = TaskStore.inMemory();
        TaskState task = store.create(TaskType.IN_PROCESS_TEAMMATE, "Teammate");
        PromptCapturingFactory factory = new PromptCapturingFactory();
        InProcessTeammateTask teammate = new InProcessTeammateTask(
            task, store, factory, teammateRequest().withPrompt("first"), teammateContext(task.id()));
        teammate.start();

        // Turn 1 runs with the original prompt and then signals idle.
        Mail idle1 = mailbox.receive(TeammateMailbox.TEAM_LEAD);
        assertEquals(MailTypes.IDLE_NOTIFICATION, idle1.type());
        assertEquals(1, factory.turns.get());
        assertEquals("first", factory.prompts.getFirst());

        // The leader dispatches a fresh prompt; the loop must feed it to turn 2.
        mailbox.send(Mail.of(MailTypes.USER_MESSAGE, TeammateMailbox.TEAM_LEAD, task.id(), "second"));
        Mail idle2 = mailbox.receive(TeammateMailbox.TEAM_LEAD);
        assertEquals(MailTypes.IDLE_NOTIFICATION, idle2.type());
        assertEquals(2, factory.turns.get());
        assertEquals("second", factory.prompts.get(1));

        teammate.stop();
    }

    @Test
    void idleNotificationCarriesNameWhenPresent() throws Exception {
        TaskStore store = TaskStore.inMemory();
        TaskState task = store.create(TaskType.IN_PROCESS_TEAMMATE, "Teammate");
        TeammateContext ctx = TeammateContext.builder()
            .agentId(task.id()).teamId("team-1").abortController(new AbortController()).name("researcher-1").build();
        InProcessTeammateTask teammate = new InProcessTeammateTask(
            task, store, new NoOpSubAgentFactory(), teammateRequest(), ctx);
        teammate.start();

        Mail idle = mailbox.receive(TeammateMailbox.TEAM_LEAD);
        assertEquals(MailTypes.IDLE_NOTIFICATION, idle.type());
        assertTrue(Strings.CS.contains(idle.payload(), "name=researcher-1"),
            "idle payload should carry the teammate name: " + idle.payload());

        teammate.stop();
    }

    @Test
    void teammateIdleBlockFeedsBackIntoNextTurnBeforeLeaderIsNotified() throws Exception {
        TaskStore store = TaskStore.inMemory();
        TaskState task = store.create(TaskType.IN_PROCESS_TEAMMATE, "Teammate");
        PromptCapturingFactory factory = new PromptCapturingFactory();
        AtomicInteger idleCalls = new AtomicInteger();
        HookDispatcher hooks = new HookAdapter() {
            @Override public HookOutcome dispatchTeammateIdleWithOutcome(
                    String teammateName, String teamName) {
                if (idleCalls.incrementAndGet() == 1) {
                    return new HookOutcome(false, null, List.of("finish the report"));
                }
                return HookOutcome.PROCEED;
            }
        };
        InProcessTeammateTask teammate = new InProcessTeammateTask(
            task, store, factory, teammateRequest().withPrompt("first"),
            teammateContext(task.id()), null, hooks);

        teammate.start();
        Mail idle = mailbox.receive(TeammateMailbox.TEAM_LEAD);

        assertEquals(MailTypes.IDLE_NOTIFICATION, idle.type());
        assertEquals(2, factory.turns.get());
        assertEquals("TeammateIdle hook feedback:\nfinish the report", factory.prompts.get(1));
        teammate.stop();
    }

    @Test
    void taskCompletedHooksRunBeforeTeammateIdleAndBlockIdleNotification() throws Exception {
        TaskStore store = TaskStore.inMemory();
        TaskState teammateState = store.create(TaskType.IN_PROCESS_TEAMMATE, "Teammate");
        TodoStore todos = TodoStore.inMemory();
        Task owned = todos.create("Ship", "finish it", null, null);
        todos.update(owned.id(), owned.withOwner("reviewer")
            .withStatus(TodoStatus.IN_PROGRESS));
        PromptCapturingFactory factory = new PromptCapturingFactory();
        List<String> events = new ArrayList<>();
        AtomicInteger completedCalls = new AtomicInteger();
        HookDispatcher hooks = new HookAdapter() {
            @Override public HookOutcome dispatchTaskCompletedWithOutcome(
                    String taskId, String subject, String description) {
                events.add("task");
                return completedCalls.incrementAndGet() == 1
                    ? new HookOutcome(false, null, List.of("not verified"))
                    : HookOutcome.PROCEED;
            }
            @Override public HookOutcome dispatchTeammateIdleWithOutcome(
                    String teammateName, String teamName) {
                events.add("idle");
                return HookOutcome.PROCEED;
            }
        };
        TeammateContext context = TeammateContext.builder()
            .agentId(teammateState.id())
            .teamId("team-1")
            .name("reviewer")
            .abortController(new AbortController())
            .build();
        InProcessTeammateTask teammate = new InProcessTeammateTask(
            teammateState, store, factory, teammateRequest(), context, todos, hooks);

        teammate.start();
        mailbox.receive(TeammateMailbox.TEAM_LEAD);

        assertEquals(List.of("task", "idle", "task", "idle"), events);
        assertEquals("TaskCompleted hook feedback:\nnot verified", factory.prompts.get(1));
        teammate.stop();
    }

    @Test
    void idleBoundaryAggregatesAllTaskCompletedFeedbackLikeReleased197() throws Exception {
        TaskStore store = TaskStore.inMemory();
        TaskState teammateState = store.create(TaskType.IN_PROCESS_TEAMMATE, "Teammate");
        TodoStore todos = TodoStore.inMemory();
        Task first = todos.create("First", "", null, null);
        Task second = todos.create("Second", "", null, null);
        todos.update(first.id(), first.withOwner("reviewer")
            .withStatus(TodoStatus.IN_PROGRESS));
        todos.update(second.id(), second.withOwner("reviewer")
            .withStatus(TodoStatus.IN_PROGRESS));
        PromptCapturingFactory factory = new PromptCapturingFactory();
        AtomicInteger taskCalls = new AtomicInteger();
        AtomicInteger idleCalls = new AtomicInteger();
        HookDispatcher hooks = new HookAdapter() {
            @Override public HookOutcome dispatchTaskCompletedWithOutcome(
                    String taskId, String subject, String description) {
                int call = taskCalls.incrementAndGet();
                if (call == 1) return new HookOutcome(false, null, List.of("first failed"));
                if (call == 2) return new HookOutcome(false, null, List.of("second failed"));
                return HookOutcome.PROCEED;
            }
            @Override public HookOutcome dispatchTeammateIdleWithOutcome(
                    String teammateName, String teamName) {
                idleCalls.incrementAndGet();
                return HookOutcome.PROCEED;
            }
        };
        TeammateContext context = TeammateContext.builder()
            .agentId(teammateState.id())
            .teamId("team-1")
            .name("reviewer")
            .abortController(new AbortController())
            .build();
        InProcessTeammateTask teammate = new InProcessTeammateTask(
            teammateState, store, factory, teammateRequest(), context, todos, hooks);

        teammate.start();
        assertEquals(MailTypes.IDLE_NOTIFICATION,
            mailbox.receive(TeammateMailbox.TEAM_LEAD).type());

        assertEquals(4, taskCalls.get());
        assertEquals(2, idleCalls.get());
        assertEquals("""
            TaskCompleted hook feedback:
            first failed
            TaskCompleted hook feedback:
            second failed""", factory.prompts.get(1));
        teammate.stop();
    }

    @Test
    void taskCompletedIdleBoundaryDoesNotTreatAgentIdAsOwnerLikeReleased197()
            throws Exception {
        TaskStore store = TaskStore.inMemory();
        TaskState teammateState = store.create(TaskType.IN_PROCESS_TEAMMATE, "Teammate");
        TodoStore todos = TodoStore.inMemory();
        Task ownedById = todos.create("Not mine by name", "", null, null);
        todos.update(ownedById.id(), ownedById.withOwner(teammateState.id())
            .withStatus(TodoStatus.IN_PROGRESS));
        PromptCapturingFactory factory = new PromptCapturingFactory();
        AtomicInteger completedCalls = new AtomicInteger();
        AtomicInteger idleCalls = new AtomicInteger();
        HookDispatcher hooks = new HookAdapter() {
            @Override public HookOutcome dispatchTaskCompletedWithOutcome(
                    String taskId, String subject, String description) {
                completedCalls.incrementAndGet();
                return HookOutcome.PROCEED;
            }
            @Override public HookOutcome dispatchTeammateIdleWithOutcome(
                    String teammateName, String teamName) {
                idleCalls.incrementAndGet();
                return HookOutcome.PROCEED;
            }
        };
        TeammateContext context = TeammateContext.builder()
            .agentId(teammateState.id())
            .teamId("team-1")
            .name("reviewer")
            .abortController(new AbortController())
            .build();
        InProcessTeammateTask teammate = new InProcessTeammateTask(
            teammateState, store, factory, teammateRequest(), context, todos, hooks);

        teammate.start();
        assertEquals(MailTypes.IDLE_NOTIFICATION,
            mailbox.receive(TeammateMailbox.TEAM_LEAD).type());

        assertEquals(0, completedCalls.get());
        assertEquals(1, idleCalls.get());
        teammate.stop();
    }

    @Test
    void taskCompletedIdleBoundaryReloadsTheSharedTaskListLikeReleased197(
            @TempDir Path tempDir) throws Exception {
        TaskStore store = TaskStore.inMemory();
        TaskState teammateState = store.create(TaskType.IN_PROCESS_TEAMMATE, "Teammate");
        TodoStore teammateView = new TodoStore(tempDir, "team-1");
        TodoStore leaderView = new TodoStore(tempDir, "team-1");
        Task assigned = leaderView.create("Ship", "finish it", null, null);
        leaderView.update(assigned.id(), assigned.withOwner("reviewer")
            .withStatus(TodoStatus.IN_PROGRESS));
        AtomicInteger completedCalls = new AtomicInteger();
        HookDispatcher hooks = new HookAdapter() {
            @Override public HookOutcome dispatchTaskCompletedWithOutcome(
                    String taskId, String subject, String description) {
                completedCalls.incrementAndGet();
                return HookOutcome.PROCEED;
            }
            @Override public HookOutcome dispatchTeammateIdleWithOutcome(
                    String teammateName, String teamName) {
                return HookOutcome.PROCEED;
            }
        };
        TeammateContext context = TeammateContext.builder()
            .agentId(teammateState.id())
            .teamId("team-1")
            .name("reviewer")
            .abortController(new AbortController())
            .build();
        InProcessTeammateTask teammate = new InProcessTeammateTask(
            teammateState, store, new PromptCapturingFactory(), teammateRequest(),
            context, teammateView, hooks);

        teammate.start();
        assertEquals(MailTypes.IDLE_NOTIFICATION,
            mailbox.receive(TeammateMailbox.TEAM_LEAD).type());

        assertEquals(1, completedCalls.get());
        teammate.stop();
    }

    private abstract static class HookAdapter implements HookDispatcher {
        @Override public boolean dispatchPreToolUse(String toolName,
                JsonNode input, String toolUseId) { return true; }
        @Override public void dispatchPostToolUse(String toolName,
                JsonNode input, JsonNode output, String toolUseId) { }
        @Override public void dispatchUserPromptSubmit(String prompt) { }
        @Override public void dispatchSessionStart(String trigger) { }
        @Override public void dispatchStop(String reason) { }
        @Override public void dispatchSessionEnd(String reason) { }
    }

    @Test
    void shutdownRequestDelegatedToModelAsNextPrompt() throws Exception {
        TaskStore store = TaskStore.inMemory();
        TaskState task = store.create(TaskType.IN_PROCESS_TEAMMATE, "Teammate");
        PromptCapturingFactory factory = new PromptCapturingFactory();
        InProcessTeammateTask teammate = new InProcessTeammateTask(
            task, store, factory, teammateRequest().withPrompt("first"), teammateContext(task.id()));
        teammate.start();

        mailbox.receive(TeammateMailbox.TEAM_LEAD); // consume idle after turn 1
// A shutdown_request is handed to the model as a wrapped message, not an immediate
// abort.
        mailbox.send(Mail.of(MailTypes.SHUTDOWN_REQUEST, TeammateMailbox.TEAM_LEAD, task.id(), "please stop"));

        Mail idle2 = mailbox.receive(TeammateMailbox.TEAM_LEAD);
        assertEquals(MailTypes.IDLE_NOTIFICATION, idle2.type());
        assertEquals(2, factory.turns.get());
        String shutdownPrompt = factory.prompts.get(1);
        assertTrue(Strings.CS.contains(shutdownPrompt, "teammate-message"), "shutdown must be wrapped: " + shutdownPrompt);
        assertTrue(Strings.CI.contains(shutdownPrompt, "shut down"),
            "shutdown wrapper must mention shutdown: " + shutdownPrompt);

        teammate.stop();
    }

    @Test
    void killTransitionsRunningTaskToKilled() {
        TaskStore store = TaskStore.inMemory();
        TaskState task = store.create(TaskType.IN_PROCESS_TEAMMATE, "Teammate");
        store.updateStatus(task.id(), TaskStatus.RUNNING);
        InProcessTeammateTask teammate = new InProcessTeammateTask(
            task, store, new NoOpSubAgentFactory(), teammateRequest(), teammateContext(task.id()));

        assertTrue(teammate.kill());
        assertEquals(TaskStatus.KILLED, store.get(task.id()).get().status());
    }
}
