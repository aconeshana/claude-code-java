package com.claudecode.gateway;

/**
 * Consumer-owned boundary for WebUI runtime lifecycle controls:
 * macOS keepalive (idle-sleep prevention) and in-place runtime reload/restart.
 */
public interface GatewayRuntimeControlPort {

    /** Current keepalive (prevent-sleep) state for this host process. */
    record KeepaliveState(boolean enabled, boolean supported, boolean active) {}

    /** Summary returned by an in-place runtime reload/restart. */
    record RestartResult(boolean restarted, int reloadedTasks, int reloadedModels) {}

    default KeepaliveState keepalive() {
        return new KeepaliveState(false, false, false);
    }

    default KeepaliveState setKeepalive(boolean enabled) {
        return keepalive();
    }

    default RestartResult restartRuntime() {
        return new RestartResult(false, 0, 0);
    }
}
