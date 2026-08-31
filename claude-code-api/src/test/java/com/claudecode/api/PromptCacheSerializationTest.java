package com.claudecode.api;

import org.apache.commons.lang3.Strings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.core.prompt.SystemPromptConstants;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link AnthropicSdkClient#serializeWithCacheControl} emits the
 * Anthropic prompt-cache markers on both the system prompt and the final tool.
 *
 * <p>Prompt caching is the single biggest cost reducer for multi-turn conversations
 * — the system prompt (often 10–50k tokens of tool docs, CLAUDE.md, env context)
 * is identical across turns. Without cache_control, every turn pays full price.
 * With the {@code ephemeral} marker on a stable prefix, follow-up turns within
 * 5 minutes pay only ~10% of the original cost on the cached portion.
 *
 * <p>This is also what makes {@code /btw} side questions essentially free: they
 * fork with the exact same system bytes, so the sub-engine's request reads from
 * the parent's still-warm cache instead of repaying for the prefix.
 */
class PromptCacheSerializationTest {

    @Test
    void oneHourTtlIsAppliedToEveryAutomaticBreakpoint() throws Exception {
        CreateMessageRequest request = CreateMessageRequest.builder()
            .model("claude-sonnet-4-6")
            .systemPrompt("stable system")
            .messages(List.of(
                new CreateMessageRequest.RequestMessage("user", "hello"),
                new CreateMessageRequest.RequestMessage("assistant", "world")))
            .promptCacheTtl(CreateMessageRequest.PromptCacheTtl.ONE_HOUR)
            .build();

        JsonNode root = JsonUtils.parseTree(AnthropicSdkClient.serializeWithCacheControl(request));
        for (JsonNode systemBlock : root.get("system")) {
            if (systemBlock.has("cache_control")) {
                assertEquals("1h", systemBlock.at("/cache_control/ttl").asText());
            }
        }
        assertEquals("1h", root.at("/messages/1/content/0/cache_control/ttl").asText());
    }

    @Test
    void fiveMinuteTtlKeepsReleasedWireShapeWithoutTtlMember() throws Exception {
        CreateMessageRequest request = CreateMessageRequest.builder()
            .model("claude-sonnet-4-6")
            .systemPrompt("stable system")
            .messages(List.of(new CreateMessageRequest.RequestMessage("user", "hello")))
            .promptCacheTtl(CreateMessageRequest.PromptCacheTtl.FIVE_MINUTES)
            .build();

        JsonNode root = JsonUtils.parseTree(AnthropicSdkClient.serializeWithCacheControl(request));
        JsonNode cachedSystem = root.get("system").get(root.get("system").size() - 1);
        assertFalse(cachedSystem.at("/cache_control").has("ttl"));
        assertFalse(root.at("/messages/0/content/0/cache_control").has("ttl"));
    }

    @Test
    void explicitToolCacheControlCanCarryOneHourTtlEvenWhenAutomaticCachingIsOff() throws Exception {
        var tool = new CreateMessageRequest.ToolDefinition(
            "explicit", "caller owned", JsonUtils.parseTree("{\"type\":\"object\"}"),
            CreateMessageRequest.CacheControl.ephemeral(CreateMessageRequest.PromptCacheTtl.ONE_HOUR));
        CreateMessageRequest request = CreateMessageRequest.builder()
            .model("claude-sonnet-4-6")
            .systemPrompt("uncached")
            .messages(List.of(new CreateMessageRequest.RequestMessage("user", "hello")))
            .tools(List.of(tool))
            .promptCachingEnabled(false)
            .build();

        JsonNode root = JsonUtils.parseTree(AnthropicSdkClient.serializeWithCacheControl(request));
        assertFalse(root.at("/system/0").has("cache_control"));
        assertFalse(root.at("/messages/0/content/0").has("cache_control"));
        assertEquals("1h", root.at("/tools/0/cache_control/ttl").asText());
    }

    private final AnthropicSdkClient client = new AnthropicSdkClient(
        new ApiConfig.AnthropicConfig("test-key", null, "claude-sonnet-4", null));

    private String serialize(CreateMessageRequest req) throws Exception {
        Method m = AnthropicSdkClient.class.getDeclaredMethod(
            "serializeWithCacheControl", CreateMessageRequest.class);
        m.setAccessible(true);
        return (String) m.invoke(client, req);
    }

