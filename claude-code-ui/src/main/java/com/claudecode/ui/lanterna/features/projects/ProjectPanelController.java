package com.claudecode.ui.lanterna.features.projects;

import com.claudecode.ui.lanterna.repl.ProjectCatalogPort;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns the project drawer's open/close lifecycle and its threading: catalog
 * loads ({@link ProjectCatalogPort#listProjects()} may stat every transcript
 * directory and lite-read stale ones) run on the loader executor — virtual
 * threads in production — and results marshal back through the GUI invoker. A
 * generation counter drops loads that finish after the drawer was closed or
 * reopened, so a slow disk can never resurrect a dismissed drawer.
 *
 * <p>An open is two-staged: the cached listing paints first (no stat pass, no
 * transcript reads), then the revalidated one refreshes in place. On a warm
 * index the drawer is populated on the first frame and the user never sees the
 * loading hint; on a cold one the first stage is empty and the second behaves
 * exactly like a plain load. A Java-side extension with no 197 counterpart.
 */
public final class ProjectPanelController {
    private static final Logger log = LoggerFactory.getLogger(ProjectPanelController.class);

    private final ProjectCatalogPort port;
    private final ProjectPanel panel;
    private final Consumer<Runnable> loader;
    private final Consumer<Runnable> guiInvoker;
    private final ProjectPanel.Actions hostActions;
    private int generation;

    public ProjectPanelController(ProjectCatalogPort port, ProjectPanel panel,
                                  Consumer<Runnable> loader, Consumer<Runnable> guiInvoker,
                                  ProjectPanel.Actions hostActions) {
        this.port = Objects.requireNonNull(port);
        this.panel = Objects.requireNonNull(panel);
        this.loader = loader != null ? loader : Runnable::run;
        this.guiInvoker = guiInvoker != null ? guiInvoker : Runnable::run;
        this.hostActions = hostActions != null ? hostActions : new ProjectPanel.Actions(null, null, null);
    }

    /** Rebuilds the catalog cache in the background so the first open is cheap. */
    public void warmUp() {
        loader.accept(() -> {
            try {
                port.warmUp();
            } catch (RuntimeException failure) {
                log.debug("Project catalog warm-up failed; the drawer will load on demand", failure);
            }
        });
    }

    /** Toggles the drawer. Safe to call from the GUI thread (button, command). */
    public void toggle() {
        if (panel.isActive()) {
            generation++;
            panel.hide();
            if (hostActions.onClose() != null) hostActions.onClose().run();
            return;
        }
        int ticket = ++generation;
        panel.showLoading(panelActions());
        loader.accept(() -> {
            ProjectCatalogPort.ProjectPreferences prefs = load(
                port::projectPreferences, ProjectCatalogPort.ProjectPreferences.empty(),
                "Project preferences load failed; using defaults");
            List<ProjectCatalogPort.ProjectEntry> cached = load(
                port::cachedProjects, List.of(), "Project catalog cache read failed");
            boolean painted = !cached.isEmpty();
            if (painted) publish(ticket, cached, prefs, true);

            List<ProjectCatalogPort.ProjectEntry> fresh = load(
                port::listProjects, List.of(), "Project catalog load failed; showing empty drawer");
            publish(ticket, fresh, prefs, !painted);
        });
    }

    private static <T> T load(Supplier<T> source, T fallback, String message) {
        try {
            T value = source.get();
            return value != null ? value : fallback;
        } catch (RuntimeException failure) {
            log.warn(message, failure);
            return fallback;
        }
    }

    /**
     * @param initial {@code true} builds the drawer from scratch (focus at the
     *                top); {@code false} swaps the catalog underneath a drawer
     *                the user may already be navigating.
     */
    private void publish(int ticket, List<ProjectCatalogPort.ProjectEntry> projects,
                         ProjectCatalogPort.ProjectPreferences prefs, boolean initial) {
        guiInvoker.accept(() -> {
            if (ticket != generation || !panel.isActive()) return;
            if (initial) panel.show(projects, prefs, panelActions());
            else panel.refresh(projects);
        });
    }

    private ProjectPanel.Actions panelActions() {
        return new ProjectPanel.Actions(
            hostActions.onResume(), hostActions.onDelete(), hostActions.onPreview(),
            hostActions.onClose(), this::savePreferences);
    }

    /** Preference writes are small atomic file replaces, still kept off the GUI thread. */
    private void savePreferences(List<String> pinned, Map<String, Boolean> collapsed) {
        loader.accept(() -> {
            try {
                port.updateProjectPreferences(pinned, collapsed);
            } catch (RuntimeException failure) {
                log.warn("Project preferences save failed", failure);
            }
        });
    }
}
