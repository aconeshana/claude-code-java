package com.claudecode.services.compact;

import org.apache.commons.lang3.Strings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.core.engine.FileStateCache;
import com.claudecode.core.message.AgentListingDeltaAttachment;
import com.claudecode.core.message.AssistantContent;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.AttachmentMessage;
import com.claudecode.core.message.AttachmentPayload;
import com.claudecode.core.message.CompactFileReferenceAttachment;
import com.claudecode.core.message.DeferredToolsDeltaAttachment;
import com.claudecode.core.message.FileContentAttachment;
import com.claudecode.core.message.InvokedSkillsAttachment;
import com.claudecode.core.message.McpInstructionsDeltaAttachment;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.PlanFileReferenceAttachment;
import com.claudecode.core.message.PlanModeReminderAttachment;
import com.claudecode.core.message.TaskStatusAttachment;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.TokenEstimator;
import com.claudecode.core.message.ToolResultBlock;
import com.claudecode.core.message.ToolUseBlock;
import com.claudecode.core.message.UserMessage;
import com.claudecode.core.plan.PlanCatalogContext;
import com.claudecode.core.plan.PlanHistoryEntry;
import com.claudecode.services.compact.CompactAttachmentStateProvider.AsyncTask;
import com.claudecode.services.compact.CompactAttachmentStateProvider.InvokedSkill;
import com.claudecode.services.compact.CompactAttachmentStateProvider.PlanFile;
import com.claudecode.services.compact.CompactAttachmentStateProvider.Snapshot;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Budget/filter edge-case coverage for {@link DefaultManualCompactStrategy}'s
 * 5 post-compact attachment producers (file re-attachment, async-agent task
 * status, plan file, plan-mode reminder, invoked skills). Tool-owned mutable
 * state is represented only by immutable attachment snapshots here; the
 * concrete tool adapter is covered separately.
 */
class DefaultManualCompactStrategyAttachmentsTest {

    @TempDir Path tempDir;

    private final DefaultManualCompactStrategy strategy =
        new DefaultManualCompactStrategy(TokenEstimator.getInstance());

    // ---- Producer 1: file re-attachment ----

    @Test
    void buildFileAttachmentsReturnsEmptyForNullCache() {
        assertEquals(List.of(), strategy.buildFileAttachments(null));
    }

    @Test
    void buildFileAttachmentsIncludesSmallFileAsFullContent(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("small.txt");
        Files.writeString(file, "hello world");
        FileStateCache cache = new FileStateCache();
        cache.set(file.toString(), new FileStateCache.FileState("hello world", 1000L, null, null, false));

        List<AttachmentPayload> result = strategy.buildFileAttachments(cache);
        assertEquals(1, result.size());
        assertInstanceOf(FileContentAttachment.class, result.getFirst());
        FileContentAttachment a = (FileContentAttachment) result.getFirst();
        assertEquals(file.toString(), a.filename());
        assertEquals("hello world", a.content());
    }

    @Test
    void buildFileAttachmentsDowngradesLargeFileToPointer(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("big.txt");
        String bigContent = "x".repeat(20_000); // ~6667 estimated tokens, over the 5k per-file cap
        Files.writeString(file, bigContent);
        FileStateCache cache = new FileStateCache();
        cache.set(file.toString(), new FileStateCache.FileState(bigContent, 1000L, null, null, false));

        List<AttachmentPayload> result = strategy.buildFileAttachments(cache);
        assertEquals(1, result.size());
        assertInstanceOf(CompactFileReferenceAttachment.class, result.getFirst());
        assertEquals(file.toString(), ((CompactFileReferenceAttachment) result.getFirst()).filename());
    }

    @Test
    void buildFileAttachmentsCapsAtFiveMostRecentFiles(@TempDir Path tempDir) throws Exception {
        FileStateCache cache = new FileStateCache();
        for (int i = 0; i < 8; i++) {
            Path file = tempDir.resolve("f" + i + ".txt");
            Files.writeString(file, "content " + i);
            // Ascending timestamp: higher i = more recently touched.
            cache.set(file.toString(), new FileStateCache.FileState("content " + i, 1000L + i, null, null, false));
        }

        List<AttachmentPayload> result = strategy.buildFileAttachments(cache);
        assertEquals(5, result.size());
        // Only the 5 most recent (f7..f3) survive, most-recent-first.
        for (int idx = 0; idx < 5; idx++) {
            int expectedFileIndex = 7 - idx;
            FileContentAttachment a = (FileContentAttachment) result.get(idx);
            assertTrue(Strings.CS.endsWith(a.filename(), "f" + expectedFileIndex + ".txt"));
        }
    }

