package com.claudecode.services.hooks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Characterizes hook matching before its extraction from {@link HookEngine}.
 */
class HookMatchResolverTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void resolvesMatchingSourceLayersInCallerProvidedOrder() {
        HookMatchResolver resolver = new HookMatchResolver();
        BashCommandHook settings = new BashCommandHook("settings");
        BashCommandHook plugin = new BashCommandHook("plugin");
        BashCommandHook sdk = new BashCommandHook("sdk");
        BashCommandHook extra = new BashCommandHook("extra");
        BashCommandHook session = new BashCommandHook("session");

        List<HookMatchResolver.MatchedHook> matches = resolver.resolve(
            HookEvent.PRE_TOOL_USE,
            HookInput.forPreToolUse("Bash", MAPPER.createObjectNode(), "tool-1"),
            List.of(
                List.of(new HookMatcher(Optional.empty(), List.of(settings))),
                List.of(new HookMatcher(Optional.empty(), List.of(plugin))),
                List.of(new HookMatcher(Optional.empty(), List.of(sdk))),
                List.of(new HookMatcher(Optional.empty(), List.of(extra))),
                List.of(new HookMatcher(Optional.empty(), List.of(session)))));

        assertEquals(List.of("settings", "plugin", "sdk", "extra", "session"),
            matches.stream()
                .map(match -> ((BashCommandHook) match.command()).command())
                .toList());
    }

    @Test
    void usesTheSessionEndReasonAsItsMatcherQuery() {
        HookMatchResolver resolver = new HookMatchResolver();
        BashCommandHook clear = new BashCommandHook("clear");

        List<HookMatchResolver.MatchedHook> matches = resolver.resolve(
            HookEvent.SESSION_END,
            HookInput.forSessionEnd("clear"),
            List.of(List.of(new HookMatcher(Optional.of("clear"), List.of(clear)))));

        assertEquals(List.of(clear), matches.stream().map(HookMatchResolver.MatchedHook::command).toList());
    }

    @Test
    void matchesBashIfRulesAndKeepsLegacyAliases() {
        HookMatchResolver resolver = new HookMatchResolver();
        HookInput gitStatus = HookInput.forPreToolUse(
            "Bash", MAPPER.createObjectNode().put("command", "git status"), "tool-1");
        HookInput echoParens = HookInput.forPreToolUse(
            "Bash", MAPPER.createObjectNode().put("command", "echo (ok)"), "tool-2");

        assertTrue(resolver.matchesIfCondition(new BashCommandHook(
            "ignored", Optional.of("Bash(git *)"), Optional.empty(), Optional.empty(), Optional.empty(),
            false, false, false), gitStatus));
        assertFalse(resolver.matchesIfCondition(new BashCommandHook(
            "ignored", Optional.of("Bash(git *)"), Optional.empty(), Optional.empty(), Optional.empty(),
            false, false, false), echoParens));
        assertTrue(resolver.matchesIfCondition(new BashCommandHook(
            "ignored", Optional.of("Bash(echo \\(ok\\))"), Optional.empty(), Optional.empty(), Optional.empty(),
            false, false, false), echoParens));

        HookInput agent = HookInput.forPreToolUse("Agent", MAPPER.createObjectNode(), "tool-3");
        assertTrue(resolver.matchesIfCondition(new BashCommandHook(
            "ignored", Optional.of("Task"), Optional.empty(), Optional.empty(), Optional.empty(),
            false, false, false), agent));
    }

    @Test
    void trimsWhitespaceAroundIfRuleWildcardPatternsLikeTs() {
        assertTrue(HookMatchResolver.matchWildcardPattern("  git *  ", "git status"));
        assertTrue(HookMatchResolver.matchWildcardPattern("  git *  ", "git"));
    }

    /**
     * Pins the escaped-paren semantics the shared scanner now provides, so the extraction cannot
     * silently change which parentheses delimit the rule content. The command text itself must
     * contain real parens, which the rule spells with a leading backslash.
     */
    @Test
    void matchesIfRulesWhoseContentContainsEscapedParens() {
        HookMatchResolver resolver = new HookMatchResolver();
        HookInput input = HookInput.forPreToolUse(
            "Bash", MAPPER.createObjectNode().put("command", "echo (ok)"), "tool-1");

        // Bash(echo \(ok\)) — the escape makes the parens literal content, not delimiters.
        assertTrue(resolver.matchesIfCondition(new BashCommandHook(
            "ignored", Optional.of("Bash(echo \\(ok\\))"), Optional.empty(), Optional.empty(),
            Optional.empty(), false, false, false), input));

        // A stray escape that leaves the closing paren escaped must not match "echo (ok)".
        assertFalse(resolver.matchesIfCondition(new BashCommandHook(
            "ignored", Optional.of("Bash(echo \\(ok)"), Optional.empty(), Optional.empty(),
            Optional.empty(), false, false, false), input));

        // Doubled backslash before a paren is an escaped backslash, so the paren IS a delimiter:
        // content becomes `echo \(ok)` (literal backslash + parens), which "echo (ok)" lacks.
        assertFalse(resolver.matchesIfCondition(new BashCommandHook(
            "ignored", Optional.of("Bash(echo \\\\(ok\\\\))"), Optional.empty(), Optional.empty(),
            Optional.empty(), false, false, false), input));
    }
}
