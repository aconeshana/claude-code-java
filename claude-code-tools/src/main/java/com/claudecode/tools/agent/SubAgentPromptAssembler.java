package com.claudecode.tools.agent;

import com.claudecode.core.agent.AgentSource;
import com.claudecode.core.agent.BuiltInAgentDefinitions;
import com.claudecode.core.message.ContentBlock;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.model.ModelCatalog;
import com.claudecode.core.prompt.ArgumentSubstitutor;
import com.claudecode.core.prompt.EnvInfoSection;
import com.claudecode.core.prompt.SystemPromptConstants;
import com.claudecode.core.prompt.UnrestrictedPrompt;
import com.claudecode.runtime.query.QuerySessionEnvironment;
import com.claudecode.tools.skills.Skill;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Assembles sub-agent user prompts (including preloaded skills) and system prompts
 * (built-in/custom agent persona, memory, environment, and git status).
 */
final class SubAgentPromptAssembler {

    private static final Logger log = LoggerFactory.getLogger(SubAgentPromptAssembler.class);

    private final Path claudeHomeOverride;
    private final Supplier<List<Skill>> skillSupplier;
    private final Supplier<Boolean> includeGitInstructionsSupplier;
    private final Supplier<JsonNode> effectiveSettingsSupplier;

    SubAgentPromptAssembler(
            Path claudeHomeOverride,
            Supplier<List<Skill>> skillSupplier,
            Supplier<Boolean> includeGitInstructionsSupplier,
            Supplier<JsonNode> effectiveSettingsSupplier) {
        this.claudeHomeOverride = claudeHomeOverride;
        this.skillSupplier = skillSupplier;
        this.includeGitInstructionsSupplier = includeGitInstructionsSupplier;
        this.effectiveSettingsSupplier = effectiveSettingsSupplier;
    }

    Object buildAgentPrompt(SubAgentRequest request, String cwd) {
        BuiltInAgentDefinitions.AgentDefinition resolved = resolveAgentDefinition(request, cwd);
        if (resolved == null || resolved.skills() == null || resolved.skills().isEmpty()
                || skillSupplier == null) {
            return request.prompt();
        }

        List<Skill> available;
        try {
            available = skillSupplier.get();
        } catch (Throwable t) {
            log.debug("Unable to load skills for agent preloading", t);
            return request.prompt();
        }
        if (available == null || available.isEmpty()) return request.prompt();

        List<ContentBlock> blocks = new ArrayList<>();
        blocks.add(new TextBlock(request.prompt() == null ? "" : request.prompt()));
        boolean preloaded = false;
        for (String requestedName : resolved.skills()) {
            if (StringUtils.isBlank(requestedName)) continue;
            Skill skill = findSkill(available, requestedName.trim());
            if (skill == null || skill.content() == null) continue;
            String content = skillPromptContent(skill);
            if (StringUtils.isEmpty(content)) continue;
            blocks.add(new TextBlock(skillLoadingMetadata(skill.name())));
            blocks.add(new TextBlock(content));
            preloaded = true;
        }
        return preloaded ? MessageContent.ofBlocks(List.copyOf(blocks)) : request.prompt();
    }

    static BuiltInAgentDefinitions.AgentDefinition resolveAgentDefinition(
            SubAgentRequest request, String cwd) {
        if (request == null) return null;
        String effectiveType = StringUtils.defaultIfBlank(
            request.subagentType(), "general-purpose");
        return AgentDefinitionLoader.getAll(cwd).stream()
            .filter(a -> a.agentType().equals(effectiveType))
            .findFirst()
            .orElse(null);
    }

    private static Skill findSkill(List<Skill> available, String requested) {
        String bare = Strings.CS.contains(requested, ":")
            ? requested.substring(requested.lastIndexOf(':') + 1) : null;
        for (Skill skill : available) {
            if (requested.equals(skill.name())) return skill;
            if (bare != null && bare.equals(skill.name())
                    && skill.source() == Skill.SkillSource.PLUGIN) return skill;
        }
        return null;
    }

