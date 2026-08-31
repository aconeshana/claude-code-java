package com.claudecode.services.model;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.api.ApiConfig;
import com.claudecode.core.config.EnvUtils;
import com.claudecode.core.model.ModelContextWindows;
import com.claudecode.core.model.AnthropicProviderUrls;
import com.claudecode.core.process.SubprocessEnvironment;

import java.util.Locale;




public final class GoalContextWindowPolicy {

    public static final long DEFAULT_CONTEXT_WINDOW = ModelContextWindows.DEFAULT_CONTEXT_WINDOW;
    public static final long ONE_MILLION_CONTEXT_WINDOW =
        ModelContextWindows.ONE_MILLION_CONTEXT_WINDOW;

    private GoalContextWindowPolicy() {
    }

    public static long contextWindow(String model, ApiConfig.ApiProvider provider,
                                     String baseUrl) {
        return contextWindow(model, provider, baseUrl,
            EnvUtils.isEnvTruthy(
                SubprocessEnvironment.get("CLAUDE_CODE_DISABLE_1M_CONTEXT")));
    }

    static long contextWindow(String model, ApiConfig.ApiProvider provider,
                              String baseUrl, boolean oneMillionDisabled) {
        if (StringUtils.isBlank(model)) {
            return DEFAULT_CONTEXT_WINDOW;
        }
        String normalized = model.toLowerCase(Locale.ROOT);
        if (!oneMillionDisabled && Strings.CS.contains(normalized, "[1m]")) {
            return ONE_MILLION_CONTEXT_WINDOW;
        }
        if (ModelContextWindows.isGpt56(model)) {
            return ModelContextWindows.GPT_5_6_CONTEXT_WINDOW;
        }
        if (oneMillionDisabled) return DEFAULT_CONTEXT_WINDOW;

        ApiConfig.ApiProvider effectiveProvider = provider != null
            ? provider : ApiConfig.ApiProvider.ANTHROPIC;
        boolean nativeOneMillion = switch (effectiveProvider) {
            case ANTHROPIC -> AnthropicProviderUrls.isFirstPartyBaseUrl(baseUrl)
                && isDirectNativeOneMillionModel(normalized);
            case BEDROCK, VERTEX -> isGenerationFiveNativeOneMillionModel(normalized);
            case OPENAI_COMPAT -> false;
        };
        return nativeOneMillion
            ? ONE_MILLION_CONTEXT_WINDOW : DEFAULT_CONTEXT_WINDOW;
    }

    private static boolean isDirectNativeOneMillionModel(String model) {
        return isGenerationFiveNativeOneMillionModel(model)
            || Strings.CS.contains(model, "claude-opus-4-7")
            || Strings.CS.contains(model, "claude-opus-4-8")
            || Strings.CS.contains(model, "claude-fable-5")
            || Strings.CS.contains(model, "claude-mythos-5")
            || Strings.CS.contains(model, "claude-mythos-preview");
    }

    private static boolean isSonnetFive(String model) {
        return Strings.CS.contains(model, "claude-sonnet-5");
    }

    private static boolean isGenerationFiveNativeOneMillionModel(String model) {
        return isSonnetFive(model) || Strings.CS.contains(model, "claude-opus-5");
    }

}
