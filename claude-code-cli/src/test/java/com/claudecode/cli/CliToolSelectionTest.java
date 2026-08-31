package com.claudecode.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.permissions.PermissionBehavior;
import com.claudecode.permissions.PermissionRule;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;


class CliToolSelectionTest {

    @Test
    void parsesCommaAndSpaceSeparatorsButPreservesRuleParentheses() {
        assertEquals(
            List.of("Read", "Bash(git status, --short)", "Write", "Edit"),
            CliToolSelection.parseToolList(List.of(
                "Read,Bash(git status, --short)", "Write Edit")));
    }

    @Test
    void buildsCanonicalCliRules() {
        List<PermissionRule> rules = CliToolSelection.permissionRules(
            List.of("Task", "Bash(git *)"), PermissionBehavior.ALLOW);

        assertEquals(List.of("Agent", "Bash"),
            rules.stream().map(PermissionRule::toolName).toList());
        assertEquals(List.of(false, true),
            rules.stream().map(rule -> rule.pattern().isPresent()).toList());
    }

    @Test
    void wholeToolDenyFiltersSchemaButPatternDenyDoesNot() {
        List<PermissionRule> rules = CliToolSelection.permissionRules(
            List.of("Write", "Bash(rm *)"), PermissionBehavior.DENY);

        assertEquals(Set.of("Write"), CliToolSelection.wholeToolDenials(rules));
    }

    @Test
    void explicitBaseToolsRetainTheSelectableCatalogBeforeFiltering() {
        assertFalse(CliToolSelection.hasExplicitToolSelection(
            List.of(), List.of(), null));
        assertTrue(CliToolSelection.hasExplicitToolSelection(
            List.of(), List.of(), List.of("Glob", "Grep", "Read", "Edit")));
        assertTrue(CliToolSelection.hasExplicitToolSelection(
            List.of(), List.of(), List.of()));
        assertTrue(CliToolSelection.hasExplicitToolSelection(
            List.of("Read"), List.of(), null));
        assertTrue(CliToolSelection.hasExplicitToolSelection(
            List.of(), List.of("Write"), null));
    }

    @Test
    void baseToolsDefaultKeepsCatalogAndExplicitEmptyKeepsNothing() {
        Set<String> catalog = Set.of("Bash", "Glob", "Grep", "Read", "Write");

        assertEquals(catalog,
            CliToolSelection.selectedBaseTools(List.of("default"), catalog));
        assertEquals(Set.of(),
            CliToolSelection.selectedBaseTools(List.of(""), catalog));
        assertEquals(Set.of("Bash", "Read"),
            CliToolSelection.selectedBaseTools(List.of("Read,Bash"), catalog));
        assertEquals(Set.of("Glob", "Grep", "Read"),
            CliToolSelection.selectedBaseTools(List.of("Glob,Grep,Read"), catalog));
    }
}
