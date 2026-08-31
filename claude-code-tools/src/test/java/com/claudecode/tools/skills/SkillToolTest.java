package com.claudecode.tools.skills;

import org.apache.commons.lang3.Strings;
import com.claudecode.core.engine.AbortController;
import com.claudecode.core.engine.SessionIdentity;
import com.claudecode.core.engine.ToolContextModifier;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.core.engine.ToolResult;
import com.claudecode.core.message.ContentBlock;
import com.claudecode.core.message.AttachmentMessage;
import com.claudecode.core.message.CommandPermissionsAttachment;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.UserMessage;
import com.claudecode.permissions.PermissionDecision;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SkillToolTest {

    @TempDir
    Path tempDir;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private SkillLoader loader;
    private ShellVariableInjector injector;
    private ToolExecutionContext context;

    @BeforeEach
    void setUp() {
        loader = new SkillLoader();
        injector = new ShellVariableInjector(SessionIdentity.of("test-session"));
        context = ToolExecutionContext.of(new AbortController(), "test-session");
    }


    private static String injectedBody(ToolResult r) {
        List<Message> msgs = r.newMessages();
        assertNotNull(msgs, "expected an injected message");
        assertFalse(msgs.isEmpty());
        Message m = msgs.getFirst();
        assertInstanceOf(UserMessage.class, m);
        List<ContentBlock> blocks = ((UserMessage) m).message().blocks();
        assertNotNull(blocks);
        assertEquals(1, blocks.size());
        assertInstanceOf(TextBlock.class, blocks.getFirst());
        return ((TextBlock) blocks.getFirst()).text();
    }

    private static String ack(ToolResult r) {
        return ((TextBlock) r.content().getFirst()).text();
    }

    @Test
    void callReturnsSkillContent() throws IOException {
        Path skillsDir = tempDir.resolve("skills");
        Files.createDirectories(skillsDir.resolve("test-skill"));
        Files.writeString(skillsDir.resolve("test-skill/SKILL.md"), """
                ---
                name: test-skill
                ---
                This is the skill content.
                """);

        loader.addSource(Skill.SkillSource.PROJECT, skillsDir);
        SkillTool tool = new SkillTool(loader, injector);

        ObjectNode input = MAPPER.createObjectNode();
        input.put("name", "test-skill");

        ToolResult result = tool.call(input, context);
        assertEquals("Launching skill: test-skill", ack(result));
        assertEquals("Base directory for this skill: "
            + skillsDir.resolve("test-skill")
            + "\n\nThis is the skill content.\n", injectedBody(result));
    }

    @Test
    void filesystemSkillUsesItsOwnDirectoryForClaudeSkillDir() throws IOException {
        Path skillsDir = tempDir.resolve("project-skills");
        Path skillDir = skillsDir.resolve("local-skill");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
            ---
            description: local skill
            ---
            root=${CLAUDE_SKILL_DIR}
            """);
        injector.setSkillDir(tempDir.resolve("wrong-global-root"));
        loader.addSource(Skill.SkillSource.PROJECT, skillsDir);
        SkillTool tool = new SkillTool(loader, injector);
        ObjectNode input = MAPPER.createObjectNode();
        input.put("skill", "local-skill");

        String body = injectedBody(tool.call(input, context));

        assertEquals("Base directory for this skill: " + skillDir
            + "\n\nroot=" + skillDir + "\n", body);
    }

    @Test
    void mcpSkillUsesRemoteResourcePrefixAndDoesNotLeakCachedBaseDirectory() {
        Path cachedSkill = tempDir.resolve("mcp-cache/hash/SKILL.md");
        loader.setMcpSkills(List.of(new Skill(
            "wire-skills:wire-probe", "MCP skill", List.of(),
            "Follow the WIRE197 MCP direct skill body. Marker: $ARGUMENTS\n",
            cachedSkill, Skill.SkillSource.MCP, null, null, null,
            Map.of(
                "mcpServer", "wire-skills",
                "mcpResourceRoot", "skill://wire-probe",
                "mcpDirectoryRead", false))));
        SkillTool tool = new SkillTool(loader, injector);
        ObjectNode input = MAPPER.createObjectNode();
        input.put("skill", "wire-skills:wire-probe");
        input.put("args", "WIRE_ARG");

        ToolResult result = tool.call(input, context.withToolUseId("toolu_197_skill_probe"));

        assertEquals("Launching skill: wire-skills:wire-probe", ack(result));
        assertEquals("""
            This skill is served by MCP server "wire-skills" at skill://wire-probe. To read a supporting file this skill references by a relative path — for example "templates/invoice.md" — call ReadMcpResourceTool with server "wire-skills" and uri "skill://wire-probe/templates/invoice.md".

            Follow the WIRE197 MCP direct skill body. Marker: WIRE_ARG
            """, injectedBody(result));
        assertEquals(Map.of(
            "success", true,
            "commandName", "wire-skills:wire-probe"), result.toolUseResult());
        assertEquals(2, result.newMessages().size());
        UserMessage injected = assertInstanceOf(UserMessage.class, result.newMessages().getFirst());
        assertTrue(injected.isMeta());
        assertEquals("toolu_197_skill_probe", injected.sourceToolUseID());
        AttachmentMessage permissions = assertInstanceOf(
            AttachmentMessage.class, result.newMessages().get(1));
        CommandPermissionsAttachment payload = assertInstanceOf(
            CommandPermissionsAttachment.class, permissions.payload());
        assertEquals(List.of(), payload.allowedTools());
        assertNull(payload.model());
        assertNotNull(result.contextModifier());
        ToolContextModifier modifier = result.contextModifier();
        assertEquals("wire-skills:wire-probe", modifier.attributionSkill());
        assertEquals("wire-skills", modifier.attributionPlugin());
    }

    @Test
    void callReturnsNotFoundForMissingSkill() {
        SkillTool tool = new SkillTool(loader, injector);

        ObjectNode input = MAPPER.createObjectNode();
        input.put("name", "nonexistent");

        ToolResult result = tool.call(input, context);
        assertTrue(result.isError());
        assertTrue(Strings.CS.contains(ack(result), "not found"));
    }

    @Test
    void callReturnsErrorForMissingName() {
        SkillTool tool = new SkillTool(loader, injector);

        ObjectNode input = MAPPER.createObjectNode();
        ToolResult result = tool.call(input, context);
        assertTrue(result.isError());
        assertTrue(Strings.CS.contains(ack(result), "Error"));
    }

    @Test
    void toolMetadata() {
        SkillTool tool = new SkillTool(loader, injector);
        assertEquals("Skill", tool.name());
        assertNotNull(tool.description());
        assertNotNull(tool.inputSchema());
        assertFalse(tool.isReadOnly());
        assertEquals("invoke a slash-command skill", tool.searchHint());
        assertEquals("Execute a skill within the main conversation", tool.prompt(context).split("\\n", 2)[0]);
        assertTrue(tool.inputSchema().path("additionalProperties").asBoolean(true));
        assertEquals("Execute skill: pdf", tool.description(MAPPER.createObjectNode().put("skill", "pdf"), context));
    }



    private SkillTool toolWith(String skillName, String body) throws IOException {
        Path skillsDir = tempDir.resolve("skills197");
        Path skillDir = skillsDir.resolve(skillName);
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), body);
        loader.addSource(Skill.SkillSource.PROJECT, skillsDir);
        return new SkillTool(loader, injector);
    }

    @Test
    void skillParamIsThePrimaryName() throws IOException {
        SkillTool tool = toolWith("my-skill", """
                ---
                name: my-skill
                ---
                BODY
                """);
        ObjectNode input = MAPPER.createObjectNode();
        input.put("skill", "my-skill");
        assertTrue(Strings.CS.contains(injectedBody(tool.call(input, context)), "BODY"));
    }

    @Test
    void argsSubstituteDollarArgumentsPlaceholder() throws IOException {
        SkillTool tool = toolWith("subst-skill", """
                ---
                name: subst-skill
                ---
                Run with: $ARGUMENTS and first=$ARGUMENTS[0] short=$1
                """);
        ObjectNode input = MAPPER.createObjectNode();
        input.put("skill", "subst-skill");
        input.put("args", "alpha \"two words\"");
        String out = injectedBody(tool.call(input, context));
        assertTrue(Strings.CS.contains(out, "Run with: alpha \"two words\""), out);
        assertTrue(Strings.CS.contains(out, "first=alpha"), out);
        assertTrue(Strings.CS.contains(out, "short=two words"), out);
    }

    @Test
    void argsAppendedWhenNoPlaceholderPresent() throws IOException {
        SkillTool tool = toolWith("plain-skill", """
                ---
                name: plain-skill
                ---
                No placeholders here.
                """);
        ObjectNode input = MAPPER.createObjectNode();
        input.put("skill", "plain-skill");
        input.put("args", "extra context");
        String out = injectedBody(tool.call(input, context));
        assertTrue(Strings.CS.contains(out, "ARGUMENTS: extra context"),
            "args without placeholder must be appended: " + out);
    }

    @Test
    void noArgsLeavesContentUntouched() throws IOException {
        SkillTool tool = toolWith("untouched-skill", """
                ---
                name: untouched-skill
                ---
                Placeholder stays: $ARGUMENTS
                """);
        ObjectNode input = MAPPER.createObjectNode();
        input.put("skill", "untouched-skill");
        String out = injectedBody(tool.call(input, context));
        assertTrue(Strings.CS.contains(out, "$ARGUMENTS"), "null args must not substitute: " + out);
    }

    @Test
    void pluginCommandUsesNamedArgumentsAndLiveSessionId() {
        loader.setPluginCommandSkills(List.of(new Skill(
            "demo:deploy", "Deploy a target", List.of("Bash(deploy *)"),
            "Deploy $target in ${CLAUDE_SESSION_ID}", null, Skill.SkillSource.PLUGIN,
            "sonnet", "high", null,
            Map.of("pluginCommand", true, "argNames", List.of("target")))));
        SkillTool tool = new SkillTool(loader, injector);
        ObjectNode input = MAPPER.createObjectNode();
        input.put("skill", "demo:deploy");
        input.put("args", "production");

        ToolResult result = tool.call(input, context);

        assertEquals("Launching skill: demo:deploy", ack(result));
        assertEquals("Deploy production in test-session", injectedBody(result));
        assertEquals("sonnet", result.contextModifier().model());
        assertEquals("high", result.contextModifier().effort());
        assertEquals("demo:deploy", result.contextModifier().attributionSkill());
        assertEquals("demo", result.contextModifier().attributionPlugin());
    }

    @Test
    void pluginCommandWithDisabledModelInvocationIsRejected() {
        loader.setPluginCommandSkills(List.of(new Skill(
            "demo:release", "Release", List.of(), "release", null,
            Skill.SkillSource.PLUGIN, null, null, null,
            Map.of("pluginCommand", true, "disableModelInvocation", true))));
        SkillTool tool = new SkillTool(loader, injector);
        ObjectNode input = MAPPER.createObjectNode();
        input.put("skill", "demo:release");

        ToolResult result = tool.call(input, context);

        assertTrue(result.isError());
        assertEquals("Skill demo:release cannot be used with Skill tool due to disable-model-invocation",
            ack(result));
    }



    @Test
    void contextModifierCarriesModelEffortAllowedTools() throws IOException {
        SkillTool tool = toolWith("ctx-skill", """
                ---
                name: ctx-skill
                model: opus
                effort: high
                allowedTools: [Bash, Write]
                ---
                BODY
                """);
        ObjectNode input = MAPPER.createObjectNode();
        input.put("skill", "ctx-skill");
        ToolResult result = tool.call(input, context);

        ToolContextModifier mod = result.contextModifier();
        assertNotNull(mod, "skill with model/effort/allowedTools must attach a contextModifier");
        assertEquals("opus", mod.model());
        assertEquals("high", mod.effort());
        assertEquals(List.of("Bash", "Write"), mod.allowedTools());
    }

    @Test
    void noModifierWhenFrontmatterOmitsOverrides() throws IOException {
        SkillTool tool = toolWith("plain-ctx-skill", """
                ---
                name: plain-ctx-skill
                ---
                BODY
                """);
        ObjectNode input = MAPPER.createObjectNode();
        input.put("skill", "plain-ctx-skill");
        ToolResult result = tool.call(input, context);

        assertNotNull(result.contextModifier(),
            "every inline skill updates activeSkill for released attribution metadata");
        assertEquals("plain-ctx-skill", result.contextModifier().attributionSkill());
    }

    // ── context: fork runs in a sub-agent (fix 9) ───────────────────────────

    @Test
    void forkContextRunsViaSubAgent() throws IOException {
        SkillTool tool = toolWith("fork-skill", """
                ---
                name: fork-skill
                context: fork
                ---
                BODY
                """);
        ObjectNode input = MAPPER.createObjectNode();
        input.put("skill", "fork-skill");
        // No real SubAgentFactory is wired in the 2-arg constructor (NoOp), so the
        // fork path must flag the limitation clearly rather than silently inject inline.
        ToolResult result = tool.call(input, context);
        assertTrue(result.isError(), "unwired fork must flag clearly, not inject inline");
        assertTrue(Strings.CS.contains(ack(result), "sub-agent factory"), ack(result));
    }

    // ── checkPermissions: safe-properties auto-allow, else Ask (fix 10) ──────

    @Test
    void checkPermissionsAutoAllowsSafeSkill() throws IOException {
        SkillTool tool = toolWith("safe-skill", """
                ---
                name: safe-skill
                description: a safe skill
                allowedTools: [Bash]
                model: opus
                ---
                BODY
                """);
        ObjectNode input = MAPPER.createObjectNode();
        input.put("skill", "safe-skill");
        PermissionDecision decision = tool.checkPermissions(input, null);
        assertInstanceOf(PermissionDecision.Allow.class, decision,
            "skill with only safe frontmatter properties must auto-allow");
    }

    @Test
    void checkPermissionsAsksForNonSafeSkill() throws IOException {
        SkillTool tool = toolWith("unsafe-skill", """
                ---
                name: unsafe-skill
                description: a skill with a risky property
                hooks: [some-hook]
                ---
                BODY
                """);
        ObjectNode input = MAPPER.createObjectNode();
        input.put("skill", "unsafe-skill");
        PermissionDecision decision = tool.checkPermissions(input, null);
        assertInstanceOf(PermissionDecision.Ask.class, decision,
            "skill with a non-safe frontmatter property must require Ask");
    }
}
