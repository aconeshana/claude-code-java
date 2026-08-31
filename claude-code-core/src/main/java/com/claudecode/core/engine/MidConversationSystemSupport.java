package com.claudecode.core.engine;

import org.apache.commons.lang3.Strings;

import com.claudecode.core.config.EnvUtils;
import com.claudecode.core.model.AnthropicProviderUrls;
import com.claudecode.core.process.SubprocessEnvironment;

import java.util.Locale;
import java.util.function.Function;

/**
 * Decides whether text-only conversation attachments may be emitted as an interleaved {@code
 * role:"system"} message instead of a user {@code <system-reminder>}.
 */
public final class MidConversationSystemSupport {

    private MidConversationSystemSupport() {}

    private static volatile Function<String, String> baseUrlResolver = _ -> null;


    public static void configureBaseUrlResolver(Function<String, String> resolver) {
        baseUrlResolver = resolver != null ? resolver : _ -> null;
    }

    /** Process-environment form used by request assembly. */
    public static boolean isEnabled(String model) {
        return isEnabled(model,
            EnvUtils.isEnvTruthy(SubprocessEnvironment.get("CLAUDE_CODE_USE_BEDROCK")),
            EnvUtils.isEnvTruthy(SubprocessEnvironment.get("CLAUDE_CODE_USE_VERTEX")),
            EnvUtils.isEnvTruthy(
                SubprocessEnvironment.get("CLAUDE_CODE_FORCE_MID_CONVERSATION_SYSTEM")),
            AnthropicProviderUrls.isFirstPartyBaseUrl(baseUrlResolver.apply(model)));
    }




    static boolean isEnabled(String model, boolean bedrock, boolean vertex,
                             boolean forceEnabled, boolean firstPartyBaseUrl) {
        if (forceEnabled) return true;

        String normalized = normalize(model);
        if (isReleasedClaudeFamilyWithoutMidConversationSystem(normalized)) {
            return false;
        }
        if (Strings.CS.contains(normalized, "mid-conv-system")
                || Strings.CS.equals(normalized, "claude-mythos-5")
                || matchesFamily(normalized, "claude-opus-5")) {
            return true;
        }


        return !bedrock && !vertex && firstPartyBaseUrl;
    }

    private static boolean isReleasedClaudeFamilyWithoutMidConversationSystem(String model) {
        if (Strings.CS.startsWith(model, "claude-3-")) return true;
        return matchesFamily(model, "claude-opus-4-0")
            || matchesFamily(model, "claude-opus-4-1")
            || matchesFamily(model, "claude-opus-4-5")
            || matchesFamily(model, "claude-opus-4-6")
            || matchesFamily(model, "claude-opus-4-7")
            || matchesFamily(model, "claude-sonnet-4-0")
            || matchesFamily(model, "claude-sonnet-4-5")
            || matchesFamily(model, "claude-sonnet-4-6")
            || matchesFamily(model, "claude-haiku-4-5")

            || Strings.CS.startsWith(model, "claude-sonnet-4-2025")
            || Strings.CS.startsWith(model, "claude-opus-4-2025");
    }

    private static boolean matchesFamily(String model, String family) {
        return model.equals(family)
            || Strings.CS.startsWith(model, family + "-")
            || Strings.CS.startsWith(model, family + "[");
    }

    private static String normalize(String model) {
        if (model == null) return "";
        return model.trim().toLowerCase(Locale.ROOT)
            .replace('_', '-')
            .replace('.', '-');
    }

}
