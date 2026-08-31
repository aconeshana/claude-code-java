package com.claudecode.commands.parsing;


import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;


class SlashCommandParserTest {

    // ---- Null / blank / empty inputs → Optional.empty ----

    @Nested
    class InvalidInputs {

        @Test
        void nullInputReturnsEmpty() {
            assertTrue(SlashCommandParser.parse(null).isEmpty());
        }

        @Test
        void emptyStringReturnsEmpty() {
            assertTrue(SlashCommandParser.parse("").isEmpty());
        }

        @Test
        void blankStringReturnsEmpty() {
            assertTrue(SlashCommandParser.parse("   ").isEmpty());
        }

        @Test
        void noLeadingSlashReturnsEmpty() {
            assertTrue(SlashCommandParser.parse("help").isEmpty());
        }

        @Test
        void noLeadingSlashWithArgsReturnsEmpty() {
            assertTrue(SlashCommandParser.parse("model claude-opus").isEmpty());
        }

        @Test
        void bareSlashReturnsEmpty() {
            // Bare "/" — nothing after the slash — must return empty, NOT ParsedSlashCommand("","",false)
            assertTrue(SlashCommandParser.parse("/").isEmpty());
        }

        @Test
        void slashWithOnlySpacesReturnsEmpty() {
            // "/ " → withoutSlash=" ", split gives [""], words[0] is empty → empty
            assertTrue(SlashCommandParser.parse("/   ").isEmpty());
        }
    }

    // ---- Simple (non-MCP) commands ----

    @Nested
    class SimpleParsing {

        @Test
        void simpleCommandNoArgs() {
            Optional<ParsedSlashCommand> result = SlashCommandParser.parse("/help");
            assertTrue(result.isPresent());
            ParsedSlashCommand p = result.get();
            assertEquals("help", p.commandName());
            assertEquals("", p.args());
            assertFalse(p.isMcp());
        }

        @Test
        void commandWithSingleArg() {
            Optional<ParsedSlashCommand> result = SlashCommandParser.parse("/search foo");
            assertTrue(result.isPresent());
            ParsedSlashCommand p = result.get();
            assertEquals("search", p.commandName());
            assertEquals("foo", p.args());
            assertFalse(p.isMcp());
        }

        @Test
        void commandWithMultipleArgs() {
            Optional<ParsedSlashCommand> result = SlashCommandParser.parse("/search foo bar");
            assertTrue(result.isPresent());
            ParsedSlashCommand p = result.get();
            assertEquals("search", p.commandName());
            assertEquals("foo bar", p.args());
            assertFalse(p.isMcp());
        }

        @Test
        void caseIsPreservedInCommandName() {
            // SlashCommandParser must NOT lowercase — callers decide
            Optional<ParsedSlashCommand> result = SlashCommandParser.parse("/HELP");
            assertTrue(result.isPresent());
            assertEquals("HELP", result.get().commandName());
        }

        @Test
        void caseIsPreservedMixedCase() {
            Optional<ParsedSlashCommand> result = SlashCommandParser.parse("/MixedCase arg1");
            assertTrue(result.isPresent());
            assertEquals("MixedCase", result.get().commandName());
            assertEquals("arg1", result.get().args());
        }

        @Test
        void leadingAndTrailingWhitespaceIsTrimmed() {
            Optional<ParsedSlashCommand> result = SlashCommandParser.parse("  /help  ");
            assertTrue(result.isPresent());
            // After trim, bare "help" token — trailing space after word is not part of args
            assertEquals("help", result.get().commandName());
        }

        @Test
        void colonInCommandName() {
            // e.g. "/mcp:tool arg"
            Optional<ParsedSlashCommand> result = SlashCommandParser.parse("/mcp:tool arg");
            assertTrue(result.isPresent());
            assertEquals("mcp:tool", result.get().commandName());
            assertEquals("arg", result.get().args());
            assertFalse(result.get().isMcp());
        }
    }

    // ---- MCP detection ----

    @Nested
    class McpDetection {

        @Test
        void mcpTokenSetsIsMcpTrue() {

            Optional<ParsedSlashCommand> result = SlashCommandParser.parse("/mcp:tool (MCP) arg1 arg2");
            assertTrue(result.isPresent());
            ParsedSlashCommand p = result.get();
            assertEquals("mcp:tool (MCP)", p.commandName());
            assertEquals("arg1 arg2", p.args());
            assertTrue(p.isMcp());
        }

        @Test
        void mcpTokenWithNoArgsAfter() {
            Optional<ParsedSlashCommand> result = SlashCommandParser.parse("/tool (MCP)");
            assertTrue(result.isPresent());
            ParsedSlashCommand p = result.get();
            assertEquals("tool (MCP)", p.commandName());
            assertEquals("", p.args());
            assertTrue(p.isMcp());
        }

        @Test
        void mcpTokenWithSingleArgAfter() {
            Optional<ParsedSlashCommand> result = SlashCommandParser.parse("/tool (MCP) only-arg");
            assertTrue(result.isPresent());
            assertEquals("tool (MCP)", result.get().commandName());
            assertEquals("only-arg", result.get().args());
            assertTrue(result.get().isMcp());
        }

        @Test
        void mcpTokenMustBeLiteralParenMCP() {
            // "(mcp)" (lowercase) must NOT trigger MCP detection
            Optional<ParsedSlashCommand> result = SlashCommandParser.parse("/tool (mcp) arg");
            assertTrue(result.isPresent());
            assertFalse(result.get().isMcp());
            assertEquals("tool", result.get().commandName());
            assertEquals("(mcp) arg", result.get().args());
        }

        @Test
        void mcpTokenInFirstPositionIsNotMcp() {
            // "(MCP)" as the command name itself — words[0]="(MCP)", words[1] doesn't exist
            Optional<ParsedSlashCommand> result = SlashCommandParser.parse("/(MCP)");
            assertTrue(result.isPresent());
            assertEquals("(MCP)", result.get().commandName());
            assertFalse(result.get().isMcp());
        }

        @Test
        void mcpCasePreservedInCommandName() {
            // Original-case command name + " (MCP)" suffix
            Optional<ParsedSlashCommand> result = SlashCommandParser.parse("/MyTool (MCP) x");
            assertTrue(result.isPresent());
            assertEquals("MyTool (MCP)", result.get().commandName());
            assertTrue(result.get().isMcp());
        }
    }

    // ---- isValid helper on the record ----

    @Nested
    class IsValidOnRecord {

        @Test
        void parsedCommandIsValid() {
            ParsedSlashCommand p = SlashCommandParser.parse("/help").orElseThrow();
            assertTrue(p.isValid());
        }

        @Test
        void emptyCommandNameIsInvalid() {
            ParsedSlashCommand p = new ParsedSlashCommand("", "", false);
            assertFalse(p.isValid());
        }
    }
}
