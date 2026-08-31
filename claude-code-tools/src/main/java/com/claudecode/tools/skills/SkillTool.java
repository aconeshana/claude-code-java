package com.claudecode.tools.skills;


import com.claudecode.core.engine.ToolContextModifier;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.core.engine.ToolResult;
import com.claudecode.core.message.AttachmentMessage;
import com.claudecode.core.message.CommandPermissionsAttachment;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.MessageOrigin;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.UserMessage;
import com.claudecode.core.model.ModelSkillVisibility;
import com.claudecode.permissions.PermissionDecision;
import com.claudecode.permissions.ToolPermissionContext;
import com.claudecode.tools.AnnotatedTool;
import com.claudecode.tools.BuiltInTool;
import com.claudecode.tools.ToolTexts;
import com.claudecode.tools.agent.NoOpSubAgentFactory;
import com.claudecode.tools.agent.SubAgentFactory;
import com.claudecode.tools.agent.SubAgentRequest;
import com.claudecode.tools.agent.SubAgentResult;
import com.claudecode.core.prompt.ArgumentSubstitutor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.File;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

/**
 * Tool that returns the content of a specific skill by name.
 */
@BuiltInTool(name = "Skill")
public class SkillTool extends AnnotatedTool<JsonNode, ToolResult> {


    @Override
    public String searchHint() {
        return "invoke a slash-command skill";
    }

    private final SkillLoader skillLoader;
    private final ShellVariableInjector variableInjector;
    /**
     * Sub-agent factory used to run {@code context: "fork"} skills in an isolated
     * sub-agent. Defaults to {@link NoOpSubAgentFactory} (the 2-arg constructor);
     * a real factory must be wired by the composition root for forked execution to
     * actually run — otherwise {@link #runForkedSkill} flags the limitation clearly.
     */
    private final SubAgentFactory subAgentFactory;

    public SkillTool(SkillLoader skillLoader, ShellVariableInjector variableInjector) {
        this(skillLoader, variableInjector, new NoOpSubAgentFactory());
    }

    public SkillTool(SkillLoader skillLoader, ShellVariableInjector variableInjector,
                     SubAgentFactory subAgentFactory) {
        this.skillLoader = skillLoader;
        this.variableInjector = variableInjector;
        this.subAgentFactory = subAgentFactory;
    }

    @Override
    public String description() {

        return ToolTexts.description("Skill");
    }


    @Override
    public String description(JsonNode input, ToolExecutionContext context) {
        String skill = input == null ? null
            : input.has("skill") ? input.path("skill").asText(null)
            : input.has("name") ? input.path("name").asText(null) : null;
        return StringUtils.isBlank(skill) ? description() : "Execute skill: " + skill;
    }



    @Override
    public JsonNode inputSchema() {

        // SkillTool's schema accepts extension fields injected by command
        // adapters, while the canonical model-facing fields remain skill/args.
        ObjectNode schema = createObjectSchema();
        ObjectNode props = (ObjectNode) schema.get("properties");
        ObjectNode skillProp = mapper().createObjectNode();
        skillProp.put("description",
            "The name of a skill from the available-skills list. Do not guess names.");
        skillProp.put("type", "string");
        props.set("skill", skillProp);
        ObjectNode argsProp = mapper().createObjectNode();
        argsProp.put("description", "Optional arguments for the skill");
        argsProp.put("type", "string");
        props.set("args", argsProp);
        schema.set("required", mapper().createArrayNode().add("skill"));
        return schema;
    }

