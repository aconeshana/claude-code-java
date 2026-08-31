package com.claudecode.core.message;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.core.model.ModelNames;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The line a user sees when the model's own safeguards ended the turn.
 */
public final class RefusalErrorMessage {

    /** Where the announcement and this error line both send the user to read more. */
    public static final String LEARN_MORE_URL = "https://support.claude.com/en/articles/15363606";

    /**
     * The closing reassurance shared by this line and the refusal-fallback
     * announcement, which otherwise open differently.
     */
    static final String SAFEGUARD_REFINEMENT_NOTICE =
        "These measures let us bring you Mythos-level capabilities sooner, and we're working "
            + "to refine them.";

    /** The prefix every API-level error line shares. */
    private static final String API_ERROR = "API Error";

    private static final String USAGE_POLICY_URL = "https://www.anthropic.com/legal/aup";

    /** Where a cybersecurity refusal can be appealed when the explanation names no form. */
    private static final String DEFAULT_EXEMPTION_FORM = "https://claude.com/form/cyber-use-case";

    /**
     * The explanation is cut to this many characters, and an exemption link longer than this is
     * discarded.
     */
    private static final int MAX_TEXT_LENGTH = 400;

    private static final Pattern EXEMPTION_FORM =
        Pattern.compile("https://claude\\.com/form/\\S+");

    private static final Pattern TRAILING_PUNCTUATION = Pattern.compile("[.,;:!?)]+$");

    private static final Pattern SENTENCE_END = Pattern.compile("[.!?…]$");

    private RefusalErrorMessage() {
    }

    /**
     * The whole error line, request id included.
     */
    public static String text(StopDetails stopDetails, String requestId, String model,
                              boolean fallbackTargetExists, boolean nonInteractive,
                              boolean firstPartyLikeProvider) {
        String category = stopDetails == null ? null : stopDetails.category();
        String explanation = stopDetails == null ? null : stopDetails.explanation();
        String learnMore = nonInteractive
            ? "Learn more: " + LEARN_MORE_URL
            : "Send feedback with /feedback or learn more: " + LEARN_MORE_URL;

        String body;
        if (model != null && fallbackTargetExists) {
            body = fallbackAvailableLine(ModelNames.displayName(model), category, learnMore);
        } else if (Strings.CS.equals("cyber", category) && firstPartyLikeProvider) {
            String name = model == null ? "This model" : ModelNames.displayName(model);
            body = API_ERROR + ": " + name + "'s safeguards flagged this message for a "
                + "cybersecurity topic. If your work requires this access, you can apply for "
                + "an exemption: " + exemptionForm(explanation) + "\n" + learnMore;
        } else {
            body = API_ERROR + ": Claude Code is unable to respond to this request, which "
                + "appears to violate our Usage Policy (" + USAGE_POLICY_URL + ")."
                + explanationClause(explanation) + " "
                + (nonInteractive
                    ? "Try rephrasing the request in a new session or change your model."
                    : "Please double press esc to edit your last message or start a new "
                        + "session for Claude Code to assist with a different task.");
        }
        return StringUtils.isEmpty(requestId) ? body : body + "\nRequest ID: " + requestId;
    }

    /**
     * The branch for a model that has a fallback target. It never shows the
     * explanation — the user is about to be offered another model, so the server's
     * reasoning is noise.
     */
    private static String fallbackAvailableLine(String name, String category, String learnMore) {
        String reassurance = RefusalCategory.usesBroadSafeguardCopy(category)
            ? "They may flag safe, normal content as well. " + SAFEGUARD_REFINEMENT_NOTICE
            : "This sometimes happens with safe, normal conversations.";
        return API_ERROR + ": " + name + "'s safeguards flagged this message ("
            + USAGE_POLICY_URL + "). " + reassurance
            + " Claude Code can't respond to this request with " + name + ".\n" + learnMore;
    }

    /**
     * The server's explanation, capped and punctuated so it reads as a sentence
     * inside the surrounding line. Empty when there is nothing to say; otherwise
     * it opens with the space that separates it from the usage policy sentence.
     */
    private static String explanationClause(String explanation) {
        String trimmed = explanation == null ? null : StringUtils.stripEnd(explanation, null);
        if (StringUtils.isEmpty(trimmed)) return "";
        String capped = trimmed.length() > MAX_TEXT_LENGTH
            ? StringUtils.stripEnd(trimmed.substring(0, MAX_TEXT_LENGTH), null) + "…"
            : trimmed;
        return " " + capped + (SENTENCE_END.matcher(capped).find() ? "" : ".");
    }

    /**
     * The appeal form the explanation names, with the punctuation that ended the
     * sentence peeled back off the url. An implausibly long match is discarded in
     * favour of the generic form rather than pasted into the message.
     */
    private static String exemptionForm(String explanation) {
        if (explanation == null) return DEFAULT_EXEMPTION_FORM;
        Matcher matcher = EXEMPTION_FORM.matcher(explanation);
        if (!matcher.find()) return DEFAULT_EXEMPTION_FORM;
        String url = TRAILING_PUNCTUATION.matcher(matcher.group()).replaceFirst("");
        return url.length() <= MAX_TEXT_LENGTH ? url : DEFAULT_EXEMPTION_FORM;
    }
}