    @Test
    void systemPromptIsRewrittenIntoCacheableBlock() throws Exception {
        CreateMessageRequest req = CreateMessageRequest.builder()
            .model("claude-sonnet-4")
            .maxTokens(1024)
            .systemPrompt("You are Claude Code.")
            .messages(List.of(new CreateMessageRequest.RequestMessage("user", "hi")))
            .build();

        JsonNode root = JsonUtils.getMapper().readTree(serialize(req));

        JsonNode system = root.get("system");
        assertTrue(system.isArray(), "system field must be array form to support cache_control");
        assertEquals(2, system.size());
        assertEquals("text", system.get(0).get("type").asText());
        assertTrue(Strings.CS.startsWith(system.get(0).get("text").asText(), "x-anthropic-billing-header:"));
        assertFalse(system.get(0).has("cache_control"),
            "attribution is deliberately outside the cached identity/main blocks");
        assertEquals("You are Claude Code.", system.get(1).get("text").asText());
        assertEquals("ephemeral", system.get(1).get("cache_control").get("type").asText());
    }

    @Test
    void headlessAgentIdentityIsSplitAfterBillingAttribution() throws Exception {
        String identity = SystemPromptConstants.AGENT_SDK_SYSPROMPT_PREFIX;
        CreateMessageRequest req = CreateMessageRequest.builder()
            .model("glm-5.2")
            .maxTokens(1024)
            .systemPrompt(identity + "\n\nMAIN")
            .messages(List.of(new CreateMessageRequest.RequestMessage("user", "hello world")))
            .build();

        JsonNode system = JsonUtils.getMapper().readTree(serialize(req)).get("system");
        assertEquals(3, system.size());
        assertEquals(identity, system.get(1).get("text").asText());
        assertEquals("MAIN", system.get(2).get("text").asText());
    }

    @Test
    void entrypointFallsBackToIdentityWhenLauncherEnvIsAbsent() {
        // Pin the inference branch directly: the launcher env marker must win
        // when present, so wire-level assertions cannot rely on it being unset
        // in the test JVM (a Claude Code launcher exports entrypoint=cli).
        assertEquals("sdk-cli", AnthropicSdkClient.resolveEntrypoint(
            null, SystemPromptConstants.AGENT_SDK_SYSPROMPT_PREFIX, false));
        assertEquals("sdk-cli", AnthropicSdkClient.resolveEntrypoint(
            null, SystemPromptConstants.AGENT_SDK_CLI_PRESET_SYSPROMPT_PREFIX, false));
        assertEquals("cli", AnthropicSdkClient.resolveEntrypoint(
            null, SystemPromptConstants.CLI_SYSPROMPT_PREFIX, false));
        assertEquals("sdk-cli", AnthropicSdkClient.resolveEntrypoint(null, null, false));
        assertEquals("cli", AnthropicSdkClient.resolveEntrypoint(
            null, SystemPromptConstants.CLI_SYSPROMPT_PREFIX, true));
        assertEquals("cli", AnthropicSdkClient.resolveEntrypoint(
            "cli", SystemPromptConstants.AGENT_SDK_SYSPROMPT_PREFIX, false));
    }

    @Test
    void uncachedSystemSuffixStaysOutsideCachedIdentityAndPromptBlocks() throws Exception {
        String identity = SystemPromptConstants.AGENT_SDK_SYSPROMPT_PREFIX;
        CreateMessageRequest req = CreateMessageRequest.builder()
            .model("claude-sonnet-4-6")
            .maxTokens(64)
            .systemPrompt(identity + "\n\nCLASSIFIER"
                + CreateMessageRequest.UNCACHED_SYSTEM_SUFFIX_BOUNDARY
                + "\n\nSESSION")
            .messages(List.of(new CreateMessageRequest.RequestMessage("user", "probe")))
            .stream(false)
            .build();

        JsonNode system = JsonUtils.getMapper().readTree(serialize(req)).get("system");

        assertEquals(4, system.size());
        assertEquals(identity, system.get(1).get("text").asText());
        assertEquals("CLASSIFIER", system.get(2).get("text").asText());
        assertEquals("ephemeral", system.get(2).at("/cache_control/type").asText());
        assertEquals("\n\nSESSION", system.get(3).get("text").asText());
        assertFalse(system.get(3).has("cache_control"));
        assertFalse(Strings.CS.contains(system.toString(), CreateMessageRequest.UNCACHED_SYSTEM_SUFFIX_BOUNDARY));
    }

    @Test
    void absentSystemPromptIsLeftAlone() throws Exception {
        CreateMessageRequest req = CreateMessageRequest.builder()
            .model("claude-sonnet-4")
            .maxTokens(1024)
            .messages(List.of(new CreateMessageRequest.RequestMessage("user", "hi")))
            .build();

        JsonNode root = JsonUtils.getMapper().readTree(serialize(req));
        // No system → no system field (don't conjure an empty cache block).
        assertTrue(root.get("system") == null || root.get("system").isNull(),
            "missing system prompt must not be wrapped");
    }

