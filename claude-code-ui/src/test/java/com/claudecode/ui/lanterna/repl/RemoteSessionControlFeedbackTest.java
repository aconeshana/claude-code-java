package com.claudecode.ui.lanterna.repl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.claudecode.runtime.sessionhost.SessionHostEffortState;
import java.util.List;
import org.junit.jupiter.api.Test;

class RemoteSessionControlFeedbackTest {

    @Test
    void effortChangeNamesTheAuthoritativeLevelAndChannel() {
        SessionHostEffortState state = new SessionHostEffortState(
            "xhigh", "xhigh", List.of("auto", "low", "xhigh"));

        assertEquals(
            "Reasoning effort is now xhigh for this session (via Feishu).",
            RemoteSessionControlFeedback.effortChanged(state, "feishu"));
    }

    @Test
    void autoEffortIncludesItsEffectiveLevel() {
        SessionHostEffortState state = new SessionHostEffortState(
            "auto", "medium", List.of("auto", "low", "medium"));

        assertEquals(
            "Reasoning effort is now auto (currently medium) for this session.",
            RemoteSessionControlFeedback.effortChanged(state, ""));
    }
}
