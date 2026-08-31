package com.claudecode.cli;

import com.claudecode.session.SessionManager;
import com.claudecode.tools.plan.PlanFiles;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CliPermissionPathsTest {

    @Test
    void plansCarveOutUsesConfiguredPlansDirectory() {
        Path cwd = Path.of("/workspace/project");
        Path configured = Path.of("/workspace/project/.claude/custom-plans");
        Path previous = PlanFiles.getPlansDirectory();
        PlanFiles.configurePlansDirectory(configured);
        try {
            CliPermissionPaths paths = new CliPermissionPaths(cwd, "session-123");

            assertTrue(paths.internalEditablePaths().contains(configured));
            assertTrue(paths.internalReadablePaths().contains(configured));
        } finally {
            PlanFiles.configurePlansDirectory(previous);
        }
    }

    @Test
    void toolResultsCarveOutUsesOriginalSessionScopedDirectory() {
        Path cwd = Path.of("/workspace/project");
        String sessionId = "session-123";
        Path expected = new SessionManager(cwd.toString()).getToolResultsDir(sessionId);

        CliPermissionPaths paths = new CliPermissionPaths(cwd, sessionId);

        assertTrue(paths.internalEditablePaths().contains(expected));
        assertTrue(paths.internalReadablePaths().contains(expected));
    }
}
