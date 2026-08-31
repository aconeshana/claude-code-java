package com.claudecode.ui.lanterna.dialog;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.keybindings.UserKeybindingsStore;
import com.claudecode.runtime.doctor.DoctorPort;
import com.claudecode.runtime.doctor.DoctorReport;
import com.claudecode.runtime.doctor.DoctorReport.SettingsValidationError;
import com.claudecode.ui.lanterna.input.ContextKeybindingDispatcher;
import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.gui2.AbstractComponent;
import com.googlecode.lanterna.gui2.ComponentRenderer;
import com.googlecode.lanterna.gui2.Direction;
import com.googlecode.lanterna.gui2.Interactable;
import com.googlecode.lanterna.gui2.LinearLayout;
import com.googlecode.lanterna.gui2.Panel;
import com.googlecode.lanterna.gui2.TextGUIGraphics;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import com.claudecode.ui.lanterna.overlay.InlineOverlay;
import com.claudecode.ui.lanterna.theme.LanternaTheme;

/**
 * Inline diagnostic report dialog for {@code /doctor}.
 */
public final class DoctorDialog extends Panel implements InlineOverlay {

    private static final int LEFT_PAD = 2;
    private static final int WIDTH = 78;

    private static final String WARN = "⚠ ";
    /** Max visible report rows before scrolling kicks in — leaves room for spinner/input below. */
    private static final int MAX_VISIBLE_ROWS = 18;

    private enum State { HIDDEN, LOADING, REPORT }

    /** One pre-rendered report line: text + theme color tag + section-header emphasis. */
    private record ReportLine(String text, TextColor color, boolean bold) {
        static ReportLine of(String text) { return new ReportLine(text, LanternaTheme.welcomeDim(), false); }
        static ReportLine bold(String text) { return new ReportLine(text, LanternaTheme.statusCost(), true); }
        static ReportLine warn(String text) { return new ReportLine(text, LanternaTheme.toolWarning(), false); }
        static ReportLine error(String text) { return new ReportLine(text, LanternaTheme.toolError(), false); }

        static ReportLine boldError(String text) { return new ReportLine(text, LanternaTheme.toolError(), true); }
    }

    private final DoctorPort doctor;
    private final Body body;
    private final ContextKeybindingDispatcher keybindings =
        new ContextKeybindingDispatcher();

    private volatile State state = State.HIDDEN;
    private volatile List<ReportLine> lines = List.of();
    private volatile int scrollOffset = 0;

    private Runnable onDismiss;

    public DoctorDialog(DoctorPort doctor) {
        super(new LinearLayout(Direction.VERTICAL).setSpacing(0));
        this.doctor = doctor;
        this.body = new Body();
        this.body.setLayoutData(LinearLayout.createLayoutData(LinearLayout.Alignment.FILL));
        addComponent(body);
    }

    public void setKeybindingsStore(UserKeybindingsStore store) {
        keybindings.setStore(store);
    }

    /**
     * Activate and start collecting diagnostics. Must be called from the GUI
     * thread; the collection itself runs on a virtual thread so it never
     * stalls rendering — matches {@link MemorySelectorDialog#show}.
     *
     * @param onDismiss fired once the user closes the report (Esc/Enter)
     */
    public synchronized void show(Runnable onDismiss) {
        this.onDismiss = onDismiss;
        this.state = State.LOADING;
        this.lines = List.of();
        this.scrollOffset = 0;
        invalidate();

        Thread.ofVirtual().name("doctor-scan").start(this::loadReport);
    }

    @Override public boolean isActive() { return state != State.HIDDEN; }

    private void loadReport() {
        try {
            DoctorReport report = doctor.collect();

            this.lines = renderLines(report);
            this.state = State.REPORT;
            invalidate();
        } catch (Throwable t) {
            this.lines = List.of(ReportLine.error("Diagnostics failed to complete: " + t.getMessage()));
            this.state = State.REPORT;
            invalidate();
        }
    }

