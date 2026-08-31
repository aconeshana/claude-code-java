package com.claudecode.sdk;

import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Protocol codecs for the strongly typed official Query responses. */
final class SdkQueryJson {
    private SdkQueryJson() {}

    static SDKControlInitializeResponse initialization(JsonNode node) {
        ObjectNode value = object(node, "initialize response");
        return new SDKControlInitializeResponse(
            list(value, "commands", SdkQueryJson::slashCommand),
            list(value, "agents", SdkQueryJson::agentInfo),
            text(value, "output_style"), strings(value, "available_output_styles"),
            list(value, "models", SdkQueryJson::modelInfo),
            accountInfo(required(value, "account")), fastMode(value.get("fast_mode_state")));
    }

    static List<McpServerStatus> mcpStatuses(JsonNode node) {
        JsonNode values = node != null && node.has("mcpServers") ? node.get("mcpServers") : node;
        List<McpServerStatus> result = new ArrayList<>();
        array(values, "mcpServers").forEach(value -> result.add(mcpStatus(value)));
        return List.copyOf(result);
    }

    static McpSetServersResult mcpSetServersResult(JsonNode node) {
        ObjectNode value = object(node, "mcp_set_servers response");
        Map<String, String> errors = new LinkedHashMap<>();
        ObjectNode errorNode = object(required(value, "errors"), "errors");
        errorNode.fields().forEachRemaining(entry -> errors.put(entry.getKey(), entry.getValue().asText()));
        return new McpSetServersResult(strings(value, "added"), strings(value, "removed"), errors);
    }

    static RewindFilesResult rewind(JsonNode node) {
        ObjectNode value = object(node, "rewind_files response");
        return new RewindFilesResult(bool(value, "canRewind"), optionalText(value, "error"),
            optionalStrings(value, "filesChanged"), optionalInt(value, "insertions"),
            optionalInt(value, "deletions"));
    }

    static SDKControlReloadPluginsResponse reloadPlugins(JsonNode node) {
        ObjectNode value = object(node, "reload_plugins response");
        return new SDKControlReloadPluginsResponse(
            list(value, "commands", SdkQueryJson::slashCommand),
            list(value, "agents", SdkQueryJson::agentInfo),
            list(value, "plugins", plugin -> new SDKControlReloadPluginsResponse.PluginInfo(
                text(plugin, "name"), text(plugin, "path"), optionalText(plugin, "source"))),
            list(value, "mcpServers", SdkQueryJson::mcpStatus), integer(value, "error_count"));
    }

    static SDKControlGetContextUsageResponse contextUsage(JsonNode node) {
        ObjectNode value = object(node, "get_context_usage response");
        return new SDKControlGetContextUsageResponse(
            list(value, "categories", category -> new SDKControlGetContextUsageResponse.Category(
                text(category, "name"), integer(category, "tokens"), text(category, "color"),
                optionalBoolean(category, "isDeferred"))),
            integer(value, "totalTokens"), integer(value, "maxTokens"),
            integer(value, "rawMaxTokens"), number(value, "percentage"),
            gridRows(value), text(value, "model"),
            list(value, "memoryFiles", memory -> new SDKControlGetContextUsageResponse.MemoryFile(
                text(memory, "path"), text(memory, "type"), integer(memory, "tokens"))),
            list(value, "mcpTools", tool -> new SDKControlGetContextUsageResponse.McpTool(
                text(tool, "name"), text(tool, "serverName"), integer(tool, "tokens"),
                optionalBoolean(tool, "isLoaded"))),
            optionalList(value, "deferredBuiltinTools", SdkQueryJson::namedTokens),
            optionalList(value, "systemTools", SdkQueryJson::namedTokens),
            optionalList(value, "systemPromptSections", SdkQueryJson::namedTokens),
            list(value, "agents", agent -> new SDKControlGetContextUsageResponse.AgentUsage(
                text(agent, "agentType"), text(agent, "source"), integer(agent, "tokens"))),
            slashUsage(value.get("slashCommands")), skillUsage(value.get("skills")),
            optionalInt(value, "autoCompactThreshold"), bool(value, "isAutoCompactEnabled"),
            messageBreakdown(value.get("messageBreakdown")), apiUsage(value.get("apiUsage")));
    }

