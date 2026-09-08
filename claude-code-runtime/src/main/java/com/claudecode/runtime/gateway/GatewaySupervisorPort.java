package com.claudecode.runtime.gateway;

import com.claudecode.core.annotation.Explanation;

/**
 * Application boundary for starting the in-process web gateway from the TUI.
 */
@Explanation("Lets the TUI start the third-endpoint gateway on demand")
public interface GatewaySupervisorPort {

    /** The result of starting — or the already-running state of — the gateway. */
    record Started(String url) {}

    /**
     * Starts the gateway if it is not running, binding an auto-assigned
     * loopback port. The returned URL embeds the per-launch token.
     */
    Started start();
}
