package com.claudecode.ui.lanterna.features.memory;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.googlecode.lanterna.TextColor;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.claudecode.ui.lanterna.repl.ReplTranscriptSink;

/** Unit coverage for {@link MemoryFeature}'s GUI-free result orchestration and path shortening. */
class MemoryFeatureTest {

    /** Captures transcript output written through the sink seam. */
    private static final class CapturingSink implements ReplTranscriptSink {
        final List<String> lines = new ArrayList<>();
        @Override public void system(String text) { lines.add("system:" + text); }
        @Override public void breadcrumb(String commandLabel) { lines.add("breadcrumb:" + commandLabel); }
        @Override public void line(String text, TextColor color) { lines.add(text); }
    }

    @Test
    void editMemoryFileEchoesOpenedCaptionBeforeEditorLaunch() {
        CapturingSink sink = new CapturingSink();
        // Null GUI/dialog: openFileInEditor short-circuits, so only the caption line is emitted.
        MemoryFeature feature = new MemoryFeature(null, sink);
        Path target = Path.of(System.getProperty("user.home")).resolve("CLAUDE.md");

        feature.editMemoryFile(target);

        assertEquals(1, sink.lines.size());
        assertTrue(Strings.CS.startsWith(sink.lines.getFirst(), "  Opened memory file at "),
            "caption should precede the editor launch: " + sink.lines);
    }

    @Test
    void openFileInEditorIsANoOpWithoutAGui() {
        CapturingSink sink = new CapturingSink();
        MemoryFeature feature = new MemoryFeature(null, sink);

        feature.openFileInEditor(Path.of("/tmp/CLAUDE.md"));

        assertTrue(sink.lines.isEmpty(),
            "no editor hint should surface when the GUI isn't up: " + sink.lines);
    }

    @Test
    void shortenMemoryPathPrefersHomeShorthand() {
        Path homeFile = Path.of(System.getProperty("user.home")).resolve(".claude/CLAUDE.md");
        assertTrue(Strings.CS.startsWith(MemoryFeature.shortenMemoryPath(homeFile), "~/"),
            "home-relative paths shorten to ~/");
    }

    @Test
    void shortenMemoryPathPrefersCwdShorthandForProjectFiles() {
        Path cwdFile = Path.of(System.getProperty("user.dir")).resolve("CLAUDE.md");
        String shortened = MemoryFeature.shortenMemoryPath(cwdFile);
        assertTrue(Strings.CS.startsWith(shortened, "./") || Strings.CS.startsWith(shortened, "~/"),
            "cwd/home-relative paths shorten, got: " + shortened);
    }

    @Test
    void shortenMemoryPathFallsBackToAbsoluteForForeignPaths() {
        Path foreign = Path.of("/nonexistent-root-xyz/CLAUDE.md");
        assertEquals(foreign.toAbsolutePath().normalize().toString(),
            MemoryFeature.shortenMemoryPath(foreign));
    }

    @Test
    void shortenMemoryPathDoesNotTreatAPathPrefixSiblingAsHome() {
        Path home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
        Path sibling = home.resolveSibling(home.getFileName() + "-sibling").resolve("CLAUDE.md");

        assertEquals(sibling.toString(), MemoryFeature.shortenMemoryPath(sibling));
    }

    @Test
    void shortenMemoryPathHandlesTheWorkingDirectoryItself() {
        Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();

        assertFalse(StringUtils.isBlank(MemoryFeature.shortenMemoryPath(cwd)));
    }
}
