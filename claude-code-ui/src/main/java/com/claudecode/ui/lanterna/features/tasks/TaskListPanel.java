package com.claudecode.ui.lanterna.features.tasks;

import com.claudecode.core.platform.Platform;
import com.claudecode.core.process.SubprocessEnvironment;
import com.claudecode.core.text.FormatUtils;
import com.claudecode.ui.lanterna.theme.LanternaTheme;
import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.gui2.AbstractComponent;
import com.googlecode.lanterna.gui2.ComponentRenderer;
import com.googlecode.lanterna.gui2.TextGUIGraphics;
import java.util.stream.Collectors;











public final class TaskListPanel extends AbstractComponent<TaskListPanel> {

    private volatile TaskBoardProjection.View view = TaskBoardProjection.View.EMPTY;

    /** GUI-thread view update; background producers must marshal before calling. */
    public void refresh(TaskBoardProjection.View next) {
        view = next == null ? TaskBoardProjection.View.EMPTY : next;
        invalidate();
    }

    @Override
    protected ComponentRenderer<TaskListPanel> createDefaultRenderer() {
        return new TaskListRenderer();
    }

    private final class TaskListRenderer implements ComponentRenderer<TaskListPanel> {

        @Override
        public TerminalSize getPreferredSize(TaskListPanel component) {
            TaskBoardProjection.View current = view;
            if (!component.isVisible() || current.preferredRows() == 0) {
                return TerminalSize.of(0, 0);
            }
            return new TerminalSize(1, current.preferredRows());
        }

        @Override
        public void drawComponent(TextGUIGraphics graphics, TaskListPanel component) {
            TaskBoardProjection.View current = view;
            if (!component.isVisible() || current.preferredRows() == 0) return;
            int columns = graphics.getSize().getColumns();
            graphics.fill(' ');
            int row = current.standalone() ? 1 : 0;
            int left = current.standalone() ? 2 : 0;
            if (!current.title().isEmpty()) {
                drawTitle(graphics, left, row++, columns, current.title());
            }
            for (TaskBoardProjection.Row item : current.rows()) {
                drawItem(graphics, row++, columns, left, item);
                if (item.activity() != null && row < graphics.getSize().getRows()) {
                    graphics.setForegroundColor(LanternaTheme.welcomeDim());
                    graphics.disableModifiers(SGR.BOLD, SGR.CROSSED_OUT);
                    String activity = FormatUtils.truncate(
                        item.activity(), Math.max(15, columns - 15)) + "…";
                    putClipped(graphics, left + 2, row++, columns, activity);
                }
            }
            if (!current.overflow().isEmpty() && row < graphics.getSize().getRows()) {
                graphics.setForegroundColor(LanternaTheme.welcomeDim());
                graphics.disableModifiers(SGR.BOLD, SGR.CROSSED_OUT);
                graphics.putString(left, row, FormatUtils.truncate(
                    current.overflow(), Math.max(1, columns - left)));
            }
        }

        private void drawTitle(
                TextGUIGraphics graphics, int left, int row, int columns, String title) {
            String rendered = FormatUtils.truncate(title, Math.max(1, columns - left));
            graphics.setForegroundColor(LanternaTheme.welcomeDim());
            graphics.disableModifiers(SGR.BOLD, SGR.CROSSED_OUT);
            int start = 0;
            int x = left;
            while (start < rendered.length()) {
                boolean count = Character.isDigit(rendered.charAt(start));
                int end = start + 1;
                while (end < rendered.length()
                        && Character.isDigit(rendered.charAt(end)) == count) {
                    end++;
                }
                if (count) graphics.enableModifiers(SGR.BOLD);
                else graphics.disableModifiers(SGR.BOLD);
                String segment = rendered.substring(start, end);
                graphics.putString(x, row, segment);
                x += FormatUtils.displayWidth(segment);
                start = end;
            }
            graphics.disableModifiers(SGR.BOLD, SGR.CROSSED_OUT);
        }

