package com.claudecode.runtime.turn;

import com.claudecode.core.message.PastedContent;
import java.util.Map;

/**
 * Editable prompt reconstructed from the in-flight command queue.
 */
public record QueuedInputDraft(
        String text,
        int cursorOffset,
        Map<Integer, PastedContent> pastedContents
) {
    public QueuedInputDraft {
        text = text == null ? "" : text;
        pastedContents = pastedContents == null ? Map.of() : Map.copyOf(pastedContents);
    }
}