    @Test
    void nonStreamingRequestsOmitOptInStreamMember() throws Exception {
        CreateMessageRequest req = CreateMessageRequest.builder()
            .model("claude-sonnet-4-6")
            .maxTokens(1024)
            .messages(List.of(new CreateMessageRequest.RequestMessage("user", "hi")))
            .stream(false)
            .build();

        JsonNode root = JsonUtils.getMapper().readTree(serialize(req));
        assertFalse(root.has("stream"),
            "2.1.197 omits stream for non-streaming/fallback Messages calls");
    }

    @Test
    void toolsArrayGetsNoAutomaticCacheControlMarker() throws Exception {

// the tools array never carries
        // cache_control on the wire. The cache breakpoint that covers tool
        // definitions lives on the system prompt block instead.
        CreateMessageRequest req = CreateMessageRequest.builder()
            .model("claude-sonnet-4")
            .maxTokens(1024)
            .systemPrompt("sys")
            .messages(List.of(new CreateMessageRequest.RequestMessage("user", "hi")))
            .tools(List.of(
                new CreateMessageRequest.ToolDefinition("read", "Read a file", null),
                new CreateMessageRequest.ToolDefinition("write", "Write a file", null),
                new CreateMessageRequest.ToolDefinition("bash", "Run a shell command", null)))
            .build();

        JsonNode root = JsonUtils.getMapper().readTree(serialize(req));
        JsonNode tools = root.get("tools");
        assertEquals(3, tools.size());

        for (JsonNode tool : tools) {
            assertTrue(tool.get("cache_control") == null || tool.get("cache_control").isNull(),
                "no tool should be auto-marked with cache_control");
        }
    }

    @Test
    void explicitToolCacheControlIsPreserved() throws Exception {
        // A caller-supplied cache_control on a tool must survive serialization
        // untouched — we just don't add one ourselves.
        CreateMessageRequest req = CreateMessageRequest.builder()
            .model("claude-sonnet-4")
            .maxTokens(1024)
            .systemPrompt("sys")
            .messages(List.of(new CreateMessageRequest.RequestMessage("user", "hi")))
            .tools(List.of(new CreateMessageRequest.ToolDefinition(
                "bash", "shell", null,
                CreateMessageRequest.CacheControl.ephemeral())))
            .build();

        JsonNode root = JsonUtils.getMapper().readTree(serialize(req));
        JsonNode lastCc = root.get("tools").get(0).get("cache_control");
        assertNotNull(lastCc, "caller-set cache_control must not be dropped");
        assertEquals("ephemeral", lastCc.get("type").asText());
    }

    @Test
    void skipCacheWriteMarksSecondLastNonSystemMessage() throws Exception {
        CreateMessageRequest req = CreateMessageRequest.builder()
            .model("glm-5.2")
            .maxTokens(1024)
            .systemPrompt("sys")
            .messages(List.of(
                new CreateMessageRequest.RequestMessage("user", "question"),
                new CreateMessageRequest.RequestMessage("system", "listing"),
                new CreateMessageRequest.RequestMessage("assistant", List.of(
                    Map.of("type", "tool_use", "id", "tool-1", "name", "Skill",
                        "input", Map.of("skill", "verify")))),
                new CreateMessageRequest.RequestMessage("user", List.of(
                    Map.of("type", "tool_result", "tool_use_id", "tool-1", "content", "skill body"),
                    Map.of("type", "text", "text", "compact prompt")))))
            .skipCacheWrite(true)
            .promptCacheTtl(CreateMessageRequest.PromptCacheTtl.ONE_HOUR)
            .build();

        JsonNode root = JsonUtils.getMapper().readTree(serialize(req));
        JsonNode messages = root.get("messages");
        JsonNode assistantToolUse = messages.get(2).get("content").get(0);
        JsonNode compactTail = messages.get(3).get("content").get(1);

        assertEquals("ephemeral", assistantToolUse.get("cache_control").get("type").asText());
        assertEquals("1h", assistantToolUse.at("/cache_control/ttl").asText());
        assertFalse(compactTail.has("cache_control"),
            "the fork-only compact prompt must not become a cache-write breakpoint");
        assertFalse(root.has("skipCacheWrite"), "internal cache policy must never leak onto the wire");
        assertFalse(root.has("skip_cache_write"), "internal cache policy must never leak onto the wire");
    }

