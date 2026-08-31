package com.claudecode.runtime.query;


import org.apache.commons.lang3.Strings;
import com.claudecode.core.process.SubprocessEnvironment;

/**
 * Immutable query-level configuration snapshot, taken once at query entry.
 */
record QueryConfig(
    String sessionId,
    Gates gates) {


    public record Gates(
        boolean streamingToolExecution,
        boolean emitToolUseSummaries,
        boolean isAnt,
        boolean fastModeEnabled) {}

    /** Builds a {@link QueryConfig} from environment and session state. */
    public static QueryConfig build(String sessionId) {
        return new QueryConfig(
            sessionId,
            new Gates(
                false,
                envTrue("CLAUDE_CODE_EMIT_TOOL_USE_SUMMARIES"),
                Strings.CS.equals("ant", System.getenv("USER_TYPE")),
                !envTrue("CLAUDE_CODE_DISABLE_FAST_MODE")));
    }

    private static boolean envTrue(String key) {
        String v = SubprocessEnvironment.get(key);
        return Strings.CS.equals("1", v) || Strings.CI.equals("true", v);
    }
}
