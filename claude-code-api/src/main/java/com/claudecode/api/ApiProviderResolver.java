package com.claudecode.api;

import com.claudecode.core.config.EnvUtils;
import com.claudecode.core.process.SubprocessEnvironment;

/**
 * Resolves which LLM provider is active from environment variables.
 */
public final class ApiProviderResolver {

    private ApiProviderResolver() {}


    public static ApiConfig.ApiProvider resolve() {
        return resolve(SubprocessEnvironment.get("CLAUDE_CODE_USE_BEDROCK"),
            SubprocessEnvironment.get("CLAUDE_CODE_USE_VERTEX"));
    }

    /**
     * Explicit-env-value overload — lets callers that already read env vars
     * through their own testable seam (e.g. {@code ConfigLoader}) reuse this
     * precedence logic without going through {@link System#getenv} again.
     */
    public static ApiConfig.ApiProvider resolve(String bedrockEnv, String vertexEnv) {
        if (EnvUtils.isEnvTruthy(bedrockEnv)) return ApiConfig.ApiProvider.BEDROCK;
        if (EnvUtils.isEnvTruthy(vertexEnv)) return ApiConfig.ApiProvider.VERTEX;
        return ApiConfig.ApiProvider.ANTHROPIC;
    }

}
