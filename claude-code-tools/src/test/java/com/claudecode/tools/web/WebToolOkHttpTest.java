package com.claudecode.tools.web;

import org.apache.commons.lang3.Strings;

import com.claudecode.core.engine.AbortController;
import com.claudecode.core.engine.ToolExecutionContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.claudecode.tools.ToolHttpClient;

class WebToolOkHttpTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeEach
    void clearWebFetchCache() {

        // use the same fixture URL, so isolate each interceptor assertion.
        WebFetchTool.clearWebFetchCache();
    }

    @Test
    void webBrowserUsesInjectedOkHttpClientAndPerCallDeadline() {
        AtomicReference<String> accept = new AtomicReference<>();
        AtomicLong timeoutNanos = new AtomicLong();
        OkHttpClient client = ToolHttpClient.standard().newBuilder()
            .addInterceptor(chain -> {
                accept.set(chain.request().header("Accept"));
                timeoutNanos.set(chain.call().timeout().timeoutNanos());
                return response(chain.request(), 200, "text/html", "<h1>Browser body</h1>");
            })
            .build();
        ObjectNode input = MAPPER.createObjectNode();
        input.put("url", "https://example.com/page");
        input.put("execute_js", false);
        input.put("timeout", 7);

        String result = new WebBrowserTool(client).call(input, context());

        assertEquals("<h1>Browser body</h1>", result);
        assertTrue(Strings.CS.startsWith(accept.get(), "text/html"));
        assertEquals(Duration.ofSeconds(7).toNanos(), timeoutNanos.get());
    }

    @Test
    void braveSearchUsesInjectedOkHttpClientHeadersAndThirtySecondDeadline() throws Exception {
        AtomicReference<String> token = new AtomicReference<>();
        AtomicReference<String> url = new AtomicReference<>();
        AtomicLong timeoutNanos = new AtomicLong();
        OkHttpClient client = ToolHttpClient.standard().newBuilder()
            .addInterceptor(chain -> {
                token.set(chain.request().header("X-Subscription-Token"));
                url.set(chain.request().url().toString());
                timeoutNanos.set(chain.call().timeout().timeoutNanos());
                String json = "{\"web\":{\"results\":[{\"title\":\"Result\","
                    + "\"url\":\"https://result.example/page\",\"description\":\"Snippet\"}]}}";
                return response(chain.request(), 200, "application/json", json);
            })
            .build();
        WebSearchTool tool = new WebSearchTool(null, client);
        Method search = WebSearchTool.class.getDeclaredMethod(
            "searchWithBrave", String.class, String.class, List.class);
        search.setAccessible(true);

        String result = (String) search.invoke(tool, "java okhttp", "secret", List.of());

        assertEquals("secret", token.get());
        assertTrue(Strings.CS.contains(url.get(), "q=java+okhttp"), url.get());
        assertEquals(Duration.ofSeconds(30).toNanos(), timeoutNanos.get());
        assertTrue(Strings.CS.contains(result, "[Result](https://result.example/page)"), result);
    }

    @Test
    void webFetchRejectsAResponseThatExceedsTheTenMegabyteTransportLimit() {
        AtomicInteger requests = new AtomicInteger();
        byte[] oversized = new byte[WebFetchTool.MAX_BODY_SIZE + 1];
        OkHttpClient client = ToolHttpClient.webFetch().newBuilder()
            .addInterceptor(chain -> requests.getAndIncrement() == 0
                ? response(chain.request(), 200, "application/json", "{\"can_fetch\":true}")
                : new Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("fixture")
                    .body(ResponseBody.create(oversized, MediaType.get("text/plain")))
                    .build())
            .build();
        ObjectNode input = MAPPER.createObjectNode();
        input.put("url", "https://example.com/page");
        input.put("prompt", "");

        String result = new WebFetchTool(null, client).call(input, context());

        assertTrue(Strings.CS.contains(result, "exceeds maximum size"), result);
        assertEquals(2, requests.get());
    }

    @Test
    void webFetchPropagatesToolCancellationToTheActiveOkHttpCall() {
        AbortController abort = new AbortController();
        AtomicReference<Call> activeCall = new AtomicReference<>();
        AtomicInteger requests = new AtomicInteger();
        OkHttpClient client = ToolHttpClient.webFetch().newBuilder()
            .addInterceptor(chain -> {
                if (requests.getAndIncrement() == 0) {
                    return response(chain.request(), 200, "application/json", "{\"can_fetch\":true}");
                }
                activeCall.set(chain.call());
                abort.abort("test");
                return response(chain.request(), 200, "text/plain", "body");
            })
            .build();
        ObjectNode input = MAPPER.createObjectNode();
        input.put("url", "https://example.com/page");
        input.put("prompt", "");

        new WebFetchTool(null, client).call(input, ToolExecutionContext.of(abort, "test-session"));

        assertTrue(activeCall.get().isCanceled());
    }

    private static ToolExecutionContext context() {
        return ToolExecutionContext.of(new AbortController(), "test-session");
    }

    private static Response response(Request request, int code, String contentType, String body) {
        return new Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("fixture")
            .body(ResponseBody.create(body, MediaType.get(contentType)))
            .build();
    }
}
