package com.claudecode.ui.lanterna.repl;

import com.claudecode.runtime.memory.MemoryCatalog;
import com.claudecode.runtime.startup.StartupTrustPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** State-machine coverage for the startup security-gate controller. */
class StartupGateControllerTest {

    @TempDir Path tempDir;

    @Test
    void untrustedWorkspaceAcceptsThenRunsExternalAndManagedGatesInOrder() {
        FakeTrust trust = new FakeTrust(false);
        FakeView view = new FakeView();
        Path external = tempDir.getParent().resolve("shared/CLAUDE.md");
        MemoryCatalog catalog = cwd -> List.of(
            new MemoryCatalog.File(external, MemoryCatalog.Scope.PROJECT, cwd.resolve("CLAUDE.md")));
        AtomicBoolean ready = new AtomicBoolean();
        AtomicReference<String> exit = new AtomicReference<>();
        AtomicReference<String> appliedEnvironmentCwd = new AtomicReference<>();
        StartupGateController controller = new StartupGateController(
            trust, catalog, view, () -> List.of("hooks"), _ -> {},
            appliedEnvironmentCwd::set);

        controller.start(tempDir, () -> ready.set(true),
            (reason, code) -> exit.set(reason + ":" + code));

        assertTrue(trust.migrated);
        assertEquals(List.of("trust"), view.events);
        view.trustAccept.run();
        assertTrue(trust.accepted);
        assertEquals(List.of("trust", "external"), view.events);
        assertEquals(List.of(external.toString()), view.externalPaths);
        view.externalAllow.run();
        assertEquals(Boolean.TRUE, trust.externalDecision);
        assertEquals(List.of("trust", "external", "managed"), view.events);
        view.managedAccept.run();
        assertTrue(ready.get());
        assertEquals(tempDir.toString(), appliedEnvironmentCwd.get());
        assertNull(exit.get());
    }

    @Test
    void externalDeclinePersistsFalseButStillContinues() {
        FakeTrust trust = new FakeTrust(true);
        FakeView view = new FakeView();
        Path external = tempDir.getParent().resolve("outside.md");
        StartupGateController controller = new StartupGateController(
            trust,
            cwd -> List.of(new MemoryCatalog.File(
                external, MemoryCatalog.Scope.LOCAL, cwd.resolve("CLAUDE.md"))),
            view, List::of, _ -> {});
        AtomicBoolean ready = new AtomicBoolean();

        controller.start(tempDir, () -> ready.set(true), (_, _) -> {});
        view.externalDisable.run();

        assertEquals(Boolean.FALSE, trust.externalDecision);
        assertTrue(ready.get());
    }

    @Test
    void priorExternalDecisionSkipsScanAndManagedRejectionExits() {
        FakeTrust trust = new FakeTrust(true);
        trust.warningShown = true;
        FakeView view = new FakeView();
        AtomicBoolean scanned = new AtomicBoolean();
        AtomicBoolean ready = new AtomicBoolean();
        AtomicReference<String> exit = new AtomicReference<>();
        StartupGateController controller = new StartupGateController(
            trust, _ -> {
                scanned.set(true);
                return List.of();
            }, view, () -> List.of("apiKeyHelper"), _ -> {});

        controller.start(tempDir, () -> ready.set(true),
            (reason, code) -> exit.set(reason + ":" + code));
        view.managedExit.run();

        assertFalse(scanned.get());
        assertFalse(ready.get());
        assertEquals("Managed settings not trusted by user:1", exit.get());
    }

    private static final class FakeTrust implements StartupTrustPort {
        boolean accepted;
        boolean migrated;
        boolean warningShown;
        Boolean externalDecision;

        FakeTrust(boolean accepted) {
            this.accepted = accepted;
        }

        @Override public void migrateLegacyTrust() { migrated = true; }
        @Override public boolean isTrustAccepted(Path cwd) { return accepted; }
        @Override public void acceptTrust(Path cwd) { accepted = true; }
        @Override public boolean hasExternalIncludesApproved(Path cwd) {
            return Boolean.TRUE.equals(externalDecision);
        }
        @Override public boolean hasExternalIncludesWarningShown(Path cwd) { return warningShown; }
        @Override public void saveExternalIncludesDecision(Path cwd, boolean approved) {
            externalDecision = approved;
            warningShown = true;
        }
    }

    private static final class FakeView implements StartupGateController.View {
        final List<String> events = new ArrayList<>();
        List<String> externalPaths = List.of();
        Runnable trustAccept;
        Runnable externalAllow;
        Runnable externalDisable;
        Runnable managedAccept;
        Runnable managedExit;

        @Override
        public void promptTrust(Path cwd, Runnable onAccept, Runnable onExit) {
            events.add("trust");
            trustAccept = onAccept;
        }

        @Override
        public void promptExternalIncludes(Path cwd, List<String> paths,
                                           Runnable onAllow, Runnable onDisable,
                                           Runnable onExit) {
            events.add("external");
            externalPaths = paths;
            externalAllow = onAllow;
            externalDisable = onDisable;
        }

        @Override
        public void promptManagedSettings(Path cwd, List<String> items,
                                          Runnable onAccept, Runnable onExit) {
            events.add("managed");
            managedAccept = onAccept;
            managedExit = onExit;
        }
    }
}
