package com.claudecode.core.prompt;

import com.claudecode.core.config.EnvUtils;
import com.claudecode.core.model.ApiProviderScope;
import com.claudecode.core.process.SubprocessEnvironment;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;


public final class SystemPromptProfileResolver {

    public enum Profile {
        LONG,
        HARNESS
    }

    private static final String SIMPLE_PROMPT_ENV = "CLAUDE_CODE_SIMPLE_SYSTEM_PROMPT";
    private static final String LEAN_PROMPT = "lean_prompt";
    private static final String FABLE_5_MITIGATIONS = "fable_5_mitigations";




    private static final Map<String, Set<String>> MODEL_CAPABILITIES = Map.of(
        "claude-opus-5", Set.of(LEAN_PROMPT),
        "claude-opus-4-8", Set.of(LEAN_PROMPT),
        "claude-fable-5", Set.of(LEAN_PROMPT, FABLE_5_MITIGATIONS)
    );

    private SystemPromptProfileResolver() {}

    /** Production resolver using the process/runtime environment overlay. */
    public static Profile resolve(SystemPromptConfig config) {
        return resolve(config.modelId(), config.apiProvider(),
            SubprocessEnvironment::get, config.simpleSystemPromptModelPatterns());
    }

    /** Pure overload used by deterministic profile tests. */
    static Profile resolve(String modelId, String apiProvider,
            Function<String, String> envLookup, List<String> rolloutModelPatterns) {
        if (StringUtils.isEmpty(modelId)) return Profile.LONG;

        String explicit = envLookup.apply(SIMPLE_PROMPT_ENV);
        if (EnvUtils.isEnvTruthy(explicit)) return Profile.HARNESS;
        if (EnvUtils.isEnvDefinedFalsy(explicit)) return Profile.LONG;

        String canonical = canonicalModelId(modelId);
        boolean harness = !supportsLongPrompt(modelId, canonical, apiProvider)
            || rolloutForcesHarness(canonical, rolloutModelPatterns);
        return harness ? Profile.HARNESS : Profile.LONG;
    }


    static boolean hasCapability(String canonicalModelId, String capability) {
        Set<String> capabilities = MODEL_CAPABILITIES.get(canonicalModelId);
        return capabilities != null && capabilities.contains(capability);
    }


    static boolean usesFable5Mitigations(String modelId) {
        String canonical = canonicalModelId(modelId);
        return hasCapability(canonical, FABLE_5_MITIGATIONS)
            || Strings.CS.equals(canonical, "claude-mythos-5");
    }


    static boolean isFableFamily(String modelId) {
        return Strings.CS.startsWith(canonicalModelId(modelId), "claude-fable-");
    }

    private static boolean supportsLongPrompt(String rawModelId, String canonicalModelId,
            String apiProvider) {
        if (rawModelId.toLowerCase(Locale.ROOT).matches(".*-eap(?:$|\\[).*")) {
            return false;
        }
        if (hasCapability(canonicalModelId, LEAN_PROMPT)
                || Strings.CS.equals("claude-mythos-5", canonicalModelId)) {
            return false;
        }
        if (isReleasedLongPromptFamily(canonicalModelId)) return true;


        return !ApiProviderScope.usesFirstPartyModelIds(apiProvider);
    }

    private static boolean isReleasedLongPromptFamily(String model) {
        if (Strings.CS.contains(model, "claude-3-")
                || Strings.CS.contains(model, "haiku")
                || Strings.CS.contains(model, "sonnet")) {
            return true;
        }
        return matchesFamily(model, "claude-opus-4-0")
            || matchesFamily(model, "claude-opus-4-1")
            || matchesFamily(model, "claude-opus-4-5")
            || matchesFamily(model, "claude-opus-4-6")
            || matchesFamily(model, "claude-opus-4-7");
    }

    private static boolean rolloutForcesHarness(String canonicalModelId,
            List<String> rolloutModelPatterns) {
        if (rolloutModelPatterns == null || rolloutModelPatterns.isEmpty()) return false;
        return rolloutModelPatterns.stream()
            .filter(StringUtils::isNotBlank)
            .map(pattern -> pattern.trim().toLowerCase(Locale.ROOT))
            .anyMatch(canonicalModelId::contains);
    }


    static String canonicalModelId(String modelId) {
        if (modelId == null) return "";
        String normalized = modelId.trim().toLowerCase(Locale.ROOT)
            .replaceAll("\\[1m]$", "")
            .replace('_', '-')
            .replace('.', '-');

        List<String> knownFamilies = List.of(
            "claude-mythos-5",
            "claude-fable-5",
            "claude-opus-5",
            "claude-opus-4-8",
            "claude-opus-4-7",
            "claude-opus-4-6",
            "claude-opus-4-5",
            "claude-opus-4-1",
            "claude-opus-4-0",
            "claude-sonnet-5",
            "claude-sonnet-4-6",
            "claude-sonnet-4-5",
            "claude-haiku-4-5",
            "claude-3-7-sonnet",
            "claude-3-5-sonnet",
            "claude-3-5-haiku",
            "claude-3-opus",
            "claude-3-sonnet",
            "claude-3-haiku"
        );
        for (String family : knownFamilies) {
            if (Strings.CS.contains(normalized, family)) return family;
        }
        return normalized.replaceAll("-\\d{8}$", "");
    }

    private static boolean matchesFamily(String model, String family) {
        return Strings.CS.equals(model, family)
            || Strings.CS.startsWith(model, family + "-")
            || Strings.CS.startsWith(model, family + "[");
    }
}
