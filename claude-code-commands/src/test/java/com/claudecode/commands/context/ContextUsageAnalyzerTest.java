package com.claudecode.commands.context;

import org.apache.commons.lang3.Strings;
import com.claudecode.commands.context.ContextData.Category;
import com.claudecode.commands.context.ContextData.ContextColor;
import com.claudecode.core.engine.StreamingClient.StreamRequest.ToolDef;
import com.claudecode.core.message.AssistantContent;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.MessageFactory;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.ToolUseBlock;
import com.claudecode.core.message.Usage;
import com.claudecode.core.message.UserMessage;
import com.claudecode.core.agent.AgentSource;
import com.claudecode.core.agent.BuiltInAgentDefinitions.AgentDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ContextUsageAnalyzerTest {

    private static ContextUsageAnalyzer.Sources emptySources() {
        return new ContextUsageAnalyzer.Sources(
            List::of, () -> "", List::of, List::of, _ -> List.of(),
            null, () -> true, "/tmp/project");
    }

    private static ContextUsageAnalyzer.Sources sources(
            List<ToolDef> tools, String systemPrompt,
            List<ContextUsageAnalyzer.MemoryDocument> memory,
            List<ContextUsageAnalyzer.SkillDescriptor> skills,
            List<AgentDefinition> agents, boolean autoCompact) {
        return new ContextUsageAnalyzer.Sources(
            () -> tools, () -> systemPrompt, () -> memory, () -> skills,
            _ -> agents, null, () -> autoCompact, "/tmp/project");
    }

    private static AssistantMessage assistantWithUsage(Usage usage) {
        return new AssistantMessage("a1",
            new AssistantContent("m1", List.of(new TextBlock("hello")), usage));
    }

    private static AssistantMessage assistantWithUsage(String model, Usage usage) {
        return new AssistantMessage("a1", AssistantContent.apiResponse(
            "m1", List.of(new TextBlock("hello")), usage, model, "end_turn", null));
    }

    // ── context window ───────────────────────────────────────────────────────

    @Test
    void contextWindow_defaults200k_and1mSuffixOptsIn() {
        assertEquals(200_000L, ContextUsageAnalyzer.contextWindowFor("claude-opus-4-8"));
        assertEquals(372_000L, ContextUsageAnalyzer.contextWindowFor("gpt-5.6-sol"));
        assertEquals(1_000_000L, ContextUsageAnalyzer.contextWindowFor("claude-sonnet-5[1m]"));
        assertEquals(200_000L, ContextUsageAnalyzer.contextWindowFor(null));
    }

    @Test
    void customModelContextWindowOverridesTheBuiltInDefault() {
        ContextUsageAnalyzer.Sources sources = new ContextUsageAnalyzer.Sources(
            List::of, () -> "", List::of, List::of, _ -> List.of(),
            null, () -> false, null, "/tmp/project",
            model -> Strings.CS.equals("gpt-custom", model) ? 400_000L : null);

        assertEquals(400_000L,
            new ContextUsageAnalyzer(sources).analyze(List.of(), "gpt-custom").maxTokens());
    }

    // ── categories ───────────────────────────────────────────────────────────

    @Test
    void emptySession_hasBufferAndFreeSpaceOnly() {
        ContextData data = new ContextUsageAnalyzer(emptySources())
            .analyze(List.of(), "claude-sonnet-4-6");
        List<String> names = data.categories().stream().map(Category::name).toList();
        assertEquals(List.of(ContextData.AUTOCOMPACT_BUFFER, ContextData.FREE_SPACE), names);
        assertEquals(200_000L, data.maxTokens());

        // window to the 167k trigger: 20k summary-output reserve + 13k buffer.
        assertEquals(33_000L, data.categories().getFirst().tokens());
        assertEquals(167_000L, data.categories().get(1).tokens());
    }

    @Test
    void autoCompactDisabled_usesManualCompactBuffer() {
        ContextData data = new ContextUsageAnalyzer(sources(
                List.of(), "", List.of(), List.of(), List.of(), false))
            .analyze(List.of(), "m");
        assertTrue(data.categories().stream()
            .anyMatch(c -> ContextData.MANUAL_COMPACT_BUFFER.equals(c.name()) && c.tokens() == 3_000L));
        assertFalse(data.autoCompactEnabled());
        assertNull(data.autoCompactThreshold());
    }

    @Test
    void autoCompactEnabled_setsThreshold() {
        ContextData data = new ContextUsageAnalyzer(emptySources())
            .analyze(List.of(), "claude-sonnet-4-6");
        assertEquals(167_000L, data.autoCompactThreshold());
        assertTrue(data.autoCompactEnabled());
    }

    @Test
    void skillFrontmatterUsesTheReleasedListingLineShape() {
        var skill = new ContextUsageAnalyzer.SkillDescriptor(
            "verify", "Checks that a change really works",
            ContextUsageAnalyzer.SkillDescriptor.Source.BUILTIN);

        ContextData data = new ContextUsageAnalyzer(sources(
                List.of(), "", List.of(), List.of(skill), List.of(), true))
            .analyze(List.of(), "claude-sonnet-4-6");

        long expected = Math.round("- verify: Checks that a change really works".length() / 4.0);
        assertNotNull(data.skills());
        assertEquals(expected, data.skills().skillFrontmatter().getFirst().tokens());
    }

    @Test
    void categoriesFollowTsOrderAndColors() {
        List<ToolDef> tools = List.of(
            new ToolDef("Bash", "run a command", null),
            new ToolDef("mcp__srv__do", "an mcp tool", null));
        List<ContextUsageAnalyzer.MemoryDocument> memory = List.of(
            new ContextUsageAnalyzer.MemoryDocument(
                Path.of("/tmp/project/CLAUDE.md"),
                ContextUsageAnalyzer.MemoryScope.PROJECT, "x".repeat(400)));
        List<ContextUsageAnalyzer.SkillDescriptor> skills = List.of(
            new ContextUsageAnalyzer.SkillDescriptor("deploy", "deploys the app",
                ContextUsageAnalyzer.SkillDescriptor.Source.PROJECT));
        List<AgentDefinition> agents = List.of(
            AgentDefinition.builder("reviewer", "reviews code")
                .source(AgentSource.USER).build(),
            AgentDefinition.builder("built", "builtin")
                .source(AgentSource.BUILT_IN).build());
        List<Message> messages = List.of(
            new UserMessage("u1", MessageContent.ofText("hi there, a question")));

        ContextData data = new ContextUsageAnalyzer(sources(
                tools, "system prompt text here", memory, skills, agents, true))
            .analyze(messages, "claude-sonnet-4-6");

        List<String> names = data.categories().stream().map(Category::name).toList();
        assertEquals(List.of("System prompt", "System tools", "MCP tools", "Custom agents",
            "Memory files", "Skills", "Messages",
            ContextData.AUTOCOMPACT_BUFFER, ContextData.FREE_SPACE), names);

        assertEquals(ContextColor.PROMPT_BORDER, byName(data, "System prompt").color());
        assertEquals(ContextColor.INACTIVE, byName(data, "System tools").color());
        assertEquals(ContextColor.CYAN, byName(data, "MCP tools").color());
        assertEquals(ContextColor.PERMISSION, byName(data, "Custom agents").color());
        assertEquals(ContextColor.CLAUDE, byName(data, "Memory files").color());
        assertEquals(ContextColor.WARNING, byName(data, "Skills").color());
        assertEquals(ContextColor.PURPLE, byName(data, "Messages").color());
    }

    @Test
    void skillTokensAreSubtractedFromSystemTools() {

        // them as their own category and subtracts from the builtin bucket.
        List<ToolDef> tools = List.of(new ToolDef("Skill", "s".repeat(4000), null));
        List<ContextUsageAnalyzer.SkillDescriptor> skills = List.of(
            new ContextUsageAnalyzer.SkillDescriptor("big-skill", "d".repeat(4000),
                ContextUsageAnalyzer.SkillDescriptor.Source.USER));
        ContextData data = new ContextUsageAnalyzer(sources(
                tools, "", List.of(), skills, List.of(), true))
            .analyze(List.of(), "m");
        long builtinTokens = byName(data, "System tools").tokens();
        long skillTokens = byName(data, "Skills").tokens();
        assertTrue(skillTokens > 0);
        // System tools bucket must be strictly smaller than the raw estimate
        // of the 4000-char tool description alone.
        assertTrue(builtinTokens < 1400, "skill tokens must be subtracted, got " + builtinTokens);
    }

    @Test
    void configuredCountTokensFailureDoesNotFallBackToRoughStaticCounts() {
        ContextUsageAnalyzer.Sources sources = new ContextUsageAnalyzer.Sources(
            () -> List.of(new ToolDef("Bash", "run commands", null)),
            () -> "large system prompt that rough estimation would count",
            List::of, List::of, _ -> List.of(), null, () -> true,
            (_, _, _) -> {
                throw new IllegalStateException("count_tokens unavailable");
            },
            "/tmp/project");

        ContextData data = new ContextUsageAnalyzer(sources)
            .analyze(List.of(), "claude-sonnet-4-6");

        assertTrue(data.categories().stream()
            .noneMatch(c -> Strings.CS.equals("System prompt", c.name()) || Strings.CS.equals("System tools", c.name())));
        assertEquals(0, data.totalTokens());
    }

    @Test
    void configuredCountTokensDrivesSystemAndToolCategories() {
        ContextUsageAnalyzer.Sources sources = new ContextUsageAnalyzer.Sources(
            () -> List.of(new ToolDef("Bash", "run commands", null)),
            () -> "system",
            List::of, List::of, _ -> List.of(), null, () -> true,
            (_, _, tools) -> tools.isEmpty() ? 123L : 456L,
            "/tmp/project");

        ContextData data = new ContextUsageAnalyzer(sources)
            .analyze(List.of(), "claude-sonnet-4-6");

        assertEquals(123, byName(data, "System prompt").tokens());
        assertEquals(456, byName(data, "System tools").tokens());
    }

    // ── MCP details ──────────────────────────────────────────────────────────

    @Test
    void mcpToolsGetServerNameFromPrefix() {
        List<ToolDef> tools = List.of(
            new ToolDef("mcp__github__create_issue", "d", null),
            new ToolDef("mcp____weird", "d", null));
        ContextData data = new ContextUsageAnalyzer(sources(
                tools, "", List.of(), List.of(), List.of(), true))
            .analyze(List.of(), "m");
        assertEquals(2, data.mcpTools().size());
        assertEquals("github", data.mcpTools().getFirst().serverName());
        assertEquals("unknown", data.mcpTools().get(1).serverName());
    }

    // ── totals / api usage ───────────────────────────────────────────────────

    @Test
    void totalPrefersLastApiUsage() {
        Usage usage = new Usage(50_000, 1_000, 10_000, 40_000);
        List<Message> messages = List.of(
            new UserMessage("u1", MessageContent.ofText("hi")),
            assistantWithUsage(usage));
        ContextData data = new ContextUsageAnalyzer(emptySources()).analyze(messages, "m");
// input + cache_creation + cache_read (matches the status line).
        assertEquals(100_000L, data.totalTokens());
        assertEquals(50, data.percentage());
        assertEquals(usage, data.apiUsage());
    }

    @Test
    void gptTotalUsesProviderNormalizedDisjointCacheBuckets() {
        Usage usage = new Usage(10_000, 1_000, 0, 40_000);
        ContextData data = new ContextUsageAnalyzer(emptySources()).analyze(
            List.of(assistantWithUsage(usage)), "gpt-5.6-sol");

        assertEquals(50_000L, data.totalTokens());
        assertEquals(13, data.percentage());
        assertEquals(339_000L, data.autoCompactThreshold());
    }

    @Test
    void modelSwitchUsesTheReportingModelsUsageSemantics() {
        Usage usage = new Usage(100_000, 1_000, 0, 80_000, 101_000L);
        ContextData data = new ContextUsageAnalyzer(emptySources()).analyze(
            List.of(assistantWithUsage("gpt-5.6-sol", usage)),
            "anthropic.claude-sonnet-5");

        assertEquals(100_000L, data.totalTokens());
        assertEquals(50, data.percentage());
    }

    @Test
    void syntheticApiErrorDoesNotHideTheLastRealUsage() {
        Usage usage = new Usage(100_000, 1_000, 0, 80_000, 101_000L);
        List<Message> messages = List.of(
            assistantWithUsage("gpt-5.6-sol", usage),
            MessageFactory.createAssistantAPIErrorMessage(
                "Context limit reached · /compact or /clear to continue"));

        ContextData data = new ContextUsageAnalyzer(emptySources()).analyze(
            messages, "anthropic.claude-sonnet-5");

        assertEquals(usage, data.apiUsage());
        assertEquals(100_000L, data.totalTokens());
    }

    @Test
    void fallbackModelsStillShowTheOfficialAutoCompactBuffer() {
        ContextData data = new ContextUsageAnalyzer(emptySources())
            .analyze(List.of(), "anthropic.claude-sonnet-5");

        assertEquals(33_000L, byName(data, ContextData.AUTOCOMPACT_BUFFER).tokens());
        assertEquals(167_000L, byName(data, ContextData.FREE_SPACE).tokens());
    }

    @Test
    void totalFallsBackToEstimateWithoutApiUsage() {
        List<Message> messages = List.of(
            new UserMessage("u1", MessageContent.ofText("x".repeat(8_000))));
        ContextData data = new ContextUsageAnalyzer(emptySources()).analyze(messages, "m");
        assertNull(data.apiUsage());
        assertTrue(data.totalTokens() > 0);
        assertEquals(byName(data, "Messages").tokens(), data.totalTokens());
    }

    // ── message breakdown ────────────────────────────────────────────────────

    @Test
    void breakdownAttributesToolResultsViaToolUseId() {
        ToolUseBlock use = new ToolUseBlock("tu1", "Bash",
            new ObjectMapper().createObjectNode());
        List<Message> messages = List.of(
            new AssistantMessage("a1", new AssistantContent("m1", List.of(use), null)),
            new UserMessage("u1", MessageContent.ofToolResult(
                "tu1", List.of(new TextBlock("big output ".repeat(100))), false)));
        ContextData data = new ContextUsageAnalyzer(emptySources()).analyze(messages, "m");
        Optional<ContextData.ToolIo> bash = data.messageBreakdown().toolCallsByType().stream()
            .filter(t -> Strings.CS.equals(t.name(), "Bash")).findFirst();
        assertTrue(bash.isPresent());
        assertTrue(bash.get().callTokens() > 0);
        assertTrue(bash.get().resultTokens() > 0);
        assertTrue(data.messageBreakdown().toolResultTokens() > 0);
    }

    // ── agents ───────────────────────────────────────────────────────────────

    @Test
    void builtInAgentsAreExcluded() {
        List<AgentDefinition> agents = List.of(
            AgentDefinition.builder("built", "builtin agent")
                .source(AgentSource.BUILT_IN).build());
        ContextData data = new ContextUsageAnalyzer(sources(
                List.of(), "", List.of(), List.of(), agents, true))
            .analyze(List.of(), "m");
        assertTrue(data.agents().isEmpty());
        assertTrue(data.categories().stream().noneMatch(c -> Strings.CS.equals(c.name(), "Custom agents")));
    }

    // ── error isolation ──────────────────────────────────────────────────────

    @Test
    void throwingSuppliersDegradeToEmptySections() {
        ContextUsageAnalyzer.Sources sources = new ContextUsageAnalyzer.Sources(
            () -> { throw new IllegalStateException("tools boom"); },
            () -> { throw new IllegalStateException("prompt boom"); },
            () -> { throw new IllegalStateException("memory boom"); },
            () -> { throw new IllegalStateException("skills boom"); },
            _ -> { throw new IllegalStateException("agents boom"); },
            () -> { throw new IllegalStateException("compactor boom"); },
            () -> true, "/tmp");
        ContextData data = assertDoesNotThrow(() ->
            new ContextUsageAnalyzer(sources).analyze(List.of(), "m"));
        assertTrue(data.mcpTools().isEmpty());
        assertTrue(data.memoryFiles().isEmpty());
        assertTrue(data.agents().isEmpty());
        assertNull(data.skills());
    }

    private static Category byName(ContextData data, String name) {
        return data.categories().stream()
            .filter(c -> c.name().equals(name)).findFirst().orElseThrow();
    }
}