    private static String skillLoadingMetadata(String name) {
        return "<command-message>" + name + "</command-message>\n"
            + "<command-name>" + name + "</command-name>\n"
            + "<skill-format>true</skill-format>";
    }

    private static String skillPromptContent(Skill skill) {
        String content = skill.content();
        if (content == null) return null;
        if (skill.source() != Skill.SkillSource.MCP && skill.sourceFile() != null
                && skill.sourceFile().getFileName() != null
                && Strings.CI.equals("SKILL.md", skill.sourceFile().getFileName().toString())) {
            Path root = skill.sourceFile().toAbsolutePath().normalize().getParent();
            if (root != null) {
                String base = root.toString();
                if (File.separatorChar == '\\') base = base.replace('\\', '/');
                content = "Base directory for this skill: " + base + "\n\n" + content;
                content = content.replace("${CLAUDE_SKILL_DIR}", base);
            }
        }

        String dormantRequest = "\n\n## User Request\n\n$ARGUMENTS";
        if (Strings.CS.endsWith(content, dormantRequest)) {
            content = content.substring(0, content.length() - dormantRequest.length());
        }
        // A preloaded agent skill is already the complete instruction body.
        return ArgumentSubstitutor.substitute(content, "", skill.argumentNames(), false);
    }

    // Package-private (not private) so SubAgentTypeRoutingTest can exercise
    // it directly without threading a fake StreamingClient through the full
    // runSubAgent → QuerySession pipeline just to inspect the prompt string.
    String buildSystemPrompt(SubAgentRequest request, String cwd, boolean usingThirdPartyServices, boolean unrestricted) {
        if (request.systemPromptOverride() != null) {
            return request.systemPromptOverride();
        }
        // The claude-code-guide agent has its own dedicated system prompt


        // Bypass the generic DEFAULT_AGENT_PROMPT path when routing to it.
        if (request.subagentType() != null
                && request.subagentType().equals(ClaudeCodeGuideAgentPrompt.AGENT_TYPE)) {
            StringBuilder guide = new StringBuilder(ClaudeCodeGuideAgentPrompt.build(
                usingThirdPartyServices, buildClaudeCodeGuideContext(cwd)));
            // --unrestricted: same inheritance as the generic path below — the guide agent's
            // dedicated body is its persona, so the contract goes directly behind it.
            if (unrestricted) {
                guide.append("\n\n").append(UnrestrictedPrompt.contractBlock());
            }
            // getSystemPrompt supplies the guide-specific body; runAgent then
            // applies the same notes/environment enhancement as every other
            // sub-agent. Returning early here used to drop that shared tail.
            guide.append("\n\n").append(EnvInfoSection.agentNotes());
            guide.append("\n\n").append(EnvInfoSection.computeEnvInfo(
                resolvedSubAgentModel(request.model()), cwd, isGitRepository(cwd), List.of()));
            if (includeGitInstructions()) {
                String gitStatus = QuerySessionEnvironment.initialGitStatusSnapshot(cwd);
                if (StringUtils.isNotBlank(gitStatus)) {
                    guide.append("\n\ngitStatus: ").append(gitStatus);
                }
            }
            return guide.toString();
        }


        // Looks across built-in AND custom (~/.claude/agents, <cwd>/.claude/agents)
        // definitions — previously only checked built-ins, so a custom agent's
        // hand-authored system prompt was silently discarded and every custom
        // agent ran with the generic DEFAULT_AGENT_PROMPT instead of what the
        // user actually wrote in their .md file.
        String effectiveType = StringUtils.defaultIfBlank(
            request.subagentType(), "general-purpose");
        BuiltInAgentDefinitions.AgentDefinition resolved =
            AgentDefinitionLoader.getAll(cwd).stream()
                .filter(a -> a.agentType().equals(effectiveType))
                .findFirst()
                .orElse(null);

        StringBuilder sb = new StringBuilder();
        boolean authoredPrompt = resolved != null
            && resolved.systemPrompt() != null && !StringUtils.isBlank(resolved.systemPrompt());
        if (authoredPrompt) {
            // Custom/user/project agent: the authored body IS the whole prompt.
            sb.append(resolved.systemPrompt());
        } else {
            // Built-in with no authored prompt (general-purpose/Explore/Plan/ statusline-setup), or
            // an unresolvable subagent_type — unchanged synthesis.
            if (resolved != null) {
                sb.append("You are the \"").append(resolved.agentType()).append("\" agent.\n\n");
                sb.append(resolved.whenToUse()).append("\n\n");
            }
            sb.append(SystemPromptConstants.DEFAULT_AGENT_PROMPT);
        }

        // --unrestricted: append the delivery contract directly behind the persona, matching the
        // main loop's placement in SystemPromptService. Sub-agent prompts are synthesized here
        // rather than assembled by SystemPromptService, so this is the only injection point.
        if (unrestricted) {
            sb.append("\n\n").append(UnrestrictedPrompt.contractBlock());
        }

        if (resolved != null && resolved.memory() != null && !StringUtils.isBlank(resolved.memory())) {
            Path memoryDir = AgentMemoryPrompt.resolveDirectory(
                resolved.agentType(), resolved.memory(), Path.of(cwd), claudeHomeOverride);
            String memoryPrompt = AgentMemoryPrompt.build(memoryDir, resolved.memory());
            if (!StringUtils.isBlank(memoryPrompt)) sb.append("\n\n").append(memoryPrompt);
        }

        if (!authoredPrompt && !request.tools().isEmpty()) {
            sb.append("\n\nAvailable tools: ");
            sb.append(String.join(", ", request.tools()));
        }


        if (request.teammate()) {
            sb.append("\n\n").append(TEAMMATE_SYSTEM_ADDENDUM);
        }


        // full environment context (cwd, repository status, platform/shell,
        // model and cutoff). This is distinct from the main loop's compact
        // env_info_simple section.
        sb.append("\n\n").append(EnvInfoSection.agentNotes());
        sb.append("\n\n").append(EnvInfoSection.computeEnvInfo(
            resolvedSubAgentModel(request.model()),
            cwd,
            isGitRepository(cwd),
            List.of()));

        if (!isGitOmittingAgent(request.subagentType()) && includeGitInstructions()) {
            String gitStatus = QuerySessionEnvironment.initialGitStatusSnapshot(cwd);
            if (StringUtils.isNotBlank(gitStatus)) {
                sb.append("\n\ngitStatus: ").append(gitStatus);
            }
        }

        return sb.toString();
    }

