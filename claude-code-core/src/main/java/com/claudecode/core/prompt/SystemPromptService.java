package com.claudecode.core.prompt;

import org.apache.commons.lang3.StringUtils;
import com.claudecode.core.memdir.AutoMemoryPrompt;
import java.util.ArrayList;
import java.util.List;

/**
 * Assembles the full system prompt from named sections.
 */
public class SystemPromptService {

    private final ClaudeMdLoader claudeMdLoader;

    public SystemPromptService() {
        this(new ClaudeMdLoader());
    }

    public SystemPromptService(ClaudeMdLoader claudeMdLoader) {
        this.claudeMdLoader = claudeMdLoader;
    }

    /**
     * Build the assembled system prompt as a single joined string (parts separated by {@code "\n\n"}).
     */
    public String buildSystemPrompt(SystemPromptConfig config) {
        List<String> parts = buildSystemPromptParts(config);
        return String.join("\n\n", parts);
    }


    public List<String> buildSystemPromptParts(SystemPromptConfig config) {
        if (StringUtils.isNotBlank(config.customOverride())) {
            return List.of(config.customOverride());
        }

        String cwd = config.workingDirectory() != null
            ? config.workingDirectory() : System.getProperty("user.dir");

        SystemPromptProfileResolver.Profile profile =
            SystemPromptProfileResolver.resolve(config);
        boolean harnessProfile = profile == SystemPromptProfileResolver.Profile.HARNESS;


        // Harness: lean intro → Harness → [boundary] → Session guidance →
        //          Memory → Environment → Language → MCP/Scratchpad → Context.
        // Long:    intro → System → Doing tasks → Executing actions → Using
        //          tools → Tone → Text output → [boundary] → Session guidance
        //          → auto memory → Environment → Language → MCP/Scratchpad →
        //          Context management.




        List<String> parts = new ArrayList<>();
        if (harnessProfile) {
            addIfNotNull(parts, SystemPromptSections.getHarnessIntroSection(config.outputStyle()));
            addIfNotNull(parts, SystemPromptSections.getHarnessSection(config.modelId()));
        } else {
            addIfNotNull(parts, SystemPromptSections.getSimpleIntroSection(config.outputStyle()));
            addIfNotNull(parts, SystemPromptSections.getSimpleSystemSection());
            if (config.outputStyle() == null || config.outputStyle().keepCodingInstructions()) {
                addIfNotNull(parts, SystemPromptSections.getReleased197DoingTasksSection());
            }
            addIfNotNull(parts, SystemPromptSections.getActionsSection());
            addIfNotNull(parts,
                SystemPromptSections.getReleased197UsingYourToolsSection(config.enabledTools()));
            addIfNotNull(parts, SystemPromptSections.getReleased197ToneAndStyleSection());
            addIfNotNull(parts,
                SystemPromptSections.getModelAwareTextOutputSection(config.modelId()));
        }
        addIfNotNull(parts, SystemPromptSections.getFableIdentitySection(config.modelId()));

        // ── Boundary marker (stripped before the wire; see AnthropicSdkClient) ──
        parts.add(SystemPromptConstants.SYSTEM_PROMPT_DYNAMIC_BOUNDARY);

        addIfNotNull(parts, SystemPromptSections.getSessionSpecificGuidanceSection(
            config.enabledTools(), config.hasSkills(), config.isNonInteractiveSession(),
            harnessProfile));
        if (config.memoryDir() != null) {
            parts.add(harnessProfile
                ? AutoMemoryPrompt.memorySection197(config.memoryDir())
                : AutoMemoryPrompt.buildReleased197SystemPrompt(config.memoryDir()));
        }

        // ── Dynamic content (registry-managed via section cache) ────────────
        List<SystemPromptSection> dynamicSections = new ArrayList<>();
        dynamicSections.add(SystemPromptSection.cached("env_info_simple",
            () -> EnvInfoSection.computeSimpleEnvInfo(
                config.modelId(), cwd, config.isGitRepo(), config.isWorktree(),
                config.additionalWorkingDirectories())));
        dynamicSections.add(SystemPromptSection.cached("language",
            () -> SystemPromptSections.getLanguageSection(config.languagePreference())));
        dynamicSections.add(SystemPromptSection.cached("output_style",
            () -> SystemPromptSections.getOutputStyleSection(config.outputStyle())));
        dynamicSections.add(SystemPromptSection.uncached("mcp_instructions",
            () -> SystemPromptSections.getMcpInstructionsSection(config.mcpInstructions()),
            "MCP servers connect/disconnect between turns"));
        dynamicSections.add(SystemPromptSection.cached("scratchpad",
            () -> SystemPromptSections.getScratchpadInstructions(config.scratchpadDir())));
        dynamicSections.add(SystemPromptSection.cached("context_management",
            SystemPromptSections::getContextManagementSection));

// but it is absent from the target.

        List<String> resolved = SystemPromptSectionResolver.resolve(dynamicSections);
        for (String v : resolved) addIfNotNull(parts, v);
        addIfNotNull(parts, SystemPromptSections.getFableAutonomySection(config.modelId()));

        // ── CLAUDE.md instructions (explicit-config callers only) ──────────
        // The main loop no longer routes memory content through the system

// (# claudeMd section, see QueryHelpers.buildClaudeMdUserContext) and the wire

        if (StringUtils.isNotBlank(config.claudeMdContent())) {
            parts.add(config.claudeMdContent());
        } else if (config.claudeMdPaths() != null && !config.claudeMdPaths().isEmpty()) {
            String md = claudeMdLoader.loadAndMerge(config.claudeMdPaths());
            if (StringUtils.isNotBlank(md)) {
                parts.add(md);
            }
        }

        return parts;
    }

    private static void addIfNotNull(List<String> list, String value) {
        if (StringUtils.isNotEmpty(value)) list.add(value);
    }
}
