package com.claudecode.tools.agent;

import org.apache.commons.lang3.Strings;
import com.claudecode.core.agent.BuiltInAgentDefinitions;
import com.claudecode.core.engine.AbortController;
import com.claudecode.core.feature.FeatureGate;

import com.claudecode.core.engine.StreamingClient;
import com.claudecode.core.engine.ToolExecutionContext;
import java.util.Collections;
import java.util.Iterator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import com.claudecode.tools.ToolRegistry;

class BuiltInAgentDefinitionsTest {

    @Test
    void interactiveList_hasSixAgentsIncludingClaudeAndGuide() {

        List<BuiltInAgentDefinitions.AgentDefinition> agents =
            BuiltInAgentDefinitions.getBuiltInAgents("cli");
        assertEquals(6, agents.size());
        assertTrue(agents.stream().anyMatch(a -> Strings.CS.equals(a.agentType(), "claude")));
        assertTrue(agents.stream().anyMatch(a -> Strings.CS.equals(a.agentType(), "claude-code-guide")));
        assertEquals("haiku", BuiltInAgentDefinitions.CLAUDE_CODE_GUIDE.model());
    }

    @Test
    void sdkCliList_hasClaudeButExcludesGuide() {
        List<String> types = BuiltInAgentDefinitions.getBuiltInAgents("sdk-cli").stream()
            .map(BuiltInAgentDefinitions.AgentDefinition::agentType).toList();
        assertEquals(5, types.size());
        assertTrue(types.contains("claude"), "sdk-cli must have claude");
        assertTrue(types.contains("general-purpose"), "must have general-purpose");
        assertTrue(types.contains("Explore"),          "must have Explore");
        assertTrue(types.contains("Plan"),             "must have Plan");
        assertFalse(types.contains("claude-code-guide"), "sdk-cli must exclude claude-code-guide");
        assertTrue(types.contains("statusline-setup"), "must have statusline-setup");
        assertEquals("sonnet", BuiltInAgentDefinitions.STATUSLINE_SETUP.model());
    }

    @Test
    void whenToUse_verbatimPortedFromTs() {

        String gp  = BuiltInAgentDefinitions.GENERAL_PURPOSE.whenToUse();
        String exp = BuiltInAgentDefinitions.EXPLORE.whenToUse();
        String pln = BuiltInAgentDefinitions.PLAN.whenToUse();
        String ccg = BuiltInAgentDefinitions.CLAUDE_CODE_GUIDE.whenToUse();
        String sl  = BuiltInAgentDefinitions.STATUSLINE_SETUP.whenToUse();

        assertTrue(Strings.CS.contains(gp, "General-purpose agent"), gp);
        assertTrue(Strings.CS.contains(exp, "Fast agent specialized for exploring codebases"), exp);
        assertTrue(Strings.CS.contains(pln, "Software architect agent"), pln);
        assertTrue(Strings.CS.contains(ccg, "Can Claude..."), ccg);
        assertTrue(Strings.CS.contains(ccg, "Claude Agent SDK"), ccg);
        assertTrue(Strings.CS.contains(sl, "status line"), sl);
    }

    @Test
    void statuslineSetupAgentUsesTheNarrowConfigurationTool() {
        String prompt = BuiltInAgentDefinitions.STATUSLINE_SETUP.systemPrompt();

        assertNotNull(prompt);
        assertEquals(List.of("ConfigureStatusLine"),
            BuiltInAgentDefinitions.STATUSLINE_SETUP.tools());
        assertTrue(Strings.CS.contains(prompt, "ConfigureStatusLine"), prompt);
        assertTrue(Strings.CS.contains(prompt, "[Console]::In.ReadToEnd()"), prompt);
        assertTrue(Strings.CS.contains(prompt, "PowerShell script body"), prompt);
        assertTrue(Strings.CS.contains(prompt, "Windows PowerShell 5.1"), prompt);
        assertTrue(Strings.CS.contains(prompt, "ASCII display text"), prompt);
        assertFalse(Strings.CS.contains(prompt, "Read the target"), prompt);
        assertFalse(Strings.CS.contains(prompt, "Re-read"), prompt);
        assertTrue(Strings.CS.contains(prompt, "Never claim success"), prompt);
    }

    @Test
    void getPromptLines_producesCorrectFormat() {
        List<String> lines = BuiltInAgentDefinitions.getPromptLines("sdk-cli");
        assertEquals(5, lines.size());
        // Each line must start with "- <agentType>: "
        for (String line : lines) {
            assertTrue(Strings.CS.startsWith(line, "- "), "line must start with '- ': " + line);
            assertTrue(Strings.CS.contains(line, ": "), "line must have ': ' separator: " + line);
            assertTrue(Strings.CS.contains(line, "Tools: "), "line must include tools: " + line);
        }
    }

