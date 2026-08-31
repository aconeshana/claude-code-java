package com.claudecode.tools.web;

import org.apache.commons.lang3.Strings;

import com.claudecode.core.engine.AbortController;
import com.claudecode.core.engine.ToolExecutionContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.Headers;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import com.claudecode.tools.ToolHttpClient;


class WebFetchToolRedirectTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private ToolExecutionContext context;

    @BeforeEach
    void setUp() {
        WebFetchTool.clearWebFetchCache();
        context = ToolExecutionContext.of(new AbortController(), "test-session");
    }

    private static ObjectNode input(String url, String prompt) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("url", url);
        n.put("prompt", prompt == null ? "" : prompt);
        return n;
    }

    @Test
    void crossHostRedirectSurfacesRedirectDetected() {
        OkHttpClient client = fakeClient(Map.of(
            "https://a.com",
            fakeResponse(301, "https://b.com", null, "")));
        WebFetchTool tool = new WebFetchTool(null, client);

        String result = tool.call(input("https://a.com", "summarize"), context);

        assertTrue(Strings.CS.contains(result, "REDIRECT DETECTED"), result);
        assertTrue(Strings.CS.contains(result, "Original URL: https://a.com"), result);
        assertTrue(Strings.CS.contains(result, "Redirect URL: https://b.com"), result);
        assertTrue(Strings.CS.contains(result, "Status: 301 Moved Permanently"), result);
        assertTrue(Strings.CS.contains(result, "- url: \"https://b.com\""), result);
        assertTrue(Strings.CS.contains(result, "- prompt: \"summarize\""), result);
    }

    @Test
    void crossHostRedirectStatus307UsesTemporaryRedirectText() {
        OkHttpClient client = fakeClient(Map.of(
            "https://a.com",
            fakeResponse(307, "https://other.example", null, "")));
        WebFetchTool tool = new WebFetchTool(null, client);

        String result = tool.call(input("https://a.com", ""), context);

        assertTrue(Strings.CS.contains(result, "Status: 307 Temporary Redirect"), result);
    }

    @Test
    void crossHostRedirectStatus308UsesPermanentRedirectText() {
        OkHttpClient client = fakeClient(Map.of(
            "https://a.com",
            fakeResponse(308, "https://other.example", null, "")));
        WebFetchTool tool = new WebFetchTool(null, client);

        String result = tool.call(input("https://a.com", ""), context);

        assertTrue(Strings.CS.contains(result, "Status: 308 Permanent Redirect"), result);
    }

    @Test
    void crossHostRedirectDefaultStatusUsesFoundText() {
        OkHttpClient client = fakeClient(Map.of(
            "https://a.com",
            fakeResponse(302, "https://other.example", null, "")));
        WebFetchTool tool = new WebFetchTool(null, client);

        String result = tool.call(input("https://a.com", ""), context);

        assertTrue(Strings.CS.contains(result, "Status: 302 Found"), result);
    }

    @Test
    void sameHostRedirectIsFollowedTransparently() {
        OkHttpClient client = fakeClient(Map.of(
            "https://a.com", fakeResponse(301, "https://a.com/page2", null, ""),
            "https://a.com/page2", fakeResponse(200, null, "text/html", "<h1>Content</h1>")));
        WebFetchTool tool = new WebFetchTool(null, client);

        String result = tool.call(input("https://a.com", ""), context);

        assertFalse(Strings.CS.contains(result, "REDIRECT DETECTED"), result);
        assertTrue(Strings.CS.contains(result, "Content"), result);
    }

    @Test
    void relativeRedirectSameHostIsFollowed() {
        OkHttpClient client = fakeClient(Map.of(
            "https://a.com", fakeResponse(301, "/newpath", null, ""),
            "https://a.com/newpath", fakeResponse(200, null, "text/html", "<p>Hello</p>")));
        WebFetchTool tool = new WebFetchTool(null, client);

        String result = tool.call(input("https://a.com", ""), context);

        assertFalse(Strings.CS.contains(result, "REDIRECT DETECTED"), result);
        assertTrue(Strings.CS.contains(result, "Hello"), result);
    }

    @Test
    void relativeRedirectToDifferentHostSurfacesRedirectDetected() {
        OkHttpClient client = fakeClient(Map.of(
            "https://a.com",
            fakeResponse(301, "http://b.com/x", null, "")));
        WebFetchTool tool = new WebFetchTool(null, client);

        String result = tool.call(input("https://a.com", ""), context);

        assertTrue(Strings.CS.contains(result, "REDIRECT DETECTED"), result);
        assertTrue(Strings.CS.contains(result, "Redirect URL: http://b.com/x"), result);
    }

    @Test
    void sameHostMultiHopRedirectIsFollowed() {
        OkHttpClient client = fakeClient(Map.of(
            "https://a.com", fakeResponse(301, "https://a.com/b", null, ""),
            "https://a.com/b", fakeResponse(301, "https://a.com/c", null, ""),
            "https://a.com/c", fakeResponse(200, null, "text/html", "<p>Deep</p>")));
        WebFetchTool tool = new WebFetchTool(null, client);

        String result = tool.call(input("https://a.com", ""), context);

        assertFalse(Strings.CS.contains(result, "REDIRECT DETECTED"), result);
        assertTrue(Strings.CS.contains(result, "Deep"), result);
    }

    @Test
    void httpUrlIsUpgradedToHttpsBeforeFetch() {
        // Tool upgrades http:// -> https://, so the fake must key on https.
        OkHttpClient client = fakeClient(Map.of(
            "https://a.com",
            fakeResponse(200, null, "text/html", "<p>Upgraded</p>")));
        WebFetchTool tool = new WebFetchTool(null, client);

        String result = tool.call(input("http://a.com", ""), context);

        assertFalse(Strings.CS.contains(result, "REDIRECT DETECTED"), result);
        assertTrue(Strings.CS.contains(result, "Upgraded"), result);
    }

    // ---- offline OkHttp response fixture ----

    static OkHttpClient fakeClient(Map<String, FakeResponse> responses) {
        Interceptor interceptor = chain -> {
            String url = chain.request().url().toString();
            FakeResponse response;
            if (Strings.CS.equals("api.anthropic.com", chain.request().url().host())
                    && Strings.CS.startsWith(chain.request().url().encodedPath(), "/api/web/domain_info")) {
                response = domainInfoAllowResponse();
            } else {
                response = responses.get(url);
                if (response == null && Strings.CS.equals("/", chain.request().url().encodedPath())
                        && chain.request().url().query() == null) {
                    response = responses.get(url.substring(0, url.length() - 1));
                }
                if (response == null) {
                    response = fakeResponse(404, null, "text/plain", "");
                }
            }
            Headers.Builder headers = new Headers.Builder();
            if (response.location() != null) {
                headers.add("location", response.location());
            }
            if (response.contentType() != null) {
                headers.add("content-type", response.contentType());
            }
            MediaType mediaType = response.contentType() == null
                ? null : MediaType.parse(response.contentType());
            return new Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(response.statusCode())
                .message("fixture")
                .headers(headers.build())
                .body(ResponseBody.create(response.body(), mediaType))
                .build();
        };
        return ToolHttpClient.webFetch().newBuilder()
            .addInterceptor(interceptor)
            .build();
    }

    static FakeResponse fakeResponse(
            int statusCode, String location, String contentType, String body) {
        byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        return new FakeResponse(statusCode, location, contentType, bytes);
    }

    /** 200 OK with {@code can_fetch:true} for the domain-blocklist preflight. */
    static FakeResponse domainInfoAllowResponse() {
        return fakeResponse(200, null, "application/json", "{\"can_fetch\": true}");
    }

    record FakeResponse(int statusCode, String location, String contentType, byte[] body) {}
}
