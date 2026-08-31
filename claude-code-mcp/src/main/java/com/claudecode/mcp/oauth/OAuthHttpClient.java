package com.claudecode.mcp.oauth;

import com.claudecode.http.SharedHttpClient;
import okhttp3.OkHttpClient;

import java.time.Duration;

/**
 * Shared OkHttp profile for MCP OAuth metadata, registration, and token calls.
 *
 * <ul>
 *   <li>applies a fresh
 *       30-second deadline to each OAuth request.</li>
 *   <li>supplies
 *       redirect-capable, fast-fallback HTTP transport for metadata discovery.</li>
 * </ul>
 */
final class OAuthHttpClient {

    private static final OkHttpClient SHARED = SharedHttpClient.shared().newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .readTimeout(Duration.ofSeconds(30))
        .writeTimeout(Duration.ofSeconds(30))
        .callTimeout(Duration.ofSeconds(30))
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(false)
        .build();

    private OAuthHttpClient() {}

    static OkHttpClient shared() {
        return SHARED;
    }
}
