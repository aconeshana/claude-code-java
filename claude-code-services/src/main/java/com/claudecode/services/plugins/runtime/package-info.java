/**
 * Plugin runtime injection layer (stage-2 of the plugin subsystem): loads the
 * commands / agents / skills / hooks / MCP servers of installed-and-enabled
 * plugins into a {@link com.claudecode.services.plugins.runtime.PluginRuntimeSnapshot}
 * so the CLI can inject them into the live registries — and re-inject on
 * {@code /reload-plugins}.
 *
 * <p>Stage-1 (marketplace/install/settings base) lives in
 * {@link com.claudecode.services.plugins.marketplace}.
 */
package com.claudecode.services.plugins.runtime;
