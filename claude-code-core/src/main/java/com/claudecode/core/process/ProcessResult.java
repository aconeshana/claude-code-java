package com.claudecode.core.process;

import org.apache.commons.lang3.Strings;

import java.util.ArrayList;
import java.util.List;


public record ProcessResult(String stdout, String stderr, int exitCode, boolean timedOut) {

    /** True when the process ran to completion and exited zero. */
    public boolean succeeded() {
        return exitCode == 0 && !timedOut;
    }

    /** stdout split into lines (CRLF-aware), matching a reader's line view. */
    public List<String> stdoutLines() {
        return toLines(stdout);
    }

    /** stderr split into lines (CRLF-aware), matching a reader's line view. */
    public List<String> stderrLines() {
        return toLines(stderr);
    }

    /** A failed-to-run result (no process / interrupted), never timed out. */
    public static ProcessResult failure() {
        return new ProcessResult("", "", -1, false);
    }

/** A deadline-exceeded result; {@link #exitCode} is {@code -1}. */
    public static ProcessResult timeout() {
        return new ProcessResult("", "", -1, true);
    }

    private static List<String> toLines(String s) {
        List<String> lines = new ArrayList<>();
        for (String raw : s.split("\n", -1)) {
            if (raw.isEmpty()) continue;
            lines.add(Strings.CS.endsWith(raw, "\r") ? raw.substring(0, raw.length() - 1) : raw);
        }
        return lines;
    }
}
