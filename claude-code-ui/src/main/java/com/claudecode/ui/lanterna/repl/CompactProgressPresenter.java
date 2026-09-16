package com.claudecode.ui.lanterna.repl;

import com.claudecode.core.engine.CompactProgressEvent;
import com.claudecode.ui.lanterna.components.SpinnerComponent;
import com.claudecode.ui.lanterna.theme.LanternaTheme;
import java.util.Objects;

/**
 * Drives the footer spinner through a manual {@code /compact} or auto-compact lifecycle.
 *
 * <p>TS coverage (paths relative to the claude-code repo root):
 * <ul>
 *   <li>{@code components/Spinner.tsx} + {@code screens/REPL.tsx} — the {@code isCompacting}
 *       spinner state: system-colored "Running PreCompact hooks…" / "Compacting conversation"
 *       overrides, cleared at {@code compact_end}.</li>
 * </ul>
 */
final class CompactProgressPresenter implements ReplCommandUiBridge.Compact {

    private final SpinnerComponent spinner;
    /**
     * True when {@link #progress} started the spinner itself for a standalone
     * {@code /compact} (no query in flight — the spinner is otherwise only running during
     * turns). Cleared at {@code compact_end}, which then also stops the spinner; when compaction
     * happens mid-turn (auto-compact), the spinner was already running and must be left running.
     */
    private volatile boolean autostarted;

    CompactProgressPresenter(SpinnerComponent spinner) {
        this.spinner = Objects.requireNonNull(spinner, "spinner");
    }

    @Override
    public void progress(CompactProgressEvent event) {
        // The spinner is always mounted in the REPL tree and shows whenever isCompacting.
        if (!(event instanceof CompactProgressEvent.CompactEnd) && !spinner.isSpinning()) {
            spinner.start("Compacting");
            autostarted = true;
        }
        switch (event) {
            case CompactProgressEvent.HooksStart hs -> {
                String msg = switch (hs.hookType()) {
                    case "pre_compact"   -> "Running PreCompact hooks…";
                    case "post_compact"  -> "Running PostCompact hooks…";
                    case "session_start" -> "Running SessionStart hooks…";
                    default              -> "Running hooks…";
                };
                spinner.setOverrideColor(LanternaTheme.systemSpinner());
                spinner.setOverrideShimmerColor(LanternaTheme.systemSpinnerShimmer());
                spinner.setOverrideMessage(msg);
            }
            case CompactProgressEvent.CompactStart _ -> {
                spinner.setOverrideColor(LanternaTheme.systemSpinner());
                spinner.setOverrideShimmerColor(LanternaTheme.systemSpinnerShimmer());
                spinner.setOverrideMessage("Compacting conversation");
                spinner.setCompacting(true);
            }
            case CompactProgressEvent.CompactEnd _ -> {
                spinner.setCompacting(false);
                spinner.setOverrideColor(null);
                spinner.setOverrideShimmerColor(null);
                spinner.setOverrideMessage(null);
                if (autostarted) {
                    autostarted = false;
                    spinner.stop();
                }
            }
        }
    }
}
