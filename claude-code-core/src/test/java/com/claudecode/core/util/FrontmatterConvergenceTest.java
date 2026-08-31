package com.claudecode.core.util;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class FrontmatterConvergenceTest {
    @Test void productionParserIsShared() {
        assertSame(FrontmatterParser.shared(), FrontmatterParser.shared());
    }

    @Test void parsesYamlAndSharedCoercions() {
        var parsed = new FrontmatterParser().parse("---\ndescription: 123\npaths: src/*.{ts,tsx}, test\nshell: PowerShell\n---\nbody");
        assertEquals("123", parsed.description());
        assertEquals(List.of("src/*.ts", "src/*.tsx", "test"), parsed.paths());
        assertEquals("powershell", FrontmatterParser.parseShellFrontmatter(parsed.metadata().get("shell")));
        assertTrue(FrontmatterParser.parseBooleanFrontmatter("true"));
        assertFalse(FrontmatterParser.parseBooleanFrontmatter("TRUE"));
    }
}
