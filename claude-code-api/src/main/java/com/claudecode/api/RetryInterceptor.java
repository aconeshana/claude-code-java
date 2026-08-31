package com.claudecode.api;

import com.claudecode.core.engine.ApiRetryEvents;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * OkHttp {@link Interceptor} implementing exponential-backoff retry.
 */
public class RetryInterceptor implements Interceptor {

    private static final Logger log = LoggerFactory.getLogger(RetryInterceptor.class);


    static final long BASE_DELAY_MS = 500;

    static final long MAX_DELAY_MS = 32_000;

    static final int DEFAULT_MAX_RETRIES = 10;

    static final int MAX_529_RETRIES = 3;

    private final int maxRetries;
    private final Duration baseDelay;


    public RetryInterceptor(int maxRetries) {
        this(maxRetries, Duration.ofMillis(BASE_DELAY_MS));
    }

    RetryInterceptor(int maxRetries, Duration baseDelay) {
        this.maxRetries = maxRetries;
        this.baseDelay = baseDelay;
    }

    @Override
    public @NotNull Response intercept(@NotNull Chain chain) throws IOException {
        Request request = chain.request();
        ApiRequestTiming timing = request.tag(ApiRequestTiming.class);
        if (timing == null) {
            timing = new ApiRequestTiming();
            request = request.newBuilder().tag(ApiRequestTiming.class, timing).build();
        }
        RetryRequestPolicy policy = request.tag(RetryRequestPolicy.class);
        int attempt = 0;
        // Lifetime count of 529s seen in this call, NOT reset by intervening
        // non-529 errors — see class Javadoc.
        int count529 = ApiRetryContext.initial529Errors();
        while (true) {
            Response response;
            try {
                timing.markAttemptStarted();
                response = chain.proceed(request);
            } catch (IOException e) {
                // Connection-level failure — no Response, no status code.

                // APIConnectionError, unconditionally retryable).
                if (attempt >= maxRetries) {
                    throw e;
                }
                log.debug("Retrying API request (attempt {}/{}) after connection error: {}",
                        attempt + 1, maxRetries, e.getMessage());
                Duration delay = calculateDelay(attempt, null);
                ApiRetryEvents.emit(new ApiRetryEvents.Event(
                    0, attempt + 1, maxRetries, delay.toMillis()));
                sleepOrThrow(delay);
                attempt++;
                continue;
            }

            if (response.isSuccessful()) {
                return response;
            }

            if (response.code() == 529) {
                count529++;
                if (policy != null && !policy.retryOverload()) {
                    return response;
                }
            }
            boolean exhausted529 = count529 >= MAX_529_RETRIES;
            if (!shouldRetry(response) || attempt >= maxRetries || exhausted529) {
                return response;
            }

            Duration delay = calculateDelay(attempt, parseRetryAfterSeconds(response.header("Retry-After")));
            log.debug("Retrying API request (attempt {}/{}), delay: {}ms, status: {}",
                    attempt + 1, maxRetries, delay.toMillis(), response.code());
            ApiRetryEvents.emit(new ApiRetryEvents.Event(
                response.code(), attempt + 1, maxRetries, delay.toMillis()));
            response.close();
            sleepOrThrow(delay);
            attempt++;
        }
    }


    boolean isRetryable(int status) {
        return status == 408 || status == 409 || status == 429 || (status >= 500 && status < 600);
    }

    private boolean shouldRetry(Response response) {
        String header = response.header("x-should-retry");
        if (Strings.CI.equals("false", header)) return false;
        if (Strings.CI.equals("true", header)) return true;
        return isRetryable(response.code());
    }

    /**
     * A {@code Retry-After} response header (if present) takes precedence over the exponential formula.
     */
    Duration calculateDelay(int attempt, Long retryAfterSeconds) {
        if (retryAfterSeconds != null) {
            return Duration.ofSeconds(retryAfterSeconds);
        }
        long delayMs = Math.min(baseDelay.toMillis() * (long) Math.pow(2, attempt), MAX_DELAY_MS);
        delayMs += (long) (ThreadLocalRandom.current().nextDouble() * 0.25 * delayMs);
        return Duration.ofMillis(delayMs);
    }

    private static Long parseRetryAfterSeconds(String headerValue) {
        if (StringUtils.isBlank(headerValue)) {
            return null;
        }
        try {
            return Long.parseLong(headerValue.trim());
        } catch (NumberFormatException _) {
            return null;
        }
    }

    private static void sleepOrThrow(Duration delay) throws IOException {
        try {
            Thread.sleep(delay.toMillis());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IOException("Retry interrupted", ie);
        }
    }

}