    private static List<ReportLine> renderLines(DoctorReport report) {
        List<ReportLine> out = new ArrayList<>();

        out.add(ReportLine.bold("Diagnostics"));
        out.add(ReportLine.of("└ Version: " + report.runtime().appVersion()));
        out.add(ReportLine.of("└ Search: " + formatRipgrepStatus(report.ripgrepStatus())));
        out.add(ReportLine.of(""));

        if (!report.invalidSettings().isEmpty()) {
            out.add(ReportLine.bold("Invalid Settings"));
            LinkedHashMap<String, List<SettingsValidationError>> byFile = new LinkedHashMap<>();
            for (DoctorReport.SettingsValidationError e : report.invalidSettings()) {
                byFile.computeIfAbsent(e.file(), _ -> new ArrayList<>()).add(e);
            }
            for (var entry : byFile.entrySet()) {
                out.add(ReportLine.of("└ " + entry.getKey()));
                for (DoctorReport.SettingsValidationError e : entry.getValue()) {
                    String p = StringUtils.isNotEmpty(e.path()) ? e.path() + ": " : "";
                    out.add(ReportLine.error("    └ " + p + e.message()));
                }
            }
            out.add(ReportLine.of(""));
        }

        if (!report.sandboxDiagnostics().isEmpty()) {
            out.add(ReportLine.bold("Sandbox"));
            for (String s : report.sandboxDiagnostics()) {
                if (Strings.CS.startsWith(s, "ERROR: ")) {
                    out.add(ReportLine.error("└ " + s.substring("ERROR: ".length())));
                } else if (Strings.CS.startsWith(s, "WARNING: ")) {
                    out.add(ReportLine.warn("└ " + s.substring("WARNING: ".length())));
                } else if (Strings.CS.equals(s, "Status: Missing dependencies")) {
                    out.add(ReportLine.error("└ " + s));
                } else if (Strings.CS.equals(s, "Status: Available (with warnings)")) {
                    out.add(ReportLine.warn("└ " + s));
                } else {
                    out.add(ReportLine.of("└ " + s));
                }
            }
            out.add(ReportLine.of(""));
        }

        if (!report.mcpRows().isEmpty()) {
            for (DoctorReport.DiagnosticRow row : report.mcpRows()) out.add(toReportLine(row));
            out.add(ReportLine.of(""));
        }

        if (!report.envVarChecks().isEmpty()) {
            out.add(ReportLine.bold("Environment Variables"));
            for (DoctorReport.EnvVarCheck c : report.envVarChecks()) {

                String line = "└ " + c.name() + ": " + c.message();
                out.add(Strings.CS.equals("invalid", c.status()) ? ReportLine.error(line) : ReportLine.warn(line));
            }
            out.add(ReportLine.of(""));
        }


        if (!report.agentParseErrors().isEmpty()) {
            out.add(ReportLine.boldError("Agent Parse Errors"));
            out.add(ReportLine.error("└ Failed to parse " + report.agentParseErrors().size()
                + " agent file(s):"));
            for (DoctorReport.AgentParseError f : report.agentParseErrors()) {
                out.add(ReportLine.of("  └ " + f.path() + ": " + f.error()));
            }
            out.add(ReportLine.of(""));
        }


        // "└ N plugin error(s) detected:" then dim "  └ source [plugin]: message" rows.
        if (!report.pluginErrors().isEmpty()) {
            out.add(ReportLine.boldError("Plugin Errors"));
            out.add(ReportLine.error("└ " + report.pluginErrors().size()
                + " plugin error(s) detected:"));
            for (String e : report.pluginErrors()) out.add(ReportLine.of("  └ " + e));
            out.add(ReportLine.of(""));
        }

        if (!report.unreachableRules().isEmpty()) {
            out.add(ReportLine.bold("Unreachable Permission Rules"));
            int n = report.unreachableRules().size();
            out.add(ReportLine.warn("└ " + WARN + n + " unreachable permission rule" + (n == 1 ? "" : "s") + " detected"));
            for (DoctorReport.UnreachablePermissionRule r : report.unreachableRules()) {
                out.add(ReportLine.of("  └ " + r.ruleDisplay() + ": " + r.reason()));
                out.add(ReportLine.of("  └   Fix: " + r.fix()));
            }
            out.add(ReportLine.of(""));
        }

        DoctorReport.ContextUsage ctx = report.contextUsage();
        if (ctx.claudeMd() != null || ctx.agents() != null || ctx.mcpTools() != null) {
            out.add(ReportLine.bold("Context Usage Warnings"));
            if (ctx.claudeMd() != null) {
                List<DoctorReport.FileSize> files = ctx.claudeMd().largeFiles();
                long threshold = ctx.claudeMd().thresholdChars();
                String header = files.size() == 1
                    ? "Large CLAUDE.md file detected (" + fmt(files.getFirst().chars())
                        + " chars > " + fmt(threshold) + ")"
                    : files.size() + " large CLAUDE.md files detected (each > " + fmt(threshold) + " chars)";
                out.add(ReportLine.warn("└ " + WARN + header));
                out.add(ReportLine.of("  └ Files:"));
                for (DoctorReport.FileSize f : files) {
                    out.add(ReportLine.of("    └ " + f.path() + ": " + fmt(f.chars()) + " chars"));
                }
            }
            if (ctx.agents() != null) {
                out.add(ReportLine.warn("└ " + WARN + "Large agent descriptions (~" + fmt(ctx.agents().totalTokens())
                    + " tokens > " + fmt(ctx.agents().thresholdTokens()) + ")"));
                out.add(ReportLine.of("  └ Top contributors:"));
                for (DoctorReport.AgentTokens a : ctx.agents().topAgents()) {
                    out.add(ReportLine.of("    └ " + a.name() + ": ~" + fmt(a.tokens()) + " tokens"));
                }
                if (ctx.agents().moreCount() > 0) {
                    out.add(ReportLine.of("    └ (" + ctx.agents().moreCount() + " more custom agents)"));
                }
            }
            if (ctx.mcpTools() != null) {
                out.add(ReportLine.warn("└ " + WARN + "Large MCP tools context (~" + fmt(ctx.mcpTools().totalTokens())
                    + " tokens estimated > " + fmt(ctx.mcpTools().thresholdTokens()) + ")"));
                out.add(ReportLine.of("  └ MCP servers:"));
                for (DoctorReport.ServerTokens s : ctx.mcpTools().byServer()) {
                    out.add(ReportLine.of("    └ " + s.serverName() + ": " + s.toolCount()
                        + " tools (~" + fmt(s.tokens()) + " tokens)"));
                }
                if (ctx.mcpTools().moreCount() > 0) {
                    out.add(ReportLine.of("    └ (" + ctx.mcpTools().moreCount() + " more servers)"));
                }
            }
            out.add(ReportLine.of(""));
        }

        return out;
    }

