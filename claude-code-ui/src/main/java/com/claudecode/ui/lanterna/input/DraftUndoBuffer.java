package com.claudecode.ui.lanterna.input;

import com.claudecode.core.message.PastedContent;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;

/**
 * Bounded history of prompt states used by {@code chat:undo}.
 */
final class DraftUndoBuffer {

    private static final long DEBOUNCE_NANOS = 1_000_000_000L;

    record Snapshot(String text, int cursorOffset,
                    Map<Integer, PastedContent> pastedContents,
                    InputPanel.Mode modeOverride) {
        Snapshot {
            text = text == null ? "" : text;
            cursorOffset = Math.max(0, Math.min(cursorOffset, text.length()));
            pastedContents = pastedContents == null ? Map.of() : Map.copyOf(pastedContents);
        }
    }

    private final int maximumSize;
    private final Deque<Snapshot> entries = new ArrayDeque<>();
    private long lastRecordNanos;
    /** Latest rapid-edit state; materialized when typing pauses or undo runs. */
    private Snapshot pending;

    DraftUndoBuffer(int maximumSize) {
        if (maximumSize < 1) throw new IllegalArgumentException("maximumSize must be positive");
        this.maximumSize = maximumSize;
    }

    synchronized void record(Snapshot snapshot) {
        pending = null;
        add(snapshot);
        lastRecordNanos = System.nanoTime();
    }


    synchronized void recordDebounced(Snapshot snapshot) {
        if (snapshot == null) return;
        long now = System.nanoTime();
        if (lastRecordNanos == 0 || now - lastRecordNanos >= DEBOUNCE_NANOS) {
            pending = null;
            add(snapshot);
            lastRecordNanos = now;
        } else {
            pending = snapshot;
        }
    }

    private void add(Snapshot snapshot) {
        if (snapshot == null || snapshot.equals(entries.peekLast())) return;
        entries.addLast(snapshot);
        while (entries.size() > maximumSize) entries.removeFirst();
    }

    synchronized Snapshot undo() {

        // timeout would eventually materialize this same latest pre-edit
        // snapshot; flush it synchronously so undo remains responsive.
        if (pending != null) {
            add(pending);
            pending = null;
        }
        return entries.pollLast();
    }

    synchronized void clear() {
        entries.clear();
        pending = null;
        lastRecordNanos = 0;
    }
}