    @Test
    void captured197CliExploreListingUsesDetailedReadOnlyDescriptionAndDenyList() {
        assertEquals(
            "- Explore: Fast read-only search agent for locating code. Use it to find files by "
                + "pattern (eg. \"src/components/**/*.tsx\"), grep for symbols or keywords "
                + "(eg. \"API endpoints\"), or answer \"where is X defined / which files reference "
                + "Y.\" Do NOT use it for code review, design-doc auditing, cross-file consistency "
                + "checks, or open-ended analysis — it reads excerpts rather than whole files and "
                + "will miss content past its read window. When calling, specify search breadth: "
                + "\"quick\" for a single targeted lookup, \"medium\" for moderate exploration, or "
                + "\"very thorough\" to search across multiple locations and naming conventions. "
                + "(Tools: All tools except Agent, Artifact, ExitPlanMode, Edit, Write, NotebookEdit)",
            BuiltInAgentDefinitions.getPromptLines("cli").stream()
                .filter(line -> Strings.CS.startsWith(line, "- Explore:"))
                .findFirst().orElseThrow());
    }

    @Test
    void captured197SdkCliExploreListingUsesDetailedReadOnlyDescription() {
        assertEquals(
            "- Explore: Fast read-only search agent for locating code. Use it to find files by "
                + "pattern (eg. \"src/components/**/*.tsx\"), grep for symbols or keywords "
                + "(eg. \"API endpoints\"), or answer \"where is X defined / which files reference "
                + "Y.\" Do NOT use it for code review, design-doc auditing, cross-file consistency "
                + "checks, or open-ended analysis — it reads excerpts rather than whole files and "
                + "will miss content past its read window. When calling, specify search breadth: "
                + "\"quick\" for a single targeted lookup, \"medium\" for moderate exploration, or "
                + "\"very thorough\" to search across multiple locations and naming conventions. "
                + "(Tools: All tools except Agent, Artifact, ExitPlanMode, Edit, Write, NotebookEdit)",
            BuiltInAgentDefinitions.getPromptLines("sdk-cli").stream()
                .filter(line -> Strings.CS.startsWith(line, "- Explore:"))
                .findFirst().orElseThrow());
    }

    @Test
    void captured197ClaudeAgentHasBackgroundJobPrompt() {
        var claude = BuiltInAgentDefinitions.CLAUDE;
        assertEquals("claude", claude.agentType());
        assertEquals(List.of("*"), claude.tools());
        assertTrue(Strings.CS.startsWith(claude.systemPrompt(), "This session is a background job. The user may be live or away — respond naturally either way."));
        assertTrue(Strings.CS.contains(claude.systemPrompt(), "`result:` on its own line"));
        assertTrue(Strings.CS.endsWith(claude.systemPrompt(), "Everything else: keep working."));
    }

    @Test
    void verificationAgent_registeredOnlyWhenGateOn() {
// Default (gate off) must NOT include verification.
        assertFalse(
            BuiltInAgentDefinitions.getBuiltInAgents("cli").stream()
                .anyMatch(a -> Strings.CS.equals(a.agentType(), "verification")),
            "verification must be absent when VERIFICATION_AGENT_NUDGE is off");

// With the gate on, it is registered and carries the implemented fields.
        FeatureGate.withFlags(() -> {
            List<BuiltInAgentDefinitions.AgentDefinition> agents =
                BuiltInAgentDefinitions.getBuiltInAgents("cli");
            assertTrue(agents.stream().anyMatch(a -> Strings.CS.equals(a.agentType(), "verification")),
                "verification must be registered when VERIFICATION_AGENT_NUDGE is enabled");
            BuiltInAgentDefinitions.AgentDefinition v = agents.stream()
                .filter(a -> Strings.CS.equals(a.agentType(), "verification")).findFirst().get();
            assertTrue(Strings.CS.contains(v.systemPrompt(), "DO NOT MODIFY THE PROJECT"),
                "verification system prompt must forbid project modification");
            assertTrue(Strings.CS.contains(v.criticalSystemReminder(), "VERDICT: PASS"),
                "verification critical reminder must demand a verdict");
            assertTrue(v.background(), "verification runs in background");
            assertEquals("red", v.color());

            // mapped to a Java allow-list: write/edit tools absent, run/read present.
            assertFalse(v.tools().contains("Write"), "verification must not have Write");
            assertFalse(v.tools().contains("Edit"), "verification must not have Edit");
            assertFalse(v.tools().contains("NotebookEdit"), "verification must not have NotebookEdit");
            assertTrue(v.tools().contains("Bash"), "verification must have Bash");
            assertTrue(v.tools().contains("Read"), "verification must have Read");
        }, FeatureGate.Flag.VERIFICATION_AGENT_NUDGE);
    }

    @Test
    void agentToolDescriptionAndPrompt_followCurrentTsSplit() {
        var client = new StreamingClient() {
            @Override
            public Iterator<StreamingEvent> createStream(StreamingClient.StreamRequest r) {
                return Collections.emptyIterator();
            }
            @Override public String getModel() { return "test"; }
        };
        String desc = new AgentTool(client, new ToolRegistry()).description();
        assertEquals("Launch a new agent", desc);
        String prompt = new AgentTool(client, new ToolRegistry()).prompt(
            ToolExecutionContext.of(new AbortController(), "agent-prompt"));
        assertTrue(Strings.CS.contains(prompt, "Available agent types"));


        // explains that prompts should prove understanding; it does not use
        // the historical "Write a concrete prompt" wording.
        assertTrue(Strings.CS.contains(prompt, "Writing the prompt"));
    }
}
