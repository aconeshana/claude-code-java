package com.claudecode.tools.web;

import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.http.HttpCalls;
import com.claudecode.permissions.PermissionDecision;
import com.claudecode.permissions.ToolPermissionContext;
import com.claudecode.tools.AnnotatedTool;
import com.claudecode.tools.BuiltInTool;
import com.claudecode.tools.ToolHttpClient;
import com.claudecode.tools.ToolTexts;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;


@BuiltInTool(
    name = "WebBrowser",
    readOnly = true
)
public class WebBrowserTool extends AnnotatedTool<JsonNode, String> {

    private static final JsonNode SCHEMA = buildSchema();

    private static final Set<String> ALLOWED_PROTOCOLS = Set.of("http", "https");
    private static final int DEFAULT_TIMEOUT_SECONDS = 60;
    private static final int MAX_CONTENT_SIZE = 10 * 1024 * 1024;

    private final OkHttpClient httpClient;
    private final BrowserExecutor browserExecutor;

    public WebBrowserTool() {
        this(ToolHttpClient.standard());
    }

    public WebBrowserTool(OkHttpClient httpClient) {
        this.httpClient = httpClient;
        this.browserExecutor = new SimpleBrowserExecutor(httpClient);
    }

    public WebBrowserTool(OkHttpClient httpClient, BrowserExecutor executor) {
        this.httpClient = httpClient;
        this.browserExecutor = executor;
    }

    @Override
    public String description() {
        return ToolTexts.description("WebBrowser");
    }

    @Override
    public JsonNode inputSchema() {
        return SCHEMA;
    }

    @Override
    public String call(JsonNode input, ToolExecutionContext context) {
        String url = input.has("url") ? input.get("url").asText("") : "";
        if (StringUtils.isBlank(url)) {
            return "Error: url is required";
        }

        int timeoutSeconds = input.has("timeout") ? input.get("timeout").asInt(DEFAULT_TIMEOUT_SECONDS) : DEFAULT_TIMEOUT_SECONDS;
        boolean executeJs = input.has("execute_js") && input.get("execute_js").asBoolean(true);
        List<String> selectors = extractSelectors(input);

        try {
            validateUrl(url);

            if (executeJs && browserExecutor != null) {
                return browserExecutor.browse(url, timeoutSeconds, selectors);
            }

            return fetchPage(url, timeoutSeconds);
        } catch (IllegalArgumentException e) {
            return "Error: invalid URL - " + e.getMessage();
        } catch (IOException e) {
            return "Error: failed to fetch page - " + e.getMessage();
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return "Error: browsing was interrupted";
        }
    }

    private void validateUrl(String url) {
        try {
            URI uri = URI.create(url);
            if (uri.getScheme() == null || !ALLOWED_PROTOCOLS.contains(uri.getScheme().toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("only http and https URLs are supported");
            }
        } catch (Exception _) {
            throw new IllegalArgumentException("malformed URL: " + url);
        }
    }

    private String fetchPage(String url, int timeoutSeconds) throws IOException, InterruptedException {
        Request request = new Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36")
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .get()
            .build();

        try (Response response = HttpCalls.execute(
                httpClient, request, Duration.ofSeconds(timeoutSeconds))) {
            int statusCode = response.code();
            if (statusCode >= 400) {
                return "Error: HTTP " + statusCode;
            }

            String body = response.body().string();
            if (body.length() > MAX_CONTENT_SIZE) {
                body = body.substring(0, MAX_CONTENT_SIZE) + "\n... (content truncated)";
            }
            return body;
        }
    }

    private List<String> extractSelectors(JsonNode input) {
        List<String> selectors = new ArrayList<>();
        if (input.has("selectors") && input.get("selectors").isArray()) {
            for (JsonNode node : input.get("selectors")) {
                selectors.add(node.asText());
            }
        }
        return selectors;
    }

    @Override
    public PermissionDecision checkPermissions(JsonNode input, ToolPermissionContext permCtx) {
        String url = input.has("url") ? input.get("url").asText("") : "";
        if (!url.isEmpty()) {
            String domain = extractDomain(url);
            if (isRestrictedDomain(domain)) {
                return PermissionDecision.deny();
            }
        }
        return PermissionDecision.ask();
    }

    private String extractDomain(String url) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            if (host != null && Strings.CS.startsWith(host, "www.")) {
                host = host.substring(4);
            }
            return host != null ? host : "";
        } catch (Exception _) {
            return "";
        }
    }

    private boolean isRestrictedDomain(String domain) {
        if (StringUtils.isEmpty(domain)) {
            return false;
        }
        return Set.of("chrome-extension", "moz-extension", "safari-extension")
            .contains(domain.toLowerCase(Locale.ROOT));
    }


    private static JsonNode buildSchema() {
        ObjectNode schema = mapper().createObjectNode();
        schema.put("type", "object");

        ObjectNode properties = schema.putObject("properties");

        ObjectNode urlProp = properties.putObject("url");
        urlProp.put("type", "string");
        urlProp.put("description", "The URL to browse");

        ObjectNode timeoutProp = properties.putObject("timeout");
        timeoutProp.put("type", "integer");
        timeoutProp.put("description", "Timeout in seconds (default: 60)");
        timeoutProp.put("default", 60);

        ObjectNode executeJsProp = properties.putObject("execute_js");
        executeJsProp.put("type", "boolean");
        executeJsProp.put("description", "Whether to execute JavaScript on the page");
        executeJsProp.put("default", true);

        ObjectNode selectorsProp = properties.putObject("selectors");
        selectorsProp.put("type", "array");
        selectorsProp.putObject("items").put("type", "string");
        selectorsProp.put("description", "CSS selectors to extract specific elements");

        ObjectNode waitForProp = properties.putObject("wait_for");
        waitForProp.put("type", "string");
        waitForProp.put("description", "CSS selector or XPath to wait for before returning");

        ArrayNode required = schema.putArray("required");
        required.add("url");

        return schema;
    }

    /**
     * Interface for browser execution implementations.
     */
    public interface BrowserExecutor {
        String browse(String url, int timeoutSeconds, List<String> selectors);
    }

    /**
     * Simple browser executor using HTTP fetch.
     * For JavaScript-heavy pages, use SeleniumBrowserExecutor or PlaywrightBrowserExecutor.
     */
    public static class SimpleBrowserExecutor implements BrowserExecutor {
        private final OkHttpClient httpClient;

        public SimpleBrowserExecutor(OkHttpClient httpClient) {
            this.httpClient = httpClient;
        }

        @Override
        public String browse(String url, int timeoutSeconds, List<String> selectors) {
            try {
                Request request = new Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36")
                    .get()
                    .build();

                String body;
                try (Response response = HttpCalls.execute(
                        httpClient, request, Duration.ofSeconds(timeoutSeconds))) {
                    body = response.body().string();
                }

                if (body.length() > MAX_CONTENT_SIZE) {
                    body = body.substring(0, MAX_CONTENT_SIZE) + "\n... (content truncated)";
                }

                return body;
            } catch (Exception e) {
                return "Error: " + e.getMessage();
            }
        }
    }
}
