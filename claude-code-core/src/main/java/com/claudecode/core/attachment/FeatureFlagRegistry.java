package com.claudecode.core.attachment;

import java.util.EnumSet;
import java.util.Set;

/**
 * Read-only view of which {@link FeatureFlag}s are currently on.
 */
public final class FeatureFlagRegistry {

    private final Set<FeatureFlag> enabled;

    private FeatureFlagRegistry(Set<FeatureFlag> enabled) {
        this.enabled = EnumSet.copyOf(enabled);
    }

    public boolean isEnabled(FeatureFlag flag) {
        return enabled.contains(flag);
    }

    public static FeatureFlagRegistry allOff() {
        return new FeatureFlagRegistry(EnumSet.noneOf(FeatureFlag.class));
    }

    public static Builder builder() {
        return new Builder();
    }

/** Fluent builder — start from {@link #allOff} and enable what's needed. */
    public static final class Builder {
        private final Set<FeatureFlag> set = EnumSet.noneOf(FeatureFlag.class);

        public Builder enable(FeatureFlag flag) {
            set.add(flag);
            return this;
        }

        public Builder enableIf(FeatureFlag flag, boolean enabled) {
            if (enabled) set.add(flag);
            return this;
        }

        public FeatureFlagRegistry build() {
            return new FeatureFlagRegistry(set);
        }
    }
}
