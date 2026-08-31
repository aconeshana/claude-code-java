package com.claudecode.services.plugins.marketplace;

import org.apache.commons.lang3.Strings;

import java.util.List;

/**
 * Discriminated union of plugin error types with contextual data, replacing string-matching on
 * error messages.
 */
public sealed interface PluginError {

    /** Where the error originated (marketplace name, path, ...). */
    String source();

/**
     * User-facing display message — text.
     */
    String getMessage();


    default String plugin() {
        return null;
    }


    enum Component {
        COMMANDS("commands"),
        AGENTS("agents"),
        SKILLS("skills"),
        HOOKS("hooks"),
        OUTPUT_STYLES("output-styles");

        private final String wire;

        Component(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }
    }

    record PathNotFound(String source, String plugin, String path, Component component)
        implements PluginError {
        @Override
        public String getMessage() {
            return "Path not found: " + path + " (" + component.wire() + ")";
        }
    }

    record GitAuthFailed(String source, String plugin, String gitUrl, String authType)
        implements PluginError {
        @Override
        public String getMessage() {
            return "Git authentication failed (" + authType + "): " + gitUrl;
        }
    }

    record GitTimeout(String source, String plugin, String gitUrl, String operation)
        implements PluginError {
        @Override
        public String getMessage() {
            return "Git " + operation + " timeout: " + gitUrl;
        }
    }

    record NetworkError(String source, String plugin, String url, String details)
        implements PluginError {
        @Override
        public String getMessage() {
            return "Network error: " + url + (details != null ? " - " + details : "");
        }
    }

    record ManifestParseError(String source, String plugin, String manifestPath, String parseError)
        implements PluginError {
        @Override
        public String getMessage() {
            return "Manifest parse error: " + parseError;
        }
    }

    record ManifestValidationError(String source, String plugin, String manifestPath,
                                   List<String> validationErrors) implements PluginError {
        @Override
        public String getMessage() {
            return "Manifest validation failed: " + String.join(", ", validationErrors);
        }
    }

    record PluginNotFound(String source, String pluginId, String marketplace)
        implements PluginError {
        @Override
        public String getMessage() {
            return "Plugin " + pluginId + " not found in marketplace " + marketplace;
        }
    }

    record MarketplaceNotFound(String source, String marketplace,
                               List<String> availableMarketplaces) implements PluginError {
        @Override
        public String getMessage() {
            return "Marketplace " + marketplace + " not found";
        }
    }

    record MarketplaceLoadFailed(String source, String marketplace, String reason)
        implements PluginError {
        @Override
        public String getMessage() {
            return "Marketplace " + marketplace + " failed to load: " + reason;
        }
    }

    record McpConfigInvalid(String source, String plugin, String serverName, String validationError)
        implements PluginError {
        @Override
        public String getMessage() {
            return "MCP server " + serverName + " invalid: " + validationError;
        }
    }

    record McpServerSuppressedDuplicate(String source, String plugin, String serverName,
                                        String duplicateOf) implements PluginError {
        @Override
        public String getMessage() {
            String dup = Strings.CS.startsWith(duplicateOf, "plugin:")
                ? "server provided by plugin \"" + duplicateOfName() + "\""
                : "already-configured \"" + duplicateOf + "\"";
            return "MCP server \"" + serverName + "\" skipped — same command/URL as " + dup;
        }

        private String duplicateOfName() {
            String[] parts = duplicateOf.split(":", 2);
            return parts.length > 1 && !parts[1].isEmpty() ? parts[1] : "?";
        }
    }

    record LspConfigInvalid(String source, String plugin, String serverName, String validationError)
        implements PluginError {
        @Override
        public String getMessage() {
            return "Plugin \"" + plugin + "\" has invalid LSP server config for \""
                + serverName + "\": " + validationError;
        }
    }

    record HookLoadFailed(String source, String plugin, String hookPath, String reason)
        implements PluginError {
        @Override
        public String getMessage() {
            return "Hook load failed: " + reason;
        }
    }

    record ComponentLoadFailed(String source, String plugin, Component component, String path,
                               String reason) implements PluginError {
        @Override
        public String getMessage() {
            return component.wire() + " load failed from " + path + ": " + reason;
        }
    }

    record McpbDownloadFailed(String source, String plugin, String url, String reason)
        implements PluginError {
        @Override
        public String getMessage() {
            return "Failed to download MCPB from " + url + ": " + reason;
        }
    }

    record McpbExtractFailed(String source, String plugin, String mcpbPath, String reason)
        implements PluginError {
        @Override
        public String getMessage() {
            return "Failed to extract MCPB " + mcpbPath + ": " + reason;
        }
    }

    record McpbInvalidManifest(String source, String plugin, String mcpbPath,
                               String validationError) implements PluginError {
        @Override
        public String getMessage() {
            return "MCPB manifest invalid at " + mcpbPath + ": " + validationError;
        }
    }

    record LspServerStartFailed(String source, String plugin, String serverName, String reason)
        implements PluginError {
        @Override
        public String getMessage() {
            return "Plugin \"" + plugin + "\" failed to start LSP server \"" + serverName
                + "\": " + reason;
        }
    }

    record LspServerCrashed(String source, String plugin, String serverName, Integer exitCode,
                            String signal) implements PluginError {
        @Override
        public String getMessage() {
            if (signal != null) {
                return "Plugin \"" + plugin + "\" LSP server \"" + serverName
                    + "\" crashed with signal " + signal;
            }
            return "Plugin \"" + plugin + "\" LSP server \"" + serverName
                + "\" crashed with exit code " + (exitCode != null ? exitCode : "unknown");
        }
    }

    record LspRequestTimeout(String source, String plugin, String serverName, String method,
                             long timeoutMs) implements PluginError {
        @Override
        public String getMessage() {
            return "Plugin \"" + plugin + "\" LSP server \"" + serverName + "\" timed out on "
                + method + " request after " + timeoutMs + "ms";
        }
    }

    record LspRequestFailed(String source, String plugin, String serverName, String method,
                            String error) implements PluginError {
        @Override
        public String getMessage() {
            return "Plugin \"" + plugin + "\" LSP server \"" + serverName + "\" " + method
                + " request failed: " + error;
        }
    }

    record MarketplaceBlockedByPolicy(String source, String plugin, String marketplace,
                                      Boolean blockedByBlocklist, List<String> allowedSources)
        implements PluginError {
        @Override
        public String getMessage() {
            if (Boolean.TRUE.equals(blockedByBlocklist)) {
                return "Marketplace '" + marketplace + "' is blocked by enterprise policy";
            }
            return "Marketplace '" + marketplace + "' is not in the allowed marketplace list";
        }
    }

    record DependencyUnsatisfied(String source, String plugin, String dependency, String reason)
        implements PluginError {
        @Override
        public String getMessage() {
            String hint = Strings.CS.equals("not-enabled", reason)
                ? "disabled — enable it or remove the dependency"
                : "not found in any configured marketplace";
            return "Dependency \"" + dependency + "\" is " + hint;
        }
    }

    record PluginCacheMiss(String source, String plugin, String installPath)
        implements PluginError {
        @Override
        public String getMessage() {
            return "Plugin \"" + plugin + "\" not cached at " + installPath
                + " — run /plugins to refresh";
        }
    }

    record GenericError(String source, String plugin, String error) implements PluginError {
        @Override
        public String getMessage() {
            return error;
        }
    }
}
