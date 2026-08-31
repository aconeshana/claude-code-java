package com.claudecode.tools;

import com.claudecode.http.SharedHttpClient;
import okhttp3.OkHttpClient;

import java.time.Duration;

/**
 * OkHttp profiles used by built-in tools while retaining tool-specific policy.
 *
 * <ul>
 *   <li>Axios connect/request deadlines
 *       and explicit manual redirect handling used by domain-safe WebFetch.</li>
 *   <li>transport substrate
 *       beneath web-search execution; Anthropic search itself remains on the
 *       shared authenticated model client.</li>
 * </ul>
 *
 * <p>All profiles derive from {@link SharedHttpClient} so tools share the
 * application connection pool and dispatcher. Redirects remain disabled because
 * the previous JDK clients did not follow them automatically, and WebFetch must
 * inspect every redirect before deciding whether it is safe to follow.</p>
 */
public final class ToolHttpClient {

    private static final OkHttpClient STANDARD = SharedHttpClient.shared().newBuilder()
        .connectTimeout(Duration.ZERO)
        .readTimeout(Duration.ZERO)
        .writeTimeout(Duration.ZERO)
        .callTimeout(Duration.ZERO)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build();

    private static final OkHttpClient WEB_FETCH = SharedHttpClient.shared().newBuilder()
        .connectTimeout(Duration.ZERO)
        .readTimeout(Duration.ZERO)
        .writeTimeout(Duration.ZERO)
        .callTimeout(Duration.ZERO)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build();

    private ToolHttpClient() {}

    public static OkHttpClient standard() {
        return STANDARD;
    }

    public static OkHttpClient webFetch() {
        return WEB_FETCH;
    }

}
