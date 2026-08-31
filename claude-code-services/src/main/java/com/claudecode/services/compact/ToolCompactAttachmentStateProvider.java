package com.claudecode.services.compact;

import com.claudecode.core.plan.PlanCatalogContext;
import com.claudecode.tools.plan.EnterPlanModeTool;
import com.claudecode.tools.plan.PlanFiles;
import com.claudecode.tools.skills.InvokedSkillRegistry;
import com.claudecode.tools.tasks.TaskOutputPaths;
import com.claudecode.tools.tasks.TaskRegistry;
import com.claudecode.tools.tasks.TaskState;
import com.claudecode.tools.tasks.TaskStore;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import org.apache.commons.lang3.StringUtils;

/**
 * Adapter from concrete tool registries/helpers to the compact service's immutable attachment-state
 * boundary.
 */
final class ToolCompactAttachmentStateProvider implements CompactAttachmentStateProvider {

    private TaskStore tasks;
    private InvokedSkillRegistry invokedSkills;
    private final BooleanSupplier planModeActive;

    ToolCompactAttachmentStateProvider(TaskStore tasks,
                                       InvokedSkillRegistry invokedSkills,
                                       BooleanSupplier planModeActive) {
        this.tasks = tasks;
        this.invokedSkills = invokedSkills;
        this.planModeActive = planModeActive;
    }

    static ToolCompactAttachmentStateProvider standard() {
        return new ToolCompactAttachmentStateProvider(
            TaskRegistry.global().store(),
            InvokedSkillRegistry.global(),
            EnterPlanModeTool::isPlanModeActive);
    }

    void setTaskStore(TaskStore tasks) {
        this.tasks = tasks;
    }

    void setInvokedSkills(InvokedSkillRegistry invokedSkills) {
        this.invokedSkills = invokedSkills;
    }

    @Override
    public Snapshot snapshot(String sessionId, String agentId, boolean subAgent) {
        PlanCatalogContext planCatalog = planCatalog(sessionId, agentId);
        PlanFile plan = planFile(sessionId, agentId, planCatalog);
        List<AsyncTask> taskSnapshots = tasks == null ? List.of()
            : tasks.list().stream().map(ToolCompactAttachmentStateProvider::task).toList();
        List<InvokedSkill> skillSnapshots = invokedSkills == null ? List.of()
            : invokedSkills.entriesFor(agentId).stream()
                .map(entry -> new InvokedSkill(
                    entry.name(), entry.path(), entry.content(), entry.invokedAt()))
                .toList();
        return new Snapshot(
            agentId,
            subAgent,
            planModeActive != null && planModeActive.getAsBoolean(),
            plan,
            taskSnapshots,
            skillSnapshots,
            planCatalog != null && planCatalog.planId() != null ? planCatalog : null);
    }

    private static PlanCatalogContext planCatalog(String sessionId, String agentId) {
        if (StringUtils.isBlank(sessionId)) return null;
        return PlanFiles.currentPlanContext(sessionId, agentId);
    }

    private static PlanFile planFile(
            String sessionId, String agentId, PlanCatalogContext planCatalog) {
        if (StringUtils.isBlank(sessionId)) return null;
        Path path = planCatalog == null
            ? PlanFiles.getPlanFilePath(sessionId, agentId)
            : Path.of(planCatalog.planFilePath());
        return new PlanFile(path, PlanFiles.getPlan(sessionId, agentId));
    }

    private static AsyncTask task(TaskState task) {
        String delta = switch (task.status()) {
            case RUNNING -> task.progressSummary().orElse(null);
            case FAILED -> task.errorMessage().orElse(null);
            default -> null;
        };
        return new AsyncTask(
            task.id(),
            task.type().name().toLowerCase(Locale.ROOT),
            task.status().name().toLowerCase(Locale.ROOT),
            task.description(),
            delta,
            TaskOutputPaths.outputPath(task.id()).toString());
    }
}
