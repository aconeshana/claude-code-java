package com.claudecode.app.smoke;

import java.util.List;

/**
 * One flag, the exact process invocation it becomes, and what that process must do.
 */
record SmokeCase(
        String entryId,
        List<String> argv,
        int expectExit,
        String expectStdout,
        String expectStderr,
        List<SmokePlan.TranscriptExpectation> transcriptExpectations,
        String note) {

    String describe(SmokeTarget target) {
        return target.name() + ": " + entryId + ' ' + String.join(" ", argv);
    }
}
