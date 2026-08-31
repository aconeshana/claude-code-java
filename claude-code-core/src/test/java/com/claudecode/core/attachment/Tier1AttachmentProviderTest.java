package com.claudecode.core.attachment;

import org.apache.commons.lang3.Strings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.core.agent.BuiltInAgentDefinitions.AgentDefinition;
import com.claudecode.core.message.AgentMentionAttachment;
import com.claudecode.core.message.AssistantContent;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.AttachmentMessage;
import com.claudecode.core.message.BudgetUsdAttachment;
import com.claudecode.core.message.DynamicSkillAttachment;
import com.claudecode.core.message.McpResourceAttachment;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.OutputStyleAttachment;
import com.claudecode.core.message.OutputTokenUsageAttachment;
import com.claudecode.core.message.PlanModeExitAttachment;
import com.claudecode.core.message.PlanModeExitInfo;
import com.claudecode.core.message.SkillListingAttachment;
import com.claudecode.core.message.SkillListingEntry;
import com.claudecode.core.message.TaskReminderAttachment;
import com.claudecode.core.message.TaskReminderItem;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.TodoItem;
import com.claudecode.core.message.TodoReminderAttachment;
import com.claudecode.core.message.TokenUsageAttachment;
import com.claudecode.core.message.ToolUseBlock;
import com.claudecode.core.message.UsageSnapshot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tier-1 attachment providers — the always-on (non-feature-flagged) ports added in this phase:
 * {@code output_style}, {@code agent_mention}, {@code mcp_resource}, {@code todo_reminder}, {@code
 * plan_mode_exit}, {@code dynamic_skill}, {@code skill_listing}, {@code token_usage}, {@code
 * budget_usd}, {@code output_token_usage}.
 */
class Tier1AttachmentProviderTest {

    // ── context helper ────────────────────────────────────────────────────

    private static AttachmentContext ctx(
            List<Message> messages, String input, String agentId,
            List<String> toolNames, List<AgentDefinition> activeAgents,
            String outputStyle, List<TodoItem> todos, PlanModeExitInfo planModeExit,
            Set<String> dynamicSkillDirTriggers, List<SkillListingEntry> skills,
            BiFunction<String, String, String> mcpReader, UsageSnapshot usage) {
        return AttachmentContext.builder(".")
            .messages(messages)
            .input(input)
            .agentId(agentId)
            .toolNames(toolNames)
            .activeAgents(activeAgents)
            .previousTurnTools(List.of())
            .outputStyle(outputStyle)
            .todos(todos)
            .planModeExit(planModeExit)
            .dynamicSkillDirTriggers(dynamicSkillDirTriggers)
            .skills(skills)
            .mcpResourceReader(mcpReader)
            .usage(usage)
            .build();
    }

    private static AgentDefinition agent(String type) {
        return AgentDefinition.builder(type, "when").source(null).build();
    }

