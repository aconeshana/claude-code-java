package com.claudecode.core.model;

import java.util.Locale;

/**
 * Whether a provider serves Anthropic's own model-id namespace.
 *
 * <ul>
 *   <li>{@code ud(provider)}, the
 *       {@code firstParty}/{@code anthropicAws}/{@code gateway} test that gates
 *       every decision phrased as "is this a first-party model id".</li>
 * </ul>
 *
 * <p>Callers pass the provider they already hold; nothing here reads the
 * environment, so core stays free of process state.
 */
public final class ApiProviderScope {

    private ApiProviderScope() {
    }


    public static boolean usesFirstPartyModelIds(String provider) {
        String normalized = provider == null ? "firstparty"
            : provider.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "firstparty", "anthropic", "anthropicaws", "anthropic_aws", "gateway" -> true;
            default -> false;
        };
    }
}
