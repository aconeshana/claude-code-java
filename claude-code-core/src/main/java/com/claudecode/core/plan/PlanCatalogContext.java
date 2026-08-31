package com.claudecode.core.plan;

import java.util.List;

/** Immutable active-plan snapshot shared by tools, attachments, and compaction. */
public record PlanCatalogContext(
    String planId,
    String planStatus,
    String planFilePath,
    boolean planExists,
    boolean resumedDraft,
    List<PlanHistoryEntry> recentPlans
) {
    public PlanCatalogContext {
        recentPlans = recentPlans == null ? List.of() : List.copyOf(recentPlans);
    }
}
