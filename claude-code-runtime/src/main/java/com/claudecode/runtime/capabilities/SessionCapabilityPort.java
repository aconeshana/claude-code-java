package com.claudecode.runtime.capabilities;

import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.engine.SessionCapabilityFilter;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Locale;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

/**
 * Application port for inspecting and toggling built-in {@code Tool}s, {@code Skill}s,
 * and {@code MCP} servers/tools with session-scoped progressive overrides and project-level
 * persistent defaults ({@code ~/.claude.json}).
 */
@Explanation("Presentation-neutral port shared by TUI commands/dialogs and WebUI DSH Context capability switches")
public interface SessionCapabilityPort extends SessionCapabilityFilter {

    enum Kind {
        TOOL("tool"),
        SKILL("skill"),
        MCP_SERVER("mcp_server"),
        MCP_TOOL("mcp_tool");

        private final String wire;

        Kind(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }

        public static Kind fromWire(String raw) {
            if (raw == null) throw new IllegalArgumentException("Missing capability kind");
            String normalized = raw.strip().toLowerCase(Locale.ROOT);
            for (Kind kind : values()) {
                if (Strings.CS.equals(kind.wire, normalized)) return kind;
            }
            throw new IllegalArgumentException("Unknown capability kind: " + raw);
        }
    }

    enum Scope {
        SESSION("session"),
        DEFAULT("default");

        private final String wire;

        Scope(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }

        public static Scope fromWire(String raw) {
            if (StringUtils.isBlank(raw)) return SESSION;
            String normalized = raw.strip().toLowerCase(Locale.ROOT);
            for (Scope scope : values()) {
                if (Strings.CS.equals(scope.wire, normalized)) return scope;
            }
            throw new IllegalArgumentException("Unknown capability scope: " + raw);
        }
    }

    enum DeliveryMode {
        PREFIX_EAGER("prefix_eager"),
        PROGRESSIVE_DEFERRED("progressive_deferred"),
        DISABLED("disabled");

        private final String wire;

        DeliveryMode(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }
    }

    record BuiltInToolItem(
            String name,
            String description,
            JsonNode inputSchema,
            int tokens,
            boolean kernel,
            boolean sessionEnabled,
            boolean defaultEnabled,
            String deliveryMode) {}

    record SkillItem(
            String name,
            String source,
            String description,
            int tokens,
            boolean sessionEnabled,
            boolean defaultEnabled,
            boolean protocolVisible) {}

    record McpToolItem(
            String serverName,
            String name,
            String fullName,
            String description,
            JsonNode inputSchema,
            int tokens,
            boolean sessionEnabled,
            boolean defaultEnabled) {}

    record McpServerItem(
            String name,
            String scope,
            String transport,
            String status,
            String instructions,
            int tokens,
            boolean manageable,
            boolean sessionEnabled,
            boolean defaultEnabled,
            List<McpToolItem> tools) {
        public McpServerItem {
            tools = tools == null ? List.of() : List.copyOf(tools);
        }
    }

    record CapabilitiesSnapshot(
            String sessionId,
            String model,
            String protocol,
            int savedTokens,
            List<BuiltInToolItem> tools,
            List<SkillItem> skills,
            List<McpServerItem> mcpServers) {
        public CapabilitiesSnapshot {
            tools = tools == null ? List.of() : List.copyOf(tools);
            skills = skills == null ? List.of() : List.copyOf(skills);
            mcpServers = mcpServers == null ? List.of() : List.copyOf(mcpServers);
        }
    }

    record ToggleRequest(
            String sessionId,
            Kind kind,
            String name,
            String serverName,
            boolean enabled,
            Scope scope) {}

    record ToggleOutcome(
            boolean changed,
            String message,
            CapabilitiesSnapshot snapshot) {}

    CapabilitiesSnapshot snapshot(String sessionId, String modelOverride);

    ToggleOutcome toggle(ToggleRequest request);

    default void bindSessionProject(String sessionId, String projectPath) {}

    default void resetSession(String sessionId) {}
}
