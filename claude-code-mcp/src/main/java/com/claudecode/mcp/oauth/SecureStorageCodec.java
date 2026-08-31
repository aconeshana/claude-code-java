package com.claudecode.mcp.oauth;

import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Shared JSON <-> {@link SecureStorageData} codec used by every {@link SecureStorage} backend
 * (plaintext, keychain, fallback).
 */
final class SecureStorageCodec {

    private static final Set<String> KNOWN_TOP_LEVEL = Set.of(
        "mcpOAuth", "mcpOAuthClientConfig", "pluginSecrets");

    private SecureStorageCodec() {}

    static SecureStorageData decode(JsonNode root) {
        if (root == null || !root.isObject()) return SecureStorageData.empty();

        Map<String, SecureStorageData.McpOAuthEntry> tokens = new LinkedHashMap<>();
        JsonNode tokensNode = root.get("mcpOAuth");
        if (tokensNode != null && tokensNode.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = tokensNode.fields();
            while (it.hasNext()) {
                var e = it.next();
                tokens.put(e.getKey(), decodeTokenEntry(e.getValue()));
            }
        }

        Map<String, SecureStorageData.McpOAuthClientConfigEntry> clientCfg = new LinkedHashMap<>();
        JsonNode cfgNode = root.get("mcpOAuthClientConfig");
        if (cfgNode != null && cfgNode.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = cfgNode.fields();
            while (it.hasNext()) {
                var e = it.next();
                clientCfg.put(e.getKey(), decodeClientCfgEntry(e.getValue()));
            }
        }

        Map<String, Map<String, String>> pluginSecrets = new LinkedHashMap<>();
        JsonNode secretsNode = root.get("pluginSecrets");
        if (secretsNode != null && secretsNode.isObject()) {
            secretsNode.fields().forEachRemaining(plugin -> {
                if (!plugin.getValue().isObject()) return;
                Map<String, String> secrets = new LinkedHashMap<>();
                plugin.getValue().fields().forEachRemaining(entry -> {
                    if (entry.getValue().isTextual()) {
                        secrets.put(entry.getKey(), entry.getValue().asText());
                    }
                });
                pluginSecrets.put(plugin.getKey(), secrets);
            });
        }

        Map<String, Object> extras = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> it = root.fields();
        while (it.hasNext()) {
            var e = it.next();
            if (!KNOWN_TOP_LEVEL.contains(e.getKey())) {
                extras.put(e.getKey(), JsonUtils.getMapper().convertValue(e.getValue(), Object.class));
            }
        }

        return new SecureStorageData(tokens, clientCfg, pluginSecrets, extras);
    }

    /**
     * Encodes {@code data} on top of {@code priorRawJson} (which may carry
 * unknown fields we must preserve). Our known top-level fields are
     * always replaced with the encoded state; extras are re-applied last so
     * an in-memory update to a foreign field survives.
     */
    static ObjectNode encode(SecureStorageData data, JsonNode priorRawJson) {
        ObjectNode root = priorRawJson != null && priorRawJson.isObject()
            ? ((ObjectNode) priorRawJson.deepCopy())
            : JsonUtils.getMapper().createObjectNode();

        // Rebuild the known fields fresh.
        ObjectNode tokens = JsonUtils.getMapper().createObjectNode();
        for (var e : data.mcpOAuth().entrySet()) {
            tokens.set(e.getKey(), encodeTokenEntry(e.getValue()));
        }
        root.set("mcpOAuth", tokens);

        ObjectNode cfg = JsonUtils.getMapper().createObjectNode();
        for (var e : data.mcpOAuthClientConfig().entrySet()) {
            cfg.set(e.getKey(), encodeClientCfgEntry(e.getValue()));
        }
        root.set("mcpOAuthClientConfig", cfg);

        ObjectNode secrets = JsonUtils.getMapper().createObjectNode();
        for (var plugin : data.pluginSecrets().entrySet()) {
            ObjectNode values = JsonUtils.getMapper().createObjectNode();
            plugin.getValue().forEach(values::put);
            secrets.set(plugin.getKey(), values);
        }
        root.set("pluginSecrets", secrets);

        // Extras land last so callers that mutated extras via
// SecureStorageData.extras write through.
        for (var e : data.extras().entrySet()) {
            root.set(e.getKey(), JsonUtils.getMapper().valueToTree(e.getValue()));
        }
        return root;
    }

    private static SecureStorageData.McpOAuthEntry decodeTokenEntry(JsonNode n) {
        if (n == null || !n.isObject()) return null;
        return new SecureStorageData.McpOAuthEntry(
            txt(n, "serverName"),
            txt(n, "serverUrl"),
            txt(n, "clientId"),
            txt(n, "clientSecret"),
            txt(n, "accessToken"),
            txt(n, "refreshToken"),
            n.has("expiresAt") ? n.get("expiresAt").asLong(0) : 0L,
            txt(n, "tokenEndpoint"),
            txt(n, "scope"));
    }

    private static ObjectNode encodeTokenEntry(SecureStorageData.McpOAuthEntry t) {
        ObjectNode n = JsonUtils.getMapper().createObjectNode();
        putIfPresent(n, "serverName",     t.serverName());
        putIfPresent(n, "serverUrl",      t.serverUrl());
        putIfPresent(n, "clientId",       t.clientId());
        putIfPresent(n, "clientSecret",   t.clientSecret());
        putIfPresent(n, "accessToken",    t.accessToken());
        putIfPresent(n, "refreshToken",   t.refreshToken());
        n.put("expiresAt", t.expiresAt());
        putIfPresent(n, "tokenEndpoint",  t.tokenEndpoint());
        putIfPresent(n, "scope",          t.scope());
        return n;
    }

    private static SecureStorageData.McpOAuthClientConfigEntry decodeClientCfgEntry(JsonNode n) {
        if (n == null || !n.isObject()) return null;
        return new SecureStorageData.McpOAuthClientConfigEntry(
            txt(n, "clientId"),
            txt(n, "clientSecret"),
            txt(n, "registrationEndpoint"),
            n.has("issuedAt") ? n.get("issuedAt").asLong(0) : 0L);
    }

    private static ObjectNode encodeClientCfgEntry(SecureStorageData.McpOAuthClientConfigEntry c) {
        ObjectNode n = JsonUtils.getMapper().createObjectNode();
        putIfPresent(n, "clientId",             c.clientId());
        putIfPresent(n, "clientSecret",         c.clientSecret());
        putIfPresent(n, "registrationEndpoint", c.registrationEndpoint());
        n.put("issuedAt", c.issuedAt());
        return n;
    }

    private static String txt(JsonNode n, String key) {
        JsonNode v = n.get(key);
        return (v != null && v.isTextual()) ? v.asText() : null;
    }

    private static void putIfPresent(ObjectNode n, String key, String value) {
        if (value != null) n.put(key, value);
    }
}
