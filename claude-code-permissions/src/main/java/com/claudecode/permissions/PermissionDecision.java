package com.claudecode.permissions;

import com.claudecode.core.engine.PermissionUpdate;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * Permission decision for tool permission checks — one of three mutually exclusive outcomes.
 */
public sealed interface PermissionDecision permits
    PermissionDecision.Allow,
    PermissionDecision.Ask,
    PermissionDecision.Deny {

    /** Canonical no-payload allow decision. */
    static Allow allow() {
        return PermissionDecisionCache.ALLOW;
    }

    /** Canonical ask decision with no blocked path or suggestions. */
    static Ask ask() {
        return PermissionDecisionCache.ASK;
    }

    /** Canonical deny decision with no message or reason. */
    static Deny deny() {
        return PermissionDecisionCache.DENY;
    }

    /** Tool execution is allowed. */
    record Allow() implements PermissionDecision {}

    /**
     * User should be asked for permission.
     *
     * <p>{@code breaker} classifies the safety concern behind the ask. It exists because bypass
     * mode must swallow the tool's ordinary asks while still surfacing the few deliberately
     * bypass-immune ones — and the blocking path or message text is not a reliable signal (any
     * ask may carry either). {@code null} means "ordinary ask, not bypass-immune".
     *
     * <p>The original models the same idea structurally: 2.1.197 prefix-matches the reason
     * ({@code "Dangerous rm operation"} / {@code "Dangerous rmdir operation"}); 2.1.236 replaces
     * that with a {@code circuitBreaker} enum plus a table whose {@code bypassImmune} flags mark
     * {@code dangerousRemoval} and {@code isolatePeerMachines}.
     */
    record Ask(String blockedPath, JsonNode updatedInput, String message,
               String suggestionRuleContent, String suggestionLabel,
               List<PermissionUpdate> suggestions,
               AskBreaker breaker)
            implements PermissionDecision {

        /** Safety concerns that can exempt an ask from bypass mode. */
        public enum AskBreaker {
            /** {@code rm}/{@code rmdir} against a filesystem root or the home directory. */
            DANGEROUS_REMOVAL
        }

        public Ask {
            suggestions = List.copyOf(suggestions == null ? List.of() : suggestions);
        }

        /** Ordinary ask: not bypass-immune. */
        public Ask(String blockedPath, JsonNode updatedInput, String message,
                   String suggestionRuleContent, String suggestionLabel,
                   List<PermissionUpdate> suggestions) {
            this(blockedPath, updatedInput, message, suggestionRuleContent, suggestionLabel,
                suggestions, null);
        }

        /** True when bypass mode must still surface this ask. */
        public boolean isBypassImmune() {
            return breaker != null;
        }

        /** Compatibility shape for callers that do not provide typed suggestions. */
        public Ask(String blockedPath, JsonNode updatedInput, String message,
                   String suggestionRuleContent, String suggestionLabel) {
            this(blockedPath, updatedInput, message, suggestionRuleContent,
                suggestionLabel, List.of(), null);
        }

        /** Legacy no-arg shape: no blocked path. */
        public Ask() {
            this(null, null, null, null, null, List.of(), null);
        }

        /** Legacy path-safety shape. */
        public Ask(String blockedPath) {
            this(blockedPath, null, null, null, null, List.of(), null);
        }
    }

    /**
     * Tool execution is denied.
     */
    record Deny(String message, DecisionReason reason) implements PermissionDecision {
        public Deny() {
            this(null, null);
        }
    }
}

final class PermissionDecisionCache {
    static final PermissionDecision.Allow ALLOW = new PermissionDecision.Allow();
    static final PermissionDecision.Ask ASK = new PermissionDecision.Ask();
    static final PermissionDecision.Deny DENY = new PermissionDecision.Deny();

    private PermissionDecisionCache() {}
}
