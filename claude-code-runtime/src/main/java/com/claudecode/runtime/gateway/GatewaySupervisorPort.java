package com.claudecode.runtime.gateway;

import com.claudecode.core.annotation.Explanation;
import java.util.concurrent.CompletableFuture;

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

    /**
     * Non-blocking variant for startup warmup: joins the single in-flight
     * start (registering it when this call is the first) and returns its
     * future without making the caller wait for the bind. The binding work
     * runs on an implementation-owned thread — the caller only ever awaits
     * the future, never supplies the executor. The future completes
     * exceptionally when the start fails; a failed flight clears so a later
     * call retries.
     */
    @Explanation("Startup warmup shares one in-flight start across /web and background warmup")
    default CompletableFuture<Started> startAsync() {
        return CompletableFuture.supplyAsync(this::start);
    }
}
