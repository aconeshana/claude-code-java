package com.claudecode.ui.lanterna.transcript;

import com.claudecode.core.constants.Figures;
import com.claudecode.core.message.ContentBlock;
import com.claudecode.core.message.MessageConstants;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.ToolResultBlock;
import com.claudecode.ui.MarkdownRenderer;
import com.claudecode.ui.lanterna.theme.LanternaTheme;
import java.util.List;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

/**
 * Row-level vocabulary shared by every tool-result renderer: the {@code ⎿} result prefix
 * and continuation indent, append-or-replace-in-place of the pending {@code Waiting…} row,
 * and the fixed rows for interrupted, classifier-denied, rejected-plan and generic error
 * results.
 *
 * <ul>
 *   <li>{@code src/components/FallbackToolUseRejectedMessage.tsx} — the
 *       {@code Interrupted · What should Claude do instead?} row.</li>
 *   <li>{@code src/components/FallbackToolUseErrorMessage.tsx} — generic errors visibly
 *       carry an {@code Error:} prefix unless already prefixed.</li>
 *   <li>{@code src/components/messages/UserToolResultMessage.tsx} — classifier denial
 *       and plan-rejection ({@code PLAN_REJECTION_PREFIX}) branches.</li>
 * </ul>
 */
final class ToolResultLines {

    /** Two-cell row gutter ({@code ⏺ } on macOS, {@code ● } elsewhere) for tool, system and user rows. */
    static final String BLACK_CIRCLE = Figures.BLACK_CIRCLE + " ";
    static final String INDENT_PREFIX = Figures.RESULT_PREFIX;
    static final String INDENT_CONT = Figures.RESULT_INDENT;

    private static final MarkdownRenderer MARKDOWN_RENDERER = MarkdownRenderer.shared();

    private ToolResultLines() {}

    static String toolResultText(ToolResultBlock result) {
        StringBuilder body = new StringBuilder();
        if (result.content() != null) {
            for (ContentBlock block : result.content()) {
                if (block instanceof TextBlock(String text1)) body.append(text1);
            }
        }
        return body.toString();
    }

    static void appendDimResult(MessagePanel panel, int replaceLine, String text) {
        appendOrReplaceToolResultLine(panel, replaceLine, List.of(
            new MessagePanel.Segment(INDENT_PREFIX + text, LanternaTheme.welcomeDim())));
    }

    static String singular(int count, String plural) {
        return count == 1 && Strings.CS.endsWith(plural, "s")
            ? plural.substring(0, plural.length() - 1) : plural;
    }

    static String planFromRejectionResult(String text) {
        if (text == null || !Strings.CS.startsWith(text, MessageConstants.PLAN_REJECTION_PREFIX)) {
            return null;
        }
        String plan = text.substring(MessageConstants.PLAN_REJECTION_PREFIX.length());
        return StringUtils.isBlank(plan) ? "No plan found" : plan.stripTrailing();
    }

    static void renderRejectedPlan(MessagePanel panel, int replaceLine, String plan) {
        appendOrReplaceToolResultLine(panel, replaceLine, List.of(
            new MessagePanel.Segment(INDENT_PREFIX + "User rejected Claude's plan:",
                LanternaTheme.welcomeDim())));
        panel.appendMarkdown(plan, MARKDOWN_RENDERER, false);
    }

    static void appendOrReplaceToolResultLine(MessagePanel panel, int lineIdx,
                                                       List<MessagePanel.Segment> segments) {
        if (lineIdx >= 0) {
            panel.updateLine(lineIdx, segments);
        } else {
            panel.appendMixed(segments);
        }
    }

    static void renderInterruptedToolResult(MessagePanel panel) {
        renderInterruptedToolResult(panel, -1);
    }

    static void renderInterruptedToolResult(MessagePanel panel, int replaceLine) {
        appendOrReplaceToolResultLine(panel, replaceLine, List.of(
            new MessagePanel.Segment(INDENT_PREFIX, LanternaTheme.welcomeDim()),
            new MessagePanel.Segment("Interrupted ", LanternaTheme.welcomeDim()),
            new MessagePanel.Segment("· What should Claude do instead?",
                LanternaTheme.welcomeDim())
        ));
    }

    static void renderClassifierDenial(MessagePanel panel) {
        renderClassifierDenial(panel, -1);
    }

    static void renderClassifierDenial(MessagePanel panel, int replaceLine) {
        appendOrReplaceToolResultLine(panel, replaceLine, List.of(
            new MessagePanel.Segment(INDENT_PREFIX, LanternaTheme.welcomeDim()),
            new MessagePanel.Segment(
                "Denied by auto mode classifier · /feedback if incorrect",
                LanternaTheme.welcomeDim())
        ));
    }

    /** Generic errors visibly carry an {@code Error:} prefix. */
    static String fallbackToolErrorText(String text) {
        String trimmed = text != null ? text.trim() : "";
        if (Strings.CS.startsWith(trimmed, "Error: ")
                || Strings.CS.startsWith(trimmed, "Cancelled: ")) {
            return trimmed;
        }
        return "Error: " + trimmed;
    }
}
