package com.claudecode.runtime.query;

import com.claudecode.core.engine.StreamingClient;

import org.apache.commons.lang3.Strings;

import com.claudecode.core.prompt.McpInstructionEntry;
import com.claudecode.core.prompt.OutputStylePresets;
import com.claudecode.core.prompt.SystemPromptRuntime;
import com.claudecode.core.prompt.SystemPromptSectionResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Iterator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Wiring guard for {@code QuerySessionSpec.promptRuntimeSupplier} →
 * {@code assembleSystemPrompt}: dynamic inputs supplied by the app layer must
 * surface as their prompt sections, and a missing/throwing supplier must fall
 * back to defaults instead of failing the query. The section TEXT itself is
 * guarded by {@code SystemPrompt197ParityTest}; this test only proves the
 * data flow reaches it.
 */
class PromptRuntimeWiringTest {

    private static StreamingClient noopClient() {
        return new StreamingClient() {
            @Override public Iterator<StreamingEvent> createStream(StreamRequest request) {
                return List.<StreamingEvent>of().iterator();
            }
            @Override public String getModel() { return "test-model"; }
        };
    }

    @BeforeEach
    void reset() {
        SystemPromptSectionResolver.clearAll();
    }

    private DefaultQuerySession engineWith(SystemPromptRuntime runtime) {
        return engineWith(runtime, List.of());
    }

    private DefaultQuerySession engineWith(SystemPromptRuntime runtime, List<String> tools) {
        return new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(noopClient())
            .workingDirectory("/tmp/proj")
            .tools(tools)
            .promptRuntimeSupplier(() -> runtime)
            .build());
    }

    @Test
    void languagePreferenceProducesLanguageSection() {
        String prompt = engineWith(new SystemPromptRuntime(
            "Chinese", false, null, false, List.of(), List.of(), false))
            .fetchSystemPromptParts();
        assertTrue(Strings.CS.contains(prompt, "# Language"), "language section missing");
        assertTrue(Strings.CS.contains(prompt, "Always respond in Chinese"), prompt.substring(0, 200));
    }

    @Test
    void hasSkillsProducesSkillGuidanceBullet() {
        String prompt = engineWith(new SystemPromptRuntime(
            null, true, null, false, List.of(), List.of(), false),
            List.of("Skill"))
            .fetchSystemPromptParts();
        assertTrue(Strings.CS.contains(prompt, "invoke it via Skill"), "skill guidance bullet missing");
    }

    @Test
    void skillsOnDiskWithoutSkillToolDoNotProduceGuidanceBullet() {
        String prompt = engineWith(new SystemPromptRuntime(
            null, true, null, false, List.of(), List.of(), false))
            .fetchSystemPromptParts();
        assertFalse(Strings.CS.contains(prompt, "invoke it via Skill"),
            "skill guidance must remain gated by the Skill tool");
    }

    @Test
    void nonInteractiveFoldsShellHint() {
        String interactive = engineWith(new SystemPromptRuntime(
            null, false, null, false, List.of(), List.of(), false))
            .fetchSystemPromptParts();
        SystemPromptSectionResolver.clearAll();
        String headless = engineWith(new SystemPromptRuntime(
            null, false, null, true, List.of(), List.of(), false))
            .fetchSystemPromptParts();
        assertTrue(Strings.CS.contains(interactive, "`! <command>`"), "interactive shell hint missing");
        assertFalse(Strings.CS.contains(headless, "`! <command>`"), "headless must fold the shell hint");
        assertTrue(Strings.CS.startsWith(interactive, "You are Claude Code, Anthropic's official CLI for Claude."));
        assertTrue(Strings.CS.startsWith(headless, "You are a Claude agent, built on Anthropic's Claude Agent SDK."),
            "print/headless mode must use the Agent SDK identity block");
    }

    @Test
    void outputStyleAndMcpInstructionsSurface() {
        String prompt = engineWith(new SystemPromptRuntime(
            null, false, OutputStylePresets.EXPLANATORY, false,
            List.of(new McpInstructionEntry("gh-server", "Use for GH ops.")),
            List.of("/tmp/other"), false))
            .fetchSystemPromptParts();
        assertTrue(Strings.CS.contains(prompt, "# Output Style: Explanatory"), "output style missing");
        assertTrue(Strings.CS.contains(prompt, "# MCP Server Instructions"), "mcp instructions missing");
        assertTrue(Strings.CS.contains(prompt, "gh-server"), "mcp server name missing");
        assertTrue(Strings.CS.contains(prompt, "Additional working directories:"), "additional dirs missing");
        assertTrue(Strings.CS.contains(prompt, "/tmp/other"), "additional dir entry missing");
    }

    @Test
    void missingSupplierFallsBackToDefaults() {
        DefaultQuerySession engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(noopClient())
            .workingDirectory("/tmp/proj")
            .build());
        String prompt = engine.fetchSystemPromptParts();
        assertFalse(Strings.CS.contains(prompt, "# Language"));
        assertTrue(Strings.CS.contains(prompt, "# System"), "base prompt must still assemble");
    }

    @Test
    void throwingSupplierIsSwallowed() {
        DefaultQuerySession engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(noopClient())
            .workingDirectory("/tmp/proj")
            .promptRuntimeSupplier(() -> { throw new IllegalStateException("boom"); })
            .build());
        String prompt = assertDoesNotThrow(engine::fetchSystemPromptParts);
        assertTrue(Strings.CS.contains(prompt, "# System"), "prompt must assemble despite supplier failure");
    }

    @Test
    void appendSystemPromptIsPlacedAfterDefaultOrCustomPrompt() {
        DefaultQuerySession engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(noopClient())
            .workingDirectory("/tmp/proj")
            .systemPrompt("CUSTOM BASE")
            .appendSystemPrompt("CALLER POLICY")
            .build());
        String prompt = engine.fetchSystemPromptParts();
        assertEquals(
            """
            You are Claude Code, Anthropic's official CLI for Claude.

            CUSTOM BASE

            CALLER POLICY""",
            prompt);
    }

    @Test
    void customSystemPromptDoesNotReceiveDefaultGitContext() {
        DefaultQuerySession engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(noopClient())
            .workingDirectory(System.getProperty("user.dir"))
            .systemPrompt("CUSTOM BASE")
            .build());

        String prompt = engine.fetchSystemPromptParts();

        assertEquals(
            """
            You are Claude Code, Anthropic's official CLI for Claude.

            CUSTOM BASE""",
            prompt);
        assertFalse(Strings.CS.contains(prompt, "gitStatus:"),
            "customSystemPrompt replaces the default systemContext in the TS implementation");
    }
}