    @Test
    void promptCachingCanBeDisabledForStructuredTitleSideQuery() throws Exception {
        var mapper = JsonUtils.getMapper();
        ObjectNode titleSchema = mapper.createObjectNode();
        titleSchema.put("type", "object");
        titleSchema.putObject("properties").putObject("title").put("type", "string");
        titleSchema.putArray("required").add("title");
        titleSchema.put("additionalProperties", false);
        ObjectNode format = mapper.createObjectNode();
        format.put("type", "json_schema");
        format.set("schema", titleSchema);

        var content = mapper.createArrayNode();
        content.addObject().put("type", "text").put("text", "<session>\nfix login\n</session>");
        CreateMessageRequest req = CreateMessageRequest.builder()
            .model("glm-5.2")
            .maxTokens(32_000)
            .systemPrompt(SystemPromptConstants.CLI_SYSPROMPT_PREFIX
                + "\n\nGenerate a title")
            .messages(List.of(new CreateMessageRequest.RequestMessage("user", content)))
            .tools(List.of())
            .stream(true)
            .outputConfig(new CreateMessageRequest.OutputConfig("high", format))
            .promptCachingEnabled(false)
            .build();

        JsonNode root = JsonUtils.getMapper().readTree(serialize(req));

        assertEquals(3, root.get("system").size());
        assertFalse(root.get("system").get(1).has("cache_control"));
        assertFalse(root.get("system").get(2).has("cache_control"));
        assertFalse(root.get("messages").get(0).get("content").get(0).has("cache_control"));
        assertEquals("high", root.at("/output_config/effort").asText());
        assertEquals("json_schema", root.at("/output_config/format/type").asText());
        assertFalse(root.has("promptCachingEnabled"));
        assertFalse(root.has("prompt_caching_enabled"));
    }

    @Test
    void nativeToolSchemaGetsJsonSchemaMetaFields() throws Exception {

        ObjectNode schema = JsonUtils.getMapper().createObjectNode()
            .put("type", "object");
        schema.putObject("properties")
            .putObject("path").put("type", "string");

        CreateMessageRequest req = CreateMessageRequest.builder()
            .model("claude-sonnet-4").maxTokens(1024)
            .systemPrompt("sys")
            .messages(List.of(new CreateMessageRequest.RequestMessage("user", "hi")))
            .tools(List.of(new CreateMessageRequest.ToolDefinition("Read", "Read a file", schema)))
            .build();

        JsonNode toolSchema = JsonUtils.getMapper().readTree(serialize(req))
            .get("tools").get(0).get("input_schema");
        assertEquals("https://json-schema.org/draft/2020-12/schema",
            toolSchema.get("$schema").asText());
        assertEquals("$schema", toolSchema.fieldNames().next(),
            "$schema must be the first key, matching the golden capture's field order");
        assertTrue(toolSchema.get("additionalProperties").isBoolean()
            && !toolSchema.get("additionalProperties").asBoolean(),
            "native tool schema must default to additionalProperties:false");
    }

    @Test
    void nestedObjectSchemasAlsoGetAdditionalPropertiesFalse() throws Exception {
// Regression: the normalization used to only patch the top-level schema.
        var mapper = JsonUtils.getMapper();
        ObjectNode optionsItems = mapper.createObjectNode();
        optionsItems.put("type", "object");
        optionsItems.putObject("properties").putObject("label").put("type", "string");

        ObjectNode optionsArray = mapper.createObjectNode();
        optionsArray.put("type", "array");
        optionsArray.set("items", optionsItems);

        ObjectNode questionsItems = mapper.createObjectNode();
        questionsItems.put("type", "object");
        questionsItems.putObject("properties").set("options", optionsArray);

        ObjectNode questionsArray = mapper.createObjectNode();
        questionsArray.put("type", "array");
        questionsArray.set("items", questionsItems);

        ObjectNode metadata = mapper.createObjectNode();
        metadata.put("type", "object");

        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.set("questions", questionsArray);
        properties.set("metadata", metadata);

        CreateMessageRequest req = CreateMessageRequest.builder()
            .model("claude-sonnet-4").maxTokens(1024)
            .systemPrompt("sys")
            .messages(List.of(new CreateMessageRequest.RequestMessage("user", "hi")))
            .tools(List.of(new CreateMessageRequest.ToolDefinition("AskUserQuestion", "ask", schema)))
            .build();

        JsonNode toolSchema = JsonUtils.getMapper().readTree(serialize(req))
            .get("tools").get(0).get("input_schema");
        JsonNode qItems = toolSchema.at("/properties/questions/items");
        JsonNode oItems = toolSchema.at("/properties/questions/items/properties/options/items");
        JsonNode md = toolSchema.at("/properties/metadata");

        assertFalse(qItems.get("additionalProperties").asBoolean(true));
        assertFalse(oItems.get("additionalProperties").asBoolean(true));
        assertFalse(md.get("additionalProperties").asBoolean(true));
    }

