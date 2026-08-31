package com.claudecode.core.config;

import org.apache.commons.lang3.StringUtils;
import com.claudecode.core.process.SubprocessEnvironment;
import java.util.Optional;
import java.util.function.Supplier;


public final class ApiKeyResolver {

    public static final String ENV_API_KEY = "ANTHROPIC_API_KEY";

    private ApiKeyResolver() {}

    /**
     * Resolves the API key, consulting the explicit key, the environment, then
     * the supplied stored-key source (in that order).
     */
    public static Optional<String> resolve(String explicitKey, Supplier<String> storedKeySupplier) {
        if (StringUtils.isNotBlank(explicitKey)) {
            return Optional.of(explicitKey);
        }
        String env = SubprocessEnvironment.get(ENV_API_KEY);
        if (StringUtils.isNotBlank(env)) {
            return Optional.of(env.trim());
        }
        if (storedKeySupplier != null) {
            String stored = storedKeySupplier.get();
            if (StringUtils.isNotBlank(stored)) {
                return Optional.of(stored);
            }
        }
        return Optional.empty();
    }

    /** Resolves the API key with no explicit override. */
    public static Optional<String> resolve(Supplier<String> storedKeySupplier) {
        return resolve(null, storedKeySupplier);
    }
}