    @Override
    public ToolResult call(JsonNode input, ToolExecutionContext context) {

        String skillName = input.has("skill") ? input.get("skill").asText(null)
            : input.has("name") ? input.get("name").asText(null) : null;
        if (StringUtils.isBlank(skillName)) {
            return ToolResult.error("Error: skill name is required");
        }
        String args = input.has("args") ? input.get("args").asText(null) : null;

        Skill match = findSkill(skillName.strip());
        if (match == null) {
            return ToolResult.error("Skill not found: " + skillName);
        }
        if (match.disableModelInvocation()) {
            return ToolResult.error("Skill " + skillName
                + " cannot be used with Skill tool due to disable-model-invocation");
        }
        if (!ModelSkillVisibility.isVisible(match.name(),
                match.source() == Skill.SkillSource.BUNDLED, context.currentModel())) {
            return ToolResult.error("Skill " + skillName
                + " is hidden from GPT models; the user can invoke /" + skillName
                + " explicitly when Claude API reference material is needed");
        }
        String content = match.content();
        boolean invocationHandlesArgs = false;
        if (match.source() == Skill.SkillSource.BUILTIN
                && Strings.CS.equals("security-review", match.name())) {
            content = SecurityReviewPromptRenderer.render(context, content);
            invocationHandlesArgs = true;
        } else if (match.source() == Skill.SkillSource.BUNDLED
                && BundledSkillPromptRenderer.handles(match.name())) {
            content = BundledSkillPromptRenderer.render(match.name(), content, args,
                context.workingDirectory() == null ? null : Path.of(context.workingDirectory()));
            invocationHandlesArgs = true;
        }
        if (match.source() == Skill.SkillSource.MCP) {
            content = mcpResourcePrefix(match) + content;
        }
        Path skillRoot = skillRoot(match);
        if (skillRoot != null) {
            String root = skillRoot.toString();
            if (File.separatorChar == '\\') root = root.replace('\\', '/');
            content = "Base directory for this skill: " + root + "\n\n" + content;
            content = content.replace("${CLAUDE_SKILL_DIR}", root);
        }
        if (variableInjector != null) {
            content = variableInjector.inject(content);
        }
// $ARGUMENTS / $ARGUMENTS[n] / $n expansion.


        // plugin commands. appendIfNoPlaceholder=true matches the prior behavior.
        boolean appendUnconsumedArgs = !Strings.CS.equals("code-review", match.name());
        String resolved = invocationHandlesArgs ? content : ArgumentSubstitutor.substitute(
            content, args, match.argumentNames(), appendUnconsumedArgs);
        // Preserve exactly what the model saw (base-dir header, environment

        // addInvokedSkill after getPromptForCommand resolves the body.
        InvokedSkillRegistry.global().record(null, match, resolved);


        // forked sub-agent (executeForkedSkill) and returns the sub-agent's result
        // text, rather than injecting inline. See runForkedSkill.
        if (Strings.CS.equals("fork", match.context())) {
            return runForkedSkill(match, resolved, context);
        }


        UserMessage injected = new UserMessage(
            UUID.randomUUID().toString(),
            MessageContent.ofBlocks(List.of(new TextBlock(resolved))),
            true, false, null, MessageOrigin.USER,
            null, Instant.now(), null, null, null, null, context.toolUseId());
        AttachmentMessage permissions = new AttachmentMessage(
            UUID.randomUUID().toString(),
            new CommandPermissionsAttachment(match.allowedTools(), match.model()));


        ToolContextModifier modifier = new ToolContextModifier(
            match.allowedTools(), match.model(), match.effort(),
            match.unqualifiedName(), attributionPlugin(match));
        Map<String, Object> toolUseResult = new LinkedHashMap<>();
        toolUseResult.put("success", true);
        toolUseResult.put("commandName", match.name());
        if (match.allowedTools() != null && !match.allowedTools().isEmpty()) {
            toolUseResult.put("allowedTools", match.allowedTools());
        }
        if (StringUtils.isNotBlank(match.model())) {
            toolUseResult.put("model", match.model());
        }
        ToolResult result = ToolResult.success("Launching skill: " + match.name())
            .withToolUseResult(toolUseResult)
            .withNewMessages(List.of(injected, permissions));
        if (!modifier.isEmpty()) {
            result = result.withContextModifier(modifier);
        }
        return result;
    }


    private static String attributionPlugin(Skill skill) {
        if (skill.source() == Skill.SkillSource.MCP && skill.frontmatter() != null) {
            Object server = skill.frontmatter().get("mcpServer");
            if (server instanceof String value && !StringUtils.isBlank(value)) return value;
        }
        if (skill.source() == Skill.SkillSource.PLUGIN) {
            int separator = skill.name().indexOf(':');
            if (separator > 0) return skill.name().substring(0, separator);
        }
        return null;
    }


    private static Path skillRoot(Skill skill) {
        if (skill.source() == Skill.SkillSource.MCP) return null;
        Path source = skill.sourceFile();
        if (source == null || source.getFileName() == null
                || !Strings.CI.equals("SKILL.md", source.getFileName().toString())) {
            return null;
        }
        Path parent = source.getParent();
        return parent == null ? null : parent.toAbsolutePath().normalize();
    }