    @Test
    void buildFileAttachmentsSkipsFilesNoLongerOnDisk(@TempDir Path tempDir) {
        FileStateCache cache = new FileStateCache();
        cache.set(tempDir.resolve("gone.txt").toString(),
            new FileStateCache.FileState("was here", 1000L, null, null, false));

        assertEquals(List.of(), strategy.buildFileAttachments(cache));
    }

    @Test
    void buildFileAttachmentsExcludesClaudeMemoryAndRulesFilesBeforeRecencyLimit() throws Exception {
        Path rulesDir = tempDir.resolve(".claude").resolve("rules");
        Files.createDirectories(rulesDir);
        Path claudeMd = tempDir.resolve("CLAUDE.md");
        Path rule = rulesDir.resolve("testing.md");
        Path source = tempDir.resolve("src.txt");
        Files.writeString(claudeMd, "global instructions");
        Files.writeString(rule, "rule instructions");
        Files.writeString(source, "source content");

        FileStateCache cache = new FileStateCache();
        // Memory files are newer than the source file; they must not consume
        // one of the five slots or be restored at all.
        cache.set(claudeMd.toString(), new FileStateCache.FileState("global instructions", 3000L, null, null, false));
        cache.set(rule.toString(), new FileStateCache.FileState("rule instructions", 2000L, null, null, false));
        cache.set(source.toString(), new FileStateCache.FileState("source content", 1000L, null, null, false));

        List<AttachmentPayload> result = strategy.buildFileAttachments(cache, null);
        assertEquals(1, result.size());
        assertEquals(source.toString(), ((FileContentAttachment) result.getFirst()).filename());
    }

    @Test
    void buildFileAttachmentsExcludesTheActivePlanFile() throws Exception {
        Path plan = tempDir.resolve("plan.md");
        Path source = tempDir.resolve("source.txt");
        Files.writeString(plan, "# plan");
        Files.writeString(source, "source");

        FileStateCache cache = new FileStateCache();
        cache.set(plan.toString(), new FileStateCache.FileState("# plan", 2000L, null, null, false));
        cache.set(source.toString(), new FileStateCache.FileState("source", 1000L, null, null, false));

        List<AttachmentPayload> result = strategy.buildFileAttachments(
            cache, new PlanFile(plan, "# plan"));
        assertEquals(1, result.size());
        assertEquals(source.toString(), ((FileContentAttachment) result.getFirst()).filename());
    }

    @Test
    void partialCompactSkipsFilesAlreadyReadInThePreservedSliceButNotDedupStubs()
            throws Exception {
        Path file = tempDir.resolve("preserved.txt");
        Files.writeString(file, "fresh content");
        FileStateCache cache = new FileStateCache();
        cache.set(file.toString(), new FileStateCache.FileState(
            "old content", 1000L, null, null, false));
        ToolUseBlock read = new ToolUseBlock(
            "read-1", "Read", JsonNodeFactory.instance.objectNode()
                .put("file_path", file.toString()));
        AssistantMessage call = new AssistantMessage(
            "assistant-read", AssistantContent.of(List.of(read)));
        UserMessage fullResult = new UserMessage(
            "read-result", MessageContent.ofBlocks(List.of(
                new ToolResultBlock("read-1", List.of(new TextBlock("fresh content")), false))));

        assertEquals(List.of(), strategy.buildFileAttachments(
            cache, null, List.of(call, fullResult)));
        assertEquals(0, cache.size(), "197 clears pre-compact read state before rebuilding it");

        cache.set(file.toString(), new FileStateCache.FileState(
            "old content", 1000L, null, null, false));
        UserMessage unchangedStub = new UserMessage(
            "read-stub", MessageContent.ofBlocks(List.of(
                new ToolResultBlock("read-1", List.of(new TextBlock(
                    "[file_unchanged] The file has not been modified since last read.")), false))));

        List<AttachmentPayload> restored = strategy.buildFileAttachments(
            cache, null, List.of(call, unchangedStub));
        assertEquals(1, restored.size());
        assertEquals("fresh content",
            assertInstanceOf(FileContentAttachment.class, restored.getFirst()).content());
        assertEquals("fresh content", cache.get(file.toString()).content(),
            "the fresh compact read becomes the new edit-safety baseline");
    }

    // ---- Producer 2: async agent task status ----

