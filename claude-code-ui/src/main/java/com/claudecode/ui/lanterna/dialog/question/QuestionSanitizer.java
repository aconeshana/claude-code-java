package com.claudecode.ui.lanterna.dialog.question;

import com.claudecode.core.text.DisplaySanitizer;
import com.claudecode.core.text.FormatUtils;
import com.claudecode.tools.questions.QuestionPresenter;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Projects the model-supplied {@code AskUserQuestion} input into render-ready
 * {@link DisplayQuestion}s, scrubbing every string that is about to reach the terminal.
 *
 * <p>Authority is the {@code 2.1.236} bundle; the primitives this class composes live in
 * {@link DisplaySanitizer}, whose Javadoc records their bundle formulas. Pure functions, no
 * Lanterna dependency.
 *
 * <ul>
 *   <li>Covers: {@code w2g} — the whole projection. See {@link #sanitize(List)}.</li>
 *   <li>Covers: {@code A2g} — the design-variant predicate that selects between the two
 *       renderers, and gates whether {@code annotations} carry preview/notes. See
 *       {@link #isDesignVariant(DisplayQuestion)}.</li>
 *   <li>Covers: {@code E2g} — resolving a picked option's raw value to the sanitized label that
 *       is written into {@code answers}. See {@link #answerValueFor(DisplayQuestion, String)}.</li>
 *   <li>Covers: {@code jHe} / {@code Bcr} — the header projection and its blank test. See
 *       {@link #sanitizeHeader(String)}.</li>
 *   <li>Covers: {@code w2g}'s inline {@code _le} key function, which differs from the default
 *       {@code ZCf}: option labels get no 256-code-unit cap, no 48-column cap, and no JSON
 *       quoting. See {@link #labelKey(String)}.</li>
 * </ul>
 */
public final class QuestionSanitizer {

    /**
     * {@code oFg} — shown in place of a preview that exceeded
     * {@link DisplaySanitizer#TEXT_LIMIT} code units.
     */
    public static final String WITHHELD_PREVIEW_NOTICE =
        "(preview cannot be shown in full — compare the option labels and descriptions instead)";

    private QuestionSanitizer() {}

    /** {@code w2g} — sanitizes every question, preserving order. */
    public static List<DisplayQuestion> sanitize(List<QuestionPresenter.Question> questions) {
        if (questions == null || questions.isEmpty()) return List.of();
        List<DisplayQuestion> sanitized = new ArrayList<>(questions.size());
        for (QuestionPresenter.Question question : questions) {
            sanitized.add(sanitizeQuestion(question));
        }
        return List.copyOf(sanitized);
    }

    private static DisplayQuestion sanitizeQuestion(QuestionPresenter.Question question) {
        List<QuestionPresenter.Option> options =
            question.options() == null ? List.of() : question.options();
        List<String> displayLabels = DisplaySanitizer.dedupeDisplayLabels(
            options.stream().map(QuestionPresenter.Option::label).toList(),
            QuestionSanitizer::labelKey);

        List<DisplayQuestion.DisplayOption> displayOptions = new ArrayList<>(options.size());
        for (int index = 0; index < options.size(); index++) {
            QuestionPresenter.Option option = options.get(index);
            displayOptions.add(new DisplayQuestion.DisplayOption(
                option.label(),
                displayLabels.get(index),
                displayText(option.description()).text(),
                sanitizePreview(option.preview())));
        }
        return new DisplayQuestion(
            question.question(),
            displayText(question.question()),
            sanitizeHeader(question.header()),
            question.multiSelect(),
            displayOptions);
    }

    /** {@code pA} — clamp, scrub permissively, and decide whether the text needs a multi-line slot. */
    public static DisplayQuestion.DisplayText displayText(String value) {
        if (value == null) return new DisplayQuestion.DisplayText("", false);
        String text = DisplaySanitizer.scrubPermissive(DisplaySanitizer.clampText(value));
        return new DisplayQuestion.DisplayText(text, DisplaySanitizer.needsGutter(text));
    }

    /**
     * {@code Bcr(header) ? "" : jHe(header)} — a header that renders as nothing collapses to the
     * empty string, so the tab strip can fall back to {@code Q1}, {@code Q2}, and so on.
     */
    public static String sanitizeHeader(String header) {
        if (StringUtils.isEmpty(header)) return "";
        String clamped = DisplaySanitizer.truncateCodeUnits(header, DisplaySanitizer.LABEL_LIMIT);
        if (FormatUtils.displayWidth(clamped) == 0) return "";
        return DisplaySanitizer.sanitizeHeader(header);
    }

    /**
     * {@code w2g}'s {@code _le} key function. Unlike the default {@code ZCf} it neither clamps nor
     * JSON-quotes: an option label is scrubbed, its newlines are flattened to a visible scar, and
     * its whitespace is collapsed. Quoting only appears once two labels collide and
     * {@link DisplaySanitizer#dedupeDisplayLabels(List, java.util.function.UnaryOperator)} falls
     * back to escaped forms.
     */
    public static String labelKey(String label) {
        return DisplaySanitizer.collapseWhitespace(
            DisplaySanitizer.flattenNewlines(displayText(label).text()));
    }

    /**
     * The preview branch of {@code w2g}: absent stays absent, an over-long preview is withheld, one
     * that scrubs away to nothing is dropped, and the rest is scrubbed and kept as markdown.
     */
    public static DisplayQuestion.Preview sanitizePreview(String preview) {
        if (preview == null) return null;
        if (preview.length() > DisplaySanitizer.TEXT_LIMIT) {
            return new DisplayQuestion.Preview.Withheld();
        }
        if (!DisplaySanitizer.isVisiblyNonBlank(preview)) return null;
        return new DisplayQuestion.Preview.Full(DisplaySanitizer.scrubPermissive(preview));
    }

    /**
     * {@code A2g} — a single-select question in which at least one option carries a preview is
     * rendered as the design card rather than the plain list card.
     */
    public static boolean isDesignVariant(DisplayQuestion question) {
        return !question.multiSelect()
            && question.options().stream().anyMatch(option -> option.preview() != null);
    }

    /**
     * {@code E2g} — the value recorded in {@code answers} is the option's sanitized
     * {@code displayLabel}, falling back to the raw value when no option matches.
     */
    public static String answerValueFor(DisplayQuestion question, String value) {
        return question.options().stream()
            .filter(option -> option.value().equals(value))
            .map(DisplayQuestion.DisplayOption::displayLabel)
            .findFirst()
            .orElse(value);
    }
}
