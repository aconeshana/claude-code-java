package com.claudecode.gateway;

import static com.claudecode.gateway.GatewayRequests.readRequestJson;
import static com.claudecode.gateway.GatewayResponses.errorBody;
import static com.claudecode.gateway.GatewayResponses.respondJson;

import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.runtime.plugins.PluginMarketplacePort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

/**
 * HTTP handlers for {@code /api/plugins/**}: marketplace management, plugin
 * install/uninstall/enable/disable/update, option persistence, and live runtime reload.
 */
final class GatewayPluginsHandler {

    private final GatewayPluginsPort port;

    GatewayPluginsHandler(GatewayPluginsPort port) {
        this.port = Objects.requireNonNull(port, "port");
    }

    void handleGetSnapshot(HttpExchange exchange) throws IOException {
        respondJson(exchange, 200, serializeSnapshot());
    }

    void handleReload(HttpExchange exchange) throws IOException {
        PluginMarketplacePort mp = requireMarketplace(exchange);
        if (mp == null) return;
        port.refreshRuntime();
        respondJson(exchange, 200, serializeSnapshot());
    }

    void handleAddMarketplace(HttpExchange exchange) throws IOException {
        PluginMarketplacePort mp = requireMarketplace(exchange);
        if (mp == null) return;
        JsonNode body = readRequestJson(exchange);
        if (body == null) return;
        String source = body.path("source").asText("").trim();
        if (StringUtils.isBlank(source)) {
            respondJson(exchange, 400, errorBody("invalid_request", "source is required"));
            return;
        }
        PluginMarketplacePort.ParsedMarketplaceInput parsed = mp.parseMarketplaceInput(source);
        switch (parsed) {
            case PluginMarketplacePort.ParsedMarketplaceInput.Unrecognized _ -> {
                respondJson(exchange, 400, errorBody("invalid_request",
                    "Invalid marketplace source format. Try: owner/repo, https://..., or ./path"));
            }
            case PluginMarketplacePort.ParsedMarketplaceInput.Invalid invalid -> {
                respondJson(exchange, 400, errorBody("invalid_request", invalid.error()));
            }
            case PluginMarketplacePort.ParsedMarketplaceInput.Parsed ok -> {
                try {
                    mp.addMarketplace(ok, _ -> {});
                    respondJson(exchange, 200, serializeSnapshot());
                } catch (RuntimeException failure) {
                    respondFailure(exchange, failure);
                }
            }
        }
    }

    void handleUpdateMarketplace(HttpExchange exchange) throws IOException {
        PluginMarketplacePort mp = requireMarketplace(exchange);
        if (mp == null) return;
        JsonNode body = readRequestJson(exchange);
        if (body == null) return;
        String name = body.path("name").asText("").trim();
        try {
            if (StringUtils.isNotBlank(name)) {
                mp.updateMarketplace(name, _ -> {});
            } else {
                for (String existing : mp.marketplaces().keySet()) {
                    mp.updateMarketplace(existing, _ -> {});
                }
            }
            port.refreshRuntime();
            respondJson(exchange, 200, serializeSnapshot());
        } catch (RuntimeException failure) {
            respondFailure(exchange, failure);
        }
    }

    void handleRemoveMarketplace(HttpExchange exchange) throws IOException {
        PluginMarketplacePort mp = requireMarketplace(exchange);
        if (mp == null) return;
        JsonNode body = readRequestJson(exchange);
        if (body == null) return;
        String name = body.path("name").asText("").trim();
        if (StringUtils.isBlank(name)) {
            respondJson(exchange, 400, errorBody("invalid_request", "name is required"));
            return;
        }
        try {
            mp.removeMarketplace(name);
            port.refreshRuntime();
            respondJson(exchange, 200, serializeSnapshot());
        } catch (RuntimeException failure) {
            respondFailure(exchange, failure);
        }
    }

