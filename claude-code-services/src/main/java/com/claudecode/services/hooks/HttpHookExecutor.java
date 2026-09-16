package com.claudecode.services.hooks;

import com.claudecode.http.HttpCalls;
import com.claudecode.services.http.ServiceHttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs {@code type:"http"} hooks: POSTs the hook input JSON to the configured
 * URL under the global URL/env-var policy and parses the JSON body as hook
 * output. Shared between a parent dispatcher and its child agent dispatchers.
 *
 * <ul>
 *   <li>{@code src/utils/hooks/execHttpHook.ts} — request construction, header
 *       env-var interpolation, status handling, response parsing.</li>
 *   <li>{@code src/utils/hooks/ssrfGuard.ts} — {@code allowedHttpHookUrls}
 *       enforcement (policy object lives in {@link HttpHookPolicy}).</li>
 * </ul>
 */
public final class HttpHookExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(HttpHookExecutor.class);

    private final OkHttpClient httpClient;
    /** When true, a per-URL client honoring sandbox proxy settings is built for every request. */
    private final boolean managedHttpClient;
    private final HookOutputParser parser;
    /** Effective global HTTP-hook URL/env policy; atomically replaced on settings reload. */
    private volatile HttpHookPolicy policy = HttpHookPolicy.unrestricted();
    private volatile Supplier<Map<String, String>> sandboxProxyEnvironmentSupplier = Map::of;

    HttpHookExecutor(OkHttpClient httpClient, boolean managedHttpClient, HookOutputParser parser) {
        this.httpClient = httpClient;
        this.managedHttpClient = managedHttpClient;
        this.parser = parser;
    }

    /** Replaces the effective global HTTP-hook policy without touching hook definitions. */
    public void replacePolicy(HttpHookPolicy policy) {
        this.policy = policy != null ? policy : HttpHookPolicy.unrestricted();
    }

    /**
     * Supplies the sandbox proxy environment lazily so the proxy starts only
     * when an HTTP hook actually runs.
     */
    public void setSandboxProxyEnvironmentSupplier(Supplier<Map<String, String>> supplier) {
        this.sandboxProxyEnvironmentSupplier = supplier != null ? supplier : Map::of;
    }

    HookResult execute(HttpHook cmd, HookInput input, long defaultTimeoutMillis) {
        long timeoutMillis = cmd.timeoutSeconds()
            .map(seconds -> seconds * 1000L)
            .orElse(defaultTimeoutMillis);

        HttpHookPolicy policy = this.policy;
        if (!policy.allowsUrl(cmd.url())) {
            LOG.warn("HTTP hook blocked: {} does not match any pattern in allowedHttpHookUrls",
                cmd.url());
            return HookResult.skip();
        }

        try {
            Request.Builder reqBuilder = new Request.Builder()
                .url(cmd.url())
                .header("Content-Type", "application/json")
                .post(RequestBody.create(input.toJson(), MediaType.get("application/json")));

            // Add resolved headers (with env var interpolation)
            Map<String, String> headers = cmd.resolvedHeaders(
                Set.copyOf(policy.effectiveEnvVars(cmd.allowedEnvVars())));
            headers.forEach(reqBuilder::header);

            OkHttpClient client = managedHttpClient
                ? ServiceHttpClient.forHook(cmd.url(), sandboxProxyEnvironmentSupplier.get())
                : httpClient;
            try (Response response = HttpCalls.execute(
                    client, reqBuilder.build(), Duration.ofMillis(timeoutMillis))) {
                if (response.code() >= 400) {
                    LOG.debug("HTTP hook returned status {}", response.code());
                    return HookResult.skip();
                }

                String body = response.body().string();
                if (StringUtils.isBlank(body)) {
                    return HookResult.allow();
                }
                return parser.parse(body, input.event());
            }
        } catch (Exception e) {
            LOG.debug("HTTP hook execution error: {}", e.getMessage());
            return HookResult.skip();
        }
    }
}
