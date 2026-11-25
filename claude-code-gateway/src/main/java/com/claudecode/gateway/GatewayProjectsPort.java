package com.claudecode.gateway;

import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

/**
 * Consumer-owned boundary for managing multi-folder projects and inspecting
 * project-scoped customizations (Rules / Skills / MCP) from the Web UI.
 *
 * <p>Project display metadata (custom name and explicit registration) is stored
 * in a dedicated sidecar file ({@code $CLAUDE_CONFIG_DIR/web-projects.json}),
 * while additional bound folders are persisted strictly as standard Claude Code
 * {@code permissions.additionalDirectories} in {@code settings.local.json} /
 * {@code settings.json} so official Claude Code remains 100% compatible.
 */
@Explanation("Exposes multi-folder project registry and per-project customizations to WebUI without altering Claude Code schemas")
public interface GatewayProjectsPort {

    /**
     * One registered or discovered project entry.
     *
     * @param path                  canonical primary project directory ({@code cwd})
     * @param name                  custom display name, or blank to use directory basename
     * @param additionalDirectories merged additional folders from settings
     * @param updatedAtMs           last modification timestamp in epoch millis
     */
    record ProjectEntry(
        String path,
        String name,
        List<String> additionalDirectories,
        long updatedAtMs
    ) {
        public ProjectEntry {
            additionalDirectories = additionalDirectories == null ? List.of() : List.copyOf(additionalDirectories);
        }
    }

    /** Lists registered projects. */
    default List<ProjectEntry> listProjects() {
        return List.of();
    }

    /**
     * Creates or updates a project entry and optionally binds additional directories
     * into the project's settings file ({@code tier}: {@code "local"} or {@code "project"}).
     */
    default ProjectEntry createOrUpdateProject(
            String path, String name, List<String> additionalDirectories, String tier) {
        return new ProjectEntry(path, name == null ? "" : name,
            additionalDirectories == null ? List.of() : additionalDirectories,
            System.currentTimeMillis());
    }

    /** Updates a project's custom display name (blank clears custom name). */
    default ProjectEntry renameProject(String path, String name) {
        return new ProjectEntry(path, name == null ? "" : name, List.of(), System.currentTimeMillis());
    }

    /** Removes a project from the web registry (never deletes files on disk). */
    default void removeProject(String path) {}

    /**
     * Inspects project-scoped folders, Rules ({@code CLAUDE.md} / {@code .claude/rules}),
     * Skills, and MCP servers for {@code path}.
     */
    default ObjectNode projectCustomizations(String path) {
        return JsonUtils.getMapper().createObjectNode();
    }
}
