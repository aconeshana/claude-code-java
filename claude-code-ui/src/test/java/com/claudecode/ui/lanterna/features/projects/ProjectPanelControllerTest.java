package com.claudecode.ui.lanterna.features.projects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.ui.lanterna.repl.ProjectCatalogPort;
import com.claudecode.ui.lanterna.repl.ProjectCatalogPort.ProjectEntry;
import com.claudecode.ui.lanterna.repl.ProjectCatalogPort.ProjectSessionEntry;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * {@link ProjectPanelController} threading contract: catalog loads run off the
 * GUI thread, results marshal back through the GUI invoker, and stale loads
 * (finished after the drawer closed) are dropped. Preferences save through the
 * port, also off the GUI thread. An open is two-staged — the cached listing
 * paints first, the revalidated one refreshes in place without disturbing focus.
 */
class ProjectPanelControllerTest {

    /** Pumped-by-test port. */
    private static final class FakePort implements ProjectCatalogPort {
        List<ProjectEntry> projects = List.of();
        List<ProjectEntry> cached = List.of();
        ProjectPreferences prefs = ProjectPreferences.empty();
        AtomicInteger warmUps = new AtomicInteger();
        AtomicReference<List<String>> savedPinned = new AtomicReference<>();
        AtomicReference<Map<String, Boolean>> savedCollapsed = new AtomicReference<>();

        @Override public List<ProjectEntry> listProjects() { return projects; }
        @Override public List<ProjectEntry> cachedProjects() { return cached; }
        @Override public void warmUp() { warmUps.incrementAndGet(); }
        @Override public ProjectPreferences projectPreferences() { return prefs; }
        @Override public void updateProjectPreferences(List<String> pinned,
                                                       Map<String, Boolean> collapsed) {
            savedPinned.set(pinned);
            savedCollapsed.set(collapsed);
        }
    }

    private static ProjectSessionEntry session(String id, String cwd, long modified) {
        return new ProjectSessionEntry(id, modified, Instant.ofEpochMilli(modified), 1,
            "s-" + id, "main", cwd, null, null, null, 10, Path.of(id + ".jsonl"));
    }

    private static ProjectEntry project(String path, String name, long activity) {
        return new ProjectEntry(path, name, 1, activity,
            List.of(session("s1", path, activity)));
    }

    /** Controller + manually pumped loader/GUI queues (test = single threaded). */
    private static final class Harness {
        final FakePort port = new FakePort();
        final Queue<Runnable> loader = new ArrayDeque<>();
        final Queue<Runnable> gui = new ArrayDeque<>();
        final ProjectPanel panel = new ProjectPanel(() -> 100, () -> 30);
        final ProjectPanelController controller = new ProjectPanelController(
            port, panel, loader::add, gui::add, new ProjectPanel.Actions(null, null, null));

        void pumpLoader() { while (!loader.isEmpty()) loader.poll().run(); }
        void pumpGui() { while (!gui.isEmpty()) gui.poll().run(); }
        void pumpAll() { pumpLoader(); pumpGui(); }
    }

    @Test
    void toggleOpensWithLoadingThenPopulatesOnGuiThread() {
        Harness h = new Harness();
        h.port.projects = List.of(project("/p/a", "alpha", 1000));

        h.controller.toggle();
        assertTrue(h.panel.isActive(), "drawer activates immediately (loading state)");

        h.pumpAll();
        assertTrue(h.panel.isActive());
        assertEquals("/p/a", h.panel.focusedProjectPathForTest(), "catalog result populated");
    }

    @Test
    void toggleAgainCloses() {
        Harness h = new Harness();
        h.controller.toggle();
        h.pumpAll();
        h.controller.toggle();
        assertFalse(h.panel.isActive());
    }

    @Test
    void staleLoadArrivingAfterCloseIsDropped() {
        Harness h = new Harness();
        h.port.projects = List.of(project("/p/a", "alpha", 1000));

        h.controller.toggle();
        h.controller.toggle(); // close before the load finishes
        h.pumpAll();

        assertFalse(h.panel.isActive(), "late result must not reopen the drawer");
    }