    void handleSetAutoUpdate(HttpExchange exchange) throws IOException {
        PluginMarketplacePort mp = requireMarketplace(exchange);
        if (mp == null) return;
        JsonNode body = readRequestJson(exchange);
        if (body == null) return;
        String name = body.path("name").asText("").trim();
        JsonNode autoUpdate = body.get("auto_update");
        if (StringUtils.isBlank(name) || autoUpdate == null || !autoUpdate.isBoolean()) {
            respondJson(exchange, 400, errorBody("invalid_request",
                "name and boolean auto_update are required"));
            return;
        }
        try {
            mp.setMarketplaceAutoUpdate(name, autoUpdate.asBoolean());
            respondJson(exchange, 200, serializeSnapshot());
        } catch (RuntimeException failure) {
            respondFailure(exchange, failure);
        }
    }

    void handleInstall(HttpExchange exchange) throws IOException {
        PluginMarketplacePort mp = requireMarketplace(exchange);
        if (mp == null) return;
        JsonNode body = readRequestJson(exchange);
        if (body == null) return;
        String pluginName = body.path("plugin_name").asText("").trim();
        String marketplaceName = body.path("marketplace_name").asText("").trim();
        PluginMarketplacePort.Scope scope = parseScopeOrNull(body.path("scope").asText("user"));
        if (StringUtils.isBlank(pluginName) || StringUtils.isBlank(marketplaceName) || scope == null) {
            respondJson(exchange, 400, errorBody("invalid_request",
                "plugin_name, marketplace_name, and valid scope (user|project|local) are required"));
            return;
        }
        try {
            mp.install(pluginName, marketplaceName, scope);
            port.refreshRuntime();
            respondJson(exchange, 200, serializeSnapshot());
        } catch (RuntimeException failure) {
            respondFailure(exchange, failure);
        }
    }

    void handleToggle(HttpExchange exchange) throws IOException {
        PluginMarketplacePort mp = requireMarketplace(exchange);
        if (mp == null) return;
        JsonNode body = readRequestJson(exchange);
        if (body == null) return;
        String pluginId = body.path("plugin_id").asText("").trim();
        JsonNode enabledNode = body.get("enabled");
        PluginMarketplacePort.Scope scope = parseScopeOrNull(body.path("scope").asText("user"));
        if (StringUtils.isBlank(pluginId) || enabledNode == null || !enabledNode.isBoolean() || scope == null) {
            respondJson(exchange, 400, errorBody("invalid_request",
                "plugin_id, boolean enabled, and valid scope (user|project|local) are required"));
            return;
        }
        try {
            if (enabledNode.asBoolean()) {
                mp.enable(pluginId, scope);
            } else {
                mp.disable(pluginId, scope);
            }
            port.refreshRuntime();
            respondJson(exchange, 200, serializeSnapshot());
        } catch (RuntimeException failure) {
            respondFailure(exchange, failure);
        }
    }

    void handleUpdatePlugin(HttpExchange exchange) throws IOException {
        PluginMarketplacePort mp = requireMarketplace(exchange);
        if (mp == null) return;
        JsonNode body = readRequestJson(exchange);
        if (body == null) return;
        String pluginId = body.path("plugin_id").asText("").trim();
        PluginMarketplacePort.Scope scope = parseScopeOrNull(body.path("scope").asText("user"));
        if (StringUtils.isBlank(pluginId) || scope == null) {
            respondJson(exchange, 400, errorBody("invalid_request",
                "plugin_id and valid scope (user|project|local) are required"));
            return;
        }
        try {
            mp.updatePlugin(pluginId, scope);
            port.refreshRuntime();
            respondJson(exchange, 200, serializeSnapshot());
        } catch (RuntimeException failure) {
            respondFailure(exchange, failure);
        }
    }

    void handleUninstall(HttpExchange exchange) throws IOException {
        PluginMarketplacePort mp = requireMarketplace(exchange);
        if (mp == null) return;
        JsonNode body = readRequestJson(exchange);
        if (body == null) return;
        String pluginId = body.path("plugin_id").asText("").trim();
        PluginMarketplacePort.Scope scope = parseScopeOrNull(body.path("scope").asText("user"));
        boolean deleteData = body.path("delete_data").asBoolean(false);
        if (StringUtils.isBlank(pluginId) || scope == null) {
            respondJson(exchange, 400, errorBody("invalid_request",
                "plugin_id and valid scope (user|project|local) are required"));
            return;
        }
        try {
            mp.uninstall(pluginId, scope, deleteData);
            port.refreshRuntime();
            respondJson(exchange, 200, serializeSnapshot());
        } catch (RuntimeException failure) {
            respondFailure(exchange, failure);
        }
    }

