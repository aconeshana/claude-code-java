package com.claudecode.services.http;

import com.claudecode.http.EnvironmentProxy;
import com.claudecode.http.SharedHttpClient;
import okhttp3.OkHttpClient;

import java.time.Duration;
import java.net.URI;
import java.util.Map;

/**
 * OkHttp profiles for service-layer hooks and plugin marketplace traffic.
 *
 * <ul>
 *   <li>bounded HTTP hook POSTs with
 *       redirects disabled so callers observe the configured endpoint result.</li>
 *   <li>bounded marketplace
 *       manifest downloads with explicit HTTP status handling.</li>
 *   <li>10-second official plugin
 *       statistics fetch; production retains normal redirect following.</li>
 *   <li>direct HTTP hooks use guarded
 *       DNS; proxy-routed hooks defer target DNS and policy to the proxy.</li>
 * </ul>
 */
public final class ServiceHttpClient {

    private static final OkHttpClient NO_REDIRECTS = SharedHttpClient.shared().newBuilder()
        .connectTimeout(Duration.ZERO)
        .readTimeout(Duration.ZERO)
        .writeTimeout(Duration.ZERO)
        .callTimeout(Duration.ZERO)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build();

    private static final OkHttpClient PLUGIN_STATISTICS = SharedHttpClient.shared().newBuilder()
        .connectTimeout(Duration.ZERO)
        .readTimeout(Duration.ZERO)
        .writeTimeout(Duration.ZERO)
        .callTimeout(Duration.ZERO)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(false)
        .build();
    private static final OkHttpClient MARKETPLACE = PLUGIN_STATISTICS;
    private static final OkHttpClient DIRECT_HOOK = NO_REDIRECTS.newBuilder()
        .dns(SsrfGuardDns.SYSTEM)
        .build();

    private ServiceHttpClient() {}

    public static OkHttpClient noRedirects() {
        return NO_REDIRECTS;
    }

    public static OkHttpClient pluginStatistics() {
        return PLUGIN_STATISTICS;
    }

    public static OkHttpClient marketplace() {
        return MARKETPLACE;
    }

    /** Chooses proxy-aware or direct-SSRF-guarded transport for one HTTP hook. */
    public static OkHttpClient forHook(String url, Map<String, String> sandboxProxyEnvironment) {
        URI uri = URI.create(url);
        if (sandboxProxyEnvironment != null && !sandboxProxyEnvironment.isEmpty()) {
            OkHttpClient.Builder builder = NO_REDIRECTS.newBuilder();
            EnvironmentProxy.from(sandboxProxyEnvironment).applyTo(builder);
            return builder.build();
        }
        return SharedHttpClient.usesEnvironmentProxy(uri) ? NO_REDIRECTS : DIRECT_HOOK;
    }
}
