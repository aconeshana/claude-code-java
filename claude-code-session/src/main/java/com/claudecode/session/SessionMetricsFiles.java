package com.claudecode.session;

import com.claudecode.core.annotation.Explanation;
import java.nio.file.Path;
import java.util.Locale;
import org.apache.commons.lang3.Strings;

/** Storage-location kill switch; event/projection contracts are unchanged. */
@Explanation("provides a compatibility migration from custom transcript rows to a metrics sidecar")
final class SessionMetricsFiles {
    static final String SIDECAR_ENV = "CLAUDE_CODE_SESSION_METRICS_SIDECAR";

    private SessionMetricsFiles() {}

    static boolean useSidecar() {
        String value = System.getenv(SIDECAR_ENV);
        if (value == null) return false;
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "1", "true", "yes", "on" -> true;
            default -> false;
        };
    }

    static Path sidecar(Path transcript) {
        String name = transcript.getFileName().toString();
        String sidecarName = Strings.CS.endsWith(name, ".jsonl")
            ? name.substring(0, name.length() - ".jsonl".length()) + ".metrics.jsonl"
            : name + ".metrics.jsonl";
        return transcript.resolveSibling(sidecarName);
    }
}