    private ClaudeCodeGuideAgentPrompt.Context buildClaudeCodeGuideContext(String cwd) {
        List<Skill> skills = safeSkills();
        List<ClaudeCodeGuideAgentPrompt.Command> customSkills = guideCommands(skills);
        List<ClaudeCodeGuideAgentPrompt.Command> pluginSkills = skills.stream()
            .filter(skill -> skill.source() == Skill.SkillSource.PLUGIN)
            .filter(skill -> !StringUtils.isBlank(skill.name()))
            .map(skill -> new ClaudeCodeGuideAgentPrompt.Command(
                skill.name(), Objects.toString(skill.description(), "")))
            .toList();
        List<ClaudeCodeGuideAgentPrompt.Agent> customAgents = AgentDefinitionLoader.getAll(cwd)
            .stream()
            .filter(agent -> agent.source() != AgentSource.BUILT_IN)
            .map(agent -> new ClaudeCodeGuideAgentPrompt.Agent(
                agent.agentType(), Objects.toString(agent.whenToUse(), "")))
            .toList();
        return new ClaudeCodeGuideAgentPrompt.Context(
            customSkills, customAgents, pluginSkills, safeEffectiveSettings());
    }

    private static List<ClaudeCodeGuideAgentPrompt.Command> guideCommands(List<Skill> skills) {
        List<ClaudeCodeGuideAgentPrompt.Command> commands = new ArrayList<>();
        for (Skill skill : skills) {
            if (StringUtils.isBlank(skill.name())) continue;
            String description = Objects.toString(skill.description(), "");
            if (Strings.CS.equals("deep-research", skill.name())) {
                description = "Deep research harness — fan-out web searches, fetch sources, "
                    + "adversarially verify claims, synthesize a cited report.";
            } else if (Strings.CS.equals("loop", skill.name())) {
                description = "Run a prompt or slash command on a recurring interval "
                    + "(e.g. /loop 5m /foo, defaults to 10m)";
            }
            commands.add(new ClaudeCodeGuideAgentPrompt.Command(skill.name(), description));
            switch (skill.name()) {
                case "verify" -> commands.add(new ClaudeCodeGuideAgentPrompt.Command(
                    "debug", "Enable debug logging for this session and help diagnose issues"));
                case "simplify" -> commands.add(new ClaudeCodeGuideAgentPrompt.Command(
                    "batch", "Research and plan a large-scale change, then execute it in parallel "
                        + "across 5–30 isolated worktree agents that each open a PR."));
                case "run" -> commands.add(new ClaudeCodeGuideAgentPrompt.Command(
                    "run-skill-generator", "Author or improve the run-<unit> skill — a per-project "
                        + "skill that tells agents how to build, launch, and drive this project's app. "
                        + "Use when the user asks to set up the project, get it running, write run "
                        + "instructions, or verify build/run steps work from a clean environment."));
                case "init" -> commands.add(new ClaudeCodeGuideAgentPrompt.Command(
                    "statusline", "Set up Claude Code's status line UI"));
                case "security-review" -> {
                    commands.add(new ClaudeCodeGuideAgentPrompt.Command(
                        "insights", "Generate a report analyzing your Claude Code sessions"));
                    commands.add(new ClaudeCodeGuideAgentPrompt.Command(
                        "team-onboarding", "Help teammates ramp on Claude Code with a guide from your usage"));
                }
                default -> { }
            }
        }
        return List.copyOf(commands);
    }

