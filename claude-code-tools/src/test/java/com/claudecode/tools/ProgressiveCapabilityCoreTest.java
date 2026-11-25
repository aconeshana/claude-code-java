package com.claudecode.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.core.attachment.AttachmentContext;
import com.claudecode.core.attachment.DeferredToolsDeltaAttachmentProvider;
import com.claudecode.core.attachment.SkillListingAttachmentProvider;
import com.claudecode.core.engine.AbortController;
import com.claudecode.core.engine.SessionCapabilityFilter;
import com.claudecode.core.engine.SessionIdentity;
import com.claudecode.core.engine.StreamingClient;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.core.engine.ToolResult;
import com.claudecode.core.message.AttachmentMessage;
import com.claudecode.core.message.AttachmentRenderer;
import com.claudecode.core.message.SkillListingAttachment;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.mcp.McpClientManager;
import com.claudecode.mcp.McpToolInfo;
import com.claudecode.tools.files.GlobTool;
import com.claudecode.tools.files.GrepTool;
import com.claudecode.tools.mcp.MCPTool;
import com.claudecode.tools.skills.ShellVariableInjector;
import com.claudecode.tools.skills.Skill;
import com.claudecode.tools.skills.SkillLoader;
import com.claudecode.tools.skills.SkillTool;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProgressiveCapabilityCoreTest {

    private static final class MutableFilter implements SessionCapabilityFilter {
        final Set<String> disabledBuiltIns = new HashSet<>();
        final Set<String> disabledMcpServers = new HashSet<>();
        final Set<String> disabledMcpTools = new HashSet<>();
        final Set<String> disabledSkills = new HashSet<>();
        final Map<String, Set<String>> frozen = new ConcurrentHashMap<>();

        @Override
        public boolean isBuiltInToolEnabled(String sessionId, String toolName) {
            return !disabledBuiltIns.contains(toolName);
        }

        @Override
        public boolean isMcpServerEnabled(String sessionId, String serverName) {
            return !disabledMcpServers.contains(serverName);
        }

        @Override
        public boolean isMcpToolEnabled(String sessionId, String fullToolName) {
            return !disabledMcpTools.contains(fullToolName);
        }

        @Override
        public boolean isSkillEnabled(String sessionId, String skillName) {
            return !disabledSkills.contains(skillName);
        }

        @Override
        public Set<String> frozenInitialEagerTools(String sessionId, Supplier<Set<String>> computer) {
            return frozen.computeIfAbsent(sessionId, _ -> Set.copyOf(computer.get()));
        }
    }

    @Test
    void frozenWireToolsNeverMutateWhenBuiltInOrMcpCapabilitiesToggle(@TempDir Path tempDir) {
        MutableFilter filter = new MutableFilter();
        filter.disabledBuiltIns.add("Grep");

        ToolRegistry registry = new ToolRegistry();
        registry.register(new GlobTool());
        registry.register(new GrepTool());
        registry.register(new ToolSearchTool(registry, List::of));
        registry.register(new ToolInvokeTool(registry));
        registry.setCapabilityFilter(filter);

        ToolExecutionContext ctx = ToolExecutionContext.builder(new AbortController(), "sess-1")
            .workingDirectory(tempDir.toString())
            .currentModel("glm-5.3")
            .build();

        List<StreamingClient.StreamRequest.ToolDef> turn1Defs =
            registry.getToolDefinitions(Set.of(), ctx);
        List<String> turn1Names = turn1Defs.stream()
            .map(StreamingClient.StreamRequest.ToolDef::name)
            .toList();
        assertEquals(List.of("Glob", "ToolInvoke", "ToolSearch"), turn1Names);

        // Turn 2: Dynamically enable Grep, disable Glob, and register an MCP tool.
        filter.disabledBuiltIns.remove("Grep");
        filter.disabledBuiltIns.add("Glob");
        ObjectNode echoSchema = JsonUtils.getMapper().createObjectNode();
        echoSchema.put("type", "object");
        echoSchema.putObject("properties").putObject("msg").put("type", "string");
        McpToolInfo mcpInfo = new McpToolInfo("demo", "echo", "Echoes message", echoSchema,
            JsonUtils.getMapper().createObjectNode());
        registry.register(new MCPTool(mcpInfo, new McpClientManager()));

        // Wire tools[] MUST remain 100% identical to Turn 1.
        List<StreamingClient.StreamRequest.ToolDef> turn2Defs =
            registry.getToolDefinitions(Set.of("Grep", "mcp__demo__echo"), ctx);
        assertEquals(turn1Defs, turn2Defs);

        // Live session tools reflect the exact diff (+Grep, +mcp__demo__echo, -Glob).
        List<String> liveNames = registry.getLiveSessionToolNames("sess-1");
        assertTrue(liveNames.contains("Grep"));
        assertTrue(liveNames.contains("mcp__demo__echo"));
        assertFalse(liveNames.contains("Glob"));

        var deltaProvider = new DeferredToolsDeltaAttachmentProvider();
        var deltas = deltaProvider.collect(AttachmentContext.builder(tempDir.toString())
            .toolNames(liveNames)
            .previousTurnTools(turn1Names)
            .build());
        assertEquals(1, deltas.size());
        String rendered = AttachmentRenderer.render(deltas.getFirst()).getFirst().message().text();
        assertTrue(Strings.CS.contains(rendered, "mcp__demo__echo"));
        assertTrue(Strings.CS.contains(rendered, "Grep"));
        assertTrue(Strings.CS.contains(rendered, "The following deferred tools are no longer available"));
        assertTrue(Strings.CS.contains(rendered, "\nGlob"));
    }

    @Test
    void toolSearchReturnsTextSchemaAndToolInvokeUnwrapsOrBlocksDisabled(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("hello.txt"), "hi");
        MutableFilter filter = new MutableFilter();
        filter.disabledBuiltIns.add("Glob");

        ToolRegistry registry = new ToolRegistry();
        registry.register(new GlobTool());
        registry.register(new ToolSearchTool(registry, List::of));
        registry.register(new ToolInvokeTool(registry));
        registry.setCapabilityFilter(filter);

        ToolExecutionContext ctx = ToolExecutionContext.builder(new AbortController(), "sess-2")
            .workingDirectory(tempDir.toString())
            .currentModel("gemini-2.5-pro")
            .build();

        assertEquals(List.of("ToolInvoke", "ToolSearch"),
            registry.getToolDefinitions(Set.of(), ctx).stream()
                .map(StreamingClient.StreamRequest.ToolDef::name).toList());

        ObjectNode invokeGlob = JsonUtils.getMapper().createObjectNode();
        invokeGlob.put("tool_name", "Glob");
        invokeGlob.putObject("arguments").put("pattern", "*.txt");
        ToolResult rejected = registry.execute("ToolInvoke", invokeGlob, ctx);
        assertTrue(rejected.isError());
        assertTrue(rejected.content().getFirst() instanceof TextBlock tb
            && Strings.CS.contains(tb.text(), "currently disabled in this session"));

        filter.disabledBuiltIns.remove("Glob");
        ObjectNode searchQuery = JsonUtils.getMapper().createObjectNode().put("query", "select:Glob");
        ToolResult searchRes = registry.execute("ToolSearch", searchQuery, ctx);
        assertFalse(searchRes.isError());
        assertEquals(1, searchRes.content().size());
        assertTrue(searchRes.content().getFirst() instanceof TextBlock tb
            && Strings.CS.contains(tb.text(), "<functions>")
            && Strings.CS.contains(tb.text(), "\"name\":\"Glob\""));

        ToolResult allowed = registry.execute("ToolInvoke", invokeGlob, ctx);
        assertFalse(allowed.isError());
        assertTrue(allowed.content().getFirst() instanceof TextBlock tb
            && Strings.CS.contains(tb.text(), "hello.txt"));
    }

    @Test
    void skillDeltaAndSkillToolRespectSessionDisablement(@TempDir Path tempDir) {
        MutableFilter filter = new MutableFilter();
        SkillLoader loader = new SkillLoader();
        loader.setSessionSkillFilter(filter::isSkillEnabled);
        Skill demoSkill = new Skill("deploy-helper", "Helps deploy services", List.of(),
            "Deploy body", null, Skill.SkillSource.USER, null, null, null, Map.of());
        loader.setMcpSkills(List.of(demoSkill));

        SkillTool skillTool = new SkillTool(loader,
            new ShellVariableInjector(SessionIdentity.of("sess-3")));
        ToolExecutionContext ctx = ToolExecutionContext.builder(new AbortController(), "sess-3")
            .workingDirectory(tempDir.toString())
            .build();

        ObjectNode callInput = JsonUtils.getMapper().createObjectNode().put("skill", "deploy-helper");
        ToolResult ok = skillTool.call(callInput, ctx);
        assertFalse(ok.isError());

        filter.disabledSkills.add("deploy-helper");
        ToolResult blocked = skillTool.call(callInput, ctx);
        assertTrue(blocked.isError());
        assertTrue(blocked.content().getFirst() instanceof TextBlock tb
            && Strings.CS.contains(tb.text(), "disabled in this session"));

        var provider = new SkillListingAttachmentProvider();
        AttachmentMessage initialMsg = new AttachmentMessage("m1",
            new SkillListingAttachment(
                "- deploy-helper: Helps deploy services", 1, true, List.of("deploy-helper")));
        var deltas = provider.collect(AttachmentContext.builder(tempDir.toString())
            .messages(List.of(initialMsg))
            .toolNames(List.of("Skill"))
            .skills(List.of())
            .build());
        assertEquals(1, deltas.size());
        String rendered = AttachmentRenderer.render(deltas.getFirst()).getFirst().message().text();
        assertTrue(Strings.CS.contains(rendered, "no longer available for use with the Skill tool"));
        assertTrue(Strings.CS.contains(rendered, "- deploy-helper"));
    }
}
