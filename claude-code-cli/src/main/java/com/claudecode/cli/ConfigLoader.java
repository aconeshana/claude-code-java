package com.claudecode.cli;

import org.apache.commons.lang3.StringUtils;
import com.claudecode.api.ApiConfig;
import com.claudecode.api.ApiProviderResolver;
import com.claudecode.api.LlmClient;
import com.claudecode.api.LlmClientFactory;
import com.claudecode.core.engine.StreamingClient;
import com.claudecode.core.process.SubprocessEnvironment;
import com.claudecode.services.config.GlobalConfigStore;
import com.claudecode.core.config.ApiKeyResolver;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads configuration for the CLI from environment variables and config files.
 */
public class ConfigLoader {

    private static final Logger log = LoggerFactory.getLogger(ConfigLoader.class);
    private static final String ENV_AUTH_TOKEN = "ANTHROPIC_AUTH_TOKEN";
    private static final String ENV_BASE_URL = "ANTHROPIC_BASE_URL";
    private static final String ENV_MODEL = "ANTHROPIC_MODEL";

    /**
     * Resolves the API key from (in priority order): 1.
     */
    public String resolveApiKey(String cliApiKey) {


        return ApiKeyResolver.resolve(cliApiKey, this::getStoredApiKey).orElse(null);
    }


    public String resolveAuthToken() {
        String authToken = getEnvironmentVariable(ENV_AUTH_TOKEN);
        if (StringUtils.isNotBlank(authToken)) {
            log.debug("Using auth token from {} environment variable", ENV_AUTH_TOKEN);
            return authToken.trim();
        }
        return null;
    }

    /**
     * Resolves both optional credentials together.
     */
    public Credentials resolveCredentials(String cliApiKey) {
        return new Credentials(resolveApiKey(cliApiKey), resolveAuthToken());
    }

    /** Resolved credential pair — see {@link #resolveCredentials}. */
    public record Credentials(String apiKey, String authToken) {}

    /**
     * Resolves the model from ANTHROPIC_MODEL environment variable.
     * Returns null if not set (caller should use its own default).
     */
    public String resolveModel() {
        String envModel = getEnvironmentVariable(ENV_MODEL);
        if (StringUtils.isNotBlank(envModel)) {
            log.debug("Using model from {} environment variable: {}", ENV_MODEL, envModel);
            return envModel;
        }
        return null;
    }

    /**
     * Resolves the API base URL from ANTHROPIC_BASE_URL environment variable.
     * Returns null if not set (will use default Anthropic URL).
     */
    public String resolveBaseUrl() {
        String envUrl = getEnvironmentVariable(ENV_BASE_URL);
        if (StringUtils.isNotBlank(envUrl)) {
            log.debug("Using base URL from {} environment variable: {}", ENV_BASE_URL, envUrl);
            return envUrl;
        }
        return null;
    }

    /**
     * Creates a StreamingClient from the resolved credentials, model, and optional base URL.
     */
    public StreamingClient createStreamingClient(String apiKey, String authToken, String model, String baseUrl) {
        LlmClient llmClient = LlmClientFactory.create(
            buildProviderConfig(apiKey, authToken, model, baseUrl, List.of()));
        return new LlmClientAdapter(llmClient);
    }

    /**
     * Creates a StreamingClient from an API key and model only (no auth token, default base URL).
     */
    public StreamingClient createStreamingClient(String apiKey, String model) {
        return createStreamingClient(apiKey, null, model, null);
    }

    /**
     * Creates a raw LlmClient for non-streaming API calls (e.g. permission explainer side queries).
     * {@code apiKey} and {@code authToken} are independently optional. See
     * {@link #createStreamingClient} for provider
     * selection.
     */
    public LlmClient createLlmClient(String apiKey, String authToken, String model, String baseUrl) {
        return createLlmClient(apiKey, authToken, model, baseUrl, List.of());
    }

    public LlmClient createLlmClient(String apiKey, String authToken, String model,
                                     String baseUrl, List<String> betas) {
        return LlmClientFactory.create(buildProviderConfig(apiKey, authToken, model, baseUrl, betas));
    }

    /**
     * Resolves the active provider ({@link ApiProviderResolver}) and builds the matching {@link
     * ApiConfig} branch.
     */
    private ApiConfig buildProviderConfig(String apiKey, String authToken, String model,
                                          String baseUrl, List<String> betas) {
// Routed through getEnvironmentVariable (not the no-arg resolve) so
        // ConfigLoader subclasses can inject provider env vars for testing,
        // same seam as resolveBedrockRegion/resolveVertexProjectId below.
        ApiConfig.ApiProvider provider = ApiProviderResolver.resolve(
            getEnvironmentVariable("CLAUDE_CODE_USE_BEDROCK"),
            getEnvironmentVariable("CLAUDE_CODE_USE_VERTEX"));
        return switch (provider) {
            case ANTHROPIC -> new ApiConfig(
                ApiConfig.ApiProvider.ANTHROPIC,
                new ApiConfig.AnthropicConfig(apiKey, authToken, model, baseUrl,
                    betas == null || betas.isEmpty() ? Map.of()
                        : Map.of("anthropic-beta", String.join(",", betas))),
                null, null, null);
            case BEDROCK -> {
                log.warn("Provider resolved to BEDROCK via CLAUDE_CODE_USE_BEDROCK — Java's "
                    + "Bedrock client is currently a stub; requests will not reach AWS. Real "
                    + "Bedrock support is not yet implemented.");
                yield new ApiConfig(
                    ApiConfig.ApiProvider.BEDROCK, null, null,
                    new ApiConfig.BedrockConfig(resolveBedrockRegion(), model), null);
            }
            case VERTEX -> {
                log.warn("Provider resolved to VERTEX via CLAUDE_CODE_USE_VERTEX — Java's "
                    + "Vertex client is currently a stub; requests will not reach GCP. Real "
                    + "Vertex support is not yet implemented.");
                yield new ApiConfig(
                    ApiConfig.ApiProvider.VERTEX, null, null, null,
                    new ApiConfig.VertexConfig(resolveVertexProjectId(), resolveVertexRegion(), model));
            }
            case OPENAI_COMPAT -> throw new IllegalStateException(
                "OPENAI_COMPAT is not reachable via env-var auto-detection");
        };
    }


    private String resolveBedrockRegion() {
        String region = getEnvironmentVariable("AWS_REGION");
        if (StringUtils.isNotBlank(region)) return region;
        String defaultRegion = getEnvironmentVariable("AWS_DEFAULT_REGION");
        if (StringUtils.isNotBlank(defaultRegion)) return defaultRegion;
        return "us-east-1";
    }


    private String resolveVertexProjectId() {
        for (String key : new String[]{"ANTHROPIC_VERTEX_PROJECT_ID", "GOOGLE_CLOUD_PROJECT", "GCLOUD_PROJECT"}) {
            String value = getEnvironmentVariable(key);
            if (StringUtils.isNotBlank(value)) return value;
        }
        return null;
    }


    private String resolveVertexRegion() {
        String region = getEnvironmentVariable("CLOUD_ML_REGION");
        if (StringUtils.isNotBlank(region)) return region;
        return "us-east5";
    }

    // Visible for testing
    String getEnvironmentVariable(String name) {
        return SubprocessEnvironment.get(name);
    }

    // Visible for testing
    String getStoredApiKey() {
        return GlobalConfigStore.getApiKey().orElse(null);
    }
}
