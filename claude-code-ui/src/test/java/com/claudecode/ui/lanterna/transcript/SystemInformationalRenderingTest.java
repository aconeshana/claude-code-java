package com.claudecode.ui.lanterna.transcript;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.message.SystemMessage;
import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TextColor;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import com.claudecode.ui.lanterna.features.settings.AutoModeEntryWarningController;


class SystemInformationalRenderingTest {

    @Test
    void noticeUsesTheReleasedBlackCircleInsteadOfAnInfoGlyph() {
        StubPanel panel = new StubPanel();

        new LanternaMessageDispatcher().dispatch(new SDKMessage.System(new SystemMessage(
            "notice-1", "informational", "notice",
            AutoModeEntryWarningController.DESCRIPTION)), panel);

        assertEquals(List.of("⏺ " + AutoModeEntryWarningController.DESCRIPTION), panel.lines);
    }

    @Test
    void verboseInfoHasNoDotAndIsRenderedAsPlainText() {
        StubPanel panel = new StubPanel();
        LanternaMessageDispatcher dispatcher = new LanternaMessageDispatcher();
        dispatcher.setVerbose(true);

        dispatcher.dispatch(new SDKMessage.System(new SystemMessage(
            "info-1", "informational", "info", "verbose detail")), panel);

        assertEquals(List.of("verbose detail"), panel.lines);
    }

    @Test
    void scheduledTaskFireRendersEvenWhenVerboseIsOff() {
        StubPanel panel = new StubPanel();

        new LanternaMessageDispatcher().dispatch(new SDKMessage.System(new SystemMessage(
            "cron-1", "scheduled_task_fire", "info",
            "Running scheduled task (Aug 25 1:23am)")), panel);

        assertEquals(List.of("✻ Running scheduled task (Aug 25 1:23am)"), panel.lines);
    }

    @Test
    void scheduledTaskFireHasTopMarginAfterPreviousTurn() {
        StubPanel panel = new StubPanel();
        panel.appendLine("✻ Worked for 0s", TextColor.ANSI.DEFAULT);

        new LanternaMessageDispatcher().dispatch(new SDKMessage.System(new SystemMessage(
            "cron-1", "scheduled_task_fire", "info",
            "Running scheduled task (Aug 25 1:23am)")), panel);

        assertEquals(List.of(
            "✻ Worked for 0s",
            "",
            "✻ Running scheduled task (Aug 25 1:23am)"), panel.lines);
    }

    @Test
    void refusalFallbackAnnouncementCarriesTheConfigTip() {
        StubPanel panel = new StubPanel();

        new LanternaMessageDispatcher().dispatch(new SDKMessage.System(new SystemMessage(
            "ann-1", "model_refusal_fallback", "warning",
            "Claude Opus 4.5 declined this request; retrying with Sonnet")), panel);

        assertEquals(List.of(
            "⏺ Claude Opus 4.5 declined this request; retrying with Sonnet",
            "  ⎿  Tip: You can configure model switch behavior in /config"),
            panel.lines);
        assertEquals(Set.of(SGR.BOLD), panel.modifiersOfLastSegment,
            "released renders the announcement body bold");
    }

    @Test
    void trailingLearnMoreCollapsesIntoAHyperlinkWhenTheTerminalSupportsIt() {
        StubPanel panel = new StubPanel();
        String prefix = "Claude Opus 4.5's safeguards flagged this message."
            + " Switched to Claude Sonnet 4.5. Send feedback with /feedback or ";

        LanternaMessageDispatcher dispatcher = new LanternaMessageDispatcher();
        dispatcher.setHyperlinkSupport(() -> true);
        dispatcher.dispatch(new SDKMessage.System(new SystemMessage(
            "ann-3", "model_refusal_fallback", "warning",
            prefix + "learn more: " + LanternaMessageDispatcher.REFUSAL_HELP_URL)), panel);

        List<MessagePanel.Segment> body = panel.mixed.getFirst();
        assertEquals(3, body.size(), "dot + prefix + link, with an empty tail dropped: " + body);
        assertEquals("⏺ " + prefix, body.getFirst().text() + body.get(1).text());
        MessagePanel.Segment link = body.get(2);
        assertEquals("learn more", link.text(), "the url itself is replaced by the label");
        assertEquals(LanternaMessageDispatcher.REFUSAL_HELP_URL, link.hyperlinkUrl());
        assertEquals(Set.of(SGR.BOLD, SGR.UNDERLINE), link.modifiers());
    }

    @Test
    void trailingLearnMoreStaysLiteralWhenTheTerminalCannotLink() {
        StubPanel panel = new StubPanel();
        String content = "Switched to Claude Sonnet 4.5. Send feedback with /feedback or"
            + " learn more: " + LanternaMessageDispatcher.REFUSAL_HELP_URL;

        LanternaMessageDispatcher dispatcher = new LanternaMessageDispatcher();
        dispatcher.setHyperlinkSupport(() -> false);
        dispatcher.dispatch(new SDKMessage.System(new SystemMessage(
            "ann-4", "model_refusal_fallback", "warning", content)), panel);

        List<MessagePanel.Segment> body = panel.mixed.getFirst();
        assertEquals(2, body.size(), "no link means the released path renders one bold run: " + body);
        assertEquals("⏺ " + content, body.getFirst().text() + body.get(1).text());
        assertNull(body.get(1).hyperlinkUrl());
    }

    @Test
    void announcementWithoutTheMarkerIsNeverSplit() {
        StubPanel panel = new StubPanel();

        LanternaMessageDispatcher dispatcher = new LanternaMessageDispatcher();
        dispatcher.setHyperlinkSupport(() -> true);
        dispatcher.dispatch(new SDKMessage.System(new SystemMessage(
            "ann-5", "model_refusal_fallback", "warning",
            "Switched to Claude Sonnet 4.5.")), panel);

        List<MessagePanel.Segment> body = panel.mixed.getFirst();
        assertEquals(2, body.size(), "indexOf misses, so released returns the plain body: " + body);
        assertNull(body.get(1).hyperlinkUrl());
    }

    @Test
    void refusalWithoutAFallbackRendersNothing() {
        StubPanel panel = new StubPanel();

        new LanternaMessageDispatcher().dispatch(new SDKMessage.System(new SystemMessage(
            "ann-2", "model_refusal_no_fallback", "warning",
            "Claude declined this request")), panel);

        assertEquals(List.of(), panel.lines,
            "released returns null for this subtype — no banner, no tip");
    }

    private static final class StubPanel extends MessagePanel {
        private final List<String> lines = new ArrayList<>();
        private final List<List<MessagePanel.Segment>> mixed = new ArrayList<>();
        private Set<SGR> modifiersOfLastSegment = Set.of();

        @Override public void appendMixed(List<MessagePanel.Segment> segments) {
            mixed.add(List.copyOf(segments));
            lines.add(segments.stream().map(MessagePanel.Segment::text)
                .reduce("", String::concat));
            if (!segments.isEmpty()) {
                modifiersOfLastSegment = segments.getLast().modifiers();
            }
        }

        @Override public void appendMixed(List<MessagePanel.Segment> segments, int wrapWidthInset) {
            appendMixed(segments);
        }

        @Override public void appendLine(String text, TextColor color) {
            lines.add(text);
        }

        @Override public void appendLine(String text, TextColor color, int wrapWidthInset) {
            appendLine(text, color);
        }

        @Override public int snapshotLineCount() {
            return lines.size();
        }
    }
}
