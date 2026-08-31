package com.claudecode.tools.workflows;

import java.util.Locale;
import java.util.Map;


public final class WorkflowFeatureGate {

    private WorkflowFeatureGate() {}

    public static boolean evaluate(Map<String, String> environment,
                                   boolean managedDisabled,
                                   Boolean enabledSetting,
                                   boolean entitlementAllowed,
                                   boolean rolloutAvailable) {
        Map<String, String> env = environment == null ? Map.of() : environment;
        if (truthy(env.get("CLAUDE_CODE_DISABLE_WORKFLOWS")) || managedDisabled) return false;
        if (!entitlementAllowed) return false;

        String explicit = env.get("CLAUDE_CODE_WORKFLOWS");
        if (explicit != null) {
            if (!truthy(explicit)) return false;
            rolloutAvailable = true;
        }
        if (!rolloutAvailable) return false;
        return enabledSetting == null || enabledSetting;
    }

    public static boolean truthy(String value) {
        if (value == null) return false;
        return switch (value.strip().toLowerCase(Locale.ROOT)) {
            case "", "0", "false", "no", "off" -> false;
            default -> true;
        };
    }
}
