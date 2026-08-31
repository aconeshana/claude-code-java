package com.claudecode.ui.lanterna.components;

import java.util.List;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;


class SpinnerVerbsTest {

    @Test
    void activeVerbs_matchesTsCount() {

        assertEquals(187, SpinnerVerbs.activeVerbs().size(),
            "active-verbs.txt must have 187 entries to match TS SPINNER_VERBS");
    }

    @Test
    void activeVerbs_preservesTsInsertionOrder() {

        // (e.g. Gesticulating before Germinating). Spot-check a known pair
        // to catch silent re-sorting when editing active-verbs.txt.
        int gesticulating = SpinnerVerbs.activeVerbs().indexOf("Gesticulating");
        int germinating   = SpinnerVerbs.activeVerbs().indexOf("Germinating");
        assertTrue(gesticulating >= 0 && germinating >= 0,
            "both verbs must be present");
        assertTrue(gesticulating < germinating,
            "Gesticulating must precede Germinating to match TS source order");
    }

    @Test
    void activeVerbs_noDuplicates() {
        Set<String> seen = new HashSet<>(SpinnerVerbs.activeVerbs());
        assertEquals(SpinnerVerbs.activeVerbs().size(), seen.size(),
            "active spinner verbs must be unique");
    }

    @Test
    void activeVerbs_canonicalAnchorsPresent() {
        for (String anchor : new String[]{
            "Brewing", "Churning", "Cooking", "Crunching", "Cogitating",
            "Sautéing", "Working", "Baking", "Pondering", "Thinking"
        }) {
            assertTrue(SpinnerVerbs.activeVerbs().contains(anchor),
                "missing canonical active verb: " + anchor);
        }
    }

    @Test
    void randomActive_returnsMember() {
        for (int i = 0; i < 10; i++) {
            String v = SpinnerVerbs.randomActive();
            assertTrue(SpinnerVerbs.activeVerbs().contains(v),
                "randomActive() returned non-member: " + v);
        }
    }

    @Test
    void completionVerbs_eightCanonicalEntries() {

        assertEquals(8, SpinnerVerbs.completionVerbs().size());
        for (String anchor : new String[]{
            "Baked", "Brewed", "Churned", "Cogitated",
            "Cooked", "Crunched", "Sautéed", "Worked"
        }) {
            assertTrue(SpinnerVerbs.completionVerbs().contains(anchor),
                "missing canonical completion verb: " + anchor);
        }
    }

    @Test
    void randomCompleted_returnsMember() {
        for (int i = 0; i < 10; i++) {
            String v = SpinnerVerbs.randomCompleted();
            assertTrue(SpinnerVerbs.completionVerbs().contains(v),
                "randomCompleted() returned non-member: " + v);
        }
    }

    @Test
    void forTool_fixedMapping() {
        assertEquals("Running",    SpinnerVerbs.forTool("Bash"));
        assertEquals("Running",    SpinnerVerbs.forTool("REPL"));
        assertEquals("Running",    SpinnerVerbs.forTool("PowerShell"));
        assertEquals("Reading",    SpinnerVerbs.forTool("Read"));
        assertEquals("Writing",    SpinnerVerbs.forTool("Write"));
        assertEquals("Editing",    SpinnerVerbs.forTool("Edit"));
        assertEquals("Editing",    SpinnerVerbs.forTool("MultiEdit"));
        assertEquals("Editing",    SpinnerVerbs.forTool("NotebookEdit"));
        assertEquals("Searching",  SpinnerVerbs.forTool("Grep"));
        assertEquals("Searching",  SpinnerVerbs.forTool("WebSearch"));
        assertEquals("Globbing",   SpinnerVerbs.forTool("Glob"));
        assertEquals("Fetching",   SpinnerVerbs.forTool("WebFetch"));
        assertEquals("Delegating", SpinnerVerbs.forTool("Agent"));
        assertEquals("Delegating", SpinnerVerbs.forTool("Task"));
        assertEquals("Planning",   SpinnerVerbs.forTool("TodoWrite"));
        assertEquals("Planning",   SpinnerVerbs.forTool("TaskCreate"));
    }

    @Test
    void forTool_unknownFallsBackToRandomActive() {
        String v = SpinnerVerbs.forTool("SomeCustomMcpTool_123");
        assertTrue(SpinnerVerbs.activeVerbs().contains(v),
            "unknown tool name must fall back to an active-verb random pick");
    }

    @Test
    void forTool_nullFallsBackToRandomActive() {
        String v = SpinnerVerbs.forTool(null);
        assertTrue(SpinnerVerbs.activeVerbs().contains(v),
            "null tool name must fall back to an active-verb random pick");
    }



    @Test
    void spliceUserOverride_nullOverrideReturnsBuiltIn() {
        List<String> builtIn = List.of("A", "B");
        assertSame(builtIn, SpinnerVerbs.spliceUserOverride(builtIn, null));
    }

    @Test
    void spliceUserOverride_replaceUsesUserVerbs() {
        List<String> builtIn = List.of("A", "B");
        var out = SpinnerVerbs.spliceUserOverride(
            builtIn,
            new SpinnerVerbs.SpinnerVerbsOverride("replace", List.of("Foo", "Bar")));
        assertEquals(List.of("Foo", "Bar"), out);
    }

    @Test
    void spliceUserOverride_replaceEmptyFallsBackToBuiltIn() {

        List<String> builtIn = List.of("A", "B");
        assertSame(builtIn, SpinnerVerbs.spliceUserOverride(
            builtIn,
            new SpinnerVerbs.SpinnerVerbsOverride("replace", List.of())));
    }

    @Test
    void spliceUserOverride_appendConcatenates() {
        List<String> builtIn = List.of("A", "B");
        var out = SpinnerVerbs.spliceUserOverride(
            builtIn,
            new SpinnerVerbs.SpinnerVerbsOverride("append", List.of("Foo", "Bar")));
        assertEquals(List.of("A", "B", "Foo", "Bar"), out);
    }

    @Test
    void spliceUserOverride_unknownModeAppends() {

        List<String> builtIn = List.of("A", "B");
        var out = SpinnerVerbs.spliceUserOverride(
            builtIn,
            new SpinnerVerbs.SpinnerVerbsOverride("bogus-mode", List.of("Foo")));
        assertEquals(List.of("A", "B", "Foo"), out);
    }
}
