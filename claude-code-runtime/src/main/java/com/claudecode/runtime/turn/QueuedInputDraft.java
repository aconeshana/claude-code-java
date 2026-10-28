package com.claudecode.runtime.turn;

import com.claudecode.core.message.PastedContent;
import java.util.Map;

/**
 * Editable prompt reconstructed from the in-flight command queue.
 */
public record QueuedInputDraft(
        String text,
        int cursorOffset,
        Map<Integer, PastedContent> pastedContents,
        /** Every popped entry was a {@code bash} entry: the prompt returns in bash mode. */
        boolean bashMode
) {
    public QueuedInputDraft {
        text = text == null ? "" : text;
        pastedContents = pastedContents == null ? Map.of() : Map.copyOf(pastedContents);
    }

    public QueuedInputDraft(String text, int cursorOffset,
                            Map<Integer, PastedContent> pastedContents) {
        this(text, cursorOffset, pastedContents, false);
    }
}
