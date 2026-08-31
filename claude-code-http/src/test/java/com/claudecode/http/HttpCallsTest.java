package com.claudecode.http;

import okhttp3.MediaType;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpCallsTest {

    @Test
    void appliesPerCallDeadlineWithoutCloningTheClient() throws Exception {
        AtomicLong timeoutNanos = new AtomicLong();
        OkHttpClient client = SharedHttpClient.shared().newBuilder()
            .addInterceptor(chain -> {
                timeoutNanos.set(chain.call().timeout().timeoutNanos());
                return new Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("fixture")
                    .body(ResponseBody.create("ok", MediaType.get("text/plain")))
                    .build();
            })
            .build();

        try (Response response = HttpCalls.execute(
                client, new Request.Builder().url("https://example.com").build(),
                Duration.ofSeconds(7))) {
            assertEquals(200, response.code());
        }

        assertEquals(Duration.ofSeconds(7).toNanos(), timeoutNanos.get());
    }

    @Test
    void cancellationStaysRegisteredUntilTheResponseIsClosed() throws Exception {
        AtomicReference<Runnable> cancelAction = new AtomicReference<>();
        AtomicBoolean registrationClosed = new AtomicBoolean();
        CancellationRegistrar cancellation = action -> {
            cancelAction.set(action);
            return () -> registrationClosed.set(true);
        };
        AtomicReference<Call> call = new AtomicReference<>();
        OkHttpClient client = SharedHttpClient.shared().newBuilder()
            .addInterceptor(chain -> {
                call.set(chain.call());
                return new Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("fixture")
                    .body(ResponseBody.create("ok", MediaType.get("text/plain")))
                    .build();
            })
            .build();

        Response response = HttpCalls.execute(
            client,
            new Request.Builder().url("https://example.com").build(),
            Duration.ofSeconds(7),
            cancellation);

        cancelAction.get().run();
        assertTrue(call.get().isCanceled());
        assertFalse(registrationClosed.get());
        response.close();

        assertTrue(registrationClosed.get());
        assertFalse(response.body().source().isOpen());
    }

    @Test
    void consumingTheWholeBodyAlsoReleasesTheCancellationRegistration() throws Exception {
        AtomicBoolean registrationClosed = new AtomicBoolean();
        CancellationRegistrar cancellation = _ ->
            () -> registrationClosed.set(true);
        OkHttpClient client = SharedHttpClient.shared().newBuilder()
            .addInterceptor(chain -> new Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("fixture")
                .body(ResponseBody.create("ok", MediaType.get("text/plain")))
                .build())
            .build();

        try (Response response = HttpCalls.execute(
                client,
                new Request.Builder().url("https://example.com").build(),
                Duration.ofSeconds(7),
                cancellation)) {
            assertEquals("ok", response.body().string());
            assertTrue(registrationClosed.get());
        }
    }
}
