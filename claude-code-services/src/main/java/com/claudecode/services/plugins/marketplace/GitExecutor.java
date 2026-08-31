package com.claudecode.services.plugins.marketplace;

import java.nio.file.Path;
import java.util.List;

/**
 * Runs git subprocesses for marketplace/plugin operations.
 */
public interface GitExecutor {

    /**
     * Runs {@code git <args>} in {@code cwd} (nullable — inherit process cwd).
     * Never throws for non-zero exits; process-spawn failures surface as a
     * non-zero code with the exception message in stderr.
     */
    GitResult run(Path cwd, List<String> args);

    record GitResult(int code, String stdout, String stderr) {
        public boolean ok() {
            return code == 0;
        }
    }
}
