package com.claudecode.runtime.sessionhost;

import com.claudecode.core.annotation.Explanation;
import java.util.Arrays;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/**
 * Application boundary for configuring an IM collaboration channel from the TUI.
 */
@Explanation("Lets the TUI securely drive Feishu collaboration onboarding")
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

    CompletionStage<Result> setup(Request request, Consumer<String> progress);

    void cancel();
}
