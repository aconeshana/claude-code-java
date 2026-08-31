package com.claudecode.tools.questions;

import java.util.List;
import java.util.Map;

/**
 * UI port for {@code AskUserQuestion}: presents 1-4 multiple-choice questions and returns the
 * user's selections.
 */
public interface QuestionPresenter {

    /**
     * One question to present.
     *
     * @param question    full question text (also the key answers are returned under)
     * @param header      short chip/tag label (max ~12 chars)
     * @param options     2-4 choices
     * @param multiSelect true → user may pick several options (answer joins
     *                    labels with {@code ", "})
     */
    record Question(String question, String header, List<Option> options, boolean multiSelect) {}

    /** One choice: display label, explanation, optional preview content. */
    record Option(String label, String description, String preview) {}

    /**
     * The user's answer to one question.
     *
     * @param answer  selected label(s) (multi-select comma-joined) or the
     *                "Other" free-text
     * @param preview preview content of the selected option, when it had one
     * @param notes   free-text notes the user attached to the selection
     */
    record Answer(String answer, String preview, String notes) {}

    /**
     * Blocks until the user has answered every question (or cancelled).
     * Called from the tool-execution virtual thread — implementations marshal
     * their UI work to the GUI thread and park this one.
     *
     * @return answers keyed by question text, or {@code null} if the user
     *         cancelled (tool reports "User declined to answer questions")
     */
    Map<String, Answer> present(List<Question> questions);
}
