package com.claudecode.core.plan;

/** Compact model-visible metadata for a previously completed plan. */
public record PlanHistoryEntry(
    String planId,
    String planStatus,
    String title,
    String summary,
    String planFilePath
) {}
