package com.claudecode.tools.plan;

import com.claudecode.core.config.ClaudePaths;
import com.claudecode.core.plan.PlanCatalogContext;
import com.claudecode.core.plan.PlanSlugRegistry;
import com.claudecode.core.util.WordSlugGenerator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import org.apache.commons.lang3.StringUtils;

/**
 * Disk-based plan-file lookup, scoped to what the post-compact plan-reattachment feature and the
 * plan-mode tools actually need.
 */
public final class PlanFiles {

    private static volatile Path plansDirectory = defaultPlansDirectory();
    private static volatile boolean multiPlanEnabled;

    private PlanFiles() {}

    /** Configures the startup-resolved, session-stable plans directory. */
    public static void configurePlansDirectory(Path directory) {
        plansDirectory = Objects.requireNonNull(directory, "directory")
            .toAbsolutePath().normalize();
    }

    /** Freezes the environment-gated multi-plan behavior for the current toolchain. */
    public static void configureMultiPlan(boolean enabled) {
        multiPlanEnabled = enabled;
    }

    public static boolean isMultiPlanEnabled() {
        return multiPlanEnabled;
    }

    /** Returns the directory used by plan tools, commands, UI, and permissions. */
    public static Path getPlansDirectory() {
        return plansDirectory;
    }

    public static void resetPlansDirectory() {
        plansDirectory = defaultPlansDirectory();
        multiPlanEnabled = false;
        PlanSlugRegistry.clearAll();
    }

    private static Path defaultPlansDirectory() {
        return ClaudePaths.currentClaudeHome().resolve("plans").toAbsolutePath().normalize();
    }

    /** Plan file path for {@code sessionId}, optionally scoped to a subagent id. */
    public static Path getPlanFilePath(String sessionId, String agentId) {
        PlanCatalogStore store = store(sessionId, agentId);
        return store.activePathIfPresent().orElseGet(store::legacyPath);
    }

    /** Plan file contents, or {@code null} if no plan has been written yet. */
    public static String getPlan(String sessionId, String agentId) {
        try {
            return Files.readString(getPlanFilePath(sessionId, agentId));
        } catch (IOException _) {
            return null;
        }
    }

    /** Activates the plan for a plan-mode entry, rotating only when the feature is enabled. */
    public static PlanCatalogContext activatePlan(String sessionId, String agentId) {
        PlanCatalogStore store = store(sessionId, agentId);
        try {
            return multiPlanEnabled ? store.activate() : store.current(false);
        } catch (IOException | RuntimeException _) {
            Path fallback = store.legacyPath();
            return new PlanCatalogContext(
                null, null, fallback.toString(), Files.isRegularFile(fallback), false,
                java.util.List.of());
        }
    }

    /** Current plan snapshot without causing a completed plan to rotate. */
    public static PlanCatalogContext currentPlanContext(String sessionId, String agentId) {
        PlanCatalogStore store = store(sessionId, agentId);
        try {
            return store.current(multiPlanEnabled);
        } catch (IOException | RuntimeException _) {
            Path fallback = store.legacyPath();
            return new PlanCatalogContext(
                null, null, fallback.toString(), Files.isRegularFile(fallback), false,
                java.util.List.of());
        }
    }

    /** Completes or abandons the active plan and refreshes its deterministic metadata. */
    public static PlanCompletion completePlan(
            String sessionId, String agentId, String content, String revisesPlanId) {
        PlanCatalogStore store = store(sessionId, agentId);
        try {
            if (multiPlanEnabled && !Files.isRegularFile(store.manifestPath())) {
                store.activate();
            }
            return store.complete(content, multiPlanEnabled ? revisesPlanId : null,
                multiPlanEnabled);
        } catch (IOException _) {
            return new PlanCompletion(null, null, null, null);
        }
    }

    /** Returns a user-correctable validation error, or {@code null} when valid. */
    public static String validateRevisionTarget(
            String sessionId, String agentId, String revisesPlanId) {
        if (!multiPlanEnabled || StringUtils.isBlank(revisesPlanId)) return null;
        return store(sessionId, agentId).validateRevisionTarget(revisesPlanId);
    }

    /** Copies a parent session's slug-named plan into a fork's independent slug. */
    public static boolean copyPlanForFork(String sourceSessionId, String targetSessionId) {
        if (sourceSessionId == null || targetSessionId == null
                || sourceSessionId.equals(targetSessionId)) return false;
        PlanCatalogStore sourceStore = store(sourceSessionId, null);
        String sourceSlug = PlanSlugRegistry.get(sourceSessionId).orElse(null);
        if (sourceSlug != null && PlanSlugRegistry.get(targetSessionId)
                .filter(sourceSlug::equals).isPresent()) {
            PlanSlugRegistry.clear(targetSessionId);
        }
        PlanCatalogStore targetStore = store(targetSessionId, null);
        if (Files.isRegularFile(sourceStore.manifestPath())) {
            try {
                return sourceStore.copyCatalogTo(targetStore);
            } catch (IOException | RuntimeException _) {
                // Fall through to the released single-file copy when the catalog is unavailable.
            }
        }
        Path source = sourceStore.legacyPath();
        if (!Files.isRegularFile(source)) return false;
        Path target = targetStore.legacyPath();
        if (source.equals(target)) return false;
        try {
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException _) {
            return false;
        }
    }

    private static PlanCatalogStore store(String sessionId, String agentId) {
        Path directory = plansDirectory;
        try {
            Files.createDirectories(directory);
        } catch (IOException _) {

        }
        String slug = PlanSlugRegistry.getOrCreate(
            sessionId,
            WordSlugGenerator::generateWordSlug,
            candidate -> Files.exists(directory.resolve(candidate + ".md"))
                || Files.exists(directory.resolve(candidate + ".plans.json")));
        String baseName = agentId == null ? slug : slug + "-agent-" + agentId;
        return new PlanCatalogStore(directory, baseName);
    }

    /** Metadata returned to ExitPlanMode after catalog completion. */
    public record PlanCompletion(
        String planId,
        String title,
        String planStatus,
        String revisesPlanId
    ) {}
}
