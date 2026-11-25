package com.claudecode.cli;

import com.claudecode.runtime.interaction.InteractionCoordinator;
import com.claudecode.runtime.query.DefaultQuerySessionFactory;
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
        CliToolchainAssembler.Toolchain toolchain = CliToolchainAssembler.assemble(
            workspace, mcpRuntime, new AtomicReference<>(), new AtomicReference<>(),
            new DefaultQuerySessionFactory(), resources, errorOutput);
        CliToolchainAssembler.startMcpConnections(workspace, mcpRuntime, toolchain.toolRegistry());
        CliHeadlessSessionFactory factory = new CliHeadlessSessionFactory(
            new LlmClientAdapter(toolchain.llmClient(), HEADLESS_STREAM_CONNECT_TIMEOUT),
            toolchain.toolRegistry(), new DefaultQuerySessionFactory(),
            toolchain.permissionGate(), workspace.launch().model(), workspace.cwd(),
            toolchain.customModelCatalog(), toolchain.modelAvailability().showBuiltInModelFamilies(),
            interactions,
            toolchain.capabilityService(),
            toolchain.skillToolProvider().getSkillLoader());
        CliLanProxyAdapter lanProxy = new CliLanProxyAdapter(
            toolchain.customModelCatalog(),
            toolchain.llmClient(),
            workspace.launch().model(),
            toolchain.modelAvailability().showBuiltInModelFamilies());
        return new CliHeadlessGatewaySessions(factory, workspace.cwd(), lanProxy);
    }
}
