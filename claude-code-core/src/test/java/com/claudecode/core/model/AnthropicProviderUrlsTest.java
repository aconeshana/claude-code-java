package com.claudecode.core.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnthropicProviderUrlsTest {

    @Test
    void matchesReleased197UrlHostSemantics() {
        assertTrue(AnthropicProviderUrls.isFirstPartyBaseUrl(null));
        assertTrue(AnthropicProviderUrls.isFirstPartyBaseUrl(""));
        assertTrue(AnthropicProviderUrls.isFirstPartyBaseUrl("https://api.anthropic.com/v1"));
        assertTrue(AnthropicProviderUrls.isFirstPartyBaseUrl("https://api.anthropic.com:443/v1"));
        assertTrue(AnthropicProviderUrls.isFirstPartyBaseUrl(" HTTPS://API.ANTHROPIC.COM/v1 "));

        assertFalse(AnthropicProviderUrls.isFirstPartyBaseUrl(" "));
        assertFalse(AnthropicProviderUrls.isFirstPartyBaseUrl("https://api.anthropic.com:8443"));
        assertFalse(AnthropicProviderUrls.isFirstPartyBaseUrl("https://api-staging.anthropic.com"));
        assertFalse(AnthropicProviderUrls.isFirstPartyBaseUrl("https://proxy.example/v1"));
        assertFalse(AnthropicProviderUrls.isFirstPartyBaseUrl("not a url"));
    }
}