    private static String mcpResourcePrefix(Skill skill) {
        Map<String, Object> metadata = skill.frontmatter();
        if (metadata == null) return "";
        Object serverValue = metadata.get("mcpServer");
        Object rootValue = metadata.get("mcpResourceRoot");
        if (!(serverValue instanceof String server) || StringUtils.isBlank(server)
                || !(rootValue instanceof String root) || StringUtils.isBlank(root)) {
            return "";
        }
        return "This skill is served by MCP server \"" + server + "\" at " + root
            + ". To read a supporting file this skill references by a relative path — "
            + "for example \"templates/invoice.md\" — call ReadMcpResourceTool with server \""
            + server + "\" and uri \"" + root + "/templates/invoice.md\".\n\n";
    }

    /**
 * Exact-name match first; a {@code plugin:skill} qualified name.
     */
    private Skill findSkill(String requested) {
        String bare = Strings.CS.contains(requested, ":")
            ? requested.substring(requested.lastIndexOf(':') + 1) : null;
        for (Skill skill : skillLoader.loadAll()) {
            if (requested.equals(skill.name())) return skill;
            if (bare != null && bare.equals(skill.name())
                    && skill.source() == Skill.SkillSource.PLUGIN) {
                return skill;
            }
        }
        return null;
    }


    private ToolResult runForkedSkill(Skill match, String prompt, ToolExecutionContext context) {
        if (subAgentFactory instanceof NoOpSubAgentFactory) {
            return ToolResult.error(
                "Forked skill '" + match.name() + "' requires a wired sub-agent factory, "
                + "which is not configured in this build.");
        }
        SubAgentRequest request = SubAgentRequest.builder()
            .prompt(prompt)
            .parentContext(context)
            .async(false)
            .fork(true)
            .model(match.model())
            .cwd(context.workingDirectory())
            .description(match.name())
            .build();
        try {
            SubAgentResult result = subAgentFactory.runSubAgent(request);
            String resultText = result.output();
            if (result.isError()) {
                return ToolResult.error(
                    "Forked skill '" + match.name() + "' failed: " + resultText);
            }
            return ToolResult.success(
                "Skill \"" + match.name() + "\" completed (forked execution).\n\nResult:\n"
                + resultText)
                .withToolUseResult(Map.of("status", "forked"));
        } catch (Exception e) {
            return ToolResult.error(
                "Forked skill '" + match.name() + "' failed: " + e.getMessage());
        }
    }


    @Override
    public PermissionDecision checkPermissions(JsonNode input, ToolPermissionContext permCtx) {
        String skillName = input.has("skill") ? input.get("skill").asText(null)
            : input.has("name") ? input.get("name").asText(null) : null;
        if (StringUtils.isBlank(skillName)) {
            return PermissionDecision.ask();
        }
        Skill match = findSkill(skillName.strip());
        if (match == null) {
            return PermissionDecision.ask();
        }
        if (skillHasOnlySafeProperties(match)) {
            return PermissionDecision.allow();
        }
        return PermissionDecision.ask();
    }


    private static final Set<String> SAFE_SKILL_PROPERTIES = Set.of(
        "type", "progressMessage", "contentLength", "argNames", "model", "effort",
        "source", "pluginInfo", "disableNonInteractive", "skillRoot", "context",
        "agent", "getPromptForCommand", "frontmatterKeys", "name", "description",
        "hasUserSpecifiedDescription", "isEnabled", "isHidden", "aliases", "isMcp",
        "argumentHint", "whenToUse", "paths", "version", "disableModelInvocation",
        "userInvocable", "loadedFrom", "immediate", "userFacingName", "allowedTools",
        "mcpServer", "mcpResourceRoot", "mcpDirectoryRead"
    );


    private static boolean skillHasOnlySafeProperties(Skill skill) {
        Map<String, Object> fm = skill.frontmatter();
        if (fm == null) {
            return true;
        }
        for (Map.Entry<String, Object> e : fm.entrySet()) {
            if (SAFE_SKILL_PROPERTIES.contains(e.getKey())) {
                continue;
            }
            Object v = e.getValue();
            switch (v) {
                case null -> {
                    continue;
                }
                case Collection<?> c when c.isEmpty() -> {
                    continue;
                }
                case Map<?, ?> m when m.isEmpty() -> {
                    continue;
                }
                case String s when StringUtils.isBlank(s) -> {
                    continue;
                }
                default -> {
                }
            }
            return false;
        }
        return true;
    }


    @Override
    public Object toAutoClassifierInput(JsonNode input) {
        if (input == null) return "";
        return input.has("skill") ? input.path("skill").asText("") : input.path("name").asText("");
    }
}
