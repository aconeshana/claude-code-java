package com.claudecode.core.prompt;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * Configuration for {@link SystemPromptService#buildSystemPrompt}.
 */
public record SystemPromptConfig(
    String modelId,
    String apiProvider,
    String workingDirectory,
    boolean isGitRepo,
    boolean isWorktree,
    List<String> additionalWorkingDirectories,
    Set<String> enabledTools,
    List<McpInstructionEntry> mcpInstructions,
    OutputStyleConfig outputStyle,
    String languagePreference,
    String scratchpadDir,
    boolean isNonInteractiveSession,
    boolean hasSkills,
    List<Path> claudeMdPaths,
    String customOverride,
    /**
     * Pre-loaded, already-merged CLAUDE.md instructions block. When non-blank,
     * takes precedence over {@link #claudeMdPaths} — bypasses the legacy
     * per-path loader and uses this text verbatim as the trailing memory
     * block. Populated by callers that use the full {@code MemoryFileScanner}
     * pipeline (discovery + @include recursion + HTML strip + glob filter);
     * legacy {@link #claudeMdPaths} stays for pre-existing callers /
     * tests.
     */
    String claudeMdContent,

    Path memoryDir,
    


    List<String> simpleSystemPromptModelPatterns
) {

    public SystemPromptConfig {
        additionalWorkingDirectories = additionalWorkingDirectories != null
            ? List.copyOf(additionalWorkingDirectories) : List.of();
        enabledTools = enabledTools != null ? Set.copyOf(enabledTools) : Set.of();
        mcpInstructions = mcpInstructions != null ? List.copyOf(mcpInstructions) : List.of();
        claudeMdPaths = claudeMdPaths != null ? List.copyOf(claudeMdPaths) : List.of();
        simpleSystemPromptModelPatterns = simpleSystemPromptModelPatterns != null
            ? List.copyOf(simpleSystemPromptModelPatterns) : List.of();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Fluent builder for readability at the callsite. */
    public static final class Builder {
        private String modelId;
        private String apiProvider = "firstParty";
        private String workingDirectory;
        private boolean isGitRepo;
        private boolean isWorktree;
        private List<String> additionalWorkingDirectories;
        private Set<String> enabledTools;
        private List<McpInstructionEntry> mcpInstructions;
        private OutputStyleConfig outputStyle;
        private String languagePreference;
        private String scratchpadDir;
        private boolean isNonInteractiveSession;
        private boolean hasSkills;
        private List<Path> claudeMdPaths;
        private String customOverride;
        private String claudeMdContent;
        private Path memoryDir;
        private List<String> simpleSystemPromptModelPatterns;

        private Builder() {}

        public Builder modelId(String v) { this.modelId = v; return this; }
        public Builder apiProvider(String v) { this.apiProvider = v; return this; }
        public Builder workingDirectory(String v) { this.workingDirectory = v; return this; }
        public Builder isGitRepo(boolean v) { this.isGitRepo = v; return this; }
        public Builder isWorktree(boolean v) { this.isWorktree = v; return this; }
        public Builder additionalWorkingDirectories(List<String> v) { this.additionalWorkingDirectories = v; return this; }
        public Builder enabledTools(Set<String> v) { this.enabledTools = v; return this; }
        public Builder mcpInstructions(List<McpInstructionEntry> v) { this.mcpInstructions = v; return this; }
        public Builder outputStyle(OutputStyleConfig v) { this.outputStyle = v; return this; }
        public Builder languagePreference(String v) { this.languagePreference = v; return this; }
        public Builder scratchpadDir(String v) { this.scratchpadDir = v; return this; }
        public Builder isNonInteractiveSession(boolean v) { this.isNonInteractiveSession = v; return this; }
        public Builder hasSkills(boolean v) { this.hasSkills = v; return this; }
        public Builder claudeMdPaths(List<Path> v) { this.claudeMdPaths = v; return this; }
        public Builder customOverride(String v) { this.customOverride = v; return this; }
        public Builder claudeMdContent(String v) { this.claudeMdContent = v; return this; }
        public Builder memoryDir(Path v) { this.memoryDir = v; return this; }
        public Builder simpleSystemPromptModelPatterns(List<String> v) {
            this.simpleSystemPromptModelPatterns = v;
            return this;
        }

        public SystemPromptConfig build() {
            return new SystemPromptConfig(
                modelId, apiProvider, workingDirectory, isGitRepo, isWorktree,
                additionalWorkingDirectories, enabledTools, mcpInstructions,
                outputStyle, languagePreference, scratchpadDir,
                isNonInteractiveSession, hasSkills, claudeMdPaths, customOverride,
                claudeMdContent, memoryDir, simpleSystemPromptModelPatterns);
        }
    }
}
