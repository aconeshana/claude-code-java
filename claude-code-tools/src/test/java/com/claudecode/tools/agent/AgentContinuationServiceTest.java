package com.claudecode.tools.agent;

import com.claudecode.core.message.AssistantContent;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.ContentBlock;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.ThinkingBlock;
import com.claudecode.core.message.ToolResultBlock;
import com.claudecode.core.message.ToolUseBlock;
import com.claudecode.core.message.UserMessage;
import com.claudecode.core.engine.AbortController;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.core.engine.ToolResultBudget;
import com.claudecode.core.message.Usage;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.session.SessionManager;
import com.claudecode.session.SessionStorage;
import com.claudecode.tools.tasks.TaskRegistry;
import com.claudecode.tools.tasks.TaskStatus;
import com.claudecode.tools.tasks.TaskStore;
import com.claudecode.tools.tasks.TaskType;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentContinuationServiceTest {

    @Test
    void completedAgentIsResumedFromTranscriptUnderSameIdAndFinishes(@TempDir Path temp)
            throws Exception {
        String agentId = "a1234567890abcdef";
        Path transcript = temp.resolve("agent-" + agentId + ".jsonl");
        Path output = temp.resolve(agentId + ".output");
        UserMessage prior = new UserMessage("prior", MessageContent.ofText("original audit"));
        var persisted = (ObjectNode)
            JsonUtils.getMapper().valueToTree(prior);
        persisted.put("isSidechain", true);
        persisted.put("agentId", agentId);
        persisted.put("sessionId", "resume-session");
        persisted.putNull("parentUuid");
        Files.writeString(transcript, JsonUtils.getMapper().writeValueAsString(persisted)
            + System.lineSeparator(), StandardCharsets.UTF_8);

        AtomicReference<SubAgentRequest> captured = new AtomicReference<>();
        SubAgentFactory factory = request -> {
            captured.set(request);
            return SubAgentResult.of("continued result");
        };
        TaskRegistry registry = new TaskRegistry(TaskStore.inMemory());
        AgentContinuationService service = new AgentContinuationService(
            factory, registry, (_, _) -> transcript, (_, _) -> output);
        ToolExecutionContext context = ToolExecutionContext.of(
            new AbortController(), "resume-session");

        AgentContinuationService.ResumeResult result =
            service.resume(agentId, "continue the audit", context);

        long deadline = System.currentTimeMillis() + 2_000;
        while (System.currentTimeMillis() < deadline
                && registry.get(agentId).map(task -> task.status() == TaskStatus.RUNNING)
                    .orElse(true)) {
            Thread.onSpinWait();
        }
        SubAgentRequest request = captured.get();
        assertEquals(agentId, result.agentId());
        assertEquals(agentId, request.agentId());
        assertEquals("continue the audit", request.prompt());
        assertEquals(List.of("prior"), request.priorMessages().stream().map(Message::uuid).toList());
        UserMessage restored = assertInstanceOf(UserMessage.class,
            request.priorMessages().getFirst());
        assertEquals("original audit", restored.message().text());
        assertEquals(TaskStatus.COMPLETED, registry.get(agentId).orElseThrow().status());
        assertEquals("continued result",
            registry.get(agentId).orElseThrow().finalMessage().orElseThrow());
        assertTrue(Files.isSymbolicLink(output),
            "197 task output must point at the resumed sidechain transcript");
        assertEquals(transcript, Files.readSymbolicLink(output));
    }

    @Test
    void resumeRestoresPersistedContentReplacementState(@TempDir Path temp) throws Exception {
        String agentId = "a1234567890abcdef";
        Path transcript = temp.resolve("agent-" + agentId + ".jsonl");
        UserMessage prior = new UserMessage("prior", MessageContent.ofText("original audit"));
        ObjectNode persisted = (ObjectNode) JsonUtils.getMapper().valueToTree(prior);
        persisted.put("isSidechain", true);
        persisted.put("agentId", agentId);
        persisted.put("sessionId", "resume-session");
        persisted.putNull("parentUuid");
        ObjectNode replacement = JsonUtils.getMapper().createObjectNode();
        replacement.put("type", "content-replacement");
        replacement.put("agentId", agentId);
        replacement.putArray("replacements").addObject()
            .put("kind", "tool-result")
            .put("toolUseId", "tool-1")
            .put("replacement", "persisted preview");
        Files.writeString(transcript,
            JsonUtils.getMapper().writeValueAsString(persisted) + System.lineSeparator()
                + JsonUtils.getMapper().writeValueAsString(replacement) + System.lineSeparator());
        AtomicReference<SubAgentRequest> captured = new AtomicReference<>();
        AgentContinuationService service = new AgentContinuationService(
            request -> { captured.set(request); return SubAgentResult.of("continued"); },
            new TaskRegistry(TaskStore.inMemory()), (_, _) -> transcript,
            (_, _) -> temp.resolve(agentId + ".output"));

        service.resume(agentId, "continue", ToolExecutionContext.of(
            new AbortController(), "resume-session"));
        long deadline = System.currentTimeMillis() + 2_000;
        while (captured.get() == null && System.currentTimeMillis() < deadline) Thread.onSpinWait();

        assertEquals(List.of(new ToolResultBudget.Replacement("tool-1", "persisted preview")),
            captured.get().contentReplacements());
    }

    @Test
    void resumedAgentPublishesLiveUsageBeforeCompletion(@TempDir Path temp) throws Exception {
        String agentId = "a1234567890abcdef";
        Path transcript = temp.resolve("agent-" + agentId + ".jsonl");
        Files.writeString(transcript,
            persisted(new UserMessage("prior", MessageContent.ofText("original audit")),
                "resume-session", null, true, agentId, null) + System.lineSeparator());
        CountDownLatch usagePublished = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TaskRegistry registry = new TaskRegistry(TaskStore.inMemory());
        AgentContinuationService service = new AgentContinuationService(request -> {
            request.progressCallback().onAgentUsage("assistant-1", new Usage(100, 5, 20, 30));
            usagePublished.countDown();
            try {
                release.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
            return SubAgentResult.of("continued");
        }, registry, (_, _) -> transcript, (_, _) -> temp.resolve(agentId + ".output"));

        service.resume(agentId, "continue", ToolExecutionContext.of(
            new AbortController(), "resume-session"));
        assertTrue(usagePublished.await(2, TimeUnit.SECONDS));
        assertEquals(155, registry.get(agentId).orElseThrow().usage().orElseThrow().totalTokens());
        release.countDown();
    }

    @Test
    void persistedStoppedByUserBlocksModelResumeButHumanResumeClearsIt(@TempDir Path temp)
            throws Exception {
        String agentId = "a1234567890abcdef";
        Path transcript = temp.resolve("agent-" + agentId + ".jsonl");
        Files.writeString(transcript,
            persisted(new UserMessage("prior", MessageContent.ofText("original audit")),
                "resume-session", null, true, agentId, null) + System.lineSeparator());
        Files.writeString(temp.resolve("agent-" + agentId + ".meta.json"),
            "{\"agentType\":\"general-purpose\",\"stoppedByUser\":true}");
        AgentContinuationService service = new AgentContinuationService(
            _ -> SubAgentResult.of("continued"), new TaskRegistry(TaskStore.inMemory()),
            (_, _) -> transcript, (_, _) -> temp.resolve(agentId + ".output"));
        ToolExecutionContext context = ToolExecutionContext.of(
            new AbortController(), "resume-session");

        assertThrows(AgentContinuationService.UserStoppedAgentException.class,
            () -> service.resume(agentId, "model continue", context));
        service.resume(agentId, "human continue", context, true);
        assertFalse(new SessionStorage()
            .readAgentMetadata(transcript).orElseThrow().stoppedByUser());
    }

    @Test
    void resumeRestoresPersistedTreeDepthSnapshot(@TempDir Path temp) throws Exception {
        String agentId = "a1234567890abcdef";
        Path transcript = temp.resolve("agent-" + agentId + ".jsonl");
        Files.writeString(transcript,
            persisted(new UserMessage("prior", MessageContent.ofText("original audit")),
                "resume-session", null, true, agentId, null) + System.lineSeparator());
        Files.writeString(temp.resolve("agent-" + agentId + ".meta.json"),
            "{\"agentType\":\"general-purpose\",\"spawnDepth\":3,"
                + "\"subagentMaxDepth\":4}");
        AtomicReference<SubAgentRequest> captured = new AtomicReference<>();
        TaskRegistry registry = new TaskRegistry(TaskStore.inMemory());
        AgentContinuationService service = new AgentContinuationService(request -> {
            captured.set(request);
            return SubAgentResult.of("continued");
        }, registry, (_, _) -> transcript, (_, _) -> temp.resolve(agentId + ".output"));

        service.resume(agentId, "continue", ToolExecutionContext.of(
            new AbortController(), "resume-session"));
        awaitCompletion(registry, agentId, captured);

        assertEquals(3, captured.get().agentDepth());
        assertEquals(4, captured.get().subagentMaxDepthSnapshot());
    }

    @Test
    void resumeUsesOfficialFiveForDepthOnlyAndLegacyJavaCapForOldSidecar(
            @TempDir Path temp) throws Exception {
        assertResumeDepth(temp.resolve("official"),
            "{\"agentType\":\"general-purpose\",\"spawnDepth\":2}", 2, 5);
        assertResumeDepth(temp.resolve("legacy"),
            "{\"agentType\":\"general-purpose\"}", 1, 1);
    }

    private static void assertResumeDepth(Path dir, String metadata,
            int expectedDepth, int expectedMax) throws Exception {
        Files.createDirectories(dir);
        String agentId = "a1234567890abcdef";
        Path transcript = dir.resolve("agent-" + agentId + ".jsonl");
        Files.writeString(transcript,
            persisted(new UserMessage("prior", MessageContent.ofText("original audit")),
                "resume-session", null, true, agentId, null) + System.lineSeparator());
        Files.writeString(dir.resolve("agent-" + agentId + ".meta.json"), metadata);
        AtomicReference<SubAgentRequest> captured = new AtomicReference<>();
        TaskRegistry registry = new TaskRegistry(TaskStore.inMemory());
        AgentContinuationService service = new AgentContinuationService(request -> {
            captured.set(request);
            return SubAgentResult.of("continued");
        }, registry, (_, _) -> transcript, (_, _) -> dir.resolve(agentId + ".output"));

        service.resume(agentId, "continue", ToolExecutionContext.of(
            new AbortController(), "resume-session"));
        awaitCompletion(registry, agentId, captured);

        assertEquals(expectedDepth, captured.get().agentDepth());
        assertEquals(expectedMax, captured.get().subagentMaxDepthSnapshot());
    }

    private static void awaitCompletion(TaskRegistry registry, String agentId,
            AtomicReference<SubAgentRequest> captured) {
        long deadline = System.currentTimeMillis() + 2_000;
        while (System.currentTimeMillis() < deadline
                && (captured.get() == null || registry.get(agentId)
                    .map(task -> task.status() == TaskStatus.RUNNING).orElse(true))) {
            Thread.onSpinWait();
        }
        assertNotNull(captured.get());
        assertEquals(TaskStatus.COMPLETED, registry.get(agentId).orElseThrow().status());
    }

    @Test
    void forkedAgentResumeIncludesTheReferencedParentPrefix(@TempDir Path temp)
            throws Exception {
        String sessionId = "11111111-2222-3333-4444-555555555555";
        String agentId = "a1234567890abcdef";
        SessionManager manager = new SessionManager(temp, "/tmp/project");
        Path parentTranscript = manager.getSessionFile(sessionId);
        Path agentTranscript = manager.getAgentTranscriptPath(sessionId, agentId);
        Files.createDirectories(agentTranscript.getParent());

        Files.writeString(parentTranscript, String.join(System.lineSeparator(),
            persisted(new UserMessage("parent-u", MessageContent.ofText("parent prompt")),
                sessionId, null, false, null, null),
            persisted(new AssistantMessage("parent-a",
                    AssistantContent.of("parent-message", List.of(new TextBlock("parent reply")))),
                sessionId, "parent-u", false, null, null)) + System.lineSeparator());
        Files.writeString(agentTranscript, String.join(System.lineSeparator(),
            "{\"type\":\"fork-context-ref\",\"agentId\":\"" + agentId + "\","
                + "\"parentSessionId\":\"" + sessionId + "\","
                + "\"parentLastUuid\":\"parent-a\",\"contextLength\":2}",
            persisted(new UserMessage("agent-u", MessageContent.ofText("child prompt")),
                sessionId, null, true, agentId, null),
            persisted(new AssistantMessage("agent-a",
                    AssistantContent.of("agent-message", List.of(new TextBlock("child reply")))),
                sessionId, "agent-u", true, agentId, "general-purpose"))
            + System.lineSeparator());
        Files.writeString(agentTranscript.resolveSibling("agent-" + agentId + ".meta.json"),
            "{\"agentType\":\"fork\",\"description\":\"side question\"}");

        AtomicReference<SubAgentRequest> captured = new AtomicReference<>();
        AgentContinuationService service = new AgentContinuationService(
            request -> {
                captured.set(request);
                return SubAgentResult.of("continued");
            },
            new TaskRegistry(TaskStore.inMemory()),
            (_, _) -> agentTranscript,
            (_, _) -> temp.resolve(agentId + ".output"));

        service.resume(agentId, "continue", ToolExecutionContext.builder(
                new AbortController(), sessionId)
            .workingDirectory("/tmp/project")
            .renderedSystemPrompt("parent-system-prompt")
            .enabledTools(List.of("Read", "Bash"))
            .build());
        long deadline = System.currentTimeMillis() + 2_000;
        while (captured.get() == null && System.currentTimeMillis() < deadline) {
            Thread.onSpinWait();
        }

        assertNotNull(captured.get());
        assertEquals(List.of("parent-u", "parent-a", "agent-u", "agent-a"),
            captured.get().priorMessages().stream().map(Message::uuid).toList(),
            "the resumed model request must see the same fork prefix as 2.1.197");
        assertTrue(captured.get().fork());
        assertEquals("fork", captured.get().subagentType());
        assertEquals("parent-system-prompt", captured.get().systemPromptOverride());
        assertEquals(List.of("Read", "Bash"), captured.get().tools());
        assertEquals(200, captured.get().maxTurns());
        assertEquals("side question", captured.get().description());
    }

    @Test
    void persistedMetadataRestoresAgentTypeDescriptionAndWorktreeCwd(@TempDir Path temp)
            throws Exception {
        String sessionId = "resume-session";
        String agentId = "a1234567890abcdef";
        Path transcript = temp.resolve("agent-" + agentId + ".jsonl");
        Path output = temp.resolve(agentId + ".output");
        Path worktree = Files.createDirectory(temp.resolve("kept-worktree"));
        FileTime stale = FileTime.fromMillis(System.currentTimeMillis() - 60_000);
        Files.setLastModifiedTime(worktree, stale);
        Files.writeString(transcript,
            persisted(new UserMessage("prior", MessageContent.ofText("original audit")),
                sessionId, null, true, agentId, null) + System.lineSeparator());
        Files.writeString(temp.resolve("agent-" + agentId + ".meta.json"),
            "{\"agentType\":\"Explore\",\"worktreePath\":\""
                + worktree.toString().replace("\\", "\\\\")
                + "\",\"description\":\"inspect storage\"}");

        AtomicReference<SubAgentRequest> captured = new AtomicReference<>();
        TaskRegistry registry = new TaskRegistry(TaskStore.inMemory());
        AgentContinuationService service = new AgentContinuationService(
            request -> {
                captured.set(request);
                return SubAgentResult.of("continued");
            }, registry, (_, _) -> transcript, (_, _) -> output);
        ToolExecutionContext context = ToolExecutionContext.builder(
                new AbortController(), sessionId)
            .workingDirectory(temp.toString())
            .build();

        AgentContinuationService.ResumeResult result =
            service.resume(agentId, "continue", context);
        long deadline = System.currentTimeMillis() + 2_000;
        while (captured.get() == null && System.currentTimeMillis() < deadline) {
            Thread.onSpinWait();
        }

        assertNotNull(captured.get());
        assertEquals("Explore", captured.get().subagentType());
        assertEquals("inspect storage", captured.get().description());
        assertEquals(worktree.toString(), captured.get().cwd());
        assertEquals("inspect storage", result.description());
        assertTrue(Files.getLastModifiedTime(worktree).toMillis() > stale.toMillis(),
            "resuming a kept worktree must refresh its mtime before stale cleanup runs");
    }

    @Test
    void missingPersistedWorktreeFallsBackToParentCwd(@TempDir Path temp) throws Exception {
        String sessionId = "resume-session";
        String agentId = "a1234567890abcdef";
        Path transcript = temp.resolve("agent-" + agentId + ".jsonl");
        Files.writeString(transcript,
            persisted(new UserMessage("prior", MessageContent.ofText("original audit")),
                sessionId, null, true, agentId, null) + System.lineSeparator());
        Files.writeString(temp.resolve("agent-" + agentId + ".meta.json"),
            "{\"agentType\":\"Explore\",\"worktreePath\":\""
                + temp.resolve("deleted-worktree").toString().replace("\\", "\\\\") + "\"}");

        AtomicReference<SubAgentRequest> captured = new AtomicReference<>();
        AgentContinuationService service = new AgentContinuationService(
            request -> {
                captured.set(request);
                return SubAgentResult.of("continued");
            }, new TaskRegistry(TaskStore.inMemory()), (_, _) -> transcript,
            (_, _) -> temp.resolve(agentId + ".output"));

        service.resume(agentId, "continue", ToolExecutionContext.builder(
                new AbortController(), sessionId)
            .workingDirectory(temp.toString())
            .build());
        long deadline = System.currentTimeMillis() + 2_000;
        while (captured.get() == null && System.currentTimeMillis() < deadline) {
            Thread.onSpinWait();
        }

        assertNotNull(captured.get());
        assertEquals(temp.toString(), captured.get().cwd());
    }

    @Test
    void explicitHumanInputCanResumeAnAgentStoppedByEscape(@TempDir Path temp) throws Exception {
        String sessionId = "resume-session";
        String agentId = "a1234567890abcdef";
        Path transcript = temp.resolve("agent-" + agentId + ".jsonl");
        Files.writeString(transcript,
            persisted(new UserMessage("prior", MessageContent.ofText("original audit")),
                sessionId, null, true, agentId, null) + System.lineSeparator());
        TaskStore store = TaskStore.inMemory();
        TaskRegistry registry = new TaskRegistry(store);
        var stopped = store.createWithId(agentId,
            TaskType.LOCAL_AGENT, "inspect storage", null);
        store.updateStatus(stopped.id(), TaskStatus.RUNNING);
        store.updateStatus(stopped.id(), TaskStatus.KILLED);
        AtomicReference<SubAgentRequest> captured = new AtomicReference<>();
        AgentContinuationService service = new AgentContinuationService(
            request -> {
                captured.set(request);
                return SubAgentResult.of("continued");
            }, registry, (_, _) -> transcript, (_, _) -> temp.resolve(agentId + ".output"));

        service.resume(agentId, "continue", ToolExecutionContext.builder(
                new AbortController(), sessionId)
            .workingDirectory(temp.toString())
            .build(), true);

        long deadline = System.currentTimeMillis() + 2_000;
        while (captured.get() == null && System.currentTimeMillis() < deadline) {
            Thread.onSpinWait();
        }
        assertNotNull(captured.get());
        assertEquals(agentId, captured.get().agentId());
    }

    @Test
    void recoveryFiltersInterruptedToolUseOrphanThinkingAndWhitespace() {
        AssistantMessage unresolved = assistant("m1", List.of(
            new TextBlock("partial"),
            new ToolUseBlock("call-missing", "Bash", JsonNodeFactory.instance.objectNode())));
        AssistantMessage orphanThinking = assistant("m2", List.of(new ThinkingBlock("secret", "sig")));
        AssistantMessage whitespace = assistant("m3", List.of(new TextBlock("   ")));
        UserMessage adjacentUserOne = new UserMessage("u1", MessageContent.ofText("first"));
        UserMessage adjacentUserTwo = new UserMessage("u2", MessageContent.ofText("second"));

        List<Message> recovered = AgentContinuationService.sanitize(List.of(
            unresolved, orphanThinking, whitespace, adjacentUserOne, adjacentUserTwo));

        assertEquals(1, recovered.size());
        UserMessage merged = assertInstanceOf(UserMessage.class, recovered.getFirst());
        assertEquals(2, merged.message().blocks().size(),
            "whitespace filtering must preserve API role alternation by merging adjacent users");
    }

    @Test
    void resolvedToolUseAndThinkingFragmentWithSiblingContentAreRetained() {
        AssistantMessage thinking = assistant("shared", List.of(new ThinkingBlock("reason", "sig")));
        AssistantMessage toolUse = assistant("shared", List.of(
            new TextBlock("working"),
            new ToolUseBlock("call-ok", "Bash", JsonNodeFactory.instance.objectNode())));
        UserMessage result = new UserMessage("u1", MessageContent.ofBlocks(List.of(
            new ToolResultBlock("call-ok", List.of(new TextBlock("ok")), false))));

        List<Message> input = List.of(thinking, toolUse, result);

        assertEquals(input, AgentContinuationService.sanitize(input));
    }

    private static AssistantMessage assistant(String id, List<ContentBlock> blocks) {
        return new AssistantMessage(id, AssistantContent.of(id, blocks));
    }

    private static String persisted(
            Message message, String sessionId, String parentUuid, boolean sidechain,
            String agentId, String attributionAgent) throws Exception {
        var node = (ObjectNode)
            JsonUtils.getMapper().valueToTree(message);
        node.put("sessionId", sessionId);
        if (parentUuid == null) node.putNull("parentUuid");
        else node.put("parentUuid", parentUuid);
        node.put("isSidechain", sidechain);
        if (agentId != null) node.put("agentId", agentId);
        if (attributionAgent != null) node.put("attributionAgent", attributionAgent);
        return JsonUtils.getMapper().writeValueAsString(node);
    }
}
