package com.claudecode.tools.web;

import org.apache.commons.lang3.Strings;

import com.claudecode.core.config.ClaudePaths;
import com.claudecode.core.config.SettingsPathResolver;

import com.claudecode.core.engine.AbortController;
import com.claudecode.core.engine.ToolExecutionContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import com.claudecode.tools.ToolHttpClient;

class WebFetchToolTest {

    @Test
    void userSettingsPathHonorsClaudeConfigDir() {
        assertEquals(ClaudePaths.SETTINGS_JSON, SettingsPathResolver.userSettingsPath());
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private ToolExecutionContext context;

    @BeforeEach
    void setUp() {
        WebFetchTool.clearWebFetchCache();
        context = ToolExecutionContext.of(new AbortController(), "test-session");
    }

    @Test
    void nameIsWebFetch() {
        assertEquals("WebFetch", new WebFetchTool().name());
    }

    @Test
    void callWithEmptyUrlReturnsError() {
        WebFetchTool tool = new WebFetchTool();
        ObjectNode input = MAPPER.createObjectNode();
        input.put("url", "");

        String result = tool.call(input, context);
        assertEquals("Error: url is required", result);
    }

    @Test
    void callWithMissingUrlReturnsError() {
        WebFetchTool tool = new WebFetchTool();
        ObjectNode input = MAPPER.createObjectNode();

        String result = tool.call(input, context);
        assertEquals("Error: url is required", result);
    }

    @Test
    void callWithNullInputReturnsTheSameMissingUrlError() {
        assertEquals("Error: url is required", new WebFetchTool().call(null, context));
    }

    @Test
    void callWithInvalidSchemeReturnsError() {
        WebFetchTool tool = new WebFetchTool();
        ObjectNode input = MAPPER.createObjectNode();
        input.put("url", "ftp://example.com");

        String result = tool.call(input, context);
        assertEquals("Error: only http and https URLs are supported", result);
    }

    @Test
    void callWithInvalidUrlReturnsError() {
        WebFetchTool tool = new WebFetchTool();
        ObjectNode input = MAPPER.createObjectNode();
        input.put("url", "not a url at all %%");

        String result = tool.call(input, context);
        assertTrue(Strings.CS.startsWith(result, "Error:"));
    }

    @Test
    void htmlToTextStripsBasicTags() {
        String html = "<html><body><h1>Hello</h1><p>World</p></body></html>";
        String text = WebFetchTool.htmlToText(html);
        assertTrue(Strings.CS.contains(text, "Hello"));
        assertTrue(Strings.CS.contains(text, "World"));
        assertFalse(Strings.CS.contains(text, "<h1>"));
        assertFalse(Strings.CS.contains(text, "<p>"));
    }

    @Test
    void htmlToTextRemovesScriptAndStyle() {
        String html = "<html><head><style>body{color:red}</style></head>"
            + "<body><script>alert('x')</script><p>Content</p></body></html>";
        String text = WebFetchTool.htmlToText(html);
        assertTrue(Strings.CS.contains(text, "Content"));
        assertFalse(Strings.CS.contains(text, "alert"));
        assertFalse(Strings.CS.contains(text, "color:red"));
    }

    @Test
    void htmlToTextDecodesEntities() {
        String html = "<p>A &amp; B &lt; C &gt; D &quot;E&quot; F&#39;s</p>";
        String text = WebFetchTool.htmlToText(html);
        assertTrue(Strings.CS.contains(text, "A & B"));
        assertTrue(Strings.CS.contains(text, "< C >"));
        assertTrue(Strings.CS.contains(text, "\"E\""));
        assertTrue(Strings.CS.contains(text, "F's"));
    }

    @Test
    void htmlToTextCollapsesWhitespace() {
        String html = "<p>Hello     World</p>";
        String text = WebFetchTool.htmlToText(html);
        assertFalse(Strings.CS.contains(text, "     "));
    }

    @Test
    void isReadOnly() {
        assertTrue(new WebFetchTool().isReadOnly());
    }

    @Test
    void isConcurrencySafe() {
        assertTrue(new WebFetchTool().isConcurrencySafe());
    }

    @Test
    void schemaHasRequiredFields() {
        WebFetchTool tool = new WebFetchTool();
        var schema = tool.inputSchema();
        assertTrue(schema.has("properties"));
        assertTrue(schema.get("properties").has("url"));
    }



    @Test
    void validateURLRejectsOverlongUrl() {
        String longUrl = "https://example.com/" + "a".repeat(2001);
        assertFalse(WebFetchTool.validateURL(longUrl));
    }

    @Test
    void validateURLRejectsEmbeddedCredentials() {
        assertFalse(WebFetchTool.validateURL("https://user:pass@example.com/page"));
        assertFalse(WebFetchTool.validateURL("https://user@example.com/page"));
    }

    @Test
    void validateURLRejectsSingleLabelHost() {
        assertFalse(WebFetchTool.validateURL("https://localhost/path"));
    }

    @Test
    void validateURLAcceptsNormalUrl() {
        assertTrue(WebFetchTool.validateURL("https://example.com/path?q=1"));
    }



    @Test
    void isPermittedRedirectAllowsWwwAddRemoveAndSameOrigin() {
        assertTrue(WebFetchTool.isPermittedRedirect("http://example.com/a", "http://www.example.com/b"));
        assertTrue(WebFetchTool.isPermittedRedirect("http://www.example.com/a", "http://example.com/b"));
        assertTrue(WebFetchTool.isPermittedRedirect("http://example.com/a", "http://example.com/b"));
    }

    @Test
    void isPermittedRedirectRejectsProtocolPortCredChanges() {
        assertFalse(WebFetchTool.isPermittedRedirect("http://example.com/a", "https://example.com/b"));
        assertFalse(WebFetchTool.isPermittedRedirect("http://example.com:8080/a", "http://example.com:9090/b"));
        assertFalse(WebFetchTool.isPermittedRedirect("http://example.com/a", "http://user@example.com/b"));
        assertFalse(WebFetchTool.isPermittedRedirect("http://example.com/a", "http://other.com/b"));
    }

    // --- Fix #3 helper: isPreapprovedUrl ---

    @Test
    void isPreapprovedUrlRecognizesPreapprovedHosts() {
        assertTrue(WebFetchTool.isPreapprovedUrl("https://github.com/anthropics"));
        assertTrue(WebFetchTool.isPreapprovedUrl("https://docs.python.org/3/library"));
        assertFalse(WebFetchTool.isPreapprovedUrl("https://github.com/foo"));
        assertFalse(WebFetchTool.isPreapprovedUrl("https://evil.example.com"));
    }

    // --- Fix #5: binary content persistence ---

    @Test
    void persistBinaryContentWritesFileAndReturnsNote() {
        WebFetchTool tool = new WebFetchTool();
        byte[] bytes = "binary-bytes".getBytes(StandardCharsets.UTF_8);
        String note = tool.persistBinaryContent(bytes, "application/pdf", bytes.length);
        assertTrue(Strings.CS.contains(note, "also saved to"), "note should mention the saved path");
        assertTrue(Strings.CS.contains(note, ".pdf"), "note should reflect the mime-derived extension");
        assertTrue(Strings.CS.contains(note, "application/pdf"), "note should include the content type");
    }

    @Test
    void repeatedFetchUsesTheTsFifteenMinuteUrlCache() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        Interceptor fixture = chain -> {
            requests.incrementAndGet();
            return new Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .header("content-type", "text/plain")
                .body(ResponseBody.create("cached page", MediaType.parse("text/plain")))
                .build();
        };
        OkHttpClient client = ToolHttpClient.webFetch().newBuilder()
            .addInterceptor(fixture).build();
        WebFetchTool tool = new WebFetchTool(null, client, () -> true);
        try {
            String url = "https://cache.example.com/page";
            ObjectNode input = MAPPER.createObjectNode().put("url", url).put("prompt", "summarize");

            String first = tool.call(input, context);
            String second = tool.call(input, context);

            assertTrue(Strings.CS.contains(first, "cached page"), first);
            assertTrue(Strings.CS.contains(second, "cached page"), second);
            assertEquals(1, requests.get(), "the second call must be served by URL_CACHE");
        } finally {
            WebFetchTool.clearWebFetchCache();
        }
    }

