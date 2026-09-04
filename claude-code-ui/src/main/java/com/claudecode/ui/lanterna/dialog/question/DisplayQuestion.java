package com.claudecode.ui.lanterna.dialog.question;

import java.util.List;

/**
 * One {@code AskUserQuestion} question after sanitization — the render-ready shape the dialog
 * paints from, with the raw model-supplied strings kept alongside for the wire payload.
 *
 * <p>Authority for every field is the {@code 2.1.236} bundle, where this record is the element
 * type of {@code w2g}'s output. The layer has no counterpart in the reverse-engineered 2.1.197
 * tree — {@code displayLabel}, {@code needsGutter} and the withheld-preview kind appear zero times
 * there — so the coverage list below names bundle symbols rather than TS paths.
 *
 * <ul>
 *   <li>Covers: {@code w2g}'s element shape — {@code key} / {@code displayQuestion} /
 *       {@code displayHeader} / {@code multiSelect} / {@code options}. Built by
 *       {@link QuestionSanitizer#sanitize(java.util.List)}.</li>
 *   <li>Covers: {@code pA}'s return shape {@code {text, needsGutter}}. See {@link DisplayText}.</li>
 *   <li>Covers: the option shape {@code {value, displayLabel, displayDescription, preview}}. See
 *       {@link DisplayOption}.</li>
 *   <li>Covers: the preview union {@code undefined | {kind:"full",markdown} | {kind:"withheld"}}.
 *       See {@link Preview}.</li>
 * </ul>
 *
 * @param key             the raw question text, which is also the key answers are returned under
 * @param displayQuestion the sanitized question text plus its multi-line-slot flag
 * @param displayHeader   the sanitized chip label, or {@code ""} when the header renders blank
 * @param multiSelect     whether the user may pick several options
 * @param options         the sanitized choices, in their original order
 */
public record DisplayQuestion(
    String key,
    DisplayText displayQuestion,
    String displayHeader,
    boolean multiSelect,
    List<DisplayOption> options) {

    public DisplayQuestion {
        options = List.copyOf(options);
    }

    /**
     * {@code pA}'s result: text safe to paint, and whether it claims a multi-line slot rather than
     * a single row.
     *
     * @param text        the scrubbed, tab-flattened, length-clamped text
     * @param needsGutter {@code i9(text)} — the text contains a newline or exceeds 80 columns
     */
    public record DisplayText(String text, boolean needsGutter) {}

    /**
     * One sanitized choice.
     *
     * @param value              the raw label, sent back to the model as the option's identity
     * @param displayLabel       the sanitized, de-duplicated label painted in the option list
     * @param displayDescription the sanitized explanation, painted only by the list variant
     * @param preview            the option's preview, or {@code null} when it has none to show
     */
    public record DisplayOption(
        String value,
        String displayLabel,
        String displayDescription,
        Preview preview) {}

    /** An option's preview content, or the reason it cannot be shown. */
    public sealed interface Preview {

        /**
         * The preview survived sanitization and is rendered as markdown.
         *
         * @param markdown the scrubbed preview source
         */
        record Full(String markdown) implements Preview {}

        /** The preview exceeded {@code nFg} code units and is replaced by a standing notice. */
        record Withheld() implements Preview {}
    }
}
