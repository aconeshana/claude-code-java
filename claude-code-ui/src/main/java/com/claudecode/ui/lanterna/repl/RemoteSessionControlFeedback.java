package com.claudecode.ui.lanterna.repl;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import com.claudecode.core.annotation.Explanation;
import com.claudecode.runtime.sessionhost.SessionHostEffortState;
import java.util.Locale;

/**
 * Formats concise terminal feedback for state changed through an IM endpoint.
 */
@Explanation("Names the remote collaboration source in local TUI feedback")
final class RemoteSessionControlFeedback {

    private RemoteSessionControlFeedback() {}

    static String effortChanged(SessionHostEffortState state, String channel) {
        String current = StringUtils.isBlank(state.current()) ? state.effective() : state.current();
        StringBuilder message = new StringBuilder("Reasoning effort is now ")
            .append(current);
        if (Strings.CS.equals("auto", current) && !StringUtils.isBlank(state.effective())) {
            message.append(" (currently ").append(state.effective()).append(')');
        }
        message.append(" for this session");
        String normalizedChannel = channel == null ? "" : channel.trim().toLowerCase(Locale.ROOT);
        if (!StringUtils.isBlank(normalizedChannel)) {
            message.append(" (via ")
                .append(Character.toUpperCase(normalizedChannel.charAt(0)))
                .append(normalizedChannel.substring(1))
                .append(')');
        }
        return message.append('.').toString();
    }
}
