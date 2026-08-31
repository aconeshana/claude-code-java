package com.claudecode.app.smoke;

import java.util.List;
import java.util.Set;

/**
 * What one smoke launch produced, and the questions worth asking of it.
 * <p>The crash signatures are kept apart from the per-case expectations on purpose. A case
 * declares the exit code and output it wants; these are the failures no case would ever want,
 * and they are the reason this layer exists at all. {@code --print} swallows a great deal:
 * a native binary missing reflection metadata for one constructor can still exit 0 on some
 * paths, so an exit-code-only assertion would have let the very bug that motivated this
 * harness through.
 */
record SmokeOutcome(int exitCode, String stdout, String stderr, boolean timedOut) {

    private static final Set<String> CRASH_SIGNATURES = Set.of(
        "MissingReflectionRegistrationError",
        "MissingResourceRegistrationError",
        "NoClassDefFoundError",
        "ExceptionInInitializerError",
        "UnsatisfiedLinkError",
        "ClassNotFoundException",
        "Exception in thread \"main\"");

    /** Signatures actually present, so a failure message can name them instead of hinting. */
    List<String> crashSignatures() {
        String combined = stdout + '\n' + stderr;
        return CRASH_SIGNATURES.stream().filter(combined::contains).sorted().toList();
    }

    /** Both channels, labelled, for a failure message that does not require re-running by hand. */
    String transcript() {
        return """

            --- exit code: %s%s
            --- stdout ---
            %s
            --- stderr ---
            %s"""
            .formatted(exitCode, timedOut ? " (timed out)" : "", truncate(stdout), truncate(stderr));
    }

    /**
     * {@code --debug} output runs to thousands of lines and the tail is where a failure lands, so
     * the head is what gets dropped.
     */
    private static String truncate(String text) {
        int limit = 4_000;
        return text.length() <= limit
            ? text
            : "…(" + (text.length() - limit) + " earlier characters omitted)\n"
                + text.substring(text.length() - limit);
    }
}
