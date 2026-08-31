package com.claudecode.core.model;

import com.claudecode.core.process.SubprocessEnvironment;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

/**
 * Central catalogue for Claude model families, their current release IDs, and the environment
 * variables that can pin provider-specific replacements.
 */
public final class ModelCatalog {

    private static final String RESOURCE = "/model-catalog.json";

    public static final String LATEST_SONNET;
    public static final String LATEST_OPUS;
    public static final String LATEST_HAIKU;
    public static final String LATEST_FABLE;

    /** A curated family entry; aliases are stable while concrete IDs advance. */
    public record Family(
        String alias,
        String modelId,
        String displayName,
        String description,
        String environmentVariable,
        String nameEnvironmentVariable,
        String descriptionEnvironmentVariable
    ) {}

    public static final Family OPUS;
    public static final Family SONNET;
    public static final Family HAIKU;
    public static final Family FABLE;

    private static final int CATALOG_VERSION;
    private static final List<Family> FAMILIES;
    private static final List<Family> PICKER_FAMILIES;
    private static final Map<String, Family> FAMILIES_BY_ALIAS;
    private static volatile Function<String, String> modelOverrideLookup = _ -> null;

    static {
        LoadedCatalog loaded = loadCatalog();
        CATALOG_VERSION = loaded.version();
        FAMILIES = loaded.families();
        PICKER_FAMILIES = loaded.pickerFamilies();
        FAMILIES_BY_ALIAS = loaded.byAlias();
        OPUS = requireFamily("opus");
        SONNET = requireFamily("sonnet");
        HAIKU = requireFamily("haiku");
        FABLE = requireFamily("fable");
        LATEST_OPUS = OPUS.modelId();
        LATEST_SONNET = SONNET.modelId();
        LATEST_HAIKU = HAIKU.modelId();
        LATEST_FABLE = FABLE.modelId();
    }

    private ModelCatalog() {}

    public static int catalogVersion() {
        return CATALOG_VERSION;
    }




    public static List<Family> pickerFamilies() {
        return PICKER_FAMILIES;
    }

    /**
     * Returns all standard families for first-party use. Other providers only
     * receive families with an explicit environment or settings mapping.
     */
    public static List<Family> pickerFamilies(boolean includeBuiltIns,
                                               Function<String, String> envLookup) {
        return pickerFamilies(includeBuiltIns, envLookup, modelOverrideLookup);
    }

    static List<Family> pickerFamilies(boolean includeBuiltIns,
                                       Function<String, String> envLookup,
                                       Function<String, String> overrideLookup) {
        if (includeBuiltIns) return PICKER_FAMILIES;
        return PICKER_FAMILIES.stream()
            .filter(family -> StringUtils.isNotBlank(envLookup.apply(family.environmentVariable()))
                || StringUtils.isNotBlank(overrideLookup.apply(family.modelId())))
            .toList();
    }

    /** Resolve a stable family alias through environment, settings, and catalogue precedence. */
    public static String resolve(String model, Function<String, String> envLookup) {
        return resolve(model, envLookup, modelOverrideLookup);
    }

    /**
     * Resolve with both environment family pins and settings-backed catalogue overrides.
     */
    public static String resolve(String model, Function<String, String> envLookup,
                                 Function<String, String> overrideLookup) {
        if (StringUtils.isBlank(model)) return model;
        String normalized = model.trim().toLowerCase(Locale.ROOT);
        boolean tagged = Strings.CS.endsWith(normalized, "[1m]");
        String base = tagged ? normalized.substring(0, normalized.length() - 4).trim() : normalized;
        if (Strings.CS.equals("best", base)) base = "opus";
        Family family = family(base);
        if (family == null) return model;
        String override = envLookup.apply(family.environmentVariable());
        if (StringUtils.isBlank(override)) override = overrideLookup.apply(family.modelId());
        String resolved = StringUtils.isNotBlank(override) ? override : family.modelId();
        return resolved + (tagged ? "[1m]" : "");
    }

    /** Resolve using the live process environment and composition-root override provider. */
    public static String resolve(String model) {
        return resolve(model, SubprocessEnvironment::get,
            modelOverrideLookup);
    }

    /** Installed once by the application composition root; the function is evaluated live. */
    public static void installModelOverrideLookup(Function<String, String> lookup) {
        modelOverrideLookup = lookup != null ? lookup : _ -> null;
    }

    /** True when an alias and/or concrete ID select the same effective model. */
    public static boolean sameModel(String left, String right, Function<String, String> envLookup) {
        return sameModel(left, right, envLookup, modelOverrideLookup);
    }

    /** Semantic comparison with explicit environment and settings override seams. */
    public static boolean sameModel(String left, String right,
                                    Function<String, String> envLookup,
                                    Function<String, String> overrideLookup) {
        if (left == null || right == null) return Strings.CS.equals(left, right);
        return resolve(left, envLookup, overrideLookup)
            .equalsIgnoreCase(resolve(right, envLookup, overrideLookup));
    }

