package com.claudecode.session;

/** Options for the SDK-compatible session catalog API. */
public record ListSessionsOptions(
    String dir,
    Integer limit,
    Integer offset,
    Boolean includeWorktrees,
    Boolean includeProgrammatic
) {
    public static ListSessionsOptions defaults() {
        return new ListSessionsOptions(null, null, null, null, null);
    }

    int effectiveLimit() { return limit == null || limit <= 0 ? Integer.MAX_VALUE : limit; }
    int effectiveOffset() { return offset == null ? 0 : Math.max(0, offset); }
    boolean effectiveIncludeWorktrees() { return includeWorktrees == null || includeWorktrees; }
    boolean effectiveIncludeProgrammatic() { return includeProgrammatic == null || includeProgrammatic; }
    boolean paginated() { return effectiveOffset() > 0 || (limit != null && limit > 0); }
}