    @Test
    void buildTaskStatusAttachmentsReturnsEmptyForNullSnapshot() {
        assertEquals(List.of(), strategy.buildTaskStatusAttachments(null, null));
    }

    @Test
    void buildTaskStatusAttachmentsSkipsNonLocalAgentTasks() {
        assertEquals(List.of(), strategy.buildTaskStatusAttachments(List.of(
            task("bash-1", "local_bash", "running", "run tests", null)), null));
    }

    @Test
    void buildTaskStatusAttachmentsSkipsPendingTasks() {
        assertEquals(List.of(), strategy.buildTaskStatusAttachments(List.of(
            task("agent-1", "local_agent", "pending", "still pending", null)), null));
    }

    @Test
    void buildTaskStatusAttachmentsSkipsCallersOwnAgentId() {
        assertEquals(List.of(), strategy.buildTaskStatusAttachments(List.of(
            task("agent-self", "local_agent", "running", "self", null)), "agent-self"));
    }

    @Test
    void buildTaskStatusAttachmentsUsesProgressSummaryForRunning() {
        AsyncTask task = task(
            "agent-1", "local_agent", "running", "refactor auth", "halfway done");

        List<AttachmentPayload> result = strategy.buildTaskStatusAttachments(List.of(task), null);
        assertEquals(1, result.size());
        TaskStatusAttachment a = (TaskStatusAttachment) result.getFirst();
        assertEquals(task.id(), a.taskId());
        assertEquals("local_agent", a.taskType());
        assertEquals("running", a.status());
        assertEquals("halfway done", a.deltaSummary());
        assertEquals(task.outputFilePath(), a.outputFilePath());
    }

    @Test
    void buildTaskStatusAttachmentsUsesErrorMessageForFailed() {
        AsyncTask task = task("agent-2", "local_agent", "failed", "flaky task", "boom");

        List<AttachmentPayload> result = strategy.buildTaskStatusAttachments(List.of(task), null);
        assertEquals(1, result.size());
        TaskStatusAttachment a = (TaskStatusAttachment) result.getFirst();
        assertEquals("failed", a.status());
        assertEquals("boom", a.deltaSummary());
    }

    @Test
    void buildTaskStatusAttachmentsHasNoDeltaSummaryForCompleted() {
        AsyncTask task = task("agent-3", "local_agent", "completed", "done task", null);

        List<AttachmentPayload> result = strategy.buildTaskStatusAttachments(List.of(task), null);
        assertEquals(1, result.size());
        assertNull(((TaskStatusAttachment) result.getFirst()).deltaSummary());
    }

    // ---- Producer 3: plan file reference ----

    @Test
    void buildPlanFileAttachmentReturnsEmptyWhenSnapshotIsMissing() {
        assertEquals(List.of(), strategy.buildPlanFileAttachment(null));
        assertEquals(List.of(), strategy.buildPlanFileAttachment(
            new PlanFile(tempDir.resolve("missing.md"), null)));
    }

    @Test
    void buildPlanFileAttachmentReturnsReferenceWhenPlanExists() {
        Path planFile = tempDir.resolve("plan.md");

        List<AttachmentPayload> result = strategy.buildPlanFileAttachment(
            new PlanFile(planFile, "# The Plan\nstep 1"));
        assertEquals(1, result.size());
        PlanFileReferenceAttachment a = (PlanFileReferenceAttachment) result.getFirst();
        assertEquals(planFile.toString(), a.planFilePath());
        assertEquals("# The Plan\nstep 1", a.planContent());
    }

    // ---- Producer 4: plan-mode reminder ----

    @Test
    void buildPlanModeAttachmentReturnsEmptyWhenNotActive() {
        Snapshot state = new Snapshot(null, false, false,
            new PlanFile(tempDir.resolve("s1.md"), null), List.of(), List.of());
        assertEquals(List.of(), strategy.buildPlanModeAttachment(state));
    }

    @Test
    void buildPlanModeAttachmentReportsPlanExistsFalseWhenNoFileYet() {
        Snapshot state = new Snapshot(null, false, true,
            new PlanFile(tempDir.resolve("new-plan.md"), null), List.of(), List.of());

        List<AttachmentPayload> result = strategy.buildPlanModeAttachment(state);
        assertEquals(1, result.size());
        PlanModeReminderAttachment a = (PlanModeReminderAttachment) result.getFirst();
        assertFalse(a.planExists());
        assertFalse(a.isSubAgent());
    }

