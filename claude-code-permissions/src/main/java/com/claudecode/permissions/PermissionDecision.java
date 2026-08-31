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
     */
    record Ask(String blockedPath, JsonNode updatedInput, String message,
               String suggestionRuleContent, String suggestionLabel,
               List<PermissionUpdate> suggestions)
            implements PermissionDecision {
        public Ask {
            suggestions = List.copyOf(suggestions == null ? List.of() : suggestions);
        }

        /** Compatibility shape for callers that do not provide typed suggestions. */
        public Ask(String blockedPath, JsonNode updatedInput, String message,
                   String suggestionRuleContent, String suggestionLabel) {
            this(blockedPath, updatedInput, message, suggestionRuleContent,
                suggestionLabel, List.of());
        }

        /** Legacy no-arg shape: no blocked path. */
        public Ask() {
            this(null, null, null, null, null, List.of());
        }

        /** Legacy path-safety shape. */
        public Ask(String blockedPath) {
            this(blockedPath, null, null, null, null, List.of());
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
