package com.claudecode.runtime.sessionhost;

import com.claudecode.core.annotation.Explanation;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/**
 * Application boundary for configuring and controlling an IM collaboration channel from the TUI.
 */
@Explanation("Lets the TUI securely drive Feishu collaboration onboarding and Web-daemon control")
public interface CollaborationSetupPort {

    enum Mode { CREATE, BIND, RESUME }

    record Request(Mode mode, String appId, char[] appSecret) {
        public Request {
            appSecret = appSecret == null ? new char[0] : appSecret.clone();
        }

        @Override public char[] appSecret() { return appSecret.clone(); }

        /** Erases the request-owned defensive copy after the setup attempt completes. */
        public void clearAppSecret() { Arrays.fill(appSecret, '\0'); }
    }

    record Result(String channel, String message) {}

    boolean configured();

    /** Credentials exist, but the user has not yet selected the Feishu target chat. */
    default boolean setupPending() { return false; }

    /**
     * Channels configured for external collaboration (e.g. {@code ["feishu"]}),
     * even before the Web daemon attaches a sidecar to this TUI process.
     */
    default List<String> configuredChannels() { return List.of(); }

    /**
     * Whether enable/disable requests should be routed through the Web daemon
     * instead of mutating {@link SessionCollaborationController} purely locally.
     */
    default boolean remoteManaged() { return false; }

    /** Requests the Web daemon to attach CC Connector for {@code channel} to this TUI. */
    default CompletionStage<Void> attachChannel(String sessionId, String channel) {
        return CompletableFuture.completedFuture(null);
    }

    /** Requests the Web daemon to detach CC Connector from this TUI. */
    default CompletionStage<Void> detachChannel(String sessionId) {
        return CompletableFuture.completedFuture(null);
    }

    CompletionStage<Result> setup(Request request, Consumer<String> progress);

    void cancel();
}
