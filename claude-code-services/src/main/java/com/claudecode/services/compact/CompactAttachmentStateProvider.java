package com.claudecode.services.compact;

import com.claudecode.core.plan.PlanCatalogContext;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * Supplies an immutable snapshot of tool-owned process state needed to rebuild post-compact
 * attachments.
 */
public interface CompactAttachmentStateProvider {

    Snapshot snapshot(String sessionId, String agentId, boolean subAgent);

    record Snapshot(
        String agentId,
        boolean subAgent,
        boolean planModeActive,
        PlanFile planFile,
        List<AsyncTask> tasks,
        List<InvokedSkill> invokedSkills,
        PlanCatalogContext planCatalog
    ) {
        public Snapshot {
            tasks = tasks == null ? List.of() : List.copyOf(tasks);
            invokedSkills = invokedSkills == null ? List.of() : List.copyOf(invokedSkills);
        }

        public Snapshot(
                String agentId, boolean subAgent, boolean planModeActive,
                PlanFile planFile, List<AsyncTask> tasks, List<InvokedSkill> invokedSkills) {
            this(agentId, subAgent, planModeActive, planFile, tasks, invokedSkills, null);
        }

        public static Snapshot empty(String agentId, boolean subAgent) {
            return new Snapshot(
                agentId, subAgent, false, null, List.of(), List.of(), null);
        }
    }

    record PlanFile(Path path, String content) {
        public boolean exists() {
            return content != null;
        }
    }

    record AsyncTask(
        String id,
        String type,
        String status,
        String description,
        String deltaSummary,
        String outputFilePath
    ) {}

    record InvokedSkill(String name, String path, String content, Instant invokedAt) {}
}
