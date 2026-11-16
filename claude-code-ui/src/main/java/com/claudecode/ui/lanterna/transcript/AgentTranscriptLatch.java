package com.claudecode.ui.lanterna.transcript;

import java.util.Objects;

/**
 * Tracks which viewed agent this view has already seeded from disk.
 *
 * <p>A backgrounded agent's messages are mirrored in memory as they stream, but that mirror is fed
 * only by tool-bearing progress content, so opening a view still needs one disk read to recover the
 * plain-text rows. Repeating that read on every repaint is what made switching between the main
 * transcript and a sub-agent feel slow on large agent transcripts. This latch keeps the read to the
 * first render of a view, matching the released client's {@code diskLoaded} flag, which is likewise
 * cleared when the view is left or switched.
 */
final class AgentTranscriptLatch {

    private String seededTaskId;

    /**
     * True when opening {@code taskId}'s view still requires a disk read. Viewing nothing (the main
     * transcript) never needs one.
     */
    boolean needsRead(String taskId) {
        return taskId != null && !Objects.equals(taskId, seededTaskId);
    }

    /** Records that {@code taskId}'s view has read its transcript, so later repaints skip the disk. */
    void markRead(String taskId) {
        seededTaskId = taskId;
    }

    /**
     * Points the latch at {@code taskId}: an already-seeded view keeps its latch, a different one
     * starts unseeded so its first render reads the transcript.
     */
    void viewChangedTo(String taskId) {
        if (!Objects.equals(seededTaskId, taskId)) seededTaskId = null;
    }
}