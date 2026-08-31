package com.claudecode.core.lsp;


public enum LspRecommendationResponse {
    /** Install and enable the suggested plugin, then re-read LSP config. */
    YES,
    /** Dismiss. Only counts as an "ignore" (incrementing the ignore counter)
     *  when the dialog auto-closed on timeout, not when the user clicked it. */
    NO,
    /** Never suggest this specific plugin again. */
    NEVER,
    /** Disable all LSP recommendations globally. */
    DISABLE
}
