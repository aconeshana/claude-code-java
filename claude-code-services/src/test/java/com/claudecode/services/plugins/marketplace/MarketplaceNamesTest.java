package com.claudecode.services.plugins.marketplace;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


class MarketplaceNamesTest {

    @Test
    void kebabCaseNameIsValid() {
        assertNull(MarketplaceNames.validate("my-marketplace"));
    }

    @Test
    void emptyAndSpacedNamesRejectedWithTsMessages() {
        assertEquals("Marketplace must have a name", MarketplaceNames.validate(""));
        assertEquals(
            "Marketplace name cannot contain spaces. Use kebab-case (e.g., \"my-marketplace\")",
            MarketplaceNames.validate("My Marketplace"));
    }

    @Test
    void pathSeparatorAndTraversalNamesRejected() {
        String expected = "Marketplace name cannot contain path separators (/ or \\), "
            + "\"..\" sequences, or be \".\"";
        assertEquals(expected, MarketplaceNames.validate("a/b"));
        assertEquals(expected, MarketplaceNames.validate("a\\b"));
        assertEquals(expected, MarketplaceNames.validate("a..b"));
        assertEquals(expected, MarketplaceNames.validate("."));
    }

    @Test
    void reservedSessionNamesRejected() {
        assertEquals("Marketplace name \"inline\" is reserved for --plugin-dir session plugins",
            MarketplaceNames.validate("inline"));
        assertEquals("Marketplace name \"builtin\" is reserved for built-in plugins",
            MarketplaceNames.validate("Builtin"));
    }

    @Test
    void officialImpersonationBlocked() {
        assertTrue(MarketplaceNames.isBlockedOfficialName("claude-official"));
        assertTrue(MarketplaceNames.isBlockedOfficialName("official-anthropic-plugins"));
        assertTrue(MarketplaceNames.isBlockedOfficialName("anthropic-marketplace-new"));
        assertEquals("Marketplace name impersonates an official Anthropic/Claude marketplace",
            MarketplaceNames.validate("claude-official"));
    }

    @Test
    void allowedOfficialNamesNotBlocked() {
        assertFalse(MarketplaceNames.isBlockedOfficialName("claude-code-plugins"));
        assertFalse(MarketplaceNames.isBlockedOfficialName("Claude-Plugins-Official"));
        // Indirect variations intentionally allowed (avoid false positives).
        assertFalse(MarketplaceNames.isBlockedOfficialName("my-claude-marketplace"));
    }

    @Test
    void nonAsciiHomographNamesBlocked() {
        // Cyrillic 'а' in "аnthropic-marketplace"
        assertTrue(MarketplaceNames.isBlockedOfficialName("аnthropic-marketplace"));
    }

    @Test
    void reservedNameRequiresOfficialGithubOrg() {
        assertNull(MarketplaceNames.validateOfficialNameSource("claude-code-plugins",
            new MarketplaceSource.Github("anthropics/claude-code-plugins")));
        assertEquals(
            "The name 'claude-code-plugins' is reserved for official Anthropic marketplaces. "
                + "Only repositories from 'github.com/anthropics/' can use this name.",
            MarketplaceNames.validateOfficialNameSource("claude-code-plugins",
                new MarketplaceSource.Github("evil/claude-code-plugins")));
    }

    @Test
    void reservedNameAllowsOfficialGitUrlsOnly() {
        assertNull(MarketplaceNames.validateOfficialNameSource("anthropic-plugins",
            new MarketplaceSource.Git("https://github.com/anthropics/plugins.git")));
        assertNull(MarketplaceNames.validateOfficialNameSource("anthropic-plugins",
            new MarketplaceSource.Git("git@github.com:anthropics/plugins.git")));
        assertEquals(
            "The name 'anthropic-plugins' is reserved for official Anthropic marketplaces. "
                + "Only repositories from 'github.com/anthropics/' can use this name.",
            MarketplaceNames.validateOfficialNameSource("anthropic-plugins",
                new MarketplaceSource.Git("https://gitlab.com/evil/plugins.git")));
    }

    @Test
    void reservedNameRejectsNonGithubSources() {
        assertEquals(
            "The name 'agent-skills' is reserved for official Anthropic marketplaces and can only "
                + "be used with GitHub sources from the 'anthropics' organization.",
            MarketplaceNames.validateOfficialNameSource("agent-skills",
                new MarketplaceSource.Directory("/tmp/mkt")));
    }

    @Test
    void nonReservedNamesSkipSourceValidation() {
        assertNull(MarketplaceNames.validateOfficialNameSource("my-marketplace",
            new MarketplaceSource.Directory("/tmp/mkt")));
    }
}
