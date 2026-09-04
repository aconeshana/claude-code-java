package com.claudecode.ui.lanterna.dialog.question;

import com.claudecode.core.constants.Figures;
import com.claudecode.core.text.DisplaySanitizer;
import com.claudecode.core.text.FormatUtils;
import com.claudecode.ui.lanterna.theme.LanternaTheme;
import com.claudecode.ui.lanterna.transcript.MessagePanel.Segment;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The tab strip shared by all three {@code AskUserQuestion} screens:
 * {@code ← [☐ Approach] [☒ Storage] [✔ Submit] →}.
 *
 * <p>Authority is the {@code 2.1.236} bundle; the reverse-engineered 2.1.197 counterpart is
 * {@code components/permissions/AskUserQuestionPermissionRequest/QuestionNavigationBar.tsx}.
 *
 * <ul>
 *   <li>Covers: {@code d0t} — the whole strip, including its three width budgets. See
 *       {@link #render(List, int, Set, boolean, int)}.</li>
 *   <li>Covers: {@code njE} (label fallback {@code Q1}, {@code Q2}, …) and {@code ojE} (a tab
 *       occupies {@code 4 + width(label)} columns: chip padding, checkbox, and the gap).</li>
 *   <li>Covers: {@code ww} with {@code padded} — the chip shape {@code " … "}, painted as
 *       {@code permission}-on-{@code inverseText} while it is the current tab.</li>
 * </ul>
 *
 * <p>The strip only reports what to paint; navigation belongs to the host dialog, which binds
 * {@code tabs:previous} / {@code tabs:next} while neither the notes editor nor the chat row holds
 * focus.
 */
final class QuestionTabStrip {

    /** {@code " ✔ Submit "} — reserved even before the Submit chip itself is laid out. */
    private static final String SUBMIT_LABEL = " " + Figures.TICK + " Submit ";

    private static final String LEFT_ARROW = Figures.ARROW_LEFT + " ";
    private static final String RIGHT_ARROW = " " + Figures.ARROW_RIGHT;

    /** {@code ojE}'s constant: one padding column each side, the checkbox, and the gap after it. */
    private static final int CHIP_OVERHEAD = 4;

    private QuestionTabStrip() {}

    /**
     * Lays out the strip for one frame.
     *
     * @param questions     the sanitized questions, in tab order
     * @param currentIndex  the focused tab; {@code questions.size()} is the Submit tab
     * @param answeredKeys  the keys of questions that already carry an answer, shown as {@code ☒}
     * @param hideSubmitTab {@code true} for a lone single-select question, which auto-submits
     * @param columns       the terminal width the strip must fit into
     */
    static List<Segment> render(List<DisplayQuestion> questions, int currentIndex,
                                Set<String> answeredKeys, boolean hideSubmitTab, int columns) {
        List<String> labels = fitLabels(questions, currentIndex, hideSubmitTab, columns);
        boolean hideArrows = questions.size() == 1 && hideSubmitTab;
        int submitIndex = questions.size();

        List<Segment> strip = new ArrayList<>();
        if (!hideArrows) strip.add(arrow(LEFT_ARROW, currentIndex == 0));
        for (int index = 0; index < questions.size(); index++) {
            DisplayQuestion question = questions.get(index);
            String checkbox = answeredKeys.contains(question.key())
                ? Figures.CHECKBOX_ON : Figures.CHECKBOX_OFF;
            strip.add(chip(checkbox + " " + labelOf(labels, questions, index),
                index == currentIndex));
        }
        if (!hideSubmitTab) {
            strip.add(chip(Figures.TICK + " Submit", currentIndex == submitIndex));
        }
        if (!hideArrows) strip.add(arrow(RIGHT_ARROW, currentIndex == submitIndex));
        return List.copyOf(strip);
    }

    /**
     * {@code d0t}'s three budget branches. Note that a branch may hand back an empty label: the
     * bundle's render step then falls back to the untruncated header, so a strip squeezed below its
     * arrows-and-Submit reserve can still overflow. {@link #labelOf} reproduces that fallback rather
     * than quietly improving on it.
     */
    private static List<String> fitLabels(List<DisplayQuestion> questions, int currentIndex,
                                          boolean hideSubmitTab, int columns) {
        int reserved = FormatUtils.displayWidth(LEFT_ARROW)
            + FormatUtils.displayWidth(RIGHT_ARROW)
            + (hideSubmitTab ? 0 : FormatUtils.displayWidth(SUBMIT_LABEL));
        int budget = columns - reserved;

        List<String> headers = new ArrayList<>(questions.size());
        for (int index = 0; index < questions.size(); index++) {
            headers.add(headerOf(questions.get(index), index));
        }
        if (budget <= 0) {
            List<String> only = new ArrayList<>(headers.size());
            for (int index = 0; index < headers.size(); index++) {
                only.add(index == currentIndex
                    ? DisplaySanitizer.truncateToWidth(headers.get(index), 3) : "");
            }
            return only;
        }

        int total = 0;
        for (String header : headers) total += CHIP_OVERHEAD + FormatUtils.displayWidth(header);
        if (total <= budget) return headers;

        String current = currentIndex >= 0 && currentIndex < headers.size()
            ? headers.get(currentIndex) : "";
        double currentChip =
            Math.min(CHIP_OVERHEAD + FormatUtils.displayWidth(current), budget / 2.0);
        int others = Math.max(6,
            (int) Math.floor((budget - currentChip) / Math.max(questions.size() - 1, 1)));

        List<String> squeezed = new ArrayList<>(headers.size());
        for (int index = 0; index < headers.size(); index++) {
            int width = index == currentIndex
                ? (int) Math.floor(currentChip - CHIP_OVERHEAD) : others - CHIP_OVERHEAD;
            squeezed.add(DisplaySanitizer.truncateToWidth(headers.get(index), width));
        }
        return squeezed;
    }

    /** {@code njE} — a question with no usable header is addressed by its position. */
    private static String headerOf(DisplayQuestion question, int index) {
        if (question == null || question.displayHeader().isEmpty()) return "Q" + (index + 1);
        return question.displayHeader();
    }

    private static String labelOf(List<String> labels, List<DisplayQuestion> questions, int index) {
        String fitted = labels.get(index);
        return fitted.isEmpty() ? headerOf(questions.get(index), index) : fitted;
    }

    private static Segment chip(String content, boolean current) {
        return current
            ? new Segment(" " + content + " ", LanternaTheme.inverseText(),
                LanternaTheme.permission())
            : new Segment(" " + content + " ", LanternaTheme.inputText());
    }

    private static Segment arrow(String glyph, boolean dim) {
        return new Segment(glyph,
            dim ? LanternaTheme.welcomeDim() : LanternaTheme.inputText());
    }
}
