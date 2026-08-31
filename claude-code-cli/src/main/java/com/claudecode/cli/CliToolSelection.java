package com.claudecode.cli;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.permissions.PermissionBehavior;
import com.claudecode.permissions.PermissionEngine;
import com.claudecode.permissions.PermissionRule;
import com.claudecode.permissions.RuleSource;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class CliToolSelection {

    private CliToolSelection() {}

    static List<String> parseToolList(List<String> values) {
        if (values == null || values.isEmpty()) return List.of();
        List<String> result = new ArrayList<>();
        for (String value : values) {
            if (StringUtils.isEmpty(value)) continue;
            StringBuilder current = new StringBuilder();
            boolean inParens = false;
            for (int i = 0; i < value.length(); i++) {
                char ch = value.charAt(i);
                if (ch == '(') {
                    inParens = true;
                    current.append(ch);
                } else if (ch == ')') {
                    inParens = false;
                    current.append(ch);
                } else if ((ch == ',' || ch == ' ') && !inParens) {
                    appendToken(result, current);
                } else {
                    current.append(ch);
                }
            }
            appendToken(result, current);
        }
        return List.copyOf(result);
    }

    static List<PermissionRule> permissionRules(
            List<String> rawValues, PermissionBehavior behavior) {
        return parseToolList(rawValues).stream()
            .map(value -> PermissionEngine.permissionRuleFromString(
                value, behavior, RuleSource.CLI_ARG))
            .toList();
    }

    static Set<String> wholeToolDenials(List<PermissionRule> rules) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        if (rules == null) return names;
        for (PermissionRule rule : rules) {
            if (rule.behavior() == PermissionBehavior.DENY && rule.pattern().isEmpty()) {
                names.add(PermissionEngine.normalizeLegacyToolName(rule.toolName()));
            }
        }
        return Set.copyOf(names);
    }

    static boolean hasExplicitToolSelection(
            List<String> allowedTools,
            List<String> disallowedTools,
            List<String> baseTools) {
        return !allowedTools.isEmpty() || !disallowedTools.isEmpty() || baseTools != null;
    }

    /**
     * Returns the retained model-visible catalogue. A null flag means the
     * option was absent; explicit {@code --tools ''} is a one-element list
     * whose parsed selection is empty and therefore disables every tool.
     */
    static Set<String> selectedBaseTools(List<String> rawValues, Collection<String> catalog) {
        LinkedHashSet<String> all = new LinkedHashSet<>(catalog);
        if (rawValues == null) return Set.copyOf(all);
        String joined = String.join(" ", rawValues).trim();
        if (Strings.CS.equals("default", joined.toLowerCase(Locale.ROOT))) {
            return Set.copyOf(all);
        }
        LinkedHashSet<String> selected = new LinkedHashSet<>();
        for (String value : parseToolList(rawValues)) {
            String normalized = PermissionEngine.normalizeLegacyToolName(value);
            if (all.contains(normalized)) selected.add(normalized);
        }
        return Set.copyOf(selected);
    }

    private static void appendToken(List<String> result, StringBuilder current) {
        String token = current.toString().trim();
        if (!token.isEmpty()) result.add(token);
        current.setLength(0);
    }
}
