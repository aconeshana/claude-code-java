package com.claudecode.cli;

import com.claudecode.api.ApiConfig;
import com.claudecode.core.model.AnthropicProviderUrls;
import com.claudecode.core.model.CustomModelCatalog;
import com.claudecode.core.model.CustomModelConfig;
import com.claudecode.core.model.ModelCatalog;
import com.claudecode.core.model.ModelNames;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

/**
 * Provider/authentication capability used by model and sub-agent catalogues.
 *
 * <ul>
 *   <li>a direct Anthropic
 *       route is usable only when an API key is actually resolved.</li>
 *   <li>built-in
 *       families are projected separately from explicit provider mappings and
 *       custom model routes.</li>
 *   <li>Java gateway adaptation — an explicit Anthropic-compatible base URL
 *       requires either an API key or bearer token before built-in families are
 *       advertised; custom catalogue routes remain independent.</li>
 * </ul>
 */
record ModelAvailability(
        ApiConfig.ApiProvider provider,
        String baseUrl,
        ConfigLoader.Credentials credentials,
        CustomModelCatalog customModels) {

    boolean showBuiltInModelFamilies() {
        if (provider != ApiConfig.ApiProvider.ANTHROPIC || credentials == null) return false;
        if (AnthropicProviderUrls.isFirstPartyBaseUrl(baseUrl)) {
            return StringUtils.isNotBlank(credentials.apiKey());
        }
        return StringUtils.isNotBlank(credentials.apiKey())
            || StringUtils.isNotBlank(credentials.authToken());
    }

    boolean canCall(String model) {
        if (StringUtils.isBlank(model) || Strings.CI.equals("inherit", model)) return true;
        String resolved = ModelNames.parseUserSpecifiedModel(model);
        if (isCustom(model) || isCustom(resolved)) return true;
        if (resolvesToCatalogBuiltIn(resolved)) {
            return provider == ApiConfig.ApiProvider.ANTHROPIC
                && showBuiltInModelFamilies();
        }
        if (provider != ApiConfig.ApiProvider.ANTHROPIC) return false;
        if (AnthropicProviderUrls.isFirstPartyBaseUrl(baseUrl)) {
            return credentials != null && StringUtils.isNotBlank(credentials.apiKey());
        }
        return credentials != null && (StringUtils.isNotBlank(credentials.apiKey())
            || StringUtils.isNotBlank(credentials.authToken()));
    }

    List<String> agentOverrideModels(Predicate<String> allowlist) {
        Predicate<String> allowed = allowlist != null ? allowlist : _ -> true;
        List<String> result = new ArrayList<>();
        if (showBuiltInModelFamilies()) {
            for (String family : List.of("sonnet", "opus", "haiku", "fable")) {
                if (allowed.test(family) && canCall(family)) result.add(family);
            }
        }
        if (customModels != null) {
            try {
                customModels.list().stream()
                    .map(CustomModelConfig::modelName)
                    .filter(this::canCall)
                    .filter(allowed)
                    .sorted(Comparator.naturalOrder())
                    .filter(model -> !result.contains(model))
                    .forEach(result::add);
            } catch (RuntimeException _) {
                // A malformed optional catalogue must not break tool definition generation.
            }
        }
        return List.copyOf(result);
    }

    List<String> customModelNames(Predicate<String> allowlist) {
        Predicate<String> allowed = allowlist != null ? allowlist : _ -> true;
        if (customModels == null) return List.of();
        try {
            return customModels.list().stream()
                .map(CustomModelConfig::modelName)
                .filter(this::canCall)
                .filter(allowed)
                .sorted()
                .toList();
        } catch (RuntimeException _) {
            return List.of();
        }
    }

    private boolean isCustom(String model) {
        if (customModels == null || StringUtils.isBlank(model)) return false;
        try {
            return customModels.find(model).isPresent();
        } catch (RuntimeException _) {
            return false;
        }
    }

    private static boolean resolvesToCatalogBuiltIn(String model) {
        if (StringUtils.isBlank(model)) return false;
        String normalized = model.trim().toLowerCase(Locale.ROOT);
        if (Strings.CS.endsWith(normalized, "[1m]")) {
            normalized = normalized.substring(0, normalized.length() - 4).trim();
        }
        final String candidate = normalized;
        return ModelCatalog.pickerFamilies().stream()
            .anyMatch(family -> family.modelId().equalsIgnoreCase(candidate));
    }
}
