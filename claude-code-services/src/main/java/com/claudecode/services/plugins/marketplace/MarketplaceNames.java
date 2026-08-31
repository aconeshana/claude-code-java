package com.claudecode.services.plugins.marketplace;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Marketplace-name validation: kebab-case shape rules, path-separator/traversal rejection, reserved
 * names, and official-marketplace impersonation defense.
 */
public final class MarketplaceNames {

    /** Reserved names allowed only for official Anthropic marketplaces. */
    public static final Set<String> ALLOWED_OFFICIAL_MARKETPLACE_NAMES = Set.of(
        "claude-code-marketplace",
        "claude-code-plugins",
        "claude-plugins-official",
        "anthropic-marketplace",
        "anthropic-plugins",
        "agent-skills",
        "life-sciences",
        "knowledge-work-plugins");

    public static final String OFFICIAL_GITHUB_ORG = "anthropics";

    private static final Pattern BLOCKED_OFFICIAL_NAME_PATTERN = Pattern.compile(
        "official[^a-z0-9]*(anthropic|claude)|(?:anthropic|claude)[^a-z0-9]*official"
            + "|^(?:anthropic|claude)[^a-z0-9]*(marketplace|plugins|official)",
        Pattern.CASE_INSENSITIVE);

    private static final Pattern NON_ASCII_PATTERN = Pattern.compile("[^\\u0020-\\u007E]");

    private MarketplaceNames() {}

    /**
     * Validates a marketplace name against the {@code MarketplaceNameSchema} refinements.
     */
    public static String validate(String name) {
        if (StringUtils.isEmpty(name)) {
            return "Marketplace must have a name";
        }
        if (Strings.CS.contains(name, " ")) {
            return "Marketplace name cannot contain spaces. Use kebab-case (e.g., \"my-marketplace\")";
        }
        if (Strings.CS.contains(name, "/") || Strings.CS.contains(name, "\\") || Strings.CS.contains(name, "..") || Strings.CS.equals(name, ".")) {
            return "Marketplace name cannot contain path separators (/ or \\), \"..\" sequences, or be \".\"";
        }
        if (isBlockedOfficialName(name)) {
            return "Marketplace name impersonates an official Anthropic/Claude marketplace";
        }
        String lower = name.toLowerCase(Locale.ROOT);
        if (Strings.CS.equals(lower, "inline")) {
            return "Marketplace name \"inline\" is reserved for --plugin-dir session plugins";
        }
        if (Strings.CS.equals(lower, "builtin")) {
            return "Marketplace name \"builtin\" is reserved for built-in plugins";
        }
        return null;
    }

    /**
     * True if the name impersonates an official Anthropic/Claude marketplace
     * (and is not itself in the reserved-allowed set). Non-ASCII names are
     * blocked to prevent homograph attacks.
     */
    public static boolean isBlockedOfficialName(String name) {
        if (ALLOWED_OFFICIAL_MARKETPLACE_NAMES.contains(name.toLowerCase(Locale.ROOT))) {
            return false;
        }
        if (NON_ASCII_PATTERN.matcher(name).find()) {
            return true;
        }
        return BLOCKED_OFFICIAL_NAME_PATTERN.matcher(name).find();
    }

    /**
     * Reserved names may only come from the official Anthropic GitHub org.
     * Returns an error message when the source is not official, {@code null}
     * when valid (or the name is not reserved).
     */
    public static String validateOfficialNameSource(String name, MarketplaceSource source) {
        if (!ALLOWED_OFFICIAL_MARKETPLACE_NAMES.contains(name.toLowerCase(Locale.ROOT))) {
            return null;
        }
        if (source instanceof MarketplaceSource.Github github) {
            String repo = github.repo() == null ? "" : github.repo();
            if (!Strings.CI.startsWith(repo, OFFICIAL_GITHUB_ORG + "/")) {
                return reservedNameError(name);
            }
            return null;
        }
        if (source instanceof MarketplaceSource.Git git && git.url() != null) {
            String url = git.url().toLowerCase(Locale.ROOT);
            boolean official = Strings.CS.contains(url, "github.com/anthropics/")
                || Strings.CS.contains(url, "git@github.com:anthropics/");
            return official ? null : reservedNameError(name);
        }
        return "The name '" + name + "' is reserved for official Anthropic marketplaces and can only "
            + "be used with GitHub sources from the '" + OFFICIAL_GITHUB_ORG + "' organization.";
    }

    private static String reservedNameError(String name) {
        return "The name '" + name + "' is reserved for official Anthropic marketplaces. "
            + "Only repositories from 'github.com/" + OFFICIAL_GITHUB_ORG + "/' can use this name.";
    }
}
