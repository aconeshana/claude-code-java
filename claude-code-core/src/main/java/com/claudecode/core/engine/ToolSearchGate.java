package com.claudecode.core.engine;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import static com.claudecode.core.config.EnvUtils.isEnvTruthy;

import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.message.ContentBlock;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.ToolReferenceBlock;
import com.claudecode.core.message.ToolResultBlock;
import com.claudecode.core.message.UserMessage;
import com.claudecode.core.model.AnthropicProviderUrls;
import com.claudecode.core.model.ModelApiProtocol;
import com.claudecode.core.process.SubprocessEnvironment;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Shared statics for the ToolSearch deferred-tool-schema mechanism, used by both
 * {@code claude-code-core} (request assembly in {@link com.claudecode.runtime.query.QueryLoop}) and
 * {@code claude-code-tools} ({@code ToolSearchTool}'s {@code isEnabled}, {@code
 * ToolRegistry}'s filtering and schema-not-sent hint) — both need the exact same
 * answers, so this lives in core (which every other module already depends on)
 * rather than being duplicated per-module and risking drift.
 *
 * <ul>
 *   <li>tool-search enablement and proxy guard.</li>
 *   <li>filtering ToolSearch/deferred schemas
 *       using the request's resolved provider configuration.</li>
 * </ul>
 */
public final class ToolSearchGate {

    private static volatile String resolvedBaseUrl;
    private static volatile Function<String, ModelApiProtocol> protocolResolver =
        _ -> ModelApiProtocol.ANTHROPIC;

    private ToolSearchGate() {}

    /**
     * Whether the mechanism is active for this process at all.
     */
    public static boolean isEnabled() {
        if (isEnvTruthy(SubprocessEnvironment.get("CLAUDE_CODE_DISABLE_EXPERIMENTAL_BETAS"))) {
            return false;
        }
        if (Strings.CI.equals("false", SubprocessEnvironment.get("ENABLE_TOOL_SEARCH"))) {
            return false;
        }
        String effectiveBaseUrl = resolvedBaseUrl;
        if (StringUtils.isBlank(effectiveBaseUrl)) {
            effectiveBaseUrl = SubprocessEnvironment.get("ANTHROPIC_BASE_URL");
        }
        return !isThirdPartyProxyDefaultDisabled(
            effectiveBaseUrl,
            SubprocessEnvironment.get("ENABLE_TOOL_SEARCH"),
            isEnvTruthy(SubprocessEnvironment.get("CLAUDE_CODE_USE_BEDROCK")),
            isEnvTruthy(SubprocessEnvironment.get("CLAUDE_CODE_USE_VERTEX")));
    }

    /**
     * Request-model-aware form. The current implementation is Anthropic's
     * {@code defer_loading}/{@code tool_reference} protocol and must not be
     * projected onto OpenAI Chat Completions or Responses requests.
     */
    @Explanation("OpenAI Chat/Responses providers use protocols that "
        + "do not implement Anthropic defer_loading/tool_reference discovery.")
    public static boolean isEnabled(String model) {
        ModelApiProtocol protocol = ModelApiProtocol.ANTHROPIC;
        if (StringUtils.isNotBlank(model)) {
            ModelApiProtocol resolved = protocolResolver.apply(model);
            if (resolved != null) protocol = resolved;
        }
        return protocol == ModelApiProtocol.ANTHROPIC && isEnabled();
    }






    public static void configureResolvedBaseUrl(String baseUrl) {
        resolvedBaseUrl = baseUrl;
    }

/** Supplies the wire protocol selected for each request model. */
    public static void configureProtocolResolver(
            Function<String, ModelApiProtocol> resolver) {
        protocolResolver = resolver != null
            ? resolver : _ -> ModelApiProtocol.ANTHROPIC;
    }


    static boolean isThirdPartyProxyDefaultDisabled(String baseUrl, String enableToolSearch,
            boolean useBedrock, boolean useVertex) {
        if (StringUtils.isNotBlank(enableToolSearch)) {
            return false;
        }
        if (useBedrock || useVertex) {
            return false;
        }
        return !AnthropicProviderUrls.isFirstPartyBaseUrl(baseUrl);
    }

    /**
     * Scans {@code messages} for {@code tool_reference} blocks inside any {@code tool_result} content.
     */
    public static Set<String> extractDiscoveredToolNames(List<Message> messages) {
        Set<String> discovered = new LinkedHashSet<>();
        for (Message m : messages) {
            if (!(m instanceof UserMessage um) || um.message() == null || um.message().blocks() == null) {
                continue;
            }
            for (ContentBlock block : um.message().blocks()) {
                if (block instanceof ToolResultBlock trb && trb.content() != null) {
                    for (ContentBlock inner : trb.content()) {
                        if (inner instanceof ToolReferenceBlock(String toolName) && toolName != null) {
                            discovered.add(toolName);
                        }
                    }
                }
            }
        }
        return discovered;
    }

}
