package com.claudecode.services.http;

import com.claudecode.http.SharedHttpClient;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServiceHttpClientTest {

    @Test
    void noRedirectProfileSharesTransportAndLeavesTimeoutToTheHook() {
        OkHttpClient base = SharedHttpClient.shared();
        OkHttpClient client = ServiceHttpClient.noRedirects();

        assertSame(base.connectionPool(), client.connectionPool());
        assertSame(base.dispatcher(), client.dispatcher());
        assertEquals(0, client.connectTimeoutMillis());
        assertEquals(0, client.readTimeoutMillis());
        assertEquals(0, client.writeTimeoutMillis());
        assertEquals(0, client.callTimeoutMillis());
        assertFalse(client.followRedirects());
        assertFalse(client.followSslRedirects());
    }

    @Test
    void pluginStatisticsProfileKeepsProductionRedirectBehavior() {
        OkHttpClient base = SharedHttpClient.shared();
        OkHttpClient client = ServiceHttpClient.pluginStatistics();

        assertSame(base.connectionPool(), client.connectionPool());
        assertSame(base.dispatcher(), client.dispatcher());
        assertEquals(0, client.connectTimeoutMillis());
        assertEquals(0, client.readTimeoutMillis());
        assertEquals(0, client.writeTimeoutMillis());
        assertEquals(0, client.callTimeoutMillis());
        assertTrue(client.followRedirects());
        assertTrue(client.followSslRedirects());
    }

    @Test
    void marketplaceProfileFollowsRedirectsLikeAxios() {
        OkHttpClient client = ServiceHttpClient.marketplace();

        assertTrue(client.followRedirects());
        assertTrue(client.followSslRedirects());
        assertEquals(0, client.readTimeoutMillis());
        assertEquals(0, client.callTimeoutMillis());
    }
}
