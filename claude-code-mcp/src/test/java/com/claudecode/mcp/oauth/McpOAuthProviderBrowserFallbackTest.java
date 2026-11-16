package com.claudecode.mcp.oauth;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pins the browser-fallback command matrix used when java.awt.Desktop cannot
 * open the OAuth URL. Windows previously fell through to xdg-open, which does
 * not exist there, so the OAuth flow degraded to "open the URL manually" on
 * every headless Windows JVM.
 */
class McpOAuthProviderBrowserFallbackTest {

    private static final String URL = "https://claude.ai/oauth/authorize";

    @Test
    void windowsOpensThroughRundll32() {
        assertEquals(List.of("rundll32", "url,OpenURL", URL),
            McpOAuthProvider.fallbackOpenCommand("Windows 11", null, URL));
    }

    @Test
    void browserOverrideIsHonoredAndQuotedOnWindows() {
        assertEquals(List.of("C:\\Program Files\\Mozilla Firefox\\firefox.exe", "\"" + URL + "\""),
            McpOAuthProvider.fallbackOpenCommand(
                "Windows 11", "C:\\Program Files\\Mozilla Firefox\\firefox.exe", URL));
    }

    @Test
    void browserOverrideOnPosixPassesTheUrlVerbatim() {
        assertEquals(List.of("firefox", URL),
            McpOAuthProvider.fallbackOpenCommand("Linux", "firefox", URL));
    }

    @Test
    void macOsOpensThroughOpen() {
        assertEquals(List.of("open", URL),
            McpOAuthProvider.fallbackOpenCommand("Mac OS X", null, URL));
    }

    @Test
    void otherPosixOpensThroughXdgOpen() {
        assertEquals(List.of("xdg-open", URL),
            McpOAuthProvider.fallbackOpenCommand("Linux", null, URL));
    }
}