    @Test
    void buildPlanModeAttachmentReportsPlanExistsTrueWhenContentPresent() {
        Snapshot state = new Snapshot("agent-1", true, true,
            new PlanFile(tempDir.resolve("agent-plan.md"), "# Plan"), List.of(), List.of());
        List<AttachmentPayload> result = strategy.buildPlanModeAttachment(state);
        assertEquals(1, result.size());
        PlanModeReminderAttachment a = (PlanModeReminderAttachment) result.getFirst();
        assertTrue(a.planExists());
        assertTrue(a.isSubAgent());
    }

    @Test
    void buildPlanModeAttachmentRestoresMultiPlanCatalogAfterCompact() {
        PlanCatalogContext catalog = new PlanCatalogContext(
            "P004", "DRAFT", tempDir.resolve("s1-p004.md").toString(), true, true,
            List.of(new PlanHistoryEntry(
                "P003", "APPROVED", "Previous plan", "Previous scope.",
                tempDir.resolve("s1-p003.md").toString())));
        Snapshot state = new Snapshot(null, false, true,
            new PlanFile(Path.of(catalog.planFilePath()), "# Current plan"),
            List.of(), List.of(), catalog);

        PlanModeReminderAttachment attachment = (PlanModeReminderAttachment)
            strategy.buildPlanModeAttachment(state).getFirst();

        assertEquals("full", attachment.reminderType());
        assertEquals("P004", attachment.planId());
        assertEquals("DRAFT", attachment.planStatus());
        assertEquals(Boolean.TRUE, attachment.resumedDraft());
        assertEquals(catalog.recentPlans(), attachment.recentPlans());
    }

    // ---- Producer 5: invoked skills ----

    @Test
    void buildInvokedSkillsAttachmentReturnsEmptyForNullSnapshot() {
        assertEquals(List.of(), strategy.buildInvokedSkillsAttachment(null));
    }

    @Test
    void buildInvokedSkillsAttachmentReturnsEmptyWhenNoneInvoked() {
        assertEquals(List.of(), strategy.buildInvokedSkillsAttachment(List.of()));
    }

    @Test
    void buildInvokedSkillsAttachmentJoinsMultipleSkillsMostRecentFirst() {
        List<InvokedSkill> skills = List.of(
            skill("first", "guidance one", Instant.parse("2026-01-01T00:00:00Z")),
            skill("second", "guidance two", Instant.parse("2026-01-01T00:00:01Z")));

        List<AttachmentPayload> result = strategy.buildInvokedSkillsAttachment(skills);
        assertEquals(1, result.size());
        InvokedSkillsAttachment a = (InvokedSkillsAttachment) result.getFirst();
        assertEquals(2, a.skills().size());
        assertEquals("second", a.skills().getFirst().name());
        assertEquals("first", a.skills().get(1).name());
    }

    @Test
    void buildInvokedSkillsAttachmentLeavesShortContentUntouched() {
        InvokedSkillsAttachment a = (InvokedSkillsAttachment)
            strategy.buildInvokedSkillsAttachment(List.of(
                skill("deploy", "short guidance", Instant.EPOCH))).getFirst();
        assertEquals("short guidance", a.skills().getFirst().content());
    }

    @Test
    void truncateToTokensTruncatesContentOverPerSkillCapAndAppendsMarker() {
        String longContent = "y".repeat(24_000); // ~6000 estimated tokens, over the 5k cap
        String truncated = strategy.truncateToTokens(longContent, 5_000);
        assertTrue(truncated.length() < longContent.length());
        assertTrue(Strings.CS.endsWith(truncated, "use Read on the skill path if you need the full text]"));
    }

    @Test
    void buildInvokedSkillsAttachmentDropsSkillsOnceRunningBudgetExceeded() {
        // Each 5000-token skill (20000 chars, right at the per-skill cap, so
        // none get truncated); 5 fit exactly into the 25k skills budget, so a
        // 6th must be dropped rather than truncated further.
        String content = "z".repeat(20_000);
        List<InvokedSkill> skills = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            skills.add(skill("skill" + i, content, Instant.ofEpochSecond(i)));
        }

