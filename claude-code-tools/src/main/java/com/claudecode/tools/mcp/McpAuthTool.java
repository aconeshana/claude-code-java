package com.claudecode.tools.mcp;

import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.core.engine.ToolResult;
import com.claudecode.http.SharedHttpClient;
import com.claudecode.mcp.McpServerConfig;
import com.claudecode.permissions.PermissionDecision;
import com.claudecode.permissions.ToolPermissionContext;
import com.claudecode.tools.Tool;
import com.claudecode.tools.ToolIdentity;
import com.claudecode.tools.ToolTexts;
import java.util.Map;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

/**
 * Per-server pseudo-tool for an MCP server that requires OAuth.
 */
public final class McpAuthTool extends Tool<JsonNode, String> {

    private static final MediaType FORM = MediaType.get("application/x-www-form-urlencoded");
    private final ToolIdentity identity;
    private final String serverName;
    private final McpServerConfig serverConfig;
    private final McpRuntime provider;
    private final OkHttpClient httpClient;

    /** Compatibility fixture identity; production uses the per-server constructor. */
    public McpAuthTool() {
        this(null, null, null, defaultHttpClient());
    }

    /** Constructor used by the HTTP exchange test seam. */
    McpAuthTool(OkHttpClient httpClient) {
        this(null, null, null, Objects.requireNonNull(httpClient, "httpClient"));
    }

    McpAuthTool(String serverName, McpServerConfig serverConfig, McpRuntime provider) {
        this(serverName, serverConfig, provider, defaultHttpClient());
    }

    private McpAuthTool(String serverName, McpServerConfig serverConfig,
                        McpRuntime provider, OkHttpClient httpClient) {
        this.serverName = serverName;
        this.serverConfig = serverConfig;
        this.provider = provider;
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.identity = new ToolIdentity(StringUtils.isBlank(serverName)
            ? "mcp__auth"
            : "mcp__" + serverName + "__authenticate");
    }

    private static OkHttpClient defaultHttpClient() {
        return SharedHttpClient.shared().newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .readTimeout(Duration.ofSeconds(30))
            .writeTimeout(Duration.ofSeconds(30))
            .callTimeout(Duration.ofSeconds(30))
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(false)
            .build();
    }

    @Override
    public ToolIdentity identity() {
        return identity;
    }

    @Override
    public String description() {
        if (StringUtils.isBlank(serverName)) {
            return ToolTexts.description("McpAuth", "default");
        }
        String transport = serverConfig == null || serverConfig.transportType() == null
            ? "MCP" : serverConfig.transportType();
        String location = serverConfig != null && serverConfig.url() != null
            ? transport + " at " + serverConfig.url() : transport;
        return ToolTexts.render(ToolTexts.description("McpAuth", "named"),
            Map.of("SERVER_NAME", serverName, "LOCATION", location));
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = JsonUtils.getMapper().createObjectNode();
        schema.put("type", "object");
        schema.set("properties", JsonUtils.getMapper().createObjectNode());

        // model contract therefore does not reject extension fields here.
        return schema;
    }


    @Override
    public int maxResultSizeChars() { return 10_000; }


    @Override
    public Object toAutoClassifierInput(JsonNode input) {
        return serverName == null ? "" : serverName;
    }

    @Override
    public boolean isMcp() {
        return StringUtils.isNotBlank(serverName);
    }

    @Override
    public ToolMcpInfo mcpInfo() {
        return isMcp() ? new ToolMcpInfo(serverName, "authenticate") : null;
    }

    @Override
    public PermissionDecision checkPermissions(
            JsonNode input, ToolPermissionContext context) {
        return PermissionDecision.allow();
    }

    @Override
    public String call(JsonNode input, ToolExecutionContext context) {
        if (provider == null || serverName == null || StringUtils.isBlank(serverName)) {
            return result("error", "MCP authentication tool is not bound to a server", null);
        }
        String transport = serverConfig == null || serverConfig.transportType() == null
            ? "MCP" : serverConfig.transportType();
        if (!Strings.CS.equals("sse", transport) && !Strings.CS.equals("http", transport)) {

            // non-HTTP transports; don't turn that expected capability limit
            // into a misleading generic OAuth error.
            if (Strings.CS.equals("claudeai-proxy", transport)) {
                return result("unsupported", "This is a claude.ai MCP connector. Ask the user to run /mcp and select \""
                    + serverName + "\" to authenticate.", null);
            }
            return result("unsupported", "Server \"" + serverName + "\" uses " + transport
                + " transport which does not support OAuth from this tool. Ask the user to run /mcp and authenticate manually.", null);
        }
        try {
            McpToolProvider.AuthStart auth = provider.authenticateServer(serverName);
            String message = "Ask the user to open this URL in their browser to authorize the "
                + serverName + " MCP server:\n\n" + auth.authUrl()
                + "\n\nOnce they complete the flow, the server's tools will become available automatically.";
            return result("auth_url", message, auth.authUrl());
        } catch (RuntimeException error) {
            String message = error.getMessage() == null ? error.getClass().getSimpleName()
                : error.getMessage();
            return result("error", "Failed to start OAuth flow for " + serverName + ": " + message
                + ". Ask the user to run /mcp and authenticate manually.", null);
        }
    }

    private static String result(String status, String message, String authUrl) {
        ObjectNode data = JsonUtils.getMapper().createObjectNode();
        data.put("status", status);
        data.put("message", message);
        if (authUrl != null) data.put("authUrl", authUrl);
        return data.toString();
    }


    @Override
    public ToolResult mapResult(Object rawResult, JsonNode input, ToolExecutionContext context) {
        if (!(rawResult instanceof String output)) return null;
        try {
            JsonNode data = JsonUtils.getMapper().readTree(output);
            String message = data.path("message").asText(output);
            if (Strings.CS.equals("error", data.path("status").asText())) {
                return ToolResult.error(message).withToolUseResult(data);
            }
            return ToolResult.success(message).withToolUseResult(data);
        } catch (Exception _) {
            return null;
        }
    }

    /**
     * Legacy HTTP token exchange seam retained for existing tests. Production
     * authentication uses the PKCE provider rather than caller-supplied URLs.
     */
    String exchangeCodeForToken(String tokenUrl, String code, String clientId,
                                String redirectUri, String serverId) {
        try {
            String body = "grant_type=authorization_code"
                + "&code=" + code
                + "&client_id=" + clientId
                + "&redirect_uri=" + redirectUri;
            Request request = new Request.Builder()
                .url(tokenUrl)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .post(RequestBody.create(body.getBytes(StandardCharsets.UTF_8), FORM))
                .build();
            try (Response response = httpClient.newCall(request).execute()) {
                // OkHttp may hand back a null body on some failure paths; hoist
// it once instead of dereferencing response.body twice.
                ResponseBody responseBodyObject = response.body();
                String responseBody = responseBodyObject.string();
                if (response.code() == 200) {
                    return "OAuth token obtained for server '" + serverId + "': " + responseBody;
                }
                return "Error: token exchange failed (HTTP " + response.code() + "): " + responseBody;
            }
        } catch (Exception error) {
            return "Error: token exchange failed: " + error.getMessage();
        }
    }
}