    void handleSaveOptions(HttpExchange exchange) throws IOException {
        PluginMarketplacePort mp = requireMarketplace(exchange);
        if (mp == null) return;
        JsonNode body = readRequestJson(exchange);
        if (body == null) return;
        String pluginId = body.path("plugin_id").asText("").trim();
        JsonNode valuesNode = body.get("values");
        if (StringUtils.isBlank(pluginId) || valuesNode == null || !valuesNode.isObject()) {
            respondJson(exchange, 400, errorBody("invalid_request",
                "plugin_id and object values are required"));
            return;
        }
        PluginMarketplacePort.InstalledPlugin target = mp.installedPlugins().stream()
            .filter(item -> Strings.CS.equals(pluginId, item.pluginId()))
            .findFirst()
            .orElse(null);
        if (target == null) {
            respondJson(exchange, 404, errorBody("not_found", "Installed plugin not found: " + pluginId));
            return;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        valuesNode.properties().forEach(entry -> {
            JsonNode v = entry.getValue();
            if (v.isBoolean()) values.put(entry.getKey(), v.asBoolean());
            else if (v.isNumber()) values.put(entry.getKey(), v.numberValue());
            else if (v.isTextual()) values.put(entry.getKey(), v.asText());
        });
        try {
            mp.saveOptions(pluginId, values, target.userConfig());
            port.refreshRuntime();
            respondJson(exchange, 200, serializeSnapshot());
        } catch (RuntimeException failure) {
            respondFailure(exchange, failure);
        }
    }

    private PluginMarketplacePort requireMarketplace(HttpExchange exchange) throws IOException {
        PluginMarketplacePort mp = port.marketplace();
        if (mp == null) {
            respondJson(exchange, 404, errorBody("not_found",
                "Plugin marketplace is not configured on this gateway"));
            return null;
        }
        return mp;
    }

    private static void respondFailure(HttpExchange exchange, RuntimeException failure) throws IOException {
        String message = StringUtils.defaultIfBlank(failure.getMessage(), failure.getClass().getSimpleName());
        int status = failure instanceof IllegalArgumentException ? 400 : 409;
        respondJson(exchange, status, errorBody("plugin_error", message));
    }

    private static PluginMarketplacePort.Scope parseScopeOrNull(String raw) {
        if (StringUtils.isBlank(raw)) return PluginMarketplacePort.Scope.USER;
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "user" -> PluginMarketplacePort.Scope.USER;
            case "project" -> PluginMarketplacePort.Scope.PROJECT;
            case "local" -> PluginMarketplacePort.Scope.LOCAL;
            default -> null;
        };
    }

