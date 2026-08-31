package com.claudecode.tools;

import com.claudecode.http.SharedHttpClient;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

class ToolHttpClientTest {

    @Test
    void standardProfileSharesTransportAndLeavesTimeoutToEachOperation() {
        OkHttpClient base = SharedHttpClient.shared();
        OkHttpClient client = ToolHttpClient.standard();

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
    void webFetchProfileUsesPerHopDeadlineAndManualRedirects() {
        OkHttpClient base = SharedHttpClient.shared();
        OkHttpClient client = ToolHttpClient.webFetch();

        assertSame(base.connectionPool(), client.connectionPool());
        assertSame(base.dispatcher(), client.dispatcher());
        assertEquals(0, client.connectTimeoutMillis());
        assertEquals(0, client.readTimeoutMillis());
        assertEquals(0, client.writeTimeoutMillis());
        assertEquals(0, client.callTimeoutMillis());
        assertFalse(client.followRedirects());
        assertFalse(client.followSslRedirects());
    }
}