    private List<Skill> safeSkills() {
        try {
            List<Skill> skills = skillSupplier != null ? skillSupplier.get() : List.of();
            return skills == null ? List.of() : skills;
        } catch (RuntimeException _) {
            return List.of();
        }
    }

    private JsonNode safeEffectiveSettings() {
        try {
            JsonNode settings = effectiveSettingsSupplier != null
                ? effectiveSettingsSupplier.get() : null;
            return settings == null ? null : settings.deepCopy();
        } catch (RuntimeException _) {
            return null;
        }
    }

    private static boolean isGitOmittingAgent(String subagentType) {
        return Strings.CS.equals("Explore", subagentType) || Strings.CS.equals("Plan", subagentType);
    }

    private boolean includeGitInstructions() {
        try {
            return !Boolean.FALSE.equals(includeGitInstructionsSupplier.get());
        } catch (RuntimeException _) {
            return true;
        }
    }

    private static boolean isGitRepository(String cwd) {
        if (StringUtils.isBlank(cwd)) return false;
        try {
            Process process = new ProcessBuilder("git", "rev-parse", "--is-inside-work-tree")
                .directory(Path.of(cwd).toFile())
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
            boolean finished = process.waitFor(2, TimeUnit.SECONDS);
            return finished && process.exitValue() == 0;
        } catch (Exception _) {
            return false;
        }
    }

    static String resolvedSubAgentModel(String requested) {
        if (StringUtils.isBlank(requested)) return "claude-sonnet-4-20250514";
        return switch (requested.toLowerCase(Locale.ROOT)) {
            case "haiku", "sonnet", "opus", "fable" -> ModelCatalog.resolve(requested);
            default -> requested;
        };
    }


    private static final String TEAMMATE_SYSTEM_ADDENDUM =
        """
            You are running as an in-process teammate within a larger agent team.
            - The team leader (the main coordinator) will send you tasks. Work on one task at a time.
            - Use the SendMessage tool to communicate with the leader or other teammates by name.
            - When you finish a task and have no further tool calls, report your result briefly and stop; \
            the leader will send the next task.
            - If the leader asks you to shut down, call the shutdown approval tool (or explain why you should continue).
            - Never prompt the user directly; route all questions through the leader.""";

}
