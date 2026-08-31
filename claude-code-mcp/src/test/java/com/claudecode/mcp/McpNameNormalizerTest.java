package com.claudecode.mcp;

import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


class McpNameNormalizerTest {

    // ── generic segment normalisation ────────────────────────────────────────

    @Test
    void normalize_leavesAlnumUnderscoreDashUntouched() {
        assertEquals("notion-oauth", McpNameNormalizer.normalize("notion-oauth"));
        assertEquals("search_pages_v2", McpNameNormalizer.normalize("search_pages_v2"));
        assertEquals("A-Z_09", McpNameNormalizer.normalize("A-Z_09"));
    }

    @Test
    void normalize_replacesSpacesAndDotsWithUnderscore() {
        // Non-claude.ai names keep their raw underscore substitution — no
        // collapsing, no trimming.
        assertEquals("github_copilot", McpNameNormalizer.normalize("github copilot"));
        assertEquals("my_server_v1_2", McpNameNormalizer.normalize("my.server.v1.2"));
        assertEquals("email_example",
            McpNameNormalizer.normalize("email@example"));
    }

    @Test
    void normalize_emptyOrNull_returnsEmpty() {
        assertEquals("", McpNameNormalizer.normalize(""));
        assertEquals("", McpNameNormalizer.normalize(null));
    }

    // ── claude.ai prefix path ────────────────────────────────────────────────

    @Test
    void normalize_claudeAiPrefix_collapsesConsecutiveUnderscores() {
        // "claude.ai foo/bar/baz" → substitute → "claude_ai_foo_bar_baz"
        // (double substitution from ".", " ", and "/") → collapse → same shape
        // (already single underscores after collapse). Test the collapse path
        // by giving input that generates runs of _ from adjacent bad chars.
        assertEquals("claude_ai_agent_x",
            McpNameNormalizer.normalize("claude.ai agent  x"),
            "double space between tokens must collapse to a single underscore under claude.ai path");
    }

    @Test
    void normalize_claudeAiPrefix_stripsLeadingAndTrailingUnderscores() {
        // Input starting with claude.ai gets marked; a trailing dot after
        // normalisation becomes a stray underscore that must be trimmed.
        assertEquals("claude_ai_agent_x",
            McpNameNormalizer.normalize("claude.ai agent x."));
        assertEquals("claude_ai_x",
            McpNameNormalizer.normalize("claude.ai .x."),
            "leading garbage that becomes underscores must be stripped");
    }

    @Test
    void normalize_nonClaudeAiPrefix_preservesConsecutiveUnderscores() {
        // Regression: only the claude.ai path collapses; everyone else keeps
        // their underscore run so round-tripping is stable.
        assertEquals("weird__name", McpNameNormalizer.normalize("weird  name"),
            "non-claude.ai names preserve underscore runs (matches TS gate)");
    }

    // ── mcpCommandName end-to-end ────────────────────────────────────────────

    @Test
    void mcpCommandName_producesMcpDoubleUnderscoreFormat() {
        assertEquals("mcp__notion-oauth__search",
            McpNameNormalizer.mcpCommandName("notion-oauth", "search"));
    }

    @Test
    void mcpCommandName_normalisesBothSegments() {
        assertEquals("mcp__github_copilot__list_repos",
            McpNameNormalizer.mcpCommandName("github copilot", "list.repos"),
            "both server and prompt names must go through normalize()");
    }

    @Test
    void mcpCommandName_survivesEdgyPromptNames() {
        // A prompt name with a slash (some MCP servers namespace their
        // prompts) must not corrupt the mcp__X__Y delimiter.
        assertEquals("mcp__srv__ns_greet",
            McpNameNormalizer.mcpCommandName("srv", "ns/greet"));
    }

    // ── isValidServerName / invalidNameReason (M6-B) ─────────────────────────

    @Test
    void isValidServerName_acceptsAlnumUnderscoreDash() {
        assertTrue(McpNameNormalizer.isValidServerName("notion-oauth"));
        assertTrue(McpNameNormalizer.isValidServerName("gh_http_v2"));
        assertTrue(McpNameNormalizer.isValidServerName("A"));
        assertTrue(McpNameNormalizer.isValidServerName("0"));
        assertTrue(McpNameNormalizer.isValidServerName("srv-1_2-3"));
    }

    @Test
    void isValidServerName_rejectsSpacesDotsAndSpecials() {

        assertFalse(McpNameNormalizer.isValidServerName("my server"),
            "space is TS's canonical rejection case");
        assertFalse(McpNameNormalizer.isValidServerName("notion.oauth"),
            "dots collide with plugin namespacing");
        assertFalse(McpNameNormalizer.isValidServerName("srv/1"),
            "slash breaks mcp__X__Y delimiter roundtrip");
        assertFalse(McpNameNormalizer.isValidServerName("srv@host"));
        assertFalse(McpNameNormalizer.isValidServerName(""));
        assertFalse(McpNameNormalizer.isValidServerName(null));
    }

    @Test
    void invalidNameReason_returnsNullForValidName_stringForInvalid() {
        assertNull(McpNameNormalizer.invalidNameReason("notion-oauth"));
        assertNotNull(McpNameNormalizer.invalidNameReason("my server"));
        assertTrue(Strings.CS.contains(McpNameNormalizer.invalidNameReason("bad.name"), "bad.name"),
            "reason must include the offending name so error messages are actionable");
    }
}
