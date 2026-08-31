package com.claudecode.ui.lanterna.repl;

import com.claudecode.core.paste.PastedRefParser;
import com.claudecode.core.queue.QueuedCommand;
import com.claudecode.runtime.turn.UserInput;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

/**
 * Maps queued REPL commands into the text and provenance consumed by a turn.
 */
final class QueuedCommandMapper {

    private QueuedCommandMapper() {}

    /** True when a queued command would otherwise submit an empty user turn. */
    static boolean isBlankQueuedCommand(QueuedCommand cmd, String text) {
        boolean hasImagePaste = cmd.pastedContents() != null
            && cmd.pastedContents().values().stream()
                .anyMatch(PastedRefParser::isValidImagePaste);
        return StringUtils.isBlank(text) && !hasImagePaste;
    }

    /** Applies the queue-only provenance flags consumed by the neutral turn boundary. */
    static UserInput applyQueuedCommandProvenance(UserInput input, QueuedCommand cmd) {
        UserInput.Builder builder = input.toBuilder()
            .meta(cmd.isMeta())
            .suppressInitialAttachments(cmd.modelScheduledOrigin());
        if (Strings.CS.equals("session-host", cmd.originKind())) {
            builder.inputOrigin("remote");
        }
        if (Strings.CS.equals("task-notification", cmd.mode())) {
            builder.querySource("task-notification");
        }
        return builder.build();
    }




    static String envelope(QueuedCommand cmd) {
        String text = cmd.text();
        if (Strings.CS.equals("bash", cmd.mode()) && text != null) {
            return "<bash-input>" + text + "</bash-input>";
        }
        return text;
    }
}
