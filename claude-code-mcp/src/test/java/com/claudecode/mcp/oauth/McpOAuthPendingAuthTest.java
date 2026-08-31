package com.claudecode.mcp.oauth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.claudecode.mcp.McpException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class McpOAuthPendingAuthTest {
    @Test
    void pastedCallbackIsRestrictedToTheBoundLoopbackFlowAndKeepsStateValidation() {
        LoopbackCallbackServer callback = new LoopbackCallbackServer("state-1");
        var pending = new McpOAuthProvider.PendingAuth("https://auth.example/authorize",
            callback.redirectUri(), "state-1", CompletableFuture.completedFuture(null), callback,
            OAuthHttpClient.shared());

        assertThrows(McpException.class,
            () -> pending.submitCallbackUrl(
                "http://example.com/callback?code=stolen&state=state-1"));
        pending.submitCallbackUrl(callback.redirectUri() + "?code=ok&state=state-1");
        assertEquals("ok", callback.awaitCode(Duration.ofSeconds(1)));
        callback.close();
    }
}
