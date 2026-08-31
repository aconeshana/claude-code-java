package com.claudecode.tools.bash;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class SedEditParserTest {
    @Test
    void parsesAndAppliesSimpleInPlaceSubstitution() {
        var info = SedEditParser.parse("sed -i '' 's/old/new/g' config.yml");
        assertNotNull(info);
        assertEquals("config.yml", info.filePath());
        assertEquals("old", info.pattern());
        assertEquals("new", info.replacement());
        assertEquals("new new\n", SedEditParser.apply("old old\n", info));
    }

    @Test
    void supportsExtendedRegexAndMatchReplacement() {
        var info = SedEditParser.parse("sed -E -i.bak 's/(foo)+/[&]/i' file.txt");
        assertNotNull(info);
        assertTrue(info.extendedRegex());
        assertEquals("[FOOfoo]", SedEditParser.apply("FOOfoo", info));
    }

    @Test
    void rejectsMultipleFilesGlobsAndNonInPlaceCommands() {
        assertNull(SedEditParser.parse("sed -i 's/a/b/' a.txt b.txt"));
        assertNull(SedEditParser.parse("sed -i 's/a/b/' *.txt"));
        assertNull(SedEditParser.parse("sed 's/a/b/' a.txt"));
        assertNull(SedEditParser.parse("sed -i 's/a/b/ file.txt"));
    }

    @Test
    void mirrorsReleasedJavascriptReplacementDollarTokens() {
        var info = SedEditParser.parse("sed -E -i 's/(foo)/$1-$&-$$/' file.txt");
        assertNotNull(info);

        assertEquals("foo-$&-$", SedEditParser.apply("foo", info));
    }
}