    static ObjectNode mcpConfig(McpServerConfig config) {
        ObjectNode json = JsonUtils.getMapper().createObjectNode();
        switch (config) {
            case McpStdioServerConfig stdio -> {
                json.put("command", stdio.command());
                if (!stdio.args().isEmpty()) json.set("args", JsonUtils.getMapper().valueToTree(stdio.args()));
                if (!stdio.env().isEmpty()) json.set("env", JsonUtils.getMapper().valueToTree(stdio.env()));
            }
            case McpSseServerConfig sse -> {
                json.put("type", "sse").put("url", sse.url());
                if (!sse.headers().isEmpty()) json.set("headers", JsonUtils.getMapper().valueToTree(sse.headers()));
            }
            case McpHttpServerConfig http -> {
                json.put("type", "http").put("url", http.url());
                if (!http.headers().isEmpty()) json.set("headers", JsonUtils.getMapper().valueToTree(http.headers()));
            }
            case McpSdkServerConfigWithInstance sdk -> json.put("type", "sdk").put("name", sdk.name());
        }
        return json;
    }

    private static SlashCommand slashCommand(JsonNode node) {
        return new SlashCommand(text(node, "name"), text(node, "description"),
            text(node, "argumentHint"));
    }

    private static AgentInfo agentInfo(JsonNode node) {
        return new AgentInfo(text(node, "name"), text(node, "description"),
            optionalText(node, "model"));
    }

    private static ModelInfo modelInfo(JsonNode node) {
        return new ModelInfo(text(node, "value"), text(node, "displayName"),
            text(node, "description"), optionalBoolean(node, "supportsEffort"),
            optionalStrings(node, "supportedEffortLevels"),
            optionalBoolean(node, "supportsAdaptiveThinking"),
            optionalBoolean(node, "supportsFastMode"), optionalBoolean(node, "supportsAutoMode"));
    }

    private static AccountInfo accountInfo(JsonNode node) {
        return new AccountInfo(optionalText(node, "email"), optionalText(node, "organization"),
            optionalText(node, "subscriptionType"), optionalText(node, "tokenSource"),
            optionalText(node, "apiKeySource"), optionalText(node, "apiProvider"));
    }

    private static McpServerStatus mcpStatus(JsonNode node) {
        McpServerStatus.ServerInfo serverInfo = null;
        if (present(node.get("serverInfo"))) {
            serverInfo = new McpServerStatus.ServerInfo(text(node.get("serverInfo"), "name"),
                text(node.get("serverInfo"), "version"));
        }
        return new McpServerStatus(text(node, "name"), text(node, "status"), serverInfo,
            optionalText(node, "error"), node.get("config"), optionalText(node, "scope"),
            optionalList(node, "tools", tool -> new McpServerStatus.ToolInfo(
                text(tool, "name"), optionalText(tool, "description"), annotations(tool.get("annotations")))));
    }

    private static McpServerStatus.ToolAnnotations annotations(JsonNode node) {
        if (!present(node)) return null;
        return new McpServerStatus.ToolAnnotations(optionalBoolean(node, "readOnly"),
            optionalBoolean(node, "destructive"), optionalBoolean(node, "openWorld"));
    }

    private static List<List<SDKControlGetContextUsageResponse.GridCell>> gridRows(JsonNode node) {
        List<List<SDKControlGetContextUsageResponse.GridCell>> rows = new ArrayList<>();
        for (JsonNode row : array(required(node, "gridRows"), "gridRows")) {
            List<SDKControlGetContextUsageResponse.GridCell> cells = new ArrayList<>();
            for (JsonNode cell : array(row, "grid row")) {
                cells.add(new SDKControlGetContextUsageResponse.GridCell(text(cell, "color"),
                    bool(cell, "isFilled"), text(cell, "categoryName"), integer(cell, "tokens"),
                    number(cell, "percentage"), number(cell, "squareFullness")));
            }
            rows.add(List.copyOf(cells));
        }
        return List.copyOf(rows);
    }

    private static SDKControlGetContextUsageResponse.NamedTokens namedTokens(JsonNode node) {
        return new SDKControlGetContextUsageResponse.NamedTokens(text(node, "name"),
            integer(node, "tokens"), optionalBoolean(node, "isLoaded"));
    }

    private static SDKControlGetContextUsageResponse.SlashCommandUsage slashUsage(JsonNode node) {
        if (!present(node)) return null;
        return new SDKControlGetContextUsageResponse.SlashCommandUsage(
            integer(node, "totalCommands"), integer(node, "includedCommands"),
            integer(node, "tokens"));
    }

