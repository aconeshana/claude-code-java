package com.claudecode.cli;

import com.claudecode.core.engine.PermissionAskCallback;
import com.claudecode.core.engine.PermissionAskContext;

/**
 * {@link PermissionAskCallback} implementation for SDK control mode: instead of
 * prompting a human (TUI) or auto-denying (headless), it forwards the ask to the
 * external controller over the SDK control channel via {@link SdkControlBroker} and
 * blocks until the controller answers.
 *
 * <ul>
 *   <li>Install point matches {@code LanternaReplScreen}'s UI callback install, but only
 *       for SDK/stream-json control mode — the interactive TUI keeps its own callback.</li>
 * </ul>
 */
public final class ControlPermissionAskCallback implements PermissionAskCallback {

    private final SdkControlBroker broker;

    public ControlPermissionAskCallback(SdkControlBroker broker) {
        this.broker = broker;
    }

    @Override
    public Result ask(PermissionAskContext context) {
        return broker.askPermission(context);
    }
}
