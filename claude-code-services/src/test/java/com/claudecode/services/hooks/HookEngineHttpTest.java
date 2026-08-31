package com.claudecode.services.hooks;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;

import okio.Buffer;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HookEngineHttpTest {

    @Test
    void postsJsonWithResolvedHeadersAndHookSpecificDeadline() {
        AtomicReference<String> contentType = new AtomicReference<>();
        AtomicReference<String> customHeader = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicLong timeoutNanos = new AtomicLong();
        OkHttpClient client = new OkHttpClient.Builder()
            .followRedirects(false)
            .addInterceptor(chain -> {
                contentType.set(chain.request().header("Content-Type"));
                customHeader.set(chain.request().header("X-Hook"));
                var buffer = new Buffer();
                chain.request().body().writeTo(buffer);
                requestBody.set(buffer.readUtf8());
                timeoutNanos.set(chain.call().timeout().timeoutNanos());
                return new Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("fixture")
                    .body(ResponseBody.create(
                        "{\"decision\":\"allow\",\"additionalContext\":\"from-http\"}",
                        MediaType.get("application/json")))
                    .build();
            })
            .build();
        HttpHook hook = new HttpHook(
            "https://example.com/hook", Optional.empty(), Optional.of(7),
            Map.of("X-Hook", "configured"), List.of(), Optional.empty(), false);
        HookEngine engine = new HookEngine(HooksSettings.EMPTY, "/tmp", client);

        HookResult result = engine.executeHttpHook(hook, HookInput.forStop(false), 30);

        HookResult.Allow allow = assertInstanceOf(HookResult.Allow.class, result);
        assertEquals("from-http", allow.additionalContext().orElseThrow());
        assertEquals("application/json", contentType.get());
        assertEquals("configured", customHeader.get());
        assertEquals(Duration.ofSeconds(7).toNanos(), timeoutNanos.get());
        assertEquals(HookInput.forStop(false).toJson(), requestBody.get());
    }

    @Test
    void emptyUrlAllowlistBlocksBeforeHttpClientInvocation() {
        AtomicLong calls = new AtomicLong();
        OkHttpClient client = new OkHttpClient.Builder()
            .addInterceptor(_ -> {
                calls.incrementAndGet();
                throw new AssertionError("blocked hook must not reach the HTTP client");
            })
            .build();
        HookEngine engine = new HookEngine(HooksSettings.EMPTY, "/tmp", client);
        engine.replaceHttpHookPolicy(new HttpHookPolicy(
            List.of(), null));

        HookResult result = engine.executeHttpHook(
            new HttpHook("https://example.com/hook"), HookInput.forStop(false), 30);

        assertInstanceOf(HookResult.Skip.class, result);
        assertEquals(0, calls.get());
    }

    @Test
    void allowedUrlWildcardUsesFullStringMatching() {
        HttpHookPolicy policy = new HttpHookPolicy(
            List.of("https://*.example.com/hook/*"), null);

        assertTrue(policy.allowsUrl("https://api.example.com/hook/v1"));
        assertFalse(policy.allowsUrl("https://api.example.com/other/v1"));
        assertFalse(policy.allowsUrl("prefixhttps://api.example.com/hook/v1"));
        assertTrue(new HttpHookPolicy(null, null)
            .allowsUrl("https://anything.example"));
    }

    @Test
    void headersRemoveControlsAndIntersectEnvironmentPolicies() {
        HttpHook hook = new HttpHook(
            "https://example.com", Optional.empty(), Optional.empty(),
            Map.of("X-Test", "a\r\n\0b$MISSING"),
            List.of("MISSING", "NOT_GLOBAL"), Optional.empty(), false);

        assertEquals("ab", hook.resolvedHeaders(Set.of()).get("X-Test"));
        assertEquals(List.of("MISSING"), new HttpHookPolicy(
            null, List.of("MISSING"))
            .effectiveEnvVars(hook.allowedEnvVars()));
    }
}
