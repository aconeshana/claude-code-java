package com.claudecode.core.metrics;

import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

/**
 * Per-invocation collector bound via {@link ScopedValue} (and propagated onto HTTP requests
 * via request tags) to capture sanitized 4-protocol wire options, adaptive protocol/effort/format
 * fallbacks, and HTTP retry telemetry without mutating request DTO signatures.
 */
@Explanation("Collects 4-protocol wire options and retry/fallback traces for WebUI Trajectory")
public final class LlmRequestTraceCollector {

    private static final ScopedValue<LlmRequestTraceCollector> CURRENT = ScopedValue.newInstance();

    private final long startedAt;
    private String configuredProtocol;
    private String effectiveProtocol;
    private String providerLabel;
    private String requestedModel;
    private ObjectNode latestOptions;
    private final List<ObjectNode> attempts = new ArrayList<>();
    private int retry;
    private int maxRetries;
    private long retryDelayMs;

    public LlmRequestTraceCollector() {
        this(System.currentTimeMillis());
    }

    public LlmRequestTraceCollector(long startedAt) {
        this.startedAt = startedAt;
    }

    /** Binds {@code collector} for the synchronous call scope on the current virtual/platform thread. */
    public static <T> T observe(LlmRequestTraceCollector collector, Supplier<T> action) {
        if (collector == null) {
            return action.get();
        }
        return ScopedValue.where(CURRENT, collector).call(action::get);
    }

    /** Returns the collector bound to the current thread, or {@code null} when untraced. */
    public static LlmRequestTraceCollector current() {
        return CURRENT.isBound() ? CURRENT.get() : null;
    }

    public synchronized void recordConfiguredRoute(String protocol, String baseUrl, String model) {
        if (StringUtils.isNotBlank(protocol) && this.configuredProtocol == null) {
            this.configuredProtocol = protocol;
        }
        if (StringUtils.isNotBlank(model) && this.requestedModel == null) {
            this.requestedModel = model;
        }
        if (StringUtils.isNotBlank(baseUrl) && this.providerLabel == null && StringUtils.isNotBlank(protocol)) {
            this.providerLabel = formatProviderLabel(protocol, baseUrl);
        }
    }

    public synchronized void recordWireDispatch(
            String protocol, String baseUrl, String model, ObjectNode sanitizedOptions) {
        if (StringUtils.isNotBlank(protocol)) {
            this.effectiveProtocol = protocol;
            if (this.configuredProtocol == null) {
                this.configuredProtocol = protocol;
            }
            this.providerLabel = formatProviderLabel(protocol, baseUrl);
        }
        if (StringUtils.isNotBlank(model)) {
            this.requestedModel = model;
        }
        if (sanitizedOptions != null) {
            this.latestOptions = sanitizedOptions.deepCopy();
        }
    }

    public synchronized void recordAdaptiveFallback(
            String kind, String fromValue, String toValue, int statusCode) {
        ObjectNode node = JsonUtils.getMapper().createObjectNode();
        node.put("kind", kind);
        if (StringUtils.isNotBlank(fromValue)) node.put("from", fromValue);
        if (StringUtils.isNotBlank(toValue)) node.put("to", toValue);
        if (statusCode > 0) node.put("status", statusCode);
        node.put("atMs", System.currentTimeMillis());
        attempts.add(node);
    }

    public synchronized void recordHttpRetry(
            int statusCode, int attempt, int maxRetriesBudget, long delayMs) {
        this.retry = Math.max(this.retry, attempt);
        this.maxRetries = Math.max(this.maxRetries, maxRetriesBudget);
        this.retryDelayMs = delayMs;
        ObjectNode node = JsonUtils.getMapper().createObjectNode();
        node.put("kind", "http_retry");
        if (statusCode > 0) node.put("status", statusCode);
        node.put("attempt", attempt);
        node.put("maxRetries", maxRetriesBudget);
        node.put("delayMs", delayMs);
        node.put("atMs", System.currentTimeMillis());
        attempts.add(node);
    }

    public synchronized LlmRequestTraceRecord toRecord(
            String sessionId,
            long turn,
            long step,
            String purpose,
            long completedAt,
            String respondedModel,
            String headerSha256,
            String status,
            String error,
            String errorCode) {
        String effProto = StringUtils.defaultIfBlank(effectiveProtocol, configuredProtocol);
        String cfgProto = StringUtils.defaultIfBlank(configuredProtocol, effProto);
        ObjectNode finalOptions = latestOptions != null
            ? latestOptions.deepCopy()
            : JsonUtils.getMapper().createObjectNode();
        if (StringUtils.isNotBlank(cfgProto) && StringUtils.isNotBlank(effProto)
                && !Strings.CS.equals(cfgProto, effProto)) {
            finalOptions.put("configured_protocol", cfgProto);
        }
        String attemptsJson = null;
        if (!attempts.isEmpty()) {
            ArrayNode arr = JsonUtils.getMapper().createArrayNode();
            attempts.forEach(arr::add);
            attemptsJson = arr.toString();
            finalOptions.set("attempts", arr.deepCopy());
        }
        String optionsJson = finalOptions.isEmpty() ? null : finalOptions.toString();
        return new LlmRequestTraceRecord(
            LlmRequestTraceRecord.SCHEMA_VERSION,
            sessionId,
            turn,
            step,
            StringUtils.defaultIfBlank(purpose, "assistant"),
            startedAt,
            Math.max(startedAt, completedAt),
            cfgProto,
            effProto,
            StringUtils.defaultIfBlank(providerLabel, effProto),
            requestedModel,
            StringUtils.defaultIfBlank(respondedModel, requestedModel),
            headerSha256,
            optionsJson,
            attemptsJson,
            retry,
            maxRetries,
            retryDelayMs,
            StringUtils.defaultIfBlank(status, "completed"),
            error,
            errorCode);
    }

    /** Formats {@code "<protocol> · <host[:port]>"} while stripping credentials, paths, and query params. */
    public static String formatProviderLabel(String protocol, String baseUrl) {
        String host = sanitizeHost(baseUrl);
        if (StringUtils.isBlank(host)) {
            return protocol;
        }
        return protocol + " \u00b7 " + host;
    }

    public static String sanitizeHost(String baseUrl) {
        if (StringUtils.isBlank(baseUrl)) {
            return null;
        }
        String candidate = baseUrl.trim();
        if (!Strings.CS.contains(candidate, "://")) {
            candidate = "https://" + candidate;
        }
        try {
            URI uri = URI.create(candidate);
            String host = uri.getHost();
            if (StringUtils.isBlank(host)) {
                return null;
            }
            int port = uri.getPort();
            return port > 0 ? host + ":" + port : host;
        } catch (IllegalArgumentException _) {
            return null;
        }
    }

    public static ObjectNode copyFields(JsonNode source, String... fieldNames) {
        ObjectNode out = JsonUtils.getMapper().createObjectNode();
        if (source == null || !source.isObject()) {
            return out;
        }
        for (String field : fieldNames) {
            JsonNode v = source.get(field);
            if (v != null && !v.isNull()) {
                out.set(field, v.deepCopy());
            }
        }
        return out;
    }
}
