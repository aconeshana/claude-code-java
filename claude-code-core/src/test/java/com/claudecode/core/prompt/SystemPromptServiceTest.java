package com.claudecode.core.prompt;

import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link SystemPromptService} — the sections-based system prompt assembler introduced in
 * the 2026-07-01 rewrite.
 */
class SystemPromptServiceTest {

    private SystemPromptService service;

    @BeforeEach
    void setUp() {
        // Reset the resolver cache between tests so section conditional
        // branches (language / output style / scratchpad) are re-evaluated.
        SystemPromptSectionResolver.clearAll();
        service = new SystemPromptService();
    }

    @Test
    void assembledPromptContainsAllCoreSections() {
        SystemPromptConfig config = SystemPromptConfig.builder()
            .workingDirectory("/tmp/proj")
            .enabledTools(Set.of("Bash", "Read", "Write", "Edit", "Glob", "Grep"))
            .build();

        String prompt = service.buildSystemPrompt(config);


        assertTrue(Strings.CS.contains(prompt, "You are an interactive agent"),
            "intro identity line missing");
        assertTrue(Strings.CS.contains(prompt, "Dual-use security tools"),
            "CYBER_RISK_INSTRUCTION dual-use segment missing");

        assertTrue(Strings.CS.contains(prompt, "# System"), "# System heading missing");
        assertTrue(Strings.CS.contains(prompt, "# Doing tasks"), "# Doing tasks heading missing");
        assertTrue(Strings.CS.contains(prompt, "# Executing actions with care"),
            "actions heading missing");
        assertTrue(Strings.CS.contains(prompt, "# Using your tools"),
            "tools heading missing");
        assertTrue(Strings.CS.contains(prompt, "# Text output (does not apply to tool calls)"),
            "text-output heading missing");
        assertTrue(Strings.CS.contains(prompt, "# Context management"),
            "# Context management heading missing");
        assertFalse(Strings.CS.contains(prompt, "# Harness"),
            "a null model keeps the released long-profile default");
        // Env info dynamic section
        assertTrue(Strings.CS.contains(prompt, "# Environment"), "# Environment heading missing");
        assertTrue(Strings.CS.contains(prompt, "Primary working directory: /tmp/proj"),
            "primary cwd line missing");
        // Boundary marker between static + dynamic
        assertTrue(Strings.CS.contains(prompt, SystemPromptConstants.SYSTEM_PROMPT_DYNAMIC_BOUNDARY),
            "dynamic boundary marker missing");
    }

    @Test
    void customOverrideShortCircuitsAssembly() {
        SystemPromptConfig config = SystemPromptConfig.builder()
            .customOverride("MY OWN PROMPT")
            .workingDirectory("/tmp")
            .build();

        String prompt = service.buildSystemPrompt(config);

        assertEquals("MY OWN PROMPT", prompt);
        assertFalse(Strings.CS.contains(prompt, "# System"));
        assertFalse(Strings.CS.contains(prompt, SystemPromptConstants.SYSTEM_PROMPT_DYNAMIC_BOUNDARY));
    }

    @Test
    void blankCustomOverrideDoesNotShortCircuit() {
        SystemPromptConfig config = SystemPromptConfig.builder()
            .customOverride("   ")
            .workingDirectory("/tmp")
            .build();

        String prompt = service.buildSystemPrompt(config);

        assertTrue(Strings.CS.contains(prompt, "# System"));
    }

    @Test
    void languageSectionInjectedWhenPreferenceSet() {
        SystemPromptConfig config = SystemPromptConfig.builder()
            .languagePreference("Chinese")
            .workingDirectory("/tmp")
            .build();

        String prompt = service.buildSystemPrompt(config);

        assertTrue(Strings.CS.contains(prompt, "# Language"), "# Language heading missing");
        assertTrue(Strings.CS.contains(prompt, "Always respond in Chinese"),
            "language directive missing");
    }

    @Test
    void languageSectionOmittedWhenNoPreference() {
        SystemPromptConfig config = SystemPromptConfig.builder()
            .workingDirectory("/tmp")
            .build();

        String prompt = service.buildSystemPrompt(config);

        assertFalse(Strings.CS.contains(prompt, "# Language"));
    }

