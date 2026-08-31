package com.claudecode.lsp;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A single plugin-provided LSP server config (from or a manifest {@code lspServers} field).
 */
public record LspServerSettings(
    String command,
    List<String> args,
    Map<String, String> extensionToLanguage,
    Map<String, String> env,
    boolean enabled,
    JsonNode initializationOptions,
    JsonNode settings,
    String workspaceFolder,
    String transport,
    Long timeoutMs,
    boolean restart
) {

    /**
     * Back-compat constructor for the original 5-field plugin shape and for
     * tests — fills the richer plugin-schema fields with their defaults
     * ({@code transport} defaults to {@code "stdio"}; the rest default to
     * {@code null}/false).
     */
    public LspServerSettings(String command, List<String> args,
                             Map<String, String> extensionToLanguage, Map<String, String> env,
                             boolean enabled) {
        this(command, args, extensionToLanguage, env, enabled,
            null, null, null, "stdio", null, false);
    }

    /** Parses a plugin {@code lspServers} JSON node into settings records. */
    public static Map<String, LspServerSettings> fromJson(JsonNode lspServersNode) {
        if (lspServersNode == null || !lspServersNode.isObject()) {
            return Map.of();
        }

        Map<String, LspServerSettings> result = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = lspServersNode.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            result.put(entry.getKey(), parseEntry(entry.getValue()));
        }
        return Map.copyOf(result);
    }

    /** Parses a single LSP server config node (used for plugin-provided servers). */
    public static LspServerSettings fromNode(JsonNode node) {
        return parseEntry(node);
    }

    private static LspServerSettings parseEntry(JsonNode node) {
        if (node == null || !node.isObject()) {
            return new LspServerSettings(null, List.of(), Map.of(), Map.of(), true);
        }

        String command = node.has("command") && !node.get("command").isNull()
            ? node.get("command").asText() : null;

        List<String> args = new ArrayList<>();
        if (node.has("args") && node.get("args").isArray()) {
            node.get("args").forEach(n -> args.add(n.asText("")));
        }

        Map<String, String> extensionToLanguage = new LinkedHashMap<>();
        if (node.has("extensionToLanguage") && node.get("extensionToLanguage").isObject()) {
            node.get("extensionToLanguage").fields().forEachRemaining(
                e -> extensionToLanguage.put(e.getKey(), e.getValue().asText("")));
        }

        Map<String, String> env = new LinkedHashMap<>();
        if (node.has("env") && node.get("env").isObject()) {
            node.get("env").fields().forEachRemaining(
                e -> env.put(e.getKey(), e.getValue().asText("")));
        }

        boolean enabled = !node.has("enabled") || node.get("enabled").asBoolean(true);

        JsonNode initOptions = node.has("initializationOptions") && !node.get("initializationOptions").isNull()
            ? node.get("initializationOptions") : null;
        JsonNode settingsNode = node.has("settings") && !node.get("settings").isNull()
            ? node.get("settings") : null;
        String workspaceFolder = node.has("workspaceFolder") && !node.get("workspaceFolder").isNull()
            ? node.get("workspaceFolder").asText() : null;
        String transport = node.has("transport") && !node.get("transport").isNull()
            ? node.get("transport").asText("stdio") : "stdio";
        Long timeoutMs = node.has("timeoutMs") && !node.get("timeoutMs").isNull()
            ? node.get("timeoutMs").asLong() : null;
        boolean restart = node.has("restart") && !node.get("restart").isNull()
            && node.get("restart").asBoolean(false);

        return new LspServerSettings(command, List.copyOf(args), Map.copyOf(extensionToLanguage),
            Map.copyOf(env), enabled, initOptions, settingsNode, workspaceFolder, transport, timeoutMs, restart);
    }
}
