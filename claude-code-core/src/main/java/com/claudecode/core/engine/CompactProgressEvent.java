package com.claudecode.core.engine;




/**
 * Progress events emitted by the compact flow (manual /compact and auto-compact).
 */
public sealed interface CompactProgressEvent {

    /**
     * Fired before Pre/PostCompact or SessionStart hooks run.
     *
     * @param hookType one of {@code "pre_compact"}, {@code "post_compact"}, {@code "session_start"}
     */
    record HooksStart(String hookType) implements CompactProgressEvent {}

    /** Fired when the LLM-summarisation step starts. */
    record CompactStart() implements CompactProgressEvent {}

    /** Fired in the finally block after compaction completes (success or failure). */
    record CompactEnd() implements CompactProgressEvent {}
}
