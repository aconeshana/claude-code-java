package com.claudecode.api;

import com.claudecode.core.engine.ApiRetryEvents;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetryInterceptorPolicyTest {

    @Test
    void exposesTheFinalTransportAttemptStartAfterARetry() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        OkHttpClient client = new OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .addInterceptor(new RetryInterceptor(1, Duration.ZERO))
            .addInterceptor(chain -> {
                int attempt = attempts.incrementAndGet();
                if (attempt == 2) LockSupport.parkNanos(Duration.ofMillis(8).toNanos());
                return new Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(attempt == 1 ? 500 : 200)
                    .message("fixture")
                    .body(ResponseBody.create("{}", MediaType.get("application/json")))
                    .build();
            })
            .build();

        long callStart = System.currentTimeMillis();
        try (Response response = client.newCall(new Request.Builder()
                .url("https://example.com").build()).execute()) {
            ApiRequestTiming timing = response.request().tag(ApiRequestTiming.class);
            assertNotNull(timing);
            assertEquals(2, attempts.get());
            assertTrue(timing.lastAttemptStartMs() >= callStart);
            assertTrue(System.currentTimeMillis() - timing.lastAttemptStartMs() >= 5,
                "the exposed anchor must belong to the final delayed attempt");
        }
    }

    @Test
    void obeysExplicitDoNotRetryHeader() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        OkHttpClient client = fixtureClient(attempts, 500, "false");

        try (Response response = client.newCall(new Request.Builder()
                .url("https://example.com")
                .build()).execute()) {
            assertEquals(500, response.code());
        }

        assertEquals(1, attempts.get());
    }

    @Test
    void backgroundRequestsDoNotAmplify529() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        OkHttpClient client = fixtureClient(attempts, 529, null);
        Request request = new Request.Builder()
            .url("https://example.com")
            .tag(RetryRequestPolicy.class, RetryRequestPolicy.forQuerySource("generate_session_title"))
            .build();

        try (Response response = client.newCall(request).execute()) {
            assertEquals(529, response.code());
        }

        assertEquals(1, attempts.get());
    }

    @Test
    void foregroundRequestsKeepTheThreeOccurrence529Budget() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        OkHttpClient client = fixtureClient(attempts, 529, null);
        Request request = new Request.Builder()
            .url("https://example.com")
            .tag(RetryRequestPolicy.class, RetryRequestPolicy.forQuerySource("repl_main_thread"))
            .build();

        try (Response response = client.newCall(request).execute()) {
            assertEquals(529, response.code());
        }

        assertEquals(3, attempts.get());
    }

    @Test
    void publishesReleasedSideQuestionRetryStateBeforeEachDelay() {
        AtomicInteger attempts = new AtomicInteger();
        OkHttpClient client = fixtureClient(attempts, 429, null);
        List<ApiRetryEvents.Event> events = new ArrayList<>();

        ApiRetryEvents.observe(events::add, () -> {
            try (Response response = client.newCall(new Request.Builder()
                    .url("https://example.com").build()).execute()) {
                assertEquals(429, response.code());
                return null;
            } catch (Exception exception) {
                throw new AssertionError(exception);
            }
        });

        assertEquals(10, events.size());
        assertEquals(new ApiRetryEvents.Event(429, 1, 10, 0), events.getFirst());
        assertEquals(new ApiRetryEvents.Event(429, 10, 10, 0), events.getLast());
    }

    @Test
    void nonStreamingRecoveryCountsTheOriginatingStreaming529() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        OkHttpClient client = fixtureClient(attempts, 529, null);
        Request request = new Request.Builder()
            .url("https://example.com")
            .tag(RetryRequestPolicy.class, RetryRequestPolicy.forQuerySource("repl_main_thread"))
            .build();

        int status = ApiRetryContext.withInitial529Errors(1, () -> {
            try (Response response = client.newCall(request).execute()) {
                return response.code();
            } catch (Exception exception) {
                throw new AssertionError(exception);
            }
        });

        assertEquals(529, status);
        assertEquals(2, attempts.get(), "streaming 529 plus two sync 529s exhaust the released budget");
    }

    private static OkHttpClient fixtureClient(AtomicInteger attempts, int status,
                                               String shouldRetryHeader) {
        return new OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .addInterceptor(new RetryInterceptor(10, Duration.ZERO))
            .addInterceptor(chain -> {
                attempts.incrementAndGet();
                Response.Builder response = new Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(status)
                    .message("fixture")
                    .body(ResponseBody.create("{}", MediaType.get("application/json")));
                if (shouldRetryHeader != null) {
                    response.header("x-should-retry", shouldRetryHeader);
                }
                return response.build();
            })
            .build();
    }
}
