package com.claudecode.commands.metadata;


import org.apache.commons.lang3.Strings;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link CommandMetadataEncoder}.
 */
class CommandMetadataEncoderTest {

    // ── encodeCommandInputTags ─────────────────────────────────────────────

    @Nested
    class EncodeCommandInputTags {

        @Test
        void simpleCommandWithArgs() {
            String result = CommandMetadataEncoder.encodeCommandInputTags("model", "claude-opus-4-5");
            assertEquals(
                    """
                    <command-name>/model</command-name>
                                <command-message>model</command-message>
                                <command-args>claude-opus-4-5</command-args>""",
                    result
            );
        }

        @Test
        void emptyArgs() {
            String result = CommandMetadataEncoder.encodeCommandInputTags("clear", "");
            assertEquals(
                    """
                    <command-name>/clear</command-name>
                                <command-message>clear</command-message>
                                <command-args></command-args>""",
                    result
            );
        }

        @Test
        void argsWithSpaces() {
            String result = CommandMetadataEncoder.encodeCommandInputTags("review", "fix auth bug");
            assertEquals(
                    """
                    <command-name>/review</command-name>
                                <command-message>review</command-message>
                                <command-args>fix auth bug</command-args>""",
                    result
            );
        }

        @Test
        void commandNameAppearsWithSlashInNameTagButNotInMessageTag() {
            String result = CommandMetadataEncoder.encodeCommandInputTags("init", ".");
            assertTrue(Strings.CS.startsWith(result, "<command-name>/init</command-name>"),
                    "command-name must start with /");
            assertTrue(Strings.CS.contains(result, "<command-message>init</command-message>"),
                    "command-message must NOT have /");
        }

        @Test
        void specialCharsInCommandName() {
            // Plugin-qualified names like "plugin:skill"
            String result = CommandMetadataEncoder.encodeCommandInputTags("product-management:spec", "");
            assertTrue(Strings.CS.contains(result, "<command-name>/product-management:spec</command-name>"));
            assertTrue(Strings.CS.contains(result, "<command-message>product-management:spec</command-message>"));
        }

        @Test
        void specialCharsInArgs() {
            String result = CommandMetadataEncoder.encodeCommandInputTags("run", "<file> & \"test\"");
            assertTrue(Strings.CS.contains(result, "<command-args><file> & \"test\"</command-args>"));
        }
    }

    // ── encodeSlashCommandLoading ──────────────────────────────────────────

    @Nested
    class EncodeSlashCommandLoading {

        @Test
        void withArgs() {
            String result = CommandMetadataEncoder.encodeSlashCommandLoading("model", "claude-opus-4-5");
            assertEquals(
                    """
                    <command-message>model</command-message>
                    <command-name>/model</command-name>
                    <command-args>claude-opus-4-5</command-args>""",
                    result
            );
        }

        @Test
        void withoutArgs_nullOmitsArgsLine() {
            String result = CommandMetadataEncoder.encodeSlashCommandLoading("clear", null);
            assertEquals(
                    """
                    <command-message>clear</command-message>
                    <command-name>/clear</command-name>""",
                    result
            );
            assertFalse(Strings.CS.contains(result, "command-args"), "args line must be absent when args is null");
        }

        @Test
        void emptyStringArgsIsIncluded() {

            // so empty string SHOULD be excluded (same as null).
            // However the Java contract uses null to signal "omit".
            // When the caller passes "" it is included (explicit empty args).
            String result = CommandMetadataEncoder.encodeSlashCommandLoading("review", "");
            assertTrue(Strings.CS.contains(result, "<command-args></command-args>"));
        }

        @Test
        void commandNameHasSlashInNameTagOnly() {
            String result = CommandMetadataEncoder.encodeSlashCommandLoading("compact", null);
            assertTrue(Strings.CS.contains(result, "<command-name>/compact</command-name>"));
            assertTrue(Strings.CS.contains(result, "<command-message>compact</command-message>"));
            assertFalse(Strings.CS.contains(result, "<command-message>/compact"), "message tag must not have /");
        }

        @Test
        void pluginQualifiedCommandName() {
            String result = CommandMetadataEncoder.encodeSlashCommandLoading("plugin:cmd", "arg1");
            assertEquals(
                    """
                    <command-message>plugin:cmd</command-message>
                    <command-name>/plugin:cmd</command-name>
                    <command-args>arg1</command-args>""",
                    result
            );
        }

        @Test
        void noLeadingOrTrailingWhitespace() {
            String result = CommandMetadataEncoder.encodeSlashCommandLoading("init", null);
            assertFalse(Strings.CS.startsWith(result, " "), "no leading space");
            assertFalse(Strings.CS.endsWith(result, " ") || Strings.CS.endsWith(result, "\n"),
                    "no trailing whitespace/newline");
        }
    }

    // ── encodeSkill ───────────────────────────────────────────────────────

    @Nested
    class EncodeSkill {

        @Test
        void simpleSkillName() {
            String result = CommandMetadataEncoder.encodeSkill("tdd");
            assertEquals(
                    """
                    <command-message>tdd</command-message>
                    <command-name>tdd</command-name>
                    <skill-format>true</skill-format>""",
                    result
            );
        }

        @Test
        void pluginQualifiedSkillName() {
            String result = CommandMetadataEncoder.encodeSkill("product-management:feature-spec");
            assertEquals(
                    """
                    <command-message>product-management:feature-spec</command-message>
                    <command-name>product-management:feature-spec</command-name>
                    <skill-format>true</skill-format>""",
                    result
            );
        }

        @Test
        void skillNameHasNoLeadingSlash() {
            String result = CommandMetadataEncoder.encodeSkill("code-review");
            assertFalse(Strings.CS.contains(result, "<command-name>/"), "skill command-name must NOT have /");
        }

        @Test
        void skillFormatSentinelAlwaysPresent() {
            String result = CommandMetadataEncoder.encodeSkill("any-skill");
            assertTrue(Strings.CS.contains(result, "<skill-format>true</skill-format>"),
                    "skill-format sentinel must be present");
        }

        @Test
        void skillNameWithSpecialChars() {
            String result = CommandMetadataEncoder.encodeSkill("my-plugin:my_skill.v2");
            assertTrue(Strings.CS.contains(result, "<command-message>my-plugin:my_skill.v2</command-message>"));
            assertTrue(Strings.CS.contains(result, "<command-name>my-plugin:my_skill.v2</command-name>"));
        }

        @Test
        void noLeadingOrTrailingWhitespace() {
            String result = CommandMetadataEncoder.encodeSkill("eval");
            assertFalse(Strings.CS.startsWith(result, " "), "no leading space");
            assertFalse(Strings.CS.endsWith(result, " ") || Strings.CS.endsWith(result, "\n"),
                    "no trailing whitespace/newline");
        }
    }
}