    private static List<Message> plainTurns(int n) {
        List<Message> msgs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            msgs.add(new AssistantMessage("id" + i,
                AssistantContent.of(List.of(new TextBlock("hi")))));
        }
        return msgs;
    }

    private static UsageSnapshot usage(long used, long total, double bUsed, double bTotal,
            long outTurn, Long outBudget, long outSession) {
        return new UsageSnapshot(used, total, Math.max(0, total - used),
            bUsed, bTotal, Math.max(0, bTotal - bUsed),
            outTurn, outBudget, outSession);
    }

    // ── output_style ──────────────────────────────────────────────────────

    @Test
    void outputStyle_suppressesDefault() {
        var p = new OutputStyleAttachmentProvider();
        assertTrue(p.collect(ctx(List.of(), "", null, List.of(), List.of(),
            "default", null, null, Set.of(), null, null, null)).isEmpty());
        assertTrue(p.collect(ctx(List.of(), "", null, List.of(), List.of(),
            null, null, null, Set.of(), null, null, null)).isEmpty());
    }

    @Test
    void outputStyle_emitsNonDefault() {
        var p = new OutputStyleAttachmentProvider();
        var out = p.collect(ctx(List.of(), "", null, List.of(), List.of(),
            "concise", null, null, Set.of(), null, null, null));
        assertEquals(1, out.size());
        assertEquals("concise", ((OutputStyleAttachment) out.getFirst()).style());
    }

    // ── agent_mention ─────────────────────────────────────────────────────

    @Test
    void agentMention_noInput() {
        var p = new AgentMentionAttachmentProvider();
        assertTrue(p.collect(ctx(List.of(), null, null, List.of(),
            List.of(agent("research")), null, null, null, Set.of(), null, null, null)).isEmpty());
    }

    @Test
    void agentMention_knownAgent() {
        var p = new AgentMentionAttachmentProvider();
        var out = p.collect(ctx(List.of(), "please use @agent-research here", null,
            List.of(), List.of(agent("research")), null, null, null, Set.of(), null, null, null));
        assertEquals(1, out.size());
        assertEquals("research", ((AgentMentionAttachment) out.getFirst()).agentType());
    }

    @Test
    void agentMention_unknownAgentSuppressed() {
        var p = new AgentMentionAttachmentProvider();
        assertTrue(p.collect(ctx(List.of(), "@agent-unknown", null,
            List.of(), List.of(agent("research")), null, null, null, Set.of(), null, null, null)).isEmpty());
    }

    @Test
    void agentMention_quotedForm() {
        var p = new AgentMentionAttachmentProvider();
        var out = p.collect(ctx(List.of(), "run @\"research (agent)\"", null,
            List.of(), List.of(agent("research")), null, null, null, Set.of(), null, null, null));
        assertEquals(1, out.size());
        assertEquals("research", ((AgentMentionAttachment) out.getFirst()).agentType());
    }

    // ── mcp_resource ──────────────────────────────────────────────────────

    @Test
    void mcpResource_noReader() {
        var p = new McpResourceAttachmentProvider();
        assertTrue(p.collect(ctx(List.of(), "@srv:uri", null, List.of(),
            List.of(), null, null, null, Set.of(), null, null, null)).isEmpty());
    }

    @Test
    void mcpResource_readsContent() {
        var p = new McpResourceAttachmentProvider();
        BiFunction<String, String, String> reader = (s, u) -> "CONTENT:" + s + ":" + u;
        var out = p.collect(ctx(List.of(), "see @myserver:file:///x", null, List.of(),
            List.of(), null, null, null, Set.of(), null, reader, null));
        assertEquals(1, out.size());
        McpResourceAttachment a = (McpResourceAttachment) out.getFirst();
        assertEquals("myserver", a.server());
        assertEquals("file:///x", a.uri());
        assertEquals("CONTENT:myserver:file:///x", a.content());
    }

    @Test
    void mcpResource_nullContentSkipped() {
        var p = new McpResourceAttachmentProvider();
        BiFunction<String, String, String> reader = (_, _) -> null;
        assertTrue(p.collect(ctx(List.of(), "@srv:uri", null, List.of(),
            List.of(), null, null, null, Set.of(), null, reader, null)).isEmpty());
    }

    // ── todo_reminder ─────────────────────────────────────────────────────

    @Test
    void todoReminder_requiresTodoWriteTool() {
        var p = new TodoReminderAttachmentProvider();
        assertTrue(p.collect(ctx(plainTurns(12), "", null, List.of("Bash"),
            List.of(), null, List.of(new TodoItem("pending", "x")), null, Set.of(), null, null, null)).isEmpty());
    }

    @Test
    void todoReminder_emitsAfterThreshold() {
        var p = new TodoReminderAttachmentProvider();
        List<TodoItem> todos = List.of(new TodoItem("pending", "write code"));
        var out = p.collect(ctx(plainTurns(12), "", null, List.of("TodoWrite"),
            List.of(), null, todos, null, Set.of(), null, null, null));
        assertEquals(1, out.size());
        TodoReminderAttachment a = (TodoReminderAttachment) out.getFirst();
        assertEquals(1, a.itemCount());
        assertEquals("write code", a.content().getFirst().content());
    }

    @Test
    void todoReminder_suppressedBeforeThreshold() {
        var p = new TodoReminderAttachmentProvider();
        assertTrue(p.collect(ctx(plainTurns(5), "", null, List.of("TodoWrite"),
            List.of(), null, List.of(new TodoItem("pending", "x")), null, Set.of(), null, null, null)).isEmpty());
    }

    @Test
    void taskReminder_emitsAfterThresholdAndCarriesPersistentTaskShape() {
        List<TaskReminderItem> tasks = List.of(new TaskReminderItem(
            "7", "Write tests", "Cover the reminder", "Writing tests", null,
            "in_progress", List.of("8"), List.of(), Map.of()));
        var provider = new TaskReminderAttachmentProvider(() -> tasks);

        var out = provider.collect(ctx(plainTurns(12), "", null,
            List.of("TaskCreate", "TaskUpdate"), List.of(), null, null, null,
            Set.of(), null, null, null));

        assertEquals(1, out.size());
        TaskReminderAttachment reminder = (TaskReminderAttachment) out.getFirst();
        assertEquals(1, reminder.itemCount());
        assertEquals("7", reminder.content().getFirst().id());
        assertEquals("Write tests", reminder.content().getFirst().subject());
    }

    @Test
    void taskReminder_requiresTaskUpdateAndCountsBothManagementTools() {
        List<TaskReminderItem> tasks = List.of(new TaskReminderItem(
            "1", "Task", "Description", null, null, "pending",
            List.of(), List.of(), Map.of()));
        var provider = new TaskReminderAttachmentProvider(() -> tasks);
        assertTrue(provider.collect(ctx(plainTurns(12), "", null,
            List.of("TaskCreate"), List.of(), null, null, null,
            Set.of(), null, null, null)).isEmpty());

        for (String managementTool : List.of("TaskCreate", "TaskUpdate")) {
            List<Message> history = new ArrayList<>(plainTurns(12));
            history.add(new AssistantMessage("management",
                AssistantContent.of(List.of(new ToolUseBlock(
                    "tool-use", managementTool,
                    com.claudecode.core.serialization.JsonUtils.getMapper().createObjectNode())))));
            assertTrue(provider.collect(ctx(history, "", null,
                List.of("TaskUpdate"), List.of(), null, null, null,
                Set.of(), null, null, null)).isEmpty());
        }
    }

    @Test
    void taskReminder_respectsReminderModeOffAndBriefToolSuppression() {
        var provider = new TaskReminderAttachmentProvider(
            List::of, () -> false);
        assertTrue(provider.collect(ctx(plainTurns(12), "", null,
            List.of("TaskUpdate"), List.of(), null, null, null,
            Set.of(), null, null, null)).isEmpty());

        var enabled = new TaskReminderAttachmentProvider(List::of);
        assertTrue(enabled.collect(ctx(plainTurns(12), "", null,
            List.of("TaskUpdate", "SendUserMessage"), List.of(), null, null, null,
            Set.of(), null, null, null)).isEmpty());
    }

    // ── plan_mode_exit ────────────────────────────────────────────────────

    @Test
    void planModeExit_nullSuppressed() {
        var p = new PlanModeExitAttachmentProvider();
        assertTrue(p.collect(ctx(List.of(), "", null, List.of(),
            List.of(), null, null, null, Set.of(), null, null, null)).isEmpty());
    }

    @Test
    void planModeExit_emits() {
        var p = new PlanModeExitAttachmentProvider();
        var out = p.collect(ctx(List.of(), "", null, List.of(),
            List.of(), null, null, new PlanModeExitInfo("/plan.md", true), Set.of(), null, null, null));
        assertEquals(1, out.size());
        PlanModeExitAttachment a = (PlanModeExitAttachment) out.getFirst();
        assertEquals("/plan.md", a.planFilePath());
        assertTrue(a.planExists());
    }

    // ── dynamic_skill ─────────────────────────────────────────────────────

    @Test
    void dynamicSkill_emptyTriggers() {
        var p = new DynamicSkillAttachmentProvider();
        assertTrue(p.collect(ctx(List.of(), "", null, List.of(),
            List.of(), null, null, null, Set.of(), null, null, null)).isEmpty());
    }

    @Test
    void dynamicSkill_scansTriggerDir(@TempDir Path dir) throws IOException {
        var p = new DynamicSkillAttachmentProvider();
        Path skill = dir.resolve("myskill");
        Files.createDirectory(skill);
        Files.writeString(skill.resolve("SKILL.md"), "# hi");
        Set<String> triggers = new CopyOnWriteArraySet<>();
        triggers.add(dir.toString());
        var out = p.collect(ctx(List.of(), "", null, List.of(),
            List.of(), null, null, null, triggers, null, null, null));
        assertEquals(1, out.size());
        DynamicSkillAttachment a = (DynamicSkillAttachment) out.getFirst();
        assertEquals(List.of("myskill"), a.skillNames());
        assertTrue(triggers.isEmpty(), "dynamic skill triggers are one-shot");
    }

    // ── skill_listing ─────────────────────────────────────────────────────

    @Test
    void skillListing_noSkills() {
        var p = new SkillListingAttachmentProvider();
        assertTrue(p.collect(ctx(List.of(), "", null, List.of("Skill"),
            List.of(), null, null, null, Set.of(), List.of(), null, null)).isEmpty());
    }

    @Test
    void skillListing_requiresSkillTool() {
        var p = new SkillListingAttachmentProvider();
        assertTrue(p.collect(ctx(List.of(), "", null, List.of("Bash"),
            List.of(), null, null, null, Set.of(),
            List.of(new SkillListingEntry("x", "d")), null, null)).isEmpty());
    }

    @Test
    void skillListing_emitsInitialThenDelta() {
        var p = new SkillListingAttachmentProvider();
        List<SkillListingEntry> skills = List.of(new SkillListingEntry("x", "desc"));
        var first = p.collect(ctx(List.of(), "", null, List.of("Skill"),
            List.of(), null, null, null, Set.of(), skills, null, null));
        assertEquals(1, first.size());
        SkillListingAttachment a = (SkillListingAttachment) first.getFirst();
        assertTrue(a.isInitial());
        assertEquals(1, a.skillCount());
        assertEquals(List.of("x"), a.names());
        // Already-announced skills are suppressed on the next turn.
        var second = p.collect(ctx(List.of(), "", null, List.of("Skill"),
            List.of(), null, null, null, Set.of(), skills, null, null));
        assertTrue(second.isEmpty());
    }

    @Test
    void skillListing_reconstructsAnnouncedNamesFromResumedTranscript() {
        var p = new SkillListingAttachmentProvider();
        Message previous = new AttachmentMessage("a1", new SkillListingAttachment(
            "- x: desc", 1, true, List.of("x")));
        List<SkillListingEntry> skills = List.of(new SkillListingEntry("x", "desc"));

        var resumed = p.collect(ctx(List.of(previous), "", null, List.of("Skill"),
            List.of(), null, null, null, Set.of(), skills, null, null));

        assertTrue(resumed.isEmpty());
    }

    @Test
    void skillListingBudget_keepsBundledDescriptionsAndMakesOthersNamesOnly() {
        List<SkillListingEntry> skills = List.of(
            new SkillListingEntry("custom", "x".repeat(100), false),
            new SkillListingEntry("built", "bundled description", true));

        assertEquals(
            "- custom\n- built: bundled description",
            SkillListingFormatter.formatWithinBudget(skills, 40));
    }

    @Test
    void skillListingPriority_greedilyKeepsHighestUsageDescriptionsThatFit() {
        List<SkillListingEntry> skills = List.of(
            new SkillListingEntry("low", "l".repeat(30), false, false, 1.0),
            new SkillListingEntry("top-too-large", "x".repeat(70), false, false, 10.0),
            new SkillListingEntry("second", "s".repeat(20), false, false, 8.0),
            new SkillListingEntry("third", "t".repeat(15), false, false, 7.0));

        String formatted = SkillListingFormatter.formatWithinBudget(skills, 105);

        assertEquals(
            "- low\n"
                + "- top-too-large\n"
                + "- second: " + "s".repeat(20) + "\n"
                + "- third: " + "t".repeat(15),
            formatted);
    }

    @Test
    void skillListingNameOnlyOverride_neverSpendsDescriptionBudget() {
        List<SkillListingEntry> skills = List.of(
            new SkillListingEntry("hidden-description", "x".repeat(20), false, true, 100.0),
            new SkillListingEntry("normal", "shown", false, false, 1.0));

        assertEquals(
            "- hidden-description\n- normal: shown",
            SkillListingFormatter.formatWithinBudget(skills, 1_000));
    }

    @Test
    void skillListingBlankDescription_isRenderedNameOnly() {
        assertEquals("- init",
            SkillListingFormatter.formatWithinBudget(
                List.of(new SkillListingEntry("init", null, true, false, 0.0)),
                8_000));
        assertEquals("- eval-harness",
            SkillListingFormatter.formatWithinBudget(
                List.of(new SkillListingEntry("eval-harness", "", false, false, 0.0)),
                8_000));
    }

    @Test
    void skillListingDescription_isCappedAtOneThousandFiveHundredThirtySixCharacters() {
        String formatted = SkillListingFormatter.formatWithinBudget(
            List.of(new SkillListingEntry("long", "x".repeat(2_000), false)), 8_000);
        assertEquals(1_536 + "- long: ".length(), formatted.length());
        assertTrue(Strings.CS.endsWith(formatted, "…"));
    }

    @Test
    void skillListingBudgetUsesModelSpecificBytesPerToken() {
        assertEquals(6_000, SkillListingFormatter.charBudgetForModel("glm-5.2"));
        assertEquals(8_000,
            SkillListingFormatter.charBudgetForModel("claude-sonnet-4-6"));
        assertEquals(6_000,
            SkillListingFormatter.charBudgetForModel("claude-opus-4-8"));
        assertEquals(14_880,
            SkillListingFormatter.charBudgetForModel("gpt-5.6-sol"));
    }

    // ── token_usage / budget_usd / output_token_usage (main-thread only) ──

    @Test
    void tokenUsage_suppressedForSubAgent() {
        var p = new TokenUsageAttachmentProvider();
        assertTrue(p.collect(ctx(List.of(), "", "sub", List.of(),
            List.of(), null, null, null, Set.of(), null, null,
            usage(100, 200, 1, 2, 50, null, 50))).isEmpty());
    }

    @Test
    void tokenUsage_emitsOnMainThread() {
        var p = new TokenUsageAttachmentProvider();
        var out = p.collect(ctx(List.of(), "", null, List.of(),
            List.of(), null, null, null, Set.of(), null, null,
            usage(100, 200, 1, 2, 50, null, 50)));
        assertEquals(1, out.size());
        TokenUsageAttachment a = (TokenUsageAttachment) out.getFirst();
        assertEquals(100, a.used());
        assertEquals(200, a.total());
        assertEquals(100, a.remaining());
    }

    @Test
    void budgetUsd_zeroTotalSuppressed() {
        var p = new BudgetUsdAttachmentProvider();
        assertTrue(p.collect(ctx(List.of(), "", null, List.of(),
            List.of(), null, null, null, Set.of(), null, null,
            usage(100, 200, 1, 0, 50, null, 50))).isEmpty());
    }

    @Test
    void budgetUsd_emits() {
        var p = new BudgetUsdAttachmentProvider();
        var out = p.collect(ctx(List.of(), "", null, List.of(),
            List.of(), null, null, null, Set.of(), null, null,
            usage(100, 200, 1.5, 5.0, 50, null, 50)));
        assertEquals(1, out.size());
        BudgetUsdAttachment a = (BudgetUsdAttachment) out.getFirst();
        assertEquals(1.5, a.used(), 0.001);
        assertEquals(5.0, a.total(), 0.001);
        assertEquals(3.5, a.remaining(), 0.001);
    }

    @Test
    void outputTokenUsage_nullBudgetSuppressed() {
        var p = new OutputTokenUsageAttachmentProvider();
        assertTrue(p.collect(ctx(List.of(), "", null, List.of(),
            List.of(), null, null, null, Set.of(), null, null,
            usage(100, 200, 1, 2, 50, null, 50))).isEmpty());
    }

    @Test
    void outputTokenUsage_emitsWithBudget() {
        var p = new OutputTokenUsageAttachmentProvider();
        var out = p.collect(ctx(List.of(), "", null, List.of(),
            List.of(), null, null, null, Set.of(), null, null,
            usage(100, 200, 1, 2, 50, 100L, 50)));
        assertEquals(1, out.size());
        OutputTokenUsageAttachment a = (OutputTokenUsageAttachment) out.getFirst();
        assertEquals(50, a.turn());
        assertEquals(100L, a.budget());
        assertEquals(50, a.session());
    }
}