    private ObjectNode serializeSnapshot() {
        ObjectNode root = JsonUtils.getMapper().createObjectNode();
        PluginMarketplacePort mp = port.marketplace();
        root.put("supported", mp != null);

        GatewayPluginsPort.RuntimeSummary summary = port.summary();
        ObjectNode summaryNode = root.putObject("summary");
        summaryNode.put("command_count", summary.commandCount());
        summaryNode.put("agent_count", summary.agentCount());
        summaryNode.put("skill_count", summary.skillCount());
        summaryNode.put("mcp_count", summary.mcpCount());
        summaryNode.put("error_count", summary.errorCount());

        ArrayNode installedArr = root.putArray("installed");
        ArrayNode marketplacesArr = root.putArray("marketplaces");
        ArrayNode errorsArr = root.putArray("errors");
        if (mp == null) {
            return root;
        }

        Map<String, Map<String, Object>> optionsByPlugin = port.allPluginOptions();
        for (PluginMarketplacePort.InstalledPlugin item : mp.installedPlugins()) {
            ObjectNode pNode = installedArr.addObject();
            pNode.put("plugin_id", item.pluginId());
            pNode.put("name", PluginMarketplacePort.pluginName(item.pluginId()));
            String mkt = PluginMarketplacePort.pluginMarketplace(item.pluginId());
            pNode.put("marketplace", mkt == null ? "" : mkt);
            pNode.put("enabled", Boolean.TRUE.equals(item.enabled()));
            pNode.put("description", StringUtils.defaultString(item.description()));
            pNode.put("version", StringUtils.defaultString(item.version()));
            pNode.put("author", StringUtils.defaultString(item.author()));
            pNode.put("homepage", StringUtils.defaultString(item.homepage()));
            pNode.put("repository", StringUtils.defaultString(item.repository()));
            ArrayNode installsNode = pNode.putArray("installations");
            for (PluginMarketplacePort.Installation inst : item.installations()) {
                ObjectNode iNode = installsNode.addObject();
                iNode.put("scope", inst.scope().wire());
                iNode.put("project_path", StringUtils.defaultString(inst.projectPath()));
                iNode.put("install_path", inst.installPath() == null ? "" : inst.installPath().toString());
                iNode.put("version", StringUtils.defaultString(inst.version()));
            }
            ObjectNode schemaNode = pNode.putObject("user_config");
            if (item.userConfig() != null) {
                item.userConfig().forEach((key, opt) -> {
                    ObjectNode oNode = schemaNode.putObject(key);
                    oNode.put("type", StringUtils.defaultString(opt.type(), "string"));
                    oNode.put("title", StringUtils.defaultString(opt.title(), key));
                    oNode.put("description", StringUtils.defaultString(opt.description()));
                    oNode.put("required", Boolean.TRUE.equals(opt.required()));
                    oNode.put("sensitive", Boolean.TRUE.equals(opt.sensitive()));
                    if (opt.defaultValue() != null) {
                        oNode.set("default", JsonUtils.getMapper().valueToTree(opt.defaultValue()));
                    }
                });
            }
            ObjectNode valuesNode = pNode.putObject("options");
            Map<String, Object> existing = optionsByPlugin.getOrDefault(item.pluginId(), Map.of());
            existing.forEach((key, val) -> {
                PluginMarketplacePort.ConfigOption optSchema =
                    item.userConfig() != null ? item.userConfig().get(key) : null;
                if (optSchema != null && Boolean.TRUE.equals(optSchema.sensitive())) {
                    valuesNode.put(key, val != null && !String.valueOf(val).isEmpty() ? "••••••••" : "");
                } else if (val != null) {
                    valuesNode.set(key, JsonUtils.getMapper().valueToTree(val));
                }
            });
        }

        Map<String, List<PluginMarketplacePort.PluginEntry>> catalog = port.catalogByMarketplace();
        mp.marketplaces().forEach((name, mkt) -> {
            ObjectNode mNode = marketplacesArr.addObject();
            mNode.put("name", mkt.name());
            mNode.put("source", StringUtils.defaultString(mkt.source()));
            mNode.put("install_location", StringUtils.defaultString(mkt.installLocation()));
            mNode.put("last_updated", StringUtils.defaultString(mkt.lastUpdated()));
            mNode.put("auto_update", Boolean.TRUE.equals(mkt.autoUpdate()));
            ArrayNode pluginsNode = mNode.putArray("plugins");
            for (PluginMarketplacePort.PluginEntry entry : catalog.getOrDefault(name, List.of())) {
                ObjectNode eNode = pluginsNode.addObject();
                eNode.put("name", entry.name());
                eNode.put("plugin_id", entry.name() + "@" + mkt.name());
                eNode.put("marketplace", mkt.name());
                eNode.put("description", StringUtils.defaultString(entry.description()));
                eNode.put("version", StringUtils.defaultString(entry.version()));
                eNode.put("author", entry.author() != null ? StringUtils.defaultString(entry.author().name()) : "");
                eNode.put("category", StringUtils.defaultString(entry.category()));
                eNode.put("homepage", StringUtils.defaultString(entry.homepage()));
                eNode.put("repository", StringUtils.defaultString(entry.repository()));
                ArrayNode tagsNode = eNode.putArray("tags");
                if (entry.tags() != null) {
                    entry.tags().forEach(tagsNode::add);
                }
            }
        });

        for (PluginMarketplacePort.ErrorView err : mp.errors()) {
            ObjectNode errNode = errorsArr.addObject();
            errNode.put("source", StringUtils.defaultString(err.source()));
            errNode.put("message", StringUtils.defaultString(err.message()));
            errNode.put("guidance", StringUtils.defaultString(err.guidance()));
            errNode.put("action", err.action() != null ? err.action().name() : "NONE");
            errNode.put("target", StringUtils.defaultString(err.target()));
        }

        return root;
    }
}