    static String formatRipgrepStatus(DoctorReport.RipgrepStatus status) {
        if (!status.working()) return "Not working (Java regex fallback)";
        return switch (status.mode()) {
            case BUILTIN -> "OK (vendor)";
            case SYSTEM -> "OK ("
                + (status.systemPath() != null ? status.systemPath() : "system") + ")";
        };
    }


    private static String fmt(long n) {
        return String.format(Locale.US, "%,d", n);
    }

    private static ReportLine toReportLine(DoctorReport.DiagnosticRow row) {
        return switch (row.style()) {
            case HEADER -> ReportLine.bold(row.text());
            case DIM    -> ReportLine.of(row.text());
            case WARN   -> ReportLine.warn(row.text());
            case ERROR  -> ReportLine.error(row.text());
        };
    }

    @Override
    public synchronized void handleKey(KeyStroke key, AtomicBoolean deliver) {
        if (state == State.HIDDEN) return;
        ContextKeybindingDispatcher.Result resolved = keybindings.resolve("Confirmation", key);
        if (resolved instanceof ContextKeybindingDispatcher.Result.Consumed) {
            deliver.set(false);
            return;
        }
        if (resolved instanceof ContextKeybindingDispatcher.Result.Action(String value)
                && (Strings.CS.equals("confirm:yes", value) || Strings.CS.equals("confirm:no",
            value))) {
            dismiss();
            deliver.set(false);
            return;
        }
        if (state == State.LOADING) {
            deliver.set(false);
            return;
        }
        KeyType t = key.getKeyType();
        int maxScroll = Math.max(0, lines.size() - MAX_VISIBLE_ROWS);
        if (t == KeyType.ARROW_UP) {
            scrollOffset = Math.max(0, scrollOffset - 1);
            invalidate();
            deliver.set(false);
            return;
        }
        if (t == KeyType.ARROW_DOWN) {
            scrollOffset = Math.min(maxScroll, scrollOffset + 1);
            invalidate();
            deliver.set(false);
            return;
        }
        if (t == KeyType.PAGE_UP) {
            scrollOffset = Math.max(0, scrollOffset - MAX_VISIBLE_ROWS);
            invalidate();
            deliver.set(false);
            return;
        }
        if (t == KeyType.PAGE_DOWN) {
            scrollOffset = Math.min(maxScroll, scrollOffset + MAX_VISIBLE_ROWS);
            invalidate();
            deliver.set(false);
            return;
        }
        if (t == KeyType.HOME) {
            scrollOffset = 0;
            invalidate();
            deliver.set(false);
            return;
        }
        if (t == KeyType.END) {
            scrollOffset = maxScroll;
            invalidate();
            deliver.set(false);
        }
    }

