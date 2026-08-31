package com.claudecode.core.prompt;

import java.nio.file.Path;
import java.util.List;

/**
 * Per-query dynamic inputs for system prompt assembly, gathered by the app layer (CLI / UI) and
 * handed to the engine via {@code QueryEngineConfig.promptRuntimeSupplier}.
 */
public record SystemPromptRuntime(
    String languagePreference,
    boolean hasSkills,
    OutputStyleConfig outputStyle,
    boolean isNonInteractiveSession,
    List<McpInstructionEntry> mcpInstructions,
    List<String> additionalWorkingDirectories,
    boolean isWorktree,
    /**
     * Auto-memory directory for the {@code # Memory} section. Null → section
     * omitted (workers, tests, memory-disabled sessions). The app layer must
     * ensure the directory exists before supplying it
     * ({@code AutoMemoryPrompt.ensureAutoMemDir}).
     */
    Path memoryDir,
    /**
     * The agent-types + skills listing injected as a {@code role:system} message right after the first
     * user turn.
     */
    String agentListingMessage,
    /** Model substrings forced onto the Harness profile by rollout state. */
    List<String> simpleSystemPromptModelPatterns
) {
    public SystemPromptRuntime {
        mcpInstructions = mcpInstructions != null ? List.copyOf(mcpInstructions) : List.of();
        additionalWorkingDirectories = additionalWorkingDirectories != null
            ? List.copyOf(additionalWorkingDirectories) : List.of();
        simpleSystemPromptModelPatterns = simpleSystemPromptModelPatterns != null
            ? List.copyOf(simpleSystemPromptModelPatterns) : List.of();
    }

    /** Pre-memoryDir shape — existing callers/tests without a memory dir. */
    public SystemPromptRuntime(String languagePreference, boolean hasSkills,
                               OutputStyleConfig outputStyle, boolean isNonInteractiveSession,
                               List<McpInstructionEntry> mcpInstructions,
                               List<String> additionalWorkingDirectories, boolean isWorktree) {
        this(languagePreference, hasSkills, outputStyle, isNonInteractiveSession,
            mcpInstructions, additionalWorkingDirectories, isWorktree, null, null, List.of());
    }

    /** Pre-profile-rollout shape retained for existing callers. */
    public SystemPromptRuntime(String languagePreference, boolean hasSkills,
                               OutputStyleConfig outputStyle, boolean isNonInteractiveSession,
                               List<McpInstructionEntry> mcpInstructions,
                               List<String> additionalWorkingDirectories, boolean isWorktree,
                               Path memoryDir, String agentListingMessage) {
        this(languagePreference, hasSkills, outputStyle, isNonInteractiveSession,
            mcpInstructions, additionalWorkingDirectories, isWorktree, memoryDir,
            agentListingMessage, List.of());
    }

    /** All-defaults instance — engines wired without a supplier (tests, workers). */
    public static SystemPromptRuntime empty() {
        return new SystemPromptRuntime(null, false, null, false, List.of(), List.of(),
            false, null, null, List.of());
    }
}
