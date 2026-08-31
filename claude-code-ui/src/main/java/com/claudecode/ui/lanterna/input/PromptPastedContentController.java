package com.claudecode.ui.lanterna.input;

import com.claudecode.core.message.PastedContent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Owns the pasted-content identity map and the lazy-space invariant for prompt chips.
 */
final class PromptPastedContentController {

    private final Map<Integer, PastedContent> contents = new ConcurrentHashMap<>();
    private final AtomicInteger nextId = new AtomicInteger();
    private boolean pendingSpaceAfterChip;

    int nextId() {
        return nextId.incrementAndGet();
    }

    void put(PastedContent content) {
        if (content == null) return;
        contents.put(content.id(), content);
        nextId.accumulateAndGet(content.id(), Math::max);
    }

    void remove(int id) {
        contents.remove(id);
    }

    boolean isEmpty() {
        return contents.isEmpty();
    }

    void clear() {
        contents.clear();
        pendingSpaceAfterChip = false;
    }

    Map<Integer, PastedContent> snapshot() {
        return contents.isEmpty() ? Map.of() : Map.copyOf(contents);
    }

    Collection<PastedContent> valuesSnapshot() {
        return new ArrayList<>(contents.values());
    }

    void restore(Map<Integer, PastedContent> restored) {
        contents.clear();
        if (restored != null) {
            restored.values().forEach(this::put);
        }
        pendingSpaceAfterChip = false;
    }

    /** Returns a separator for a new chip, then sets the lazy-space state. */
    String prefixBeforeChipAndArm(boolean armLazySpace) {
        String prefix = pendingSpaceAfterChip ? " " : "";
        pendingSpaceAfterChip = armLazySpace;
        return prefix;
    }

    /** Consumes the state for any key and reports whether a synthetic space is needed. */
    boolean consumeLazySpace(boolean printableNonSpace) {
        boolean insert = pendingSpaceAfterChip && printableNonSpace;
        pendingSpaceAfterChip = false;
        return insert;
    }
}