    @Test
    void mcpToolSchemaIsLeftUntouched() throws Exception {

        JsonNode schema = JsonUtils.getMapper().createObjectNode().put("type", "object");

        CreateMessageRequest req = CreateMessageRequest.builder()
            .model("claude-sonnet-4").maxTokens(1024)
            .systemPrompt("sys")
            .messages(List.of(new CreateMessageRequest.RequestMessage("user", "hi")))
            .tools(List.of(new CreateMessageRequest.ToolDefinition(
                "mcp__example__do_thing", "do a thing", schema)))
            .build();

        JsonNode toolSchema = JsonUtils.getMapper().readTree(serialize(req))
            .get("tools").get(0).get("input_schema");
        assertNull(toolSchema.get("$schema"),
          "MCP tool schema must not gain a $schema pointer Java didn't author");
        assertNull(toolSchema.get("additionalProperties"),
          "MCP tool schema must not gain additionalProperties Java didn't author");
    }

    @Test
    void structuredOutputToolSchemaIsLeftUntouched() throws Exception {
        // The StructuredOutput tool's input_schema is the caller-supplied

        JsonNode schema = JsonUtils.getMapper().createObjectNode().put("type", "object");

        CreateMessageRequest req = CreateMessageRequest.builder()
            .model("claude-sonnet-4").maxTokens(1024)
            .systemPrompt("sys")
            .messages(List.of(new CreateMessageRequest.RequestMessage("user", "hi")))
            .tools(List.of(new CreateMessageRequest.ToolDefinition(
                "StructuredOutput", "Return structured output in the requested format", schema)))
            .build();

        JsonNode toolSchema = JsonUtils.getMapper().readTree(serialize(req))
            .get("tools").get(0).get("input_schema");
        assertNull(toolSchema.get("$schema"),
          "StructuredOutput schema must not gain a $schema pointer Java didn't author");
        assertNull(toolSchema.get("additionalProperties"),
          "StructuredOutput schema must not gain additionalProperties Java didn't author");
    }

    @Test
    void existingAdditionalPropertiesValueIsNotClobbered() throws Exception {

        // semantics) — the global normalization must not overwrite it to false.
        JsonNode schema = JsonUtils.getMapper().createObjectNode()
            .put("type", "object")
            .put("additionalProperties", true);

        CreateMessageRequest req = CreateMessageRequest.builder()
            .model("claude-sonnet-4").maxTokens(1024)
            .systemPrompt("sys")
            .messages(List.of(new CreateMessageRequest.RequestMessage("user", "hi")))
            .tools(List.of(new CreateMessageRequest.ToolDefinition("ExitPlanMode", "exit plan mode", schema)))
            .build();

        JsonNode toolSchema = JsonUtils.getMapper().readTree(serialize(req))
            .get("tools").get(0).get("input_schema");
        assertTrue(toolSchema.get("additionalProperties").asBoolean(),
            "a tool's own additionalProperties value must survive untouched");
    }

    @Test
    void identicalSystemPromptsProduceByteIdenticalSystemBlocks() throws Exception {
        // /btw correctness test: parent engine and forked side-question engine
        // pass the same systemPrompt string. The serialized 'system' field must
        // be byte-identical so Anthropic's cache key matches.
        CreateMessageRequest parent = CreateMessageRequest.builder()
            .model("claude-sonnet-4").maxTokens(1024)
            .systemPrompt("Long shared prefix with tool docs and CLAUDE.md.")
            .messages(List.of(new CreateMessageRequest.RequestMessage("user", "main turn")))
            .build();

        CreateMessageRequest side = CreateMessageRequest.builder()
            .model("claude-sonnet-4").maxTokens(1024)
            .systemPrompt("Long shared prefix with tool docs and CLAUDE.md.")
            .messages(List.of(new CreateMessageRequest.RequestMessage("user", "side turn")))
            .build();

        JsonNode parentSystem = JsonUtils.getMapper().readTree(serialize(parent)).get("system");
        JsonNode sideSystem   = JsonUtils.getMapper().readTree(serialize(side)).get("system");
        assertEquals(parentSystem, sideSystem,
            "parent and forked side-question system blocks must match byte-for-byte for cache hit");
    }
}