    private static SDKControlGetContextUsageResponse.SkillUsage skillUsage(JsonNode node) {
        if (!present(node)) return null;
        return new SDKControlGetContextUsageResponse.SkillUsage(integer(node, "totalSkills"),
            integer(node, "includedSkills"), integer(node, "tokens"),
            list(node, "skillFrontmatter", skill -> new SDKControlGetContextUsageResponse.SkillFrontmatter(
                text(skill, "name"), text(skill, "source"), integer(skill, "tokens"))));
    }

    private static SDKControlGetContextUsageResponse.MessageBreakdown messageBreakdown(JsonNode node) {
        if (!present(node)) return null;
        return new SDKControlGetContextUsageResponse.MessageBreakdown(
            integer(node, "toolCallTokens"), integer(node, "toolResultTokens"),
            integer(node, "attachmentTokens"), integer(node, "assistantMessageTokens"),
            integer(node, "userMessageTokens"),
            list(node, "toolCallsByType", tool -> new SDKControlGetContextUsageResponse.ToolCallUsage(
                text(tool, "name"), integer(tool, "callTokens"), integer(tool, "resultTokens"))),
            list(node, "attachmentsByType", attachment -> new SDKControlGetContextUsageResponse.AttachmentUsage(
                text(attachment, "name"), integer(attachment, "tokens"))));
    }

    private static SDKControlGetContextUsageResponse.ApiUsage apiUsage(JsonNode node) {
        if (!present(node)) return null;
        return new SDKControlGetContextUsageResponse.ApiUsage(integer(node, "input_tokens"),
            integer(node, "output_tokens"), integer(node, "cache_creation_input_tokens"),
            integer(node, "cache_read_input_tokens"));
    }

    private static FastModeState fastMode(JsonNode node) {
        if (!present(node)) return null;
        return switch (node.asText()) {
            case "off" -> FastModeState.OFF;
            case "cooldown" -> FastModeState.COOLDOWN;
            case "on" -> FastModeState.ON;
            default -> throw new IllegalArgumentException("Unknown fast_mode_state: " + node.asText());
        };
    }

    private interface Decoder<T> { T decode(JsonNode node); }

    private static <T> List<T> list(JsonNode node, String field, Decoder<T> decoder) {
        List<T> result = new ArrayList<>();
        array(required(node, field), field).forEach(value -> result.add(decoder.decode(value)));
        return List.copyOf(result);
    }

    private static <T> List<T> optionalList(JsonNode node, String field, Decoder<T> decoder) {
        return present(node.get(field)) ? list(node, field, decoder) : List.of();
    }

    private static List<String> strings(JsonNode node, String field) {
        List<String> result = new ArrayList<>();
        array(required(node, field), field).forEach(value -> result.add(value.asText()));
        return List.copyOf(result);
    }

    private static List<String> optionalStrings(JsonNode node, String field) {
        return present(node.get(field)) ? strings(node, field) : List.of();
    }

    private static JsonNode required(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (!present(value)) throw new IllegalArgumentException("Missing required field: " + field);
        return value;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = required(node, field);
        if (!value.isTextual()) throw new IllegalArgumentException("Expected text field: " + field);
        return value.asText();
    }

    private static String optionalText(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return present(value) ? value.asText() : null;
    }

    private static int integer(JsonNode node, String field) {
        JsonNode value = required(node, field);
        if (!value.isIntegralNumber()) throw new IllegalArgumentException("Expected integer field: " + field);
        return value.intValue();
    }

    private static Integer optionalInt(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return present(value) ? integer(node, field) : null;
    }

    private static boolean bool(JsonNode node, String field) {
        JsonNode value = required(node, field);
        if (!value.isBoolean()) throw new IllegalArgumentException("Expected boolean field: " + field);
        return value.booleanValue();
    }

    private static Boolean optionalBoolean(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return present(value) ? bool(node, field) : null;
    }

    private static double number(JsonNode node, String field) {
        JsonNode value = required(node, field);
        if (!value.isNumber()) throw new IllegalArgumentException("Expected number field: " + field);
        return value.doubleValue();
    }

    private static ObjectNode object(JsonNode node, String label) {
        if (node == null || !node.isObject()) throw new IllegalArgumentException("Expected object: " + label);
        return (ObjectNode) node;
    }

    private static ArrayNode array(JsonNode node, String label) {
        if (node == null || !node.isArray()) throw new IllegalArgumentException("Expected array: " + label);
        return (ArrayNode) node;
    }

    private static boolean present(JsonNode node) { return node != null && !node.isNull(); }
}