    @Test
    void collapseChangeSavesPreferencesOffGuiThread() {
        Harness h = new Harness();
        h.port.projects = List.of(project("/p/a", "alpha", 1000));
        h.controller.toggle();
        h.pumpAll();

        // expand the focused project → panel fires onPreferencesChanged
        h.panel.handleKey(new KeyStroke(
            KeyType.ARROW_RIGHT), new AtomicBoolean(true));
        h.pumpLoader();

        assertEquals(Map.of("/p/a", false), h.port.savedCollapsed.get());
        assertEquals(List.of(), h.port.savedPinned.get());
    }

    @Test
    void reopenRefreshesFromPort() {
        Harness h = new Harness();
        h.port.projects = List.of(project("/p/a", "alpha", 1000));
        h.controller.toggle();
        h.pumpAll();
        assertEquals("/p/a", h.panel.focusedProjectPathForTest());
        h.controller.toggle();

        h.port.projects = List.of(project("/p/b", "beta", 2000));
        h.controller.toggle();
        h.pumpAll();
        assertEquals("/p/b", h.panel.focusedProjectPathForTest(), "each open reloads");
    }

    @Test
    void portFailureLeavesDrawerWithEmptyStateNotCrash() {
        Harness h = new Harness();
        ProjectCatalogPort broken = new ProjectCatalogPort() {
            @Override public List<ProjectEntry> listProjects() {
                throw new RuntimeException("disk exploded");
            }
        };
        ProjectPanelController controller = new ProjectPanelController(
            broken, h.panel, h.loader::add, h.gui::add, new ProjectPanel.Actions(null, null, null));

        controller.toggle();
        h.pumpAll(); // must not throw

        assertTrue(h.panel.isActive());
        assertNull(h.panel.focusedProjectPathForTest(), "failure degrades to the empty state");
    }

    @Test
    void cachedListingPaintsBeforeTheRevalidatedOne() {
        Harness h = new Harness();
        h.port.cached = List.of(project("/p/stale", "stale", 1000));
        h.port.projects = List.of(project("/p/a", "alpha", 3000));

        h.controller.toggle();
        h.pumpLoader();

        // Both stages ran on the loader; draining the GUI queue one task at a
        // time proves the cheap one is published first.
        h.gui.poll().run();
        assertEquals("/p/stale", h.panel.focusedProjectPathForTest(), "cache paints first");
        h.pumpGui();
        assertEquals("/p/a", h.panel.focusedProjectPathForTest(),
            "the revalidated listing replaces it");
    }

    @Test
    void refreshKeepsTheUsersPlaceInTheTree() {
        Harness h = new Harness();
        h.port.cached = List.of(project("/p/a", "alpha", 3000),
            project("/p/b", "beta", 2000));
        h.port.projects = h.port.cached;

        h.controller.toggle();
        h.pumpLoader();
        h.gui.poll().run();                                   // cached stage

        h.panel.handleKey(new KeyStroke(KeyType.ARROW_DOWN), new AtomicBoolean(true));
        assertEquals("/p/b", h.panel.focusedProjectPathForTest());

        h.pumpGui();                                          // revalidated stage
        assertEquals("/p/b", h.panel.focusedProjectPathForTest(),
            "a refresh must not feel like the drawer reopened");
    }

    @Test
    void escapeClosesWhileStillLoading() {
        Harness h = new Harness();
        h.port.projects = List.of(project("/p/a", "alpha", 1000));

        h.controller.toggle();                                // load not pumped yet
        h.panel.handleKey(new KeyStroke(KeyType.ESCAPE), new AtomicBoolean(true));

        assertFalse(h.panel.isActive(), "the drawer must never feel wedged mid-load");
        h.pumpAll();
        assertFalse(h.panel.isActive(), "and the in-flight load must not revive it");
    }

    @Test
    void warmUpRunsOffTheGuiThread() {
        Harness h = new Harness();

        h.controller.warmUp();
        assertEquals(0, h.port.warmUps.get(), "never on the caller's (GUI) thread");
        h.pumpLoader();
        assertEquals(1, h.port.warmUps.get());
    }
}
