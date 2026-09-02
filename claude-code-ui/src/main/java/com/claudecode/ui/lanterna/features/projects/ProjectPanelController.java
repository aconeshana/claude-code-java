package com.claudecode.ui.lanterna.features.projects;

import com.claudecode.ui.lanterna.repl.ProjectCatalogPort;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns the project drawer's open/close lifecycle and its threading: catalog
 * loads ({@link ProjectCatalogPort#listProjects()} may stat every transcript
 * directory and lite-read stale ones) run on the loader executor — virtual
 * threads in production — and results marshal back through the GUI invoker. A
 * generation counter drops loads that finish after the drawer was closed or
 * reopened, so a slow disk can never resurrect a dismissed drawer. A Java-side
 * extension with no 197 counterpart.
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

    /** Toggles the drawer. Safe to call from the GUI thread (button, command). */
    public void toggle() {
        if (panel.isActive()) {
            generation++;
            panel.hide();
            if (hostActions.onClose() != null) hostActions.onClose().run();
            return;
        }
        int ticket = ++generation;
        panel.showLoading();
        loader.accept(() -> {
            List<ProjectCatalogPort.ProjectEntry> projects;
            ProjectCatalogPort.ProjectPreferences prefs;
            try {
                projects = port.listProjects();
                prefs = port.projectPreferences();
            } catch (RuntimeException failure) {
                log.warn("Project catalog load failed; showing empty drawer", failure);
                projects = List.of();
                prefs = ProjectCatalogPort.ProjectPreferences.empty();
            }
            List<ProjectCatalogPort.ProjectEntry> loaded = projects;
            ProjectCatalogPort.ProjectPreferences loadedPrefs = prefs;
            guiInvoker.accept(() -> {
                if (ticket != generation || !panel.isActive()) return;
                panel.show(loaded, loadedPrefs, new ProjectPanel.Actions(
                    hostActions.onResume(), hostActions.onDelete(), hostActions.onPreview(),
                    hostActions.onClose(), this::savePreferences));
            });
        });
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
