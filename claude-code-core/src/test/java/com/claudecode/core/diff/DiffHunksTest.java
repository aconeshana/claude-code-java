package com.claudecode.core.diff;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Covers {@link DiffHunks#compute} — the LCS + 3-context-line hunk grouping
 * that matches the npm {@code diff} package's {@code structuredPatch}.
 */
class DiffHunksTest {

    /** Joins lines l{from}..l{to} ("l1", "l2", …) with '\n'. */
    private static String numberedLines(int from, int to) {
        return IntStream.rangeClosed(from, to)
            .mapToObj(i -> "l" + i)
            .collect(Collectors.joining("\n"));
    }

    @Test
    void identicalContentYieldsNoHunks() {
        String content = "alpha\nbeta\ngamma";
        assertTrue(DiffHunks.compute(content, content).isEmpty());
        assertTrue(DiffHunks.compute("", "").isEmpty());
    }

    @Test
    void singleLineChangeYieldsOneHunkWithThreeContextLinesEachSide() {
        String oldContent = numberedLines(1, 9);
        String newContent = oldContent.replace("l5", "L5");

        List<StructuredPatchHunk> hunks = DiffHunks.compute(oldContent, newContent);

        assertEquals(1, hunks.size());
        StructuredPatchHunk hunk = hunks.getFirst();
        assertEquals(2, hunk.oldStart());
        assertEquals(7, hunk.oldLines());
        assertEquals(2, hunk.newStart());
        assertEquals(7, hunk.newLines());
        assertEquals(
            List.of(" l2", " l3", " l4", "-l5", "+L5", " l6", " l7", " l8"),
            hunk.lines());
    }

    @Test
    void changeAtFileHeadHasNoLeadingContext() {
        String oldContent = "a\nb\nc\nd\ne";
        String newContent = "A\nb\nc\nd\ne";

        List<StructuredPatchHunk> hunks = DiffHunks.compute(oldContent, newContent);

        assertEquals(1, hunks.size());
        StructuredPatchHunk hunk = hunks.getFirst();
        assertEquals(1, hunk.oldStart());
        assertEquals(1, hunk.newStart());
        assertEquals(List.of("-a", "+A", " b", " c", " d"), hunk.lines());
        assertEquals(4, hunk.oldLines());
        assertEquals(4, hunk.newLines());
    }

    @Test
    void changeAtFileTailHasNoTrailingContext() {
        String oldContent = "a\nb\nc\nd\ne";
        String newContent = "a\nb\nc\nd\nE";

        List<StructuredPatchHunk> hunks = DiffHunks.compute(oldContent, newContent);

        assertEquals(1, hunks.size());
        StructuredPatchHunk hunk = hunks.getFirst();
        assertEquals(2, hunk.oldStart());
        assertEquals(2, hunk.newStart());
        assertEquals(List.of(" b", " c", " d", "-e", "+E"), hunk.lines());
        assertEquals(4, hunk.oldLines());
        assertEquals(4, hunk.newLines());
    }

    @Test
    void changesMoreThanSixLinesApartProduceTwoHunks() {
        // Equal run of 9 lines (l3..l11) between the changes: > 2*context → split.
        String oldContent = numberedLines(1, 15);
        String newContent = oldContent.replace("l2", "X").replace("l12", "Y");

        List<StructuredPatchHunk> hunks = DiffHunks.compute(oldContent, newContent);

        assertEquals(2, hunks.size());

        StructuredPatchHunk first = hunks.getFirst();
        assertEquals(1, first.oldStart());
        assertEquals(1, first.newStart());
        assertEquals(List.of(" l1", "-l2", "+X", " l3", " l4", " l5"), first.lines());

        StructuredPatchHunk second = hunks.get(1);
        assertEquals(9, second.oldStart());
        assertEquals(9, second.newStart());
        assertEquals(
            List.of(" l9", " l10", " l11", "-l12", "+Y", " l13", " l14", " l15"),
            second.lines());
    }

    @Test
    void changesWithinSixLinesMergeIntoOneHunk() {
        // Equal run of 3 lines (l3..l5) between the changes: ≤ 2*context → merge.
        String oldContent = numberedLines(1, 10);
        String newContent = oldContent.replace("l2", "X").replace("l6", "Y");

        List<StructuredPatchHunk> hunks = DiffHunks.compute(oldContent, newContent);

        assertEquals(1, hunks.size());
        StructuredPatchHunk hunk = hunks.getFirst();
        assertEquals(1, hunk.oldStart());
        assertEquals(1, hunk.newStart());
        assertEquals(
            List.of(" l1", "-l2", "+X", " l3", " l4", " l5", "-l6", "+Y", " l7", " l8", " l9"),
            hunk.lines());
        assertEquals(9, hunk.oldLines());
        assertEquals(9, hunk.newLines());
    }

    @Test
    void pureAdditionYieldsPlusOnlyHunk() {
        List<StructuredPatchHunk> hunks = DiffHunks.compute("a\nb", "a\nx\nb");

        assertEquals(1, hunks.size());
        StructuredPatchHunk hunk = hunks.getFirst();
        assertEquals(List.of(" a", "+x", " b"), hunk.lines());
        assertEquals(2, hunk.oldLines());
        assertEquals(3, hunk.newLines());
        assertEquals(1, hunk.addedCount());
        assertEquals(0, hunk.removedCount());
    }

    @Test
    void pureDeletionYieldsMinusOnlyHunk() {
        List<StructuredPatchHunk> hunks = DiffHunks.compute("a\nx\nb", "a\nb");

        assertEquals(1, hunks.size());
        StructuredPatchHunk hunk = hunks.getFirst();
        assertEquals(List.of(" a", "-x", " b"), hunk.lines());
        assertEquals(3, hunk.oldLines());
        assertEquals(2, hunk.newLines());
        assertEquals(0, hunk.addedCount());
        assertEquals(1, hunk.removedCount());
    }

    @Test
    void countLinesChanged_countsAddedAndRemovedFromHunks() {
        // old: 3 lines; new: replace middle line + add one → +2 / -1
        var hunks = DiffHunks.compute("a\nb\nc\n", "a\nB\nc\nd\n");
        long[] counts = DiffHunks.countLinesChanged(hunks, null);
        assertEquals(2, counts[0], "added");
        assertEquals(1, counts[1], "removed");
    }

    @Test
    void countLinesChanged_newFileCountsAllLinesAsAdded() {
        long[] counts = DiffHunks.countLinesChanged(List.of(), "x\ny\nz");
        assertEquals(3, counts[0], "all lines added for a new file");
        assertEquals(0, counts[1]);
    }

    @Test
    void countLinesChanged_identicalContentIsZero() {
        long[] counts = DiffHunks.countLinesChanged(DiffHunks.compute("a\nb\n", "a\nb\n"), null);
        assertEquals(0, counts[0]);
        assertEquals(0, counts[1]);
    }
}
