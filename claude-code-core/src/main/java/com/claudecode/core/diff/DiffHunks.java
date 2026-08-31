package com.claudecode.core.diff;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import java.util.ArrayList;
import java.util.List;


public final class DiffHunks {

    private static final int CONTEXT_LINES = 3;

    private DiffHunks() {}

    /**
     * Diff two full-file contents into unified hunks. Returns an empty list
     * when the contents are identical.
     */
    public static List<StructuredPatchHunk> compute(String oldContent, String newContent) {
        return compute(oldContent, newContent, CONTEXT_LINES);
    }


    public static List<StructuredPatchHunk> compute(String oldContent, String newContent, int contextLines) {
        List<String> oldLines = splitLines(oldContent);
        List<String> newLines = splitLines(newContent);
        List<Op> ops = editScript(lcsTable(oldLines, newLines), oldLines, newLines);
        return groupIntoHunks(ops, contextLines);
    }

    /**
     * Line-change counts for the process cost tracker.
     */
    public static long[] countLinesChanged(List<StructuredPatchHunk> hunks, String newFileContent) {
        if ((hunks == null || hunks.isEmpty()) && newFileContent != null && !newFileContent.isEmpty()) {
            return new long[] { newFileContent.split("\r?\n", -1).length, 0 };
        }
        long added = 0, removed = 0;
        if (hunks != null) {
            for (StructuredPatchHunk h : hunks) {
                if (h.lines() == null) continue;
                for (String line : h.lines()) {
                    if (Strings.CS.startsWith(line, "+")) added++;
                    else if (Strings.CS.startsWith(line, "-")) removed++;
                }
            }
        }
        return new long[] { added, removed };
    }

    private record Op(char kind, String text) {}

    private static List<String> splitLines(String content) {
        if (StringUtils.isEmpty(content)) return List.of();
        return List.of(content.split("\n", -1));
    }

    private static int[][] lcsTable(List<String> a, List<String> b) {
        int[][] len = new int[a.size() + 1][b.size() + 1];
        for (int i = a.size() - 1; i >= 0; i--) {
            for (int j = b.size() - 1; j >= 0; j--) {
                if (a.get(i).equals(b.get(j))) {
                    len[i][j] = len[i + 1][j + 1] + 1;
                } else {
                    len[i][j] = Math.max(len[i + 1][j], len[i][j + 1]);
                }
            }
        }
        return len;
    }

    /** Walk the LCS table into a flat edit script of (' '|'-'|'+') ops. */
    private static List<Op> editScript(int[][] len, List<String> a, List<String> b) {
        List<Op> ops = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < a.size() && j < b.size()) {
            if (a.get(i).equals(b.get(j))) {
                ops.add(new Op(' ', a.get(i)));
                i++;
                j++;
            } else if (len[i + 1][j] >= len[i][j + 1]) {
                ops.add(new Op('-', a.get(i)));
                i++;
            } else {
                ops.add(new Op('+', b.get(j)));
                j++;
            }
        }
        while (i < a.size()) {
            ops.add(new Op('-', a.get(i++)));
        }
        while (j < b.size()) {
            ops.add(new Op('+', b.get(j++)));
        }
        return ops;
    }

    /**
     * Group the edit script into hunks with {@value CONTEXT_LINES} lines of
     * context, merging changes whose context windows touch — the same
     * clustering the npm diff package performs.
     */
    private static List<StructuredPatchHunk> groupIntoHunks(List<Op> ops, int contextLines) {
        List<StructuredPatchHunk> hunks = new ArrayList<>();
        int oldLine = 1;
        int newLine = 1;

        int idx = 0;
        while (idx < ops.size()) {
            Op op = ops.get(idx);
            if (op.kind() == ' ') {
                oldLine++;
                newLine++;
                idx++;
                continue;
            }

            // Found a change: back up to include leading context.
            int start = idx;
            int leadingContext = 0;
            while (start > 0 && leadingContext < contextLines
                    && ops.get(start - 1).kind() == ' ') {
                start--;
                leadingContext++;
            }

            // Extend forward: consume changes, allowing runs of ≤ 2*context
            // equal lines between them (touching context windows merge).
            int end = idx;
            int lastChange = idx;
            while (end < ops.size()) {
                if (ops.get(end).kind() != ' ') {
                    lastChange = end;
                    end++;
                    continue;
                }
                // Count the equal-run length from here.
                int run = 0;
                int probe = end;
                while (probe < ops.size() && ops.get(probe).kind() == ' ') {
                    run++;
                    probe++;
                }
                if (probe < ops.size() && run <= contextLines * 2) {
                    end = probe;   // gap is small and another change follows — merge
                } else {
                    break;
                }
            }
            int trailingEnd = Math.min(ops.size(), lastChange + 1 + contextLines);

            int hunkOldStart = oldLine - leadingContext;
            int hunkNewStart = newLine - leadingContext;
            List<String> lines = new ArrayList<>();
            int hunkOldLines = 0;
            int hunkNewLines = 0;
            for (int k = start; k < trailingEnd; k++) {
                Op o = ops.get(k);
                lines.add(o.kind() + o.text());
                if (o.kind() == ' ') {
                    hunkOldLines++;
                    hunkNewLines++;
                    if (k >= idx) {
                        oldLine++;
                        newLine++;
                    }
                } else if (o.kind() == '-') {
                    hunkOldLines++;
                    oldLine++;
                } else {
                    hunkNewLines++;
                    newLine++;
                }
            }
            hunks.add(new StructuredPatchHunk(
                hunkOldStart, hunkOldLines, hunkNewStart, hunkNewLines, lines));
            idx = trailingEnd;
        }
        return hunks;
    }
}
