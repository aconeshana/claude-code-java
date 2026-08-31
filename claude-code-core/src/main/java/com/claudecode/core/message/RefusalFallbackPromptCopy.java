package com.claudecode.core.message;

import com.claudecode.core.model.ModelNames;

/**
 * What the pause dialog says while a refused turn waits for the user to pick a
 * model.
 *
 * <ul>
 *   <li>the dialog body
 *       ({@code Oda}), the two option labels ({@code Lda}), and the provider
 *       hint shown underneath them ({@code Pda}).</li>
 * </ul>
 *
 * <p>The body is {@link RefusalFallbackAnnouncement}'s wording without the
 * sentence that names the new model: at dialog time nothing has switched yet.
 * Both are built from the same {@code reasonClause}, so a change to one category's
 * wording cannot land in only one of the two places.
 *
 * <p>Pure text, like {@link RefusalErrorMessage}: the provider arrives as a
 * boolean rather than being read from the process.
 */
public final class RefusalFallbackPromptCopy {

    /**
     * Shown under the options when the deployment has no built-in fallback mapping, so the user can
     * wire one up rather than only choosing for this turn.
     */
    private static final String THIRD_PARTY_GUIDANCE =
        "To enable automatic fallback on this provider, set "
            + "`ANTHROPIC_DEFAULT_FABLE_MODEL` to your Fable 5 model ID and "
            + "`ANTHROPIC_DEFAULT_OPUS_MODEL` to your Opus 4.8 model ID.";

    private RefusalFallbackPromptCopy() {
    }

    /**
     * Why the turn was flagged and how to report a false positive.
     *
     * @param refusedModel the model whose safeguards ended the turn
     * @param category the refusal category the server sent, or {@code null}
     */
    public static String body(String refusedModel, String category) {
        return RefusalFallbackAnnouncement.reasonClause(refusedModel, category) + " "
            + RefusalFallbackAnnouncement.FEEDBACK_LINE;
    }

    /** The option that replays the turn somewhere else. */
    public static String switchLabel(String fallbackModel) {
        return "Switch to " + ModelNames.displayName(fallbackModel);
    }

    /** The option that hands the prompt back so the user can rewrite it. */
    public static String editLabel(String refusedModel) {
        return "Edit prompt and retry with " + ModelNames.displayName(refusedModel);
    }

    /**
     * The provider hint, or {@code null} when there is nothing to configure.
     *
     * @param firstPartyLikeProvider whether the provider serves Anthropic's own
     *                               model ids, from
     *                               {@code ApiProviderScope#usesFirstPartyModelIds}
     */
    public static String guidance(boolean firstPartyLikeProvider) {
        return firstPartyLikeProvider ? null : THIRD_PARTY_GUIDANCE;
    }
}
