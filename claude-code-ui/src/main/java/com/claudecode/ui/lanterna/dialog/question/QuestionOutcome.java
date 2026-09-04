package com.claudecode.ui.lanterna.dialog.question;

import com.claudecode.tools.questions.QuestionPresenter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The three ways an {@code AskUserQuestion} card can end, mirroring the three permission results
 * the {@code 2.1.236} bundle's host can hand back.
 *
 * <ul>
 *   <li>Covers: {@code zys} — the submit path, {@code behavior:"allow"} with a rewritten
 *       {@code updatedInput}. See {@link Submitted}.</li>
 *   <li>Covers: {@code k2g} — the {@code Chat about this} path, {@code behavior:"deny"} with a
 *       clarification {@code feedback} body rather than an abort. See {@link Clarify} and
 *       {@link ClarifyFeedback}.</li>
 *   <li>Covers: {@code awo} — {@code onFinalResponse("cancel")} and every {@code escape},
 *       {@code behavior:"deny"} with no feedback. See {@link Cancelled}.</li>
 * </ul>
 */
public sealed interface QuestionOutcome {

    /** {@code zys} — every recorded answer, keyed by question text, in question order. */
    record Submitted(Map<String, QuestionPresenter.Answer> answers) implements QuestionOutcome {

        public Submitted {
            answers = Collections.unmodifiableMap(new LinkedHashMap<>(answers));
        }
    }

    /** {@code k2g} — deny the tool call and ask the model to reformulate its questions. */
    record Clarify(String feedback) implements QuestionOutcome {}

    /** {@code awo} — deny with no feedback. */
    record Cancelled() implements QuestionOutcome {}
}
