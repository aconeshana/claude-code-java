package com.claudecode.mcp.oauth;

import com.claudecode.http.SharedHttpClient;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OAuthHttpClientTest {

    @Test
    void sharesApplicationTransportResources() {
        OkHttpClient base = SharedHttpClient.shared();
        OkHttpClient oauth = OAuthHttpClient.shared();

        assertSame(base.connectionPool(), oauth.connectionPool());
        assertSame(base.dispatcher(), oauth.dispatcher());
        assertTrue(oauth.fastFallback());
    }

    @Test
    void appliesFreshThirtySecondDeadlinePerCall() {
        OkHttpClient oauth = OAuthHttpClient.shared();

        assertEquals(Duration.ofSeconds(30).toMillis(), oauth.connectTimeoutMillis());
        assertEquals(Duration.ofSeconds(30).toMillis(), oauth.readTimeoutMillis());
        assertEquals(Duration.ofSeconds(30).toMillis(), oauth.writeTimeoutMillis());
        assertEquals(Duration.ofSeconds(30).toMillis(), oauth.callTimeoutMillis());
        assertTrue(oauth.followRedirects());
        assertTrue(oauth.followSslRedirects());
        assertTrue(oauth.interceptors().isEmpty());
    }
}
