package com.claudecode.http;

import okhttp3.ConnectionPool;
import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Application-wide OkHttp transport substrate shared by higher-level clients.
 *
 * <ul>
 *   <li>provides the pooled, fallback-capable
 *       HTTP transport used beneath the Java Anthropic client.</li>
 *   <li>provides shared transport resources
 *       beneath retries; retry policy itself remains owned by the API module.</li>
 *   <li>applies HTTP(S)/ALL proxy environment
 *       variables and NO_PROXY bypass rules to every derived client.</li>
 * </ul>
 *
 * <p>Derived clients should use {@code shared.newBuilder} so they retain the
 * common connection pool and dispatcher while adding only their domain-specific
 * timeouts, authentication, interceptors, or event-stream behavior.</p>
 */
public final class SharedHttpClient {

    private static volatile boolean initialized;

    private static final class Holder {
        private static final Dispatcher DISPATCHER = new Dispatcher();
        private static final ConnectionPool CONNECTION_POOL =
                new ConnectionPool(5, 5, TimeUnit.MINUTES);
        private static final EnvironmentProxy ENVIRONMENT_PROXY = EnvironmentProxy.system();
        private static final OkHttpClient SHARED = buildShared();

        static {
            initialized = true;
        }
    }

    private SharedHttpClient() {}

    public static OkHttpClient shared() {
        return Holder.SHARED;
    }

    public static boolean usesEnvironmentProxy(URI uri) {
        return Holder.ENVIRONMENT_PROXY.usesProxy(uri);
    }

    /**
     * Refreshes the environment-backed proxy selector in place. The shared
     * OkHttp connection pool and all derived clients retain the selector object,
     * so existing clients observe later settings.env proxy changes as well.
     */
    public static void refreshEnvironmentProxy(Map<String, String> environment) {
        Holder.ENVIRONMENT_PROXY.refreshFrom(environment);
    }

    private static OkHttpClient buildShared() {
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
            .dispatcher(Holder.DISPATCHER)
            .connectionPool(Holder.CONNECTION_POOL)
            .fastFallback(true);
        Holder.ENVIRONMENT_PROXY.applyTo(builder);
        return builder.build();
    }

    static boolean isInitializedForTest() {
        return initialized;
    }
}