    private synchronized void dismiss() {
        if (state == State.HIDDEN) return;
        Runnable cb = onDismiss;
        state = State.HIDDEN;
        lines = List.of();
        onDismiss = null;
        invalidate();
        if (cb != null) cb.run();
    }

    @Override
    public synchronized TerminalSize calculatePreferredSize() {
        if (state == State.HIDDEN) return new TerminalSize(0, 0);
        int rows = state == State.LOADING ? 1 : Math.min(lines.size(), MAX_VISIBLE_ROWS) + 2;
        return new TerminalSize(WIDTH, rows);
    }

    @Override public Interactable nextFocus(Interactable fromThis) { return isActive() ? super.nextFocus(fromThis) : null; }
    @Override public Interactable previousFocus(Interactable fromThis) { return isActive() ? super.previousFocus(fromThis) : null; }

    // Test-facing accessors — package-private on purpose so tests can drive
    // through the state machine without going through a real GUI thread.
    int lineCount() { return lines.size(); }
    int scrollOffset() { return scrollOffset; }

    enum PublicState { HIDDEN_S, LOADING_S, REPORT_S }
    PublicState visibleState() {
        return switch (state) {
            case HIDDEN -> PublicState.HIDDEN_S;
            case LOADING -> PublicState.LOADING_S;
            case REPORT -> PublicState.REPORT_S;
        };
    }

    // ── Rendering ────────────────────────────────────────────────────────

    private final class Body extends AbstractComponent<Body> {
        @Override protected ComponentRenderer<Body> createDefaultRenderer() {
            return new BodyRenderer();
        }
    }

    private final class BodyRenderer implements ComponentRenderer<Body> {
        @Override public TerminalSize getPreferredSize(Body c) {
            return isActive() ? calculatePreferredSize() : new TerminalSize(0, 0);
        }

        @Override public void drawComponent(TextGUIGraphics g, Body c) {
            if (state == State.HIDDEN) return;
            g.fill(' ');
            int cols = g.getSize().getColumns();
            g.setForegroundColor(LanternaTheme.divider());
            g.putString(0, 0, "─".repeat(Math.max(0, cols)));

            if (state == State.LOADING) {
                g.setForegroundColor(LanternaTheme.welcomeDim());
                g.putString(LEFT_PAD, 1, "◐ Checking installation status…");
                return;
            }

            int visibleRows = Math.min(lines.size(), MAX_VISIBLE_ROWS);
            int end = Math.min(scrollOffset + visibleRows, lines.size());
            for (int i = scrollOffset; i < end; i++) {
                ReportLine line = lines.get(i);
                g.setForegroundColor(line.color());
                if (line.bold()) g.enableModifiers(SGR.BOLD);

                // diagnostic lines from overflowing or splitting mid-glyph.
                g.putString(LEFT_PAD, 1 + (i - scrollOffset), InlineOverlay.clip(line.text(), cols - LEFT_PAD - 2));
                if (line.bold()) g.disableModifiers(SGR.BOLD);
            }

            drawScrollIndicators(g, cols, visibleRows);

            g.setForegroundColor(LanternaTheme.welcomeDim());
            g.putString(LEFT_PAD, visibleRows + 1, "↑/↓/PgUp/PgDn to scroll · Esc/Enter to close");
        }

        private void drawScrollIndicators(TextGUIGraphics g, int cols, int visibleRows) {
            if (lines.size() <= visibleRows) return;
            g.setForegroundColor(LanternaTheme.welcomeDim());
            if (scrollOffset > 0) g.putString(cols - 2, 1, "↑");
            if (scrollOffset + visibleRows < lines.size()) g.putString(cols - 2, visibleRows, "↓");
        }

    }
}