        InvokedSkillsAttachment a = (InvokedSkillsAttachment)
            strategy.buildInvokedSkillsAttachment(skills).getFirst();
        assertEquals(5, a.skills().size());
    }

    @Test
    void buildPostCompactAttachmentsReannouncesAgentsWithoutSkillListing() {
        String listing = """
            Available agent types for the Agent tool:
            - claude: Catch-all agent. (Tools: *)
            - Explore: Read-only search. (Tools: Read, Grep)

            When you launch multiple agents for independent work, send them in a single message \
            with multiple tool uses so they run concurrently.

            The following skills are available for use with the Skill tool:

            - verify""";
        CompactAttachmentContext ctx = new CompactAttachmentContext(
            null, Snapshot.empty(null, false), null, listing);

        List<Message> attachments =
            strategy.buildPostCompactAttachments(ctx);

        assertEquals(1, attachments.size());
        AgentListingDeltaAttachment payload = assertInstanceOf(
            AgentListingDeltaAttachment.class,
            assertInstanceOf(AttachmentMessage.class, attachments.getFirst()).payload());
        assertEquals(List.of("claude", "Explore"), payload.addedTypes());
        assertEquals(List.of(
            "- claude: Catch-all agent. (Tools: *)",
            "- Explore: Read-only search. (Tools: Read, Grep)"), payload.addedLines());
    }

    @Test
    void buildPostCompactAttachmentsReannouncesMcpInstructionsAfterAgents() {
        CompactAttachmentContext ctx = new CompactAttachmentContext(
            null,
            () -> Snapshot.empty(null, false),
            null,
            "Available agent types for the Agent tool:\n- general-purpose: General agent. (Tools: *)",
            () -> Map.of("wire-compact", "WIRE197 MCP compact instructions"));

        List<Message> attachments =
            strategy.buildPostCompactAttachments(ctx);

        assertEquals(2, attachments.size());
        McpInstructionsDeltaAttachment payload = assertInstanceOf(
            McpInstructionsDeltaAttachment.class,
            assertInstanceOf(AttachmentMessage.class, attachments.get(1)).payload());
        assertEquals(List.of("wire-compact"), payload.addedNames());
        assertEquals(List.of("## wire-compact\nWIRE197 MCP compact instructions"), payload.addedBlocks());
        assertEquals(List.of(), payload.removedNames());
    }

    @Test
    void partialCompactDeltasToolsAgentsAndMcpAgainstThePreservedSlice() {
        List<Message> preserved = List.of(
            new AttachmentMessage("tools-before", new DeferredToolsDeltaAttachment(
                List.of("Read"), List.of("Read"), List.of())),
            new AttachmentMessage("agents-before", new AgentListingDeltaAttachment(
                List.of("general-purpose"), List.of("- general-purpose: General agent."),
                List.of(), true, true)),
            new AttachmentMessage("mcp-before", new McpInstructionsDeltaAttachment(
                List.of("wire-old"), List.of("## wire-old\nold"), List.of())));
        CompactAttachmentContext ctx = new CompactAttachmentContext(
            null,
            () -> Snapshot.empty(null, false),
            null,
            """
                Available agent types for the Agent tool:
                - general-purpose: General agent. (Tools: *)
                - Explore: Read-only search. (Tools: Read, Grep)
                """,
            () -> Map.of(
                "wire-old", "old",
                "wire-new", "new"),
            () -> List.of("Read", "Edit"));

        List<AttachmentPayload> payloads = strategy
            .buildPostCompactAttachments(ctx, preserved).stream()
            .map(AttachmentMessage.class::cast)
            .map(AttachmentMessage::payload)
            .toList();

        DeferredToolsDeltaAttachment tools = payloads.stream()
            .filter(DeferredToolsDeltaAttachment.class::isInstance)
            .map(DeferredToolsDeltaAttachment.class::cast).findFirst().orElseThrow();
        assertEquals(List.of("Edit"), tools.addedNames());
        AgentListingDeltaAttachment agents = payloads.stream()
            .filter(AgentListingDeltaAttachment.class::isInstance)
            .map(AgentListingDeltaAttachment.class::cast).findFirst().orElseThrow();
        assertEquals(List.of("Explore"), agents.addedTypes());
        assertFalse(agents.isInitial());
        McpInstructionsDeltaAttachment mcp = payloads.stream()
            .filter(McpInstructionsDeltaAttachment.class::isInstance)
            .map(McpInstructionsDeltaAttachment.class::cast).findFirst().orElseThrow();
        assertEquals(List.of("wire-new"), mcp.addedNames());
        assertEquals(List.of(), mcp.removedNames());
    }

    private static AsyncTask task(String id, String type, String status,
                                  String description, String delta) {
        return new AsyncTask(id, type, status, description, delta,
            "/tmp/tasks/" + id + ".output");
    }

    private static InvokedSkill skill(String name, String content, Instant invokedAt) {
        return new InvokedSkill(name, "userSettings:" + name, content, invokedAt);
    }
}
