package com.claudecode.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.core.state.CwdState;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The validating half of a cross-project resume. {@code apply} is exercised end to end by the
 * runtime's {@code SessionLifecycleTest}; what matters here is that a target the session cannot
 * actually move into is rejected before anything is committed.
 */
class CliProjectSwitchTest {

    @TempDir
    Path projects;

    /** Collaborators are all optional: {@code prepare} touches none of them. */
    private static CliProjectSwitch validatorOnly() {
        return new CliProjectSwitch(null, null, null, null);
    }

    @Test
    void prepareRejectsAMissingTargetDirectory() {
        Path gone = projects.resolve("deleted-checkout");

        IOException failure = assertThrows(IOException.class,
            () -> validatorOnly().prepare(gone.toString()));

        assertTrue(Strings.CS.contains(failure.getMessage(), "no longer exists"),
            failure.getMessage());
    }

    @Test
    void prepareRejectsAFileMasqueradingAsAProject() throws IOException {
        Path notADirectory = Files.createFile(projects.resolve("repo.txt"));

        assertThrows(IOException.class, () -> validatorOnly().prepare(notADirectory.toString()));
    }

    @Test
    void prepareAcceptsAReadableDirectory() throws IOException {
        Path target = Files.createDirectory(projects.resolve("repo"));

        validatorOnly().prepare(target.toString());
    }

    @Test
    void currentProjectRootFollowsProjectIdentityRatherThanTheLaunchDirectory() throws IOException {
        Path original = CwdState.getOriginalCwd();
        Path target = Files.createDirectory(projects.resolve("moved")).toRealPath();
        try {
            CwdState.setOriginalCwd(target);
            assertEquals(target.toString(), CliProjectSwitch.currentProjectRoot());
        } finally {
            if (original != null) CwdState.setOriginalCwd(original);
        }
    }
}
