package com.claudecode.ui.lanterna.dialog.question;

import com.claudecode.tools.questions.QuestionPresenter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;

/**
 * Turns the host's recorded answers into the {@code AskUserQuestion} answer set the tool submits.
 *
 * <ul>
 *   <li>Covers: {@code zys} — the {@code answers} map is the recorded values verbatim, while
 *       {@code annotations} are built per question and only for design variants: the picked
 *       option's {@code full} preview markdown, and the trimmed notes buffer. See
 *       {@link #collect(List, Map, List)}.</li>
 *   <li>Covers: {@code S4E}'s multi-select and {@code __other__} branches — how a plain list card's
 *       toggled options and free-text row collapse into one answer string. See
 *       {@link #listAnswer(DisplayQuestion, QuestionState)}.</li>
 * </ul>
 *
 * <p>Not covered: {@code R2g} / {@code imageAttachments} and the {@code (Image attached)} suffix.
 * The Java card has no image-paste channel.
 */
public final class AnswerSubmission {

    private AnswerSubmission() {}

    /**
     * {@code zys} — one {@link QuestionPresenter.Answer} per answered question, in question order.
     * Questions the user never answered are absent, exactly as they are absent from the bundle's
     * {@code answersToSubmit}.
     *
     * @param questions every question in the request, in order
     * @param answers   the recorded answers, keyed by {@link DisplayQuestion#key()}
     * @param states    the per-question editor state, positionally aligned with {@code questions}
     */
    public static Map<String, QuestionPresenter.Answer> collect(
            List<DisplayQuestion> questions,
            Map<String, String> answers,
            List<QuestionState> states) {
        Map<String, QuestionPresenter.Answer> collected = new LinkedHashMap<>();
        for (int index = 0; index < questions.size(); index++) {
            DisplayQuestion question = questions.get(index);
            String answer = answers.get(question.key());
            if (answer == null) continue;
            boolean design = QuestionSanitizer.isDesignVariant(question);
            collected.put(question.key(), new QuestionPresenter.Answer(
                answer,
                design ? previewOf(question, answer) : null,
                design ? StringUtils.trimToNull(states.get(index).text()) : null));
        }
        return collected;
    }

    /**
     * {@code zys}'s {@code p}/{@code f} pair: the annotation preview is the picked option's
     * markdown, and only when that preview survived sanitization as {@code kind:"full"}.
     */
    private static String previewOf(DisplayQuestion question, String answer) {
        for (DisplayQuestion.DisplayOption option : question.options()) {
            if (!option.displayLabel().equals(answer)) continue;
            return option.preview() instanceof DisplayQuestion.Preview.Full full
                ? full.markdown()
                : null;
        }
        return null;
    }

    /**
     * The plain list card's answer string: every ticked option's sanitized label, with the
     * free-text row appended when it is checked — or standing alone when nothing else is.
     */
    public static String listAnswer(DisplayQuestion question, QuestionState state) {
        List<String> labels = new ArrayList<>();
        for (Integer index : state.selectedIndices()) {
            labels.add(question.options().get(index).displayLabel());
        }
        String text = state.text().strip();
        if (state.otherSelected() && labels.isEmpty()) return text;
        String joined = String.join(", ", labels);
        return state.otherSelected() ? joined + ", " + text : joined;
    }
}
