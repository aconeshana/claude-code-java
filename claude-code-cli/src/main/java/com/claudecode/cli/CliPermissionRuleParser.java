package com.claudecode.cli;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.permissions.PermissionBehavior;
import com.claudecode.permissions.PermissionRule;
import com.claudecode.permissions.RuleSource;

import java.util.List;

final class CliPermissionRuleParser {

    private CliPermissionRuleParser() {}

    public static List<PermissionRule> parse(List<String> specs, RuleSource source) {
        if (specs == null || specs.isEmpty()) return List.of();
        return specs.stream()
            .filter(StringUtils::isNotBlank)
            .map(spec -> parseOne(spec.trim(), source))
            .toList();
    }

    private static PermissionRule parseOne(String spec, RuleSource source) {
        int paren = spec.indexOf('(');
        if (paren > 0 && Strings.CS.endsWith(spec, ")")) {
            return PermissionRule.withPattern(spec.substring(0, paren).trim(),
                PermissionBehavior.ALLOW, source,
                spec.substring(paren + 1, spec.length() - 1).trim());
        }
        return PermissionRule.of(spec, PermissionBehavior.ALLOW, source);
    }
}
