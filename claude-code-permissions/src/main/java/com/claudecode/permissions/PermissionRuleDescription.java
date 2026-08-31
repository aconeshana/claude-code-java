package com.claudecode.permissions;

import org.apache.commons.lang3.Strings;

import java.util.Optional;


public final class PermissionRuleDescription {

    private static final String BASH_TOOL_NAME = "Bash";

    private PermissionRuleDescription() {}


    public static Optional<String> describe(PermissionRule rule) {
        if (BASH_TOOL_NAME.equals(rule.toolName())) {
            return Optional.of(rule.pattern()
                .map(p -> Strings.CS.endsWith(p, ":*")
                    ? "Any Bash command starting with " + p.substring(0, p.length() - 2)
                    : "The Bash command " + p)
                .orElse("Any Bash command"));
        }
        return rule.pattern().isEmpty()
            ? Optional.of("Any use of the " + rule.toolName() + " tool")
            : Optional.empty();
    }
}
