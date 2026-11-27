package com.claudecode.mcp.oauth;

import com.claudecode.mcp.McpServerConfig;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.core.util.HashUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Locale;
import java.util.TreeMap;

/**
 * SHA-256 fingerprint of a server's config used to key its credentials in {@link SecureStorage}.
 */
public final class ServerKey {

    private ServerKey() {}

    public static String forConfig(McpServerConfig config) {
        // Sorted map so key ordering doesn't leak into the hash.
        ObjectNode root = JsonUtils.getMapper().createObjectNode();
        root.put("type", config.transportType());
        root.put("url", config.url() == null ? "" : config.url());
        ObjectNode hdrs = root.putObject("headers");
        TreeMap<String, String> sorted = new TreeMap<>(config.headers());
        for (var e : sorted.entrySet()) {
            hdrs.put(e.getKey(), e.getValue());
        }
        String json;
        try {
            json = JsonUtils.getMapper().writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Impossible: json serialisation of a fresh ObjectNode", e);
        }

        byte[] hash = HashUtils.sha256Bytes(json);
        StringBuilder hex = new StringBuilder(64);
        for (byte b : hash) hex.append(String.format(Locale.ROOT, "%02x", b));
        return config.name() + "|" + hex.substring(0, 16);
    }
}
