package com.claudecode.services.plugins.runtime;

import com.claudecode.core.annotation.Explanation;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

/**
 * Runtime specification of one plugin-declared model provider sidecar ({@code modelProviders} in
 * {@code .claude-plugin/plugin.json}).
 *
 * <p>The host process remains completely protocol- and vendor-agnostic: it spawns the declared
 * loopback HTTP sidecar, injects {@code POCO_PROVIDER_HOST}, {@code POCO_PROVIDER_PORT},
 * {@code POCO_PROVIDER_TOKEN}, and {@code POCO_PROVIDER_DATA_DIR}, discovers models from
 * {@code modelsPath}, and optionally reverse-proxies the plugin's management UI under
 * {@code /api/plugins/{pluginId}/ui/**}.
 */
@Explanation("Generic plugin model-provider sidecar descriptor with optional embedded management UI")
public record PluginModelProviderSpec(
        String pluginId,
        String pluginName,
        String providerId,
        String command,
        List<String> args,
        Map<String, String> env,
        Path workDir,
        Path dataDir,
        String protocol,
        String modelPrefix,
        String healthPath,
        String modelsPath,
        UiSpec ui) {

    /** Optional management UI served by the sidecar and reverse-proxied into WebUI. */
    public record UiSpec(String title, String path, String description, boolean settingsTab) {
        public UiSpec {
            title = title == null ? "" : title.trim();
            path = normalizePath(path, "/ui/");
            description = description == null ? "" : description.trim();
        }

        public UiSpec(String title, String path, String description) {
            this(title, path, description, false);
        }
    }

    public PluginModelProviderSpec {
        Objects.requireNonNull(pluginId, "pluginId");
        Objects.requireNonNull(pluginName, "pluginName");
        Objects.requireNonNull(providerId, "providerId");
        Objects.requireNonNull(command, "command");
        args = args == null ? List.of() : List.copyOf(args);
        env = env == null ? Map.of() : Map.copyOf(env);
        protocol = StringUtils.isBlank(protocol) ? "chat" : protocol.trim();
        healthPath = normalizePath(healthPath, "/health");
        modelsPath = normalizePath(modelsPath, "/v1/models");
    }

    /** Stable composite key uniquely identifying this provider across installed plugins. */
    public String key() {
        return pluginId + "/" + providerId;
    }

    private static String normalizePath(String raw, String fallback) {
        if (StringUtils.isBlank(raw)) {
            return fallback;
        }
        String trimmed = raw.trim();
        return Strings.CS.startsWith(trimmed, "/") ? trimmed : "/" + trimmed;
    }
}
