package com.claudecode.tools.agent;

import org.apache.commons.lang3.Strings;
import com.claudecode.core.engine.StreamingClient;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Collections;
import java.util.Iterator;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import com.claudecode.tools.ToolRegistry;

class AgentToolSchemaTest {

// Inspect the schema at the model boundary. AgentTool.inputSchema is the
// permissive execution schema; ToolRegistry applies the established wire
    // projection that hides teammate-only fields when teams are disabled.
    private static JsonNode schema() {
        return schema(new AgentTool(dummyClient(), new ToolRegistry()));
    }

    private static JsonNode schema(AgentTool agentTool) {
        var registry = new ToolRegistry();
        registry.register(agentTool);
        return (JsonNode) registry.getToolDefinitions().stream()
            .filter(definition -> Strings.CS.equals("Agent", definition.name()))
            .findFirst().orElseThrow().inputSchema();
    }

    private static StreamingClient dummyClient() {
        var dummyClient = new StreamingClient() {
            @Override
            public Iterator<StreamingEvent>
                    createStream(StreamingClient.StreamRequest request) {
                return Collections.emptyIterator();
            }
            @Override public String getModel() { return "test"; }
        };
        return dummyClient;
    }

    @Test
    void schema_requiresDescriptionAndPromptIn197WireProfile() {
        var required = schema().get("required");
        Set<String> fields = new HashSet<>();
        required.forEach(n -> fields.add(n.asText()));
        assertEquals(Set.of("description", "prompt"), fields,
            "lossless Claude Code 2.1.197 capture requires description and prompt");
    }

    @Test
    void schema_hasSubagentType() {
        var props = schema().get("properties");
        assertTrue(props.has("subagent_type"), "subagent_type must be in schema");
        assertFalse(Strings.CS.contains(schema().get("required").toString(), "subagent_type"),
            "subagent_type should NOT be required — it is optional in TS");
    }

    @Test
    void schema_hasCanonicalTsFields() {
        var props = schema().get("properties");
        for (String f : new String[]{
            "description", "prompt", "subagent_type", "model",
            "run_in_background", "isolation"
        }) {
            assertTrue(props.has(f), "schema missing TS canonical field: " + f);
        }
    }

    @Test
    void schema_omitsFieldsAbsentFromThe197WireCapture() {



        var props = schema().get("properties");
        for (String f : new String[]{"name", "team_name", "cwd", "mode", "tools", "budget_usd"}) {
            assertFalse(props.has(f), "schema should not expose non-197 field: " + f);
        }
    }

    @Test
    void schema_modelEnumMatchesFrozen197WireContract() {
        var modelEnum = schema().get("properties").get("model").get("enum");
        assertEquals(4, modelEnum.size());
        assertEquals(List.of("sonnet", "opus", "haiku", "fable"),
            StreamSupport.stream(modelEnum.spliterator(), false)
                .map(JsonNode::asText).toList());
        assertTrue(Strings.CS.contains(
            schema().get("properties").get("model").get("description").asText(),
            "forks always inherit the parent model"));
    }

    @Test
    void schema_modelEnumUsesSessionProjection() {
        AgentTool tool = new AgentTool(dummyClient(), new ToolRegistry());
        tool.setModelOptionsSupplier(() -> List.of("gateway-main", "reviewer"));

        var modelEnum = schema(tool).get("properties").get("model").get("enum");
        assertEquals(List.of("gateway-main", "reviewer"),
            StreamSupport.stream(modelEnum.spliterator(), false)
                .map(JsonNode::asText).toList());
    }

    @Test
    void schema_removesModelPropertyWhenNoOverrideCanExecute() {
        AgentTool tool = new AgentTool(dummyClient(), new ToolRegistry());
        tool.setModelOptionsSupplier(List::of);

        assertFalse(schema(tool).get("properties").has("model"));
    }

    @Test
    void schema_isolationEnumHasWorktree() {
        var isolationEnum = schema().get("properties").get("isolation").get("enum");
        assertEquals(1, isolationEnum.size());
        assertEquals("worktree", isolationEnum.get(0).asText());
    }
}
