package com.claudecode.cli;

import com.claudecode.ui.lanterna.repl.LanternaProgressSink;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Strongly typed hand-off between CLI launch phases and the execution router.
 *
 * <ul>
 *   <li>carries the assembled query runtime from setup
 *       through restore and into the selected print/SDK/REPL execution path.</li>
 *   <li>receives a fully initialized session rather
 *       than constructing tools, hooks, or transcript state on demand.</li>
 *   <li>receives the same session components
 *       after non-interactive routes have been excluded.</li>
 * </ul>
 */
record CliSessionRuntime(
        CliLaunchRequest request,
        CliWorkspaceBootstrap.Workspace workspace,
        CliToolchainAssembler.Toolchain toolchain,
        CliEngineAssembler.EngineRuntime engine,
        CliSessionLifecycleView lifecycle,
        CliSessionRestoreCoordinator.Restoration restoration,
        CliOutput output,
        CliOutput errorOutput,
        AtomicReference<SdkControlBroker> sdkBrokerRef,
        LanternaProgressSink progressSink) {
}
