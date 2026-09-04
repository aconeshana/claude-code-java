package com.claudecode.ui.lanterna.dialog.question;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;

/**
 * The body of the {@code Chat about this} denial — the prompt the model receives instead of an
 * answer set when the user wants to talk the questions over first.
 *
 * <ul>
 *   <li>Covers: {@code k2g} — the fixed preamble. Lines two to five carry four leading spaces in
 *       the bundle's template literal, and this port reproduces them verbatim because they reach
 *       the model unchanged.</li>
 *   <li>Covers: {@code F4E} — the question list appended to that preamble: the quoted question
 *       text, then either its answer or {@code (No answer provided)}, then the user's notes when
 *       the question is a design variant and the notes are not blank.</li>
 * </ul>
 *
 * <p>Not covered: {@code R2g} / {@code contentBlocks}. The Java card has no image-paste channel,
 * so a clarification never carries attachments.
 */
public final class ClarifyFeedback {

    /**
     * {@code k2g}'s template head. Built by joining rather than as a text block so the four-space
     * indents survive — a text block would strip them as incidental whitespace.
     */
    private static final String PREAMBLE = String.join("\n",
        "The user wants to clarify these questions.",
        "    This means they may have additional information, context or questions for you.",
        "    Take their response into account and then reformulate the questions if appropriate.",
        "    Start by asking them what they would like to clarify.",
        "    Questions asked:");

    /** {@code F4E}'s stand-in for a question the user skipped. */
    static final String NO_ANSWER = "  (No answer provided)";

    private ClarifyFeedback() {}

    /**
     * Builds the feedback body.
     *
     * @param questions every question in the request, in order
     * @param answers   the answers recorded so far, keyed by {@link DisplayQuestion#key()}
     * @param notes     the raw notes buffer per question key; only design variants contribute
     */
    public static String build(List<DisplayQuestion> questions,
                               Map<String, String> answers,
                               Map<String, String> notes) {
        List<String> blocks = new ArrayList<>(questions.size());
        for (DisplayQuestion question : questions) {
            blocks.add(String.join("\n", questionBlock(question, answers, notes)));
        }
        return PREAMBLE + "\n" + String.join("\n", blocks);
    }

    private static List<String> questionBlock(DisplayQuestion question,
                                              Map<String, String> answers,
                                              Map<String, String> notes) {
        List<String> rows = new ArrayList<>(3);
        rows.add("- \"" + question.displayQuestion().text() + "\"");
        String answer = answers.get(question.key());
        rows.add(StringUtils.isEmpty(answer) ? NO_ANSWER : "  Answer: " + answer);
        String note = QuestionSanitizer.isDesignVariant(question) ? notes.get(question.key()) : null;
        if (StringUtils.isNotBlank(note)) rows.add("  User notes: " + note.strip());
        return rows;
    }
}