    @Test
    void cacheHitSkipsTheDomainPreflightLikeTs() {
        AtomicInteger domainChecks = new AtomicInteger();
        AtomicInteger pageFetches = new AtomicInteger();
        OkHttpClient client = ToolHttpClient.webFetch().newBuilder()
            .addInterceptor(chain -> {
                String requestUrl = chain.request().url().toString();
                if (Strings.CS.contains(requestUrl, "/api/web/domain_info?domain=")) {
                    domainChecks.incrementAndGet();
                    return new Response.Builder()
                        .request(chain.request()).protocol(Protocol.HTTP_1_1)
                        .code(200).message("OK")
                        .header("content-type", "application/json")
                        .body(ResponseBody.create("{\"can_fetch\":true}",
                            MediaType.parse("application/json")))
                        .build();
                }
                pageFetches.incrementAndGet();
                return new Response.Builder()
                    .request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK")
                    .header("content-type", "text/plain")
                    .body(ResponseBody.create("cached after preflight",
                        MediaType.parse("text/plain")))
                    .build();
            }).build();
        WebFetchTool tool = new WebFetchTool(null, client, () -> false);
        ObjectNode input = MAPPER.createObjectNode()
            .put("url", "https://cache-preflight.example.com/page")
            .put("prompt", "");

        tool.call(input, context);
        tool.call(input, context);

        assertEquals(1, domainChecks.get(), "URL_CACHE hit must not re-run domain_info");
        assertEquals(1, pageFetches.get(), "URL_CACHE hit must not re-fetch the page");
    }
}