        private void drawItem(
                TextGUIGraphics graphics,
                int row,
                int columns,
                int left,
                TaskBoardProjection.Row item) {
            String icon;
            TextColor iconColor;
            boolean unicodeIcons = supportsUnicodeTaskIcons();
            switch (item.status()) {
                case COMPLETED -> {
                    icon = unicodeIcons ? "✔" : "√";
                    iconColor = LanternaTheme.toolSuccess();
                }
                case IN_PROGRESS -> {
                    icon = unicodeIcons ? "◼" : "■";
                    iconColor = LanternaTheme.claude();
                }
                case PENDING -> {
                    icon = unicodeIcons ? "◻" : "□";
                    iconColor = LanternaTheme.inputText();
                }
                default -> throw new IllegalStateException("Unknown task status: " + item.status());
            }
            graphics.setForegroundColor(iconColor);
            graphics.disableModifiers(SGR.BOLD, SGR.CROSSED_OUT);
            graphics.putString(left, row, icon + " ");

            String owner = item.owner() == null ? "" : " (@" + item.owner() + ")";
            int subjectWidth = Math.max(
                15, columns - 15 - FormatUtils.displayWidth(owner));
            String subject = FormatUtils.truncate(item.subject(), subjectWidth);
            graphics.setForegroundColor(item.dim()
                ? LanternaTheme.welcomeDim() : LanternaTheme.inputText());
            if (item.bold()) graphics.enableModifiers(SGR.BOLD);
            if (item.strikethrough()) graphics.enableModifiers(SGR.CROSSED_OUT);
            putClipped(graphics, left + 2, row, columns, subject);
            graphics.disableModifiers(SGR.BOLD, SGR.CROSSED_OUT);

            int x = left + 2 + FormatUtils.displayWidth(subject);
            if (!owner.isEmpty()) {
                graphics.setForegroundColor(LanternaTheme.welcomeDim());
                putClipped(graphics, x, row, columns, " (");
                x += 2;
                TextColor ownerColor = LanternaTheme.agentColor(item.ownerColor());
                graphics.setForegroundColor(ownerColor == null
                    ? LanternaTheme.welcomeDim() : ownerColor);
                String handle = "@" + item.owner();
                putClipped(graphics, x, row, columns, handle);
                x += FormatUtils.displayWidth(handle);
                graphics.setForegroundColor(LanternaTheme.welcomeDim());
                putClipped(graphics, x, row, columns, ")");
                x++;
            }
            String suffix = blockerSuffix(item);
            if (!suffix.isEmpty()) {
                graphics.setForegroundColor(LanternaTheme.welcomeDim());
                putClipped(graphics, x, row, columns, suffix);
            }
        }

        private void putClipped(
                TextGUIGraphics graphics, int column, int row, int columns, String value) {
            int available = columns - column;
            if (available <= 0 || value.isEmpty()) return;
            graphics.putString(column, row, FormatUtils.truncateNoEllipsis(value, available));
        }

        private String blockerSuffix(TaskBoardProjection.Row item) {
            if (!item.blocked()) return "";
            return " › blocked by " + item.openBlockers().stream()
                .map(id -> "#" + id)
                .collect(Collectors.joining(", "));
        }

        private static boolean supportsUnicodeTaskIcons() {
            String term = SubprocessEnvironment.get("TERM");
            if (!Platform.IS_WINDOWS) return !"linux".equals(term);
            if (present("WT_SESSION") || present("TERMINUS_SUBLIME")
                    || "{cmd::Cmder}".equals(SubprocessEnvironment.get("ConEmuTask"))) {
                return true;
            }
            String termProgram = SubprocessEnvironment.get("TERM_PROGRAM");
            return "Terminus-Sublime".equals(termProgram)
                || "vscode".equals(termProgram)
                || "xterm-256color".equals(term)
                || "alacritty".equals(term)
                || "rxvt-unicode".equals(term)
                || "rxvt-unicode-256color".equals(term)
                || "JetBrains-JediTerm".equals(
                    SubprocessEnvironment.get("TERMINAL_EMULATOR"));
        }

        private static boolean present(String name) {
            String value = SubprocessEnvironment.get(name);
            return value != null && !value.isEmpty();
        }
    }
}
