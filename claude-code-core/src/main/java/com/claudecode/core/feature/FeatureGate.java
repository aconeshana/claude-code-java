package com.claudecode.core.feature;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.core.process.SubprocessEnvironment;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Runtime feature gate.
 */
public final class FeatureGate {

    public enum Flag {
        HISTORY_SNIP,
        CONTEXT_COLLAPSE,
        REACTIVE_COMPACT,
        TOKEN_BUDGET,
        CHICAGO_MCP,
        BG_SESSIONS,
        CACHED_MICROCOMPACT,
        TEMPLATES,
        EXPERIMENTAL_SKILL_SEARCH,
        COORDINATOR_MODE,
        EXTRACT_MEMORIES,
        KAIROS,
        /** Channel transports are the second half of the KAIROS interactive gate. */
        KAIROS_CHANNELS,
        WEB_BROWSER_TOOL,

        STRICT_TOOLS,

        VERIFICATION_AGENT_NUDGE,
    }

    private static final ScopedValue<Set<Flag>> SCOPED_OVERRIDES = ScopedValue.newInstance();

    private FeatureGate() {}

    public static boolean isEnabled(Flag flag) {
        Set<Flag> overrides = SCOPED_OVERRIDES.isBound() ? SCOPED_OVERRIDES.get() : null;
        if (overrides != null) return overrides.contains(flag);
        String val = SubprocessEnvironment.get("CLAUDE_CODE_FEATURE_" + flag.name());
        if (StringUtils.isBlank(val)) {
            return flag == Flag.COORDINATOR_MODE;
        }
        return Strings.CS.equals("1", val) || Strings.CI.equals("true", val);
    }

    /**
     * Enables {@code flags} for the calling thread during {@code block}, then restores
     * the previous state. Safe for nested calls.
     */
    public static void withFlags(Runnable block, Flag... flags) {
        Set<Flag> current = SCOPED_OVERRIDES.isBound() ? SCOPED_OVERRIDES.get() : null;
        Set<Flag> next = current != null ? new HashSet<>(current) : new HashSet<>();
        Collections.addAll(next, flags);
        ScopedValue.where(SCOPED_OVERRIDES, Set.copyOf(next)).run(block);
    }
}
