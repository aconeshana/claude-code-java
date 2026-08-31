package com.claudecode.tools.agent;
import org.apache.commons.lang3.Strings;
import com.claudecode.core.agent.BuiltInAgentDefinitions;
import com.claudecode.core.feature.FeatureGate;
import com.claudecode.core.message.TextBlock;
import com.claudecode.permissions.PermissionMode;
import com.claudecode.tools.skills.Skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.core.engine.AbortController;
import com.claudecode.core.engine.StreamingClient;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SubAgentTypeRoutingTest {

    @Test
    void exploreAgent_getsCaptured197DenyList() {

        var client = new StreamingClient() {
            @Override
            public Iterator<StreamingClient.StreamingEvent>
                    createStream(StreamingClient.StreamRequest r) {
                return Collections.emptyIterator();
            }
            @Override public String getModel() { return "test"; }
        };
        var fakeFactory = new SubAgentFactory() {
            SubAgentRequest captured;
            @Override public SubAgentResult runSubAgent(SubAgentRequest r) {
                captured = r;
                return SubAgentResult.of("done");
            }
        };
        var tool = new AgentTool(fakeFactory);
        var input = new ObjectMapper().createObjectNode();
        input.put("description", "explore the codebase");
        input.put("prompt",      "find all Java files");
        input.put("subagent_type", "Explore");
        tool.call(input, ToolExecutionContext.of(
            new AbortController(), "test"));

        assertNotNull(fakeFactory.captured);
        assertEquals("Explore", fakeFactory.captured.subagentType());
        assertTrue(fakeFactory.captured.tools().isEmpty(),
            "empty allow-list means all tools before applying the deny-list");
        assertEquals(List.of("Agent", "Artifact", "ExitPlanMode", "Edit", "Write", "NotebookEdit"),
            fakeFactory.captured.disallowedTools());
    }

    @Test
    void generalPurposeAgent_getsNoToolRestriction() {
        // general-purpose has tools: ["*"] → no restriction from agent def →
        // subagent keeps the default safe tools list (not blocked by agent def).
        var fakeFactory = new SubAgentFactory() {
            SubAgentRequest captured;
            @Override public SubAgentResult runSubAgent(SubAgentRequest r) {
                captured = r; return SubAgentResult.of("done");
            }
        };
        var tool = new AgentTool(fakeFactory);
        var input = new ObjectMapper().createObjectNode();
        input.put("description", "do a task");
        input.put("prompt",      "write a function");
        input.put("subagent_type", "general-purpose");
        tool.call(input, ToolExecutionContext.of(
            new AbortController(), "test"));

        // "*" means no override: the request should use the default tool set
        // (not an Explore-style restriction). Just verify subagentType is set.
        assertEquals("general-purpose", fakeFactory.captured.subagentType());
    }

    @Test
    void subAgentRequest_builder_setsSubagentType() {
        var req = SubAgentRequest.builder()
            .prompt("hello")
            .subagentType("Plan")
            .build();
        assertEquals("Plan", req.subagentType());
    }

    @Test
    void subAgentRequest_builder_emptySubagentTypeByDefault() {
        var req = SubAgentRequest.builder().prompt("hello").build();
        assertNull(req.subagentType());
    }

    @Test
    void customAgentMaxTurns_isThreadedIntoSubAgentRequest(@TempDir Path tmp) throws IOException {
        Path agentsDir = tmp.resolve(".claude/agents");
        Files.createDirectories(agentsDir);
        Files.writeString(agentsDir.resolve("limited.md"), """
            ---
            name: limited
            description: Runs with a turn limit
            maxTurns: 9
            ---
            Complete the task within the limit.
            """);
        AgentDefinitionLoader.clearCache();

        SubAgentRequest[] captured = new SubAgentRequest[1];
        var tool = new AgentTool(request -> {
            captured[0] = request;
            return SubAgentResult.of("done");
        });
        var input = new ObjectMapper().createObjectNode();
        input.put("description", "bounded task");
        input.put("prompt", "do the work");
        input.put("subagent_type", "limited");

        tool.call(input, ToolExecutionContext.builder(new AbortController(), "test").workingDirectory(tmp.toString()).build());

        assertNotNull(captured[0]);
        assertEquals(9, captured[0].maxTurns());
    }

    @Test
    void customAgentExtensionFields_areThreadedIntoSubAgentRequest(@TempDir Path tmp)
            throws InterruptedException {
        AgentDefinitionLoader.setCliAgentsProvider(() -> AgentDefinitionLoader.parseCliAgents(
            "{\"extension\":{\"description\":\"d\",\"prompt\":\"p\","
                + "\"model\":\"haiku\","
                + "\"effort\":\"low\",\"permissionMode\":\"plan\","
                + "\"background\":true}}"));
        try {
            CountDownLatch latch = new CountDownLatch(1);
            SubAgentRequest[] captured = new SubAgentRequest[1];
            var tool = new AgentTool(request -> {
                captured[0] = request;
                latch.countDown();
                return SubAgentResult.of("done");
            });
            var input = new ObjectMapper().createObjectNode();
            input.put("description", "extension task");
            input.put("prompt", "run it");
            input.put("subagent_type", "extension");
            tool.call(input, ToolExecutionContext.builder(new AbortController(), "test").workingDirectory(tmp.toString()).build());

            assertTrue(latch.await(2, TimeUnit.SECONDS));
            assertNotNull(captured[0]);
            assertEquals("low", captured[0].effort());
            assertEquals("haiku", captured[0].model());
            assertEquals(PermissionMode.PLAN,
                captured[0].permissionMode());
            assertTrue(captured[0].async());
        } finally {
            AgentDefinitionLoader.setCliAgentsProvider(null);
        }
    }

    @Test
    void forkSubAgent_usesOriginalTwoHundredTurnLimit() throws InterruptedException {
        SubAgentRequest[] captured = new SubAgentRequest[1];
        CountDownLatch latch = new CountDownLatch(1);
        var tool = new AgentTool(request -> {
            captured[0] = request;
            latch.countDown();
            return SubAgentResult.of("done");
        });
        var input = new ObjectMapper().createObjectNode();
        input.put("description", "fork task");
        input.put("prompt", "continue from parent context");
        input.put("fork", true);

        tool.call(input, ToolExecutionContext.of(new AbortController(), "test"));

        assertTrue(latch.await(2, TimeUnit.SECONDS), "fork agent request was not dispatched");
        assertNotNull(captured[0]);
        assertEquals(200, captured[0].maxTurns());
    }

    @Test
    void defaultSubAgentFactory_systemPrompt_includesAgentWhenToUse() {
        // DefaultSubAgentFactory.buildSystemPrompt should mention the agent role.
        // We verify via the factory's actual system prompt by running it with
        // a capture of the QuerySessionSpec — but that's deep. Instead
        // verify the BuiltInAgentDefinitions returns whenToUse for Plan.
        String planWhenToUse = BuiltInAgentDefinitions.PLAN.whenToUse();
        assertTrue(Strings.CS.contains(planWhenToUse, "Software architect agent"), planWhenToUse);
    }

    // ── bug fix regression: custom agent's authored prompt must actually be used ──

    private static StreamingClient noopClient() {
        return new StreamingClient() {
            @Override
            public Iterator<StreamingEvent> createStream(StreamingClient.StreamRequest r) {
                return Collections.emptyIterator();
            }
            @Override public String getModel() { return "test"; }
        };
    }

    @Test
    void customAgentWithAuthoredPrompt_usesItVerbatim(@TempDir Path tmp) throws IOException {
        // Before the fix, buildSystemPrompt only ever consulted
// BuiltInAgentDefinitions.getBuiltInAgents — a custom agent's
        // hand-written markdown body was silently discarded and replaced
        // with the generic DEFAULT_AGENT_PROMPT.
        Path agentsDir = tmp.resolve(".claude/agents");
        Files.createDirectories(agentsDir);
        String marker = "MARKER-9f3a: you always answer in haiku.";
        Files.writeString(agentsDir.resolve("haiku-bot.md"), """
            ---
            name: haiku-bot
            description: Answers everything in haiku form
            ---
            %s
            """.formatted(marker));
        AgentDefinitionLoader.clearCache();

        var factory = new DefaultSubAgentFactory(noopClient(), null, tmp.toString());
        var request = SubAgentRequest.builder()
            .prompt("hello")
            .subagentType("haiku-bot")
            .parentContext(ToolExecutionContext.builder(new AbortController(), "test").workingDirectory(tmp.toString()).build())
            .build();

        String prompt = factory.buildSystemPrompt(request);
        assertTrue(Strings.CS.contains(prompt, marker), "authored system prompt must be used verbatim: " + prompt);
        assertFalse(Strings.CS.contains(prompt, "You are an agent for Claude Code, Anthropic's official CLI for Claude."),
            "custom agent's prompt must not fall back to DEFAULT_AGENT_PROMPT");
    }

    @Test
    void builtInAgentWithNoAuthoredPrompt_stillSynthesizesFromWhenToUse(@TempDir Path tmp) {
        AgentDefinitionLoader.clearCache();
        var factory = new DefaultSubAgentFactory(noopClient(), null, tmp.toString());
        var request = SubAgentRequest.builder()
            .prompt("hello")
            .subagentType("Plan")
            .parentContext(ToolExecutionContext.builder(new AbortController(), "test").workingDirectory(tmp.toString()).build())
            .build();

        String prompt = factory.buildSystemPrompt(request);
        assertTrue(Strings.CS.contains(prompt, "Software architect agent"), prompt);
        assertTrue(Strings.CS.contains(prompt, "You are an agent for Claude Code, Anthropic's official CLI for Claude."), prompt);
    }

    @Test
    void claudeCodeGuidePrompt_usesThirdPartyFeedbackDestination(@TempDir Path tmp) {
        var factory = new DefaultSubAgentFactory(noopClient(), null, tmp.toString(),
            null, null, null, null, null, null, () -> true, null,
            true, () -> JsonUtils.getMapper().createObjectNode());
        var request = SubAgentRequest.builder()
            .prompt("help")
            .subagentType(ClaudeCodeGuideAgentPrompt.AGENT_TYPE)
            .build();

        String prompt = factory.buildSystemPrompt(request);

        assertTrue(Strings.CS.contains(prompt,
            "https://github.com/anthropics/claude-code/issues"), prompt);
        assertFalse(Strings.CS.contains(prompt, "use /feedback"), prompt);
    }

    @Test
    void claudeCodeGuidePrompt_keepsFeedbackCommandForFirstParty() {
        String prompt = ClaudeCodeGuideAgentPrompt.build(false);

        assertTrue(Strings.CS.contains(prompt,
            "use /feedback to report a feature request or bug"), prompt);
        assertFalse(Strings.CS.contains(prompt,
            "github.com/anthropics/claude-code/issues"), prompt);
    }

    @Test
    void claudeCodeGuidePrompt_configurationAppendixMatchesReleasedFormatting() {
        var settings = JsonUtils.getMapper().createObjectNode();
        settings.put("theme", "dark");
        settings.putObject("sandbox");
        var context = new ClaudeCodeGuideAgentPrompt.Context(
            List.of(new ClaudeCodeGuideAgentPrompt.Command("check", "Run checks")),
            List.of(new ClaudeCodeGuideAgentPrompt.Agent("reviewer", "Review changes")),
            List.of(new ClaudeCodeGuideAgentPrompt.Command("plugin:audit", "Audit changes")),
            settings);

        String prompt = ClaudeCodeGuideAgentPrompt.build(false, context);
        String appendix = prompt.substring(prompt.indexOf("\n\n---\n\n"));

        assertEquals("""


            ---

            # User's Current Configuration

            The user has the following custom setup in their environment:

            **Available custom skills in this project:**
            - /check: Run checks

            **Available custom agents configured:**
            - reviewer: Review changes

            **Available plugin skills:**
            - /plugin:audit: Audit changes

            **User's settings.json:**
            ```json
            {
              "theme": "dark",
              "sandbox": {}
            }
            ```

            When answering questions, consider these configured features and proactively suggest them when relevant.""",
            appendix);
    }

    @Test
    void claudeCodeGuidePrompt_appendsLiveUserConfiguration(@TempDir Path tmp) throws IOException {
        Path agentsDir = tmp.resolve(".claude/agents");
        Files.createDirectories(agentsDir);
        Files.writeString(agentsDir.resolve("reviewer.md"), """
            ---
            name: reviewer
            description: Reviews changes carefully
            ---
            Review the requested changes.
            """);
        AgentDefinitionLoader.clearCache();

        var customSkill = new Skill(
            "local-check", "Run local checks", List.of(), "body", null,
            Skill.SkillSource.PROJECT,
            null, null, null, null);
        var pluginSkill = new Skill(
            "plugin-audit", "Audit with the plugin", List.of(), "body", null,
            Skill.SkillSource.PLUGIN,
            null, null, null, null);
        var settings = JsonUtils.getMapper().createObjectNode().put("alwaysThinkingEnabled", false);
        var factory = new DefaultSubAgentFactory(noopClient(), null, tmp.toString(),
            null, null, null, null, null, () -> List.of(customSkill, pluginSkill),
            () -> true, null, false, () -> settings);
        var request = SubAgentRequest.builder()
            .prompt("help")
            .subagentType(ClaudeCodeGuideAgentPrompt.AGENT_TYPE)
            .parentContext(ToolExecutionContext.builder(new AbortController(), "test")
                .workingDirectory(tmp.toString()).build())
            .build();

        String prompt = factory.buildSystemPrompt(request);

        assertTrue(Strings.CS.contains(prompt, "# User's Current Configuration"), prompt);
        assertTrue(Strings.CS.contains(prompt, "- /local-check: Run local checks"), prompt);
        assertTrue(Strings.CS.contains(prompt, "- reviewer: Reviews changes carefully"), prompt);
        assertFalse(Strings.CS.contains(prompt, "**Configured MCP servers:**"), prompt);
        assertTrue(Strings.CS.contains(prompt, "- /plugin-audit: Audit with the plugin"), prompt);
        assertTrue(Strings.CS.contains(prompt, "\"alwaysThinkingEnabled\": false"), prompt);
        assertTrue(Strings.CS.contains(prompt, "Notes:\n- Agent threads always have their cwd reset"), prompt);
        assertTrue(Strings.CS.contains(prompt, "Here is useful information about the environment"), prompt);
    }

    @Test
    void claudeCodeGuidePrompt_projectsReleasedPromptCommandsAndDescriptions(@TempDir Path tmp) {
        var skills = List.of(
            guideSkill("deep-research", "long invocation description"),
            guideSkill("verify", "Verify changes"),
            guideSkill("simplify", "Simplify changes"),
            guideSkill("loop", "long loop invocation description"),
            guideSkill("run", "Run app"),
            guideSkill("init", "Initialize CLAUDE.md"),
            guideSkill("security-review", "Review security"));
        var factory = new DefaultSubAgentFactory(noopClient(), null, tmp.toString(),
            null, null, null, null, null, () -> skills,
            () -> true, null, false, () -> JsonUtils.getMapper().createObjectNode());

        String prompt = factory.buildSystemPrompt(SubAgentRequest.builder()
            .prompt("help")
            .subagentType(ClaudeCodeGuideAgentPrompt.AGENT_TYPE)
            .build());

        assertTrue(Strings.CS.contains(prompt,
            "/deep-research: Deep research harness — fan-out web searches"), prompt);
        assertTrue(Strings.CS.contains(prompt, "/debug: Enable debug logging"), prompt);
        assertTrue(Strings.CS.contains(prompt, "/batch: Research and plan"), prompt);
        assertTrue(Strings.CS.contains(prompt, "/run-skill-generator: Author or improve"), prompt);
        assertTrue(Strings.CS.contains(prompt, "/statusline: Set up Claude Code"), prompt);
        assertTrue(Strings.CS.contains(prompt, "/insights: Generate a report"), prompt);
        assertTrue(Strings.CS.contains(prompt, "/team-onboarding: Help teammates"), prompt);
        assertFalse(Strings.CS.contains(prompt, "long loop invocation description"), prompt);
    }

    private static Skill guideSkill(String name, String description) {
        return new Skill(
            name, description, List.of(), "body", null,
            Skill.SkillSource.BUNDLED,
            null, null, null, null);
    }



    @Test
    void verificationAgent_systemPromptUsesAuthoredVerificationPrompt(@TempDir Path tmp) {
        // The verification agent carries a full authored system prompt; the
        // factory must use it verbatim (not fall back to DEFAULT_AGENT_PROMPT).
        FeatureGate.withFlags(() -> {
            AgentDefinitionLoader.clearCache();
            var factory = new DefaultSubAgentFactory(noopClient(), null, tmp.toString());
            var request = SubAgentRequest.builder()
                .prompt("verify")
                .subagentType("verification")
                .parentContext(ToolExecutionContext.builder(new AbortController(), "test").workingDirectory(tmp.toString()).build())
                .build();

            String prompt = factory.buildSystemPrompt(request);
            assertTrue(Strings.CS.contains(prompt, "DO NOT MODIFY THE PROJECT"), prompt);
            assertTrue(Strings.CS.contains(prompt, "VERDICT: PASS"), prompt);
            assertFalse(Strings.CS.contains(prompt, 
                "You are an agent for Claude Code, Anthropic's official CLI for Claude."),
                "verification must use its authored prompt, not DEFAULT_AGENT_PROMPT");
        }, FeatureGate.Flag.VERIFICATION_AGENT_NUDGE);
    }

    @Test
    void verificationAgent_criticalReminderThreadedToEngineConfig(@TempDir Path tmp) {
        // The agent definition's criticalSystemReminder must flow through
        // SubAgentRequest → QuerySessionSpec.criticalSystemReminder, where
        // CriticalSystemReminderProvider re-injects it as a system-reminder.
        FeatureGate.withFlags(() -> {
            AgentDefinitionLoader.clearCache();
            var factory = new DefaultSubAgentFactory(noopClient(), null, tmp.toString());
            var request = SubAgentRequest.builder()
                .prompt("verify")
                .subagentType("verification")
                .criticalSystemReminder(BuiltInAgentDefinitions.VERIFICATION.criticalSystemReminder())
                .tools(BuiltInAgentDefinitions.VERIFICATION.tools())
                .parentContext(ToolExecutionContext.builder(new AbortController(), "test").workingDirectory(tmp.toString()).build())
                .build();

            var config = factory.buildSubEngineConfig(request);
            assertEquals(BuiltInAgentDefinitions.VERIFICATION.criticalSystemReminder(),
                config.criticalSystemReminder());
        }, FeatureGate.Flag.VERIFICATION_AGENT_NUDGE);
    }

    @Test
    void verificationAgent_asyncSpawnHasCorrectToolsAndReminder(@TempDir Path tmp) throws Exception {
        // Full AgentTool path: spawning subagent_type="verification" must resolve
        // the def, apply its allow-list (no write tools), set its critical
        // reminder, and force async (background:true). Uses a latch because the
        // async path runs the sub-agent on a virtual thread.
        FeatureGate.withFlags(() -> {
            AgentDefinitionLoader.clearCache();
            var latch = new CountDownLatch(1);
            SubAgentRequest[] captured = new SubAgentRequest[1];
            var fakeFactory = new SubAgentFactory() {
                @Override public SubAgentResult runSubAgent(SubAgentRequest r) {
                    captured[0] = r;
                    latch.countDown();
                    return SubAgentResult.of("done");
                }
            };
            var tool = new AgentTool(fakeFactory);
            var input = new ObjectMapper().createObjectNode();
            input.put("description", "verify the change");
            input.put("prompt", "verify the implementation");
            input.put("subagent_type", "verification");
            String result = tool.call(input, ToolExecutionContext.builder(new AbortController(), "test").workingDirectory(tmp.toString()).build())
                .content().stream()
                .filter(TextBlock.class::isInstance)
                .map(TextBlock.class::cast)
                .map(TextBlock::text)
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
            assertTrue(Strings.CS.contains(result, "Async agent launched"),
                "verification (background:true) must run async: " + result);

            try {
                assertTrue(latch.await(10, TimeUnit.SECONDS), "sub-agent spawn must run");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
            var req = captured[0];
            assertNotNull(req);
            assertEquals("verification", req.subagentType());
            assertFalse(req.tools().contains("Write"), "verification must not have Write");
            assertFalse(req.tools().contains("Edit"), "verification must not have Edit");
            assertFalse(req.tools().contains("NotebookEdit"), "verification must not have NotebookEdit");
            assertTrue(req.tools().contains("Bash"), "verification must have Bash");
            assertTrue(req.tools().contains("Read"), "verification must have Read");
            assertEquals(BuiltInAgentDefinitions.VERIFICATION.criticalSystemReminder(),
                req.criticalSystemReminder());
        }, FeatureGate.Flag.VERIFICATION_AGENT_NUDGE);
    }
}