    @Test
    void scratchpadInstructionsInjectedWhenDirSet() {
        SystemPromptConfig config = SystemPromptConfig.builder()
            .scratchpadDir("/var/scratch/session-42")
            .workingDirectory("/tmp")
            .build();

        String prompt = service.buildSystemPrompt(config);

        assertTrue(Strings.CS.contains(prompt, "# Scratchpad Directory"),
            "# Scratchpad Directory heading missing");
        assertTrue(Strings.CS.contains(prompt, "/var/scratch/session-42"),
            "scratchpad path missing");
    }

    @Test
    void outputStyleSectionInjectedWhenActive() {
        SystemPromptConfig config = SystemPromptConfig.builder()
            .outputStyle(OutputStylePresets.EXPLANATORY)
            .workingDirectory("/tmp")
            .build();

        String prompt = service.buildSystemPrompt(config);

        assertTrue(Strings.CS.contains(prompt, "# Output Style: Explanatory"),
            "output style heading missing");
    }

    @Test
    void claudeMdBlockAppendedWhenPathProvided(@TempDir Path tempDir) throws IOException {
        Path claudeMd = tempDir.resolve("CLAUDE.md");
        Files.writeString(claudeMd, "Always use tabs for indentation.");

        SystemPromptConfig config = SystemPromptConfig.builder()
            .claudeMdPaths(List.of(claudeMd))
            .workingDirectory(tempDir.toString())
            .build();

        String prompt = service.buildSystemPrompt(config);

        assertTrue(Strings.CS.contains(prompt, "Always use tabs for indentation."),
            "CLAUDE.md content missing");
    }

    @Test
    void assembledPromptWithNoClaudeMdOmitsBlock() {
        SystemPromptConfig config = SystemPromptConfig.builder()
            .workingDirectory("/tmp")
            .build();

        String prompt = service.buildSystemPrompt(config);

        assertFalse(Strings.CS.contains(prompt, "Always use tabs"),
            "unexpected CLAUDE.md fragment");
    }

    @Test
    void mcpInstructionsInjectedWhenEntriesProvided() {
        SystemPromptConfig config = SystemPromptConfig.builder()
            .mcpInstructions(List.of(
                new McpInstructionEntry("github-mcp", "Use for GH ops.")))
            .workingDirectory("/tmp")
            .build();

        String prompt = service.buildSystemPrompt(config);

        assertTrue(Strings.CS.contains(prompt, "# MCP Server Instructions"),
            "MCP heading missing");
        assertTrue(Strings.CS.contains(prompt, "github-mcp"), "MCP server name missing");
        assertTrue(Strings.CS.contains(prompt, "Use for GH ops."), "MCP instructions body missing");
    }

    @Test
    void sessionGuidanceOmittedWhenNoQualifyingBullets() {
        // No tools, non-interactive → no bullets qualify → section null → omitted
        SystemPromptConfig config = SystemPromptConfig.builder()
            .enabledTools(Set.of())
            .isNonInteractiveSession(true)
            .workingDirectory("/tmp")
            .build();

        String prompt = service.buildSystemPrompt(config);

        assertFalse(Strings.CS.contains(prompt, "# Session-specific guidance"));
    }

    @Test
    void doingTasksSectionRemovedWhenOutputStyleOverrides() {
        // A style with keepCodingInstructions=false replaces the coding-focused

        // Explanatory/Learning both keep them; craft an ad-hoc style that
        // overrides for the test.
        OutputStyleConfig override = new OutputStyleConfig(
            "Poet", "poetic replies", "Speak only in haiku.", false,
            OutputStyleConfig.Source.BUILT_IN, false);
        SystemPromptConfig config = SystemPromptConfig.builder()
            .outputStyle(override)
            .workingDirectory("/tmp")
            .build();

        String prompt = service.buildSystemPrompt(config);

        assertFalse(Strings.CS.contains(prompt, "# Doing tasks"),
            "# Doing tasks should be omitted when keepCodingInstructions=false");
    }
}
