package com.claudecode.commands.plugins;

import org.apache.commons.lang3.StringUtils;

import java.util.List;

/**
 * Session-scoped command view of the plugin runtime.
 */
public interface PluginRuntimePort {

    record Summary(int commandCount, int agentCount, int skillCount,
                   int mcpCount, int errorCount) { }

    record RefreshResult(int enabledCount, int disabledCount, int commandCount,
                         int agentCount, int hookCount, int mcpCount,
                         int lspCount, int errorCount) { }

    record Diagnostic(String source, String plugin, String message) {
        public String formatted() {
            String origin = StringUtils.isBlank(source) ? "unknown" : source;
            String owner = StringUtils.isBlank(plugin) ? "" : " [" + plugin + "]";
            return origin + owner + ": " + message;
        }
    }

    Summary summary();

    default List<Diagnostic> diagnostics() { return List.of(); }

    RefreshResult refresh();
}
