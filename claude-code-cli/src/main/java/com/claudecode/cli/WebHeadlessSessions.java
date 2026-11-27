package com.claudecode.cli;

import com.claudecode.runtime.interaction.InteractionCoordinator;
import com.claudecode.runtime.query.DefaultQuerySessionFactory;
import com.claudecode.services.plugins.marketplace.InstalledPluginsStore;
import com.claudecode.services.plugins.marketplace.PluginDirectories;
import com.claudecode.services.plugins.marketplace.PluginSettingsStore;
import com.claudecode.services.plugins.runtime.PluginRuntimeLoader;
import com.claudecode.tools.mcp.ManagedMcpRuntime;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Builds the headless session factory for {@code claude web} from the same toolchain the TUI
 * uses, scoped to the directory {@code claude web} was launched from.
 *
 * <p>The bootstrap and toolchain report warnings and their own failures through a
 * {@link CliOutput}, which the caller supplies: binding one to a process-global stream is a
 * composition-root decision, and this factory is not that root.
 */
final class WebHeadlessSessions {

    /** Bounds the wait for response headers so a stalled upstream fails the turn instead of hanging it. */
    private static final Duration HEADLESS_STREAM_CONNECT_TIMEOUT = Duration.ofSeconds(60);

    private WebHeadlessSessions() {
    }

    static CliHeadlessGatewaySessions create(ManagedMcpRuntime mcpRuntime, CliResourceScope resources,
            CliOutput errorOutput) {
        return create(mcpRuntime, resources, null, errorOutput);
    }

    static CliHeadlessGatewaySessions create(ManagedMcpRuntime mcpRuntime, CliResourceScope resources,
            InteractionCoordinator interactions, CliOutput errorOutput) {
        CliLaunchRequest request = new CliLaunchRequest(null, null, null, null, null, null, null);
        CliWorkspaceBootstrap.Workspace workspace = CliWorkspaceBootstrap.bootstrap(
            request, CliWorkspaceBootstrap.parseSettingSources(null), errorOutput);
        AtomicReference<CliPluginRuntimeView> pluginRuntimeRef = new AtomicReference<>();
        CliToolchainAssembler.Toolchain toolchain = CliToolchainAssembler.assemble(
            workspace, mcpRuntime, new AtomicReference<>(), pluginRuntimeRef,
            new DefaultQuerySessionFactory(), resources, errorOutput);
        CliToolchainAssembler.startMcpConnections(workspace, mcpRuntime, toolchain.toolRegistry());
        CliPluginRuntime pluginRuntime = createAndLoadPluginRuntime(
            workspace, toolchain, mcpRuntime, resources, errorOutput);
        pluginRuntimeRef.set(pluginRuntime);
        CliHeadlessSessionFactory factory = new CliHeadlessSessionFactory(
            new LlmClientAdapter(toolchain.llmClient(), HEADLESS_STREAM_CONNECT_TIMEOUT),
            toolchain.toolRegistry(), new DefaultQuerySessionFactory(),
            toolchain.permissionGate(), workspace.launch().model(),
            toolchain.customModelCatalog(), toolchain.modelAvailability().showBuiltInModelFamilies(),
            interactions,
            toolchain.capabilityService(),
            toolchain.skillToolProvider().getSkillLoader());
        CliLanProxyAdapter lanProxy = new CliLanProxyAdapter(
            toolchain.customModelCatalog(),
            toolchain.llmClient(),
            workspace.launch().model(),
            toolchain.modelAvailability().showBuiltInModelFamilies());
        return new CliHeadlessGatewaySessions(factory, workspace.cwd(), lanProxy, pluginRuntime);
    }

    private static CliPluginRuntime createAndLoadPluginRuntime(
            CliWorkspaceBootstrap.Workspace workspace,
            CliToolchainAssembler.Toolchain toolchain,
            ManagedMcpRuntime mcpRuntime,
            CliResourceScope resources,
            CliOutput errorOutput) {
        try {
            PluginDirectories pluginDirs = PluginDirectories.standard();
            PluginRuntimeLoader loader = new PluginRuntimeLoader(
                pluginDirs,
                PluginSettingsStore.standard(workspace.cwd()),
                new InstalledPluginsStore(pluginDirs.installedPluginsFile()),
                toolchain.inlinePluginPaths(),
                toolchain.inlinePluginPathsWithoutMcp());
            CliPluginRuntime runtime = resources.own(new CliPluginRuntime(
                loader,
                workspace.cwd(),
                toolchain.skillToolProvider().getSkillLoader(),
                workspace.hookEngine(),
                mcpRuntime.clientRuntime(),
                toolchain.toolRegistry()));
            runtime.loadAndInject();
            return runtime;
        } catch (RuntimeException failure) {
            errorOutput.println("Warning: failed to load enabled plugins in web gateway: "
                + failure.getMessage());
            return null;
        }
    }
}
