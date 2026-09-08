package com.claudecode.gateway;

import java.util.List;

/**
 * Consumer-owned boundary for the gateway's two-level project→session listing.
 *
 * <p>The sessions endpoint mirrors the data shape of the TUI's project→session
 * picker ({@code /resume} two-level menu and the project catalog panel), but
 * the gateway must not depend on the session module or UI types: the CLI
 * composition root injects an adapter, the same seam
 * {@code ProjectCatalogPort} uses for the UI panel.
 */
public interface GatewaySessionCatalogPort {

    /** One session row under a project. */
    record SessionEntry(
        String id,
        String summary,
        int messageCount,
        long lastModifiedMs,
        String gitBranch,
        String cwd,
        String customTitle,
        String firstPrompt) {}

    /** One project row with its sessions, most recently active first. */
    record ProjectEntry(
        String projectPath,
        String projectName,
        int sessionCount,
        long lastActivityMs,
        List<SessionEntry> sessions) {

        public ProjectEntry {
            sessions = sessions != null ? List.copyOf(sessions) : List.of();
        }
    }

    /** The fingerprint-validated listing; may block on transcript reads. */
    default List<ProjectEntry> listProjects() { return List.of(); }
}
