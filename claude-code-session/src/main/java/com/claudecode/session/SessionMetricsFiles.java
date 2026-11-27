package com.claudecode.session;

import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.config.EnvUtils;
import java.nio.file.Path;
import org.apache.commons.lang3.Strings;

/** Storage-location kill switch; event/projection contracts are unchanged. */
@Explanation("provides a compatibility migration from custom transcript rows to a metrics sidecar")
final class SessionMetricsFiles {
    static final String SIDECAR_ENV = "CLAUDE_CODE_SESSION_METRICS_SIDECAR";

    private SessionMetricsFiles() {}

    static boolean useSidecar() {
        return EnvUtils.isEnvTruthy(System.getenv(SIDECAR_ENV));
    }

    static Path sidecar(Path transcript) {
        String name = transcript.getFileName().toString();
        String sidecarName = Strings.CS.endsWith(name, ".jsonl")
            ? name.substring(0, name.length() - ".jsonl".length()) + ".metrics.jsonl"
            : name + ".metrics.jsonl";
        return transcript.resolveSibling(sidecarName);
    }
}