    /** Live semantic comparison including settings-backed model overrides. */
    public static boolean sameModel(String left, String right) {
        if (left == null || right == null) return Strings.CS.equals(left, right);
        return resolve(left).equalsIgnoreCase(resolve(right));
    }

    public static Family family(String alias) {
        if (alias == null) return null;
        return FAMILIES_BY_ALIAS.get(alias.trim().toLowerCase(Locale.ROOT));
    }

    /** True for stable aliases and official Claude family IDs, including legacy releases. */
    public static boolean isBuiltInSelection(String model) {
        if (StringUtils.isBlank(model)) return true;
        String normalized = model.trim().toLowerCase(Locale.ROOT);
        if (Strings.CS.endsWith(normalized, "[1m]")) {
            normalized = normalized.substring(0, normalized.length() - 4).trim();
        }
        if (Set.of("default", "best", "opusplan").contains(normalized)
                || family(normalized) != null) {
            return true;
        }
        return Strings.CS.startsWithAny(normalized, "claude-opus-", "claude-sonnet-",
            "claude-haiku-", "claude-fable-", "claude-3-");
    }

    /** Environment-aware picker label, including custom gateway names. */
    public static String label(Family family, Function<String, String> envLookup) {
        String configuredName = envLookup.apply(family.nameEnvironmentVariable());
        if (StringUtils.isNotBlank(configuredName)) return configuredName;
        String configuredModel = envLookup.apply(family.environmentVariable());
        return StringUtils.isNotBlank(configuredModel) ? configuredModel : family.displayName();
    }

    /** Environment-aware picker description. */
    public static String description(Family family, Function<String, String> envLookup) {
        String configured = envLookup.apply(family.descriptionEnvironmentVariable());
        if (StringUtils.isNotBlank(configured)) return configured;
        return StringUtils.isNotBlank(envLookup.apply(family.environmentVariable()))
            ? "Custom " + capitalize(family.alias()) + " model" : family.description();
    }

    private static LoadedCatalog loadCatalog() {
        try (InputStream input = ModelCatalog.class.getResourceAsStream(RESOURCE)) {
            if (input == null) throw new IllegalStateException("Missing bundled " + RESOURCE);
            JsonNode root = JsonUtils.getMapper().readTree(input);
            int version = requiredInt(root, "catalogVersion");
            JsonNode familyNodes = root.get("families");
            if (familyNodes == null || !familyNodes.isArray() || familyNodes.isEmpty()) {
                throw new IllegalStateException("model-catalog.json families must be a non-empty array");
            }
            List<Family> families = new ArrayList<>();
            Map<String, Family> byAlias = new HashMap<>();
            Set<String> modelIds = new HashSet<>();
            for (JsonNode node : familyNodes) {
                String alias = requiredText(node, "alias").toLowerCase(Locale.ROOT);
                String modelId = requiredText(node, "modelId");
                String env = requiredText(node, "environmentVariable");
                Family family = new Family(alias, modelId,
                    requiredText(node, "displayName"), requiredText(node, "description"), env,
                    env + "_NAME", env + "_DESCRIPTION");
                if (byAlias.putIfAbsent(alias, family) != null) {
                    throw new IllegalStateException("Duplicate model family alias: " + alias);
                }
                if (!modelIds.add(modelId.toLowerCase(Locale.ROOT))) {
                    throw new IllegalStateException("Duplicate model family ID: " + modelId);
                }
                families.add(family);
            }
            JsonNode pickerOrder = root.get("pickerOrder");
            if (pickerOrder == null || !pickerOrder.isArray()) {
                throw new IllegalStateException("model-catalog.json pickerOrder must be an array");
            }
            List<Family> pickerFamilies = new ArrayList<>();
            for (JsonNode aliasNode : pickerOrder) {
                String alias = aliasNode.asText();
                Family family = byAlias.get(alias.toLowerCase(Locale.ROOT));
                if (family == null) {
                    throw new IllegalStateException("Unknown picker family alias: " + alias);
                }
                pickerFamilies.add(family);
            }
            return new LoadedCatalog(version, List.copyOf(families),
                List.copyOf(pickerFamilies), Map.copyOf(byAlias));
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static Family requireFamily(String alias) {
        Family family = FAMILIES_BY_ALIAS.get(alias);
        if (family == null) throw new IllegalStateException("Missing required model family: " + alias);
        return family;
    }

    private static int requiredInt(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.canConvertToInt()) {
            throw new IllegalStateException("model-catalog.json field must be an integer: " + field);
        }
        return value.intValue();
    }

    private static String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || StringUtils.isBlank(value.asText())) {
            throw new IllegalStateException("model-catalog.json field must be non-blank: " + field);
        }
        return value.asText().trim();
    }

    private static String capitalize(String value) {
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private record LoadedCatalog(int version, List<Family> families,
                                 List<Family> pickerFamilies, Map<String, Family> byAlias) {}
}
