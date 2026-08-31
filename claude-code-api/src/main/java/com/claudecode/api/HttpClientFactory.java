package com.claudecode.api;

import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.process.SubprocessEnvironment;
import com.claudecode.http.SharedHttpClient;
import okhttp3.OkHttpClient;
import org.apache.commons.lang3.StringUtils;

import java.time.Duration;

/**
 * API-specific {@link OkHttpClient} profiles for streaming/non-streaming and
 * Anthropic/OpenAI-compatible request paths.
 */
@Explanation("Applies the shared retry policy to OpenAI-compatible providers")
public final class HttpClientFactory {

    private static final Duration API_TIMEOUT = ApiTimeouts.apiTimeout();
    private static final OkHttpClient ANTHROPIC_STREAMING = streamingProfile();
    private static final OkHttpClient ANTHROPIC_NON_STREAMING = nonStreamingProfile();
    private static final OkHttpClient OPENAI_STREAMING = streamingProfile();
    private static final OkHttpClient OPENAI_NON_STREAMING = nonStreamingProfile();

    private HttpClientFactory() {}

    /** Compatibility alias for callers that inject one client explicitly. */
    public static OkHttpClient shared() {
        return ANTHROPIC_STREAMING;
    }

    public static OkHttpClient anthropicStreaming() {
        return ANTHROPIC_STREAMING;
    }

    public static OkHttpClient anthropicNonStreaming() {
        return ANTHROPIC_NON_STREAMING;
    }

    public static OkHttpClient openAiStreaming() {
        return OPENAI_STREAMING;
    }

    public static OkHttpClient openAiNonStreaming() {
        return OPENAI_NON_STREAMING;
    }

    private static OkHttpClient streamingProfile() {
        return SharedHttpClient.shared().newBuilder()
            .connectTimeout(API_TIMEOUT)
            .readTimeout(Duration.ZERO)
            .writeTimeout(API_TIMEOUT)
            .callTimeout(Duration.ZERO)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(false)
            .addInterceptor(new RetryInterceptor(resolveMaxRetries()))
            .build();
    }

    private static OkHttpClient nonStreamingProfile() {
        return SharedHttpClient.shared().newBuilder()
            .connectTimeout(API_TIMEOUT)
            .readTimeout(API_TIMEOUT)
            .writeTimeout(API_TIMEOUT)
            .callTimeout(API_TIMEOUT)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(false)
            .addInterceptor(new RetryInterceptor(resolveMaxRetries()))
            .build();
    }


    private static int resolveMaxRetries() {
        String override = SubprocessEnvironment.get("CLAUDE_CODE_MAX_RETRIES");
        if (StringUtils.isNotBlank(override)) {
            try {
                return Integer.parseInt(override.trim());
            } catch (NumberFormatException _) {
                // fall through to default
            }
        }
        return RetryInterceptor.DEFAULT_MAX_RETRIES;
    }
}
