package com.claudecode.ui.lanterna.features.diagnostics;

import com.claudecode.tools.skills.Skill;
import com.claudecode.keybindings.UserKeybindingsStore;
import com.claudecode.runtime.doctor.DoctorPort;
import com.claudecode.ui.lanterna.dialog.DoctorDialog;
import com.claudecode.ui.lanterna.dialog.SkillsDialog;
import com.claudecode.ui.lanterna.dialog.StatsDialog;
import com.claudecode.ui.lanterna.features.ReplFeature;
import com.claudecode.ui.lanterna.overlay.InlineOverlay;
import com.claudecode.ui.lanterna.repl.InteractiveSessionPort;
import com.claudecode.ui.lanterna.repl.ReplCommandUiBridge;
import com.claudecode.ui.lanterna.repl.ReplTranscriptSink;
import com.claudecode.ui.lanterna.theme.LanternaTheme;
import com.googlecode.lanterna.gui2.Component;
import com.googlecode.lanterna.gui2.WindowBasedTextGUI;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Read-only diagnostic overlays: {@code /doctor}, {@code /stats}, and {@code /skills}.
 *
 * <p>TS coverage (paths relative to the claude-code repo root):
 * <ul>
 *   <li>{@code commands/doctor/} — {@code /doctor} loading → scrollable report overlay.</li>
 *   <li>{@code commands/stats/} — {@code /stats} usage panel over persisted sessions.</li>
 *   <li>{@code commands/skills/} — {@code /skills} read-only list overlay.</li>
 * </ul>
 */
public final class DiagnosticsFeature implements ReplCommandUiBridge.Diagnostics, ReplFeature {

    private final WindowBasedTextGUI gui;
    private final ReplTranscriptSink sink;
    private final DoctorDialog doctorDialog;
    private final SkillsDialog skillsDialog;
    private final StatsDialog statsDialog;

    public DiagnosticsFeature(WindowBasedTextGUI gui,
                              ReplTranscriptSink sink,
                              DoctorPort doctor,
                              Supplier<List<Skill>> skills,
                              InteractiveSessionPort interactiveSessions,
                              IntSupplier terminalColumns,
                              ZoneId zone,
                              UserKeybindingsStore keybindingsStore) {
        this.gui = Objects.requireNonNull(gui, "gui");
        this.sink = Objects.requireNonNull(sink, "sink");
        this.doctorDialog = new DoctorDialog(doctor);
        doctorDialog.setKeybindingsStore(keybindingsStore);
        this.skillsDialog = new SkillsDialog(
            skills != null ? skills : List::of,
            Path.of(System.getProperty("user.home")));
        skillsDialog.setKeybindingsStore(keybindingsStore);
        skillsDialog.setGuiInvoker(task -> gui.getGUIThread().invokeLater(task));
        // inline, zero height until shown
        this.statsDialog = new StatsDialog(
            interactiveSessions,
            r -> gui.getGUIThread().invokeLater(r),
            () -> {
                try { return terminalColumns.getAsInt(); }
                catch (Exception _) { return 80; }
            },
            zone);
        statsDialog.setKeybindingsStore(keybindingsStore);
    }

    @Override public List<InlineOverlay> overlays() {
        return List.of(doctorDialog, skillsDialog, statsDialog);
    }
    public Component doctorView() { return doctorDialog; }
    public Component skillsView() { return skillsDialog; }
    public Component statsView() { return statsDialog; }

    /**
     * Opens the inline diagnostic report dialog for {@code /doctor} (LOADING → scrollable
     * REPORT). Backend collection lives behind {@link DoctorPort}.
     */
    @Override
    public void openDoctor() {
        gui.getGUIThread().invokeLater(() ->
            doctorDialog.show(() ->
                sink.line("  Claude Code diagnostics dismissed", LanternaTheme.welcomeDim())));
    }

    @Override
    public void openStats() {
        gui.getGUIThread().invokeLater(() ->
            statsDialog.show(() ->
                sink.line("  Stats dialog dismissed", LanternaTheme.welcomeDim())));
    }

    @Override
    public void openSkills() {
        gui.getGUIThread().invokeLater(() ->
            skillsDialog.show(() ->
                sink.line("  Skills dialog dismissed", LanternaTheme.welcomeDim())));
    }
}
