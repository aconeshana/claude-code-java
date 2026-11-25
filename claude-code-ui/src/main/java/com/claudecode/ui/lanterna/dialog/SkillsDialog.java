package com.claudecode.ui.lanterna.dialog;

import org.apache.commons.lang3.Strings;

import com.claudecode.keybindings.UserKeybindingsStore;
import com.claudecode.runtime.capabilities.SessionCapabilityPort;
import com.claudecode.tools.skills.Skill;
import com.claudecode.ui.lanterna.input.ContextKeybindingDispatcher;
import com.claudecode.core.text.FormatUtils;
import com.claudecode.core.text.StringUtils;
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

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;
import com.claudecode.ui.lanterna.overlay.InlineOverlay;
import com.claudecode.ui.lanterna.theme.LanternaTheme;

/**
 * Read-only, scrollable skills list for {@code /skills}.
 */
public final class SkillsDialog extends Panel implements InlineOverlay {

    private static final int LEFT_PAD = 2;
    private static final int WIDTH = 78;
    private static final int MAX_VISIBLE_ROWS = 18;

    private enum State { HIDDEN, LOADING, SHOWN }

    /** One pre-rendered line: text + colour + emphasis + optional interactive Skill ref. */
    private record Row(String text, TextColor color, boolean bold, Skill skill) {
        static Row plain(String text) { return new Row(text, LanternaTheme.inputText(), false, null); }
        static Row dim(String text)   { return new Row(text, LanternaTheme.welcomeDim(), false, null); }
        static Row header(String text){ return new Row(text, LanternaTheme.welcomeDim(), true, null); }
        static Row skillRow(String text, TextColor color, Skill skill) {
            return new Row(text, color, false, skill);
        }
    }


    private static final List<Map.Entry<Skill.SkillSource, String>> GROUP_ORDER = List.of(
        Map.entry(Skill.SkillSource.PROJECT, "Project skills"),
        Map.entry(Skill.SkillSource.USER,    "User skills"),
        Map.entry(Skill.SkillSource.MANAGED, "Managed skills"),
        Map.entry(Skill.SkillSource.BUILTIN, "Built-in commands"),
        Map.entry(Skill.SkillSource.BUNDLED, "Bundled skills"),
        Map.entry(Skill.SkillSource.PLUGIN,  "Plugin skills"),
        Map.entry(Skill.SkillSource.MCP,     "MCP skills"));

    private final Supplier<List<Skill>> skillsSupplier;
    private final Path homeDir;
    private final Body body;
    private final ContextKeybindingDispatcher keybindings =
        new ContextKeybindingDispatcher();

    private volatile State state = State.HIDDEN;
    private volatile List<Row> lines = List.of();
    private volatile int scrollOffset = 0;
    private volatile int selectedSkillCursor = 0;
    private volatile SessionCapabilityPort capabilitiesPort;
    private volatile Supplier<String> sessionIdSupplier = () -> "";
    private volatile Consumer<String> feedbackSink;

    private Runnable onDismiss;
    private Consumer<Runnable> guiInvoker;
    private long loadGeneration;

    public SkillsDialog(Supplier<List<Skill>> skillsSupplier, Path homeDir) {
        super(new LinearLayout(Direction.VERTICAL).setSpacing(0));
        this.skillsSupplier = skillsSupplier;
        this.homeDir = homeDir;
        this.body = new Body();
        this.body.setLayoutData(LinearLayout.createLayoutData(LinearLayout.Alignment.FILL));
        addComponent(body);
    }

    public void setKeybindingsStore(UserKeybindingsStore store) {
        keybindings.setStore(store);
    }

    public synchronized void setGuiInvoker(Consumer<Runnable> guiInvoker) {
        this.guiInvoker = guiInvoker;
    }

    public synchronized void bindCapabilities(
            SessionCapabilityPort port,
            Supplier<String> sessionIdSupplier,
            Consumer<String> feedbackSink) {
        this.capabilitiesPort = port;
        this.sessionIdSupplier = sessionIdSupplier != null ? sessionIdSupplier : () -> "";
        this.feedbackSink = feedbackSink;
    }

    /**
     * Activate and render the skills list. A cache miss may scan several skill
     * roots, so production loads it on a virtual thread and publishes one
     * immutable row snapshot back to the GUI.
     *
     * @param onDismiss fired once the user closes the dialog
     */
    public synchronized void show(Runnable onDismiss) {
        this.onDismiss = onDismiss;
        this.scrollOffset = 0;
        this.selectedSkillCursor = 0;
        Consumer<Runnable> invoker = guiInvoker;
        if (invoker != null) {
            long generation = ++loadGeneration;
            this.lines = List.of(Row.header("Skills"), Row.dim("Loading skills…"));
            this.state = State.LOADING;
            invalidate();
            Thread.ofVirtual().name("skills-dialog-load").start(() -> {
                List<Row> loaded = loadRows();
                invoker.accept(() -> applyLoadedRows(generation, loaded));
            });
            return;
        }
        this.lines = loadRows();
        this.state = State.SHOWN;
        invalidate();
    }

    private List<Row> loadRows() {
        List<Skill> skills;
        try {
            skills = skillsSupplier != null ? skillsSupplier.get() : List.of();
        } catch (Throwable _) {
            skills = List.of();
        }
        return buildLines(skills != null ? skills : List.of());
    }

    private synchronized void applyLoadedRows(long generation, List<Row> loaded) {
        if (state == State.HIDDEN || generation != loadGeneration) return;
        this.lines = loaded;
        this.state = State.SHOWN;
        invalidate();
    }

    @Override public boolean isActive() { return state != State.HIDDEN; }

    private List<Row> buildLines(List<Skill> skills) {
        List<Row> out = new ArrayList<>();
        int total = skills.size();
        out.add(Row.header("Skills"));
        out.add(Row.dim(total == 0 ? "No skills found" : total + " " + StringUtils.plural(total, "skill")));
        out.add(Row.plain(""));

        if (total == 0) {
            out.add(Row.dim("Create skills in .claude/skills/ or ~/.claude/skills/"));
            return out;
        }

        // Group by source (dedup already handled by SkillLoader.loadAll).
        Map<Skill.SkillSource, List<Skill>> bySource = new LinkedHashMap<>();
        for (Skill s : skills) {
            bySource.computeIfAbsent(s.source(), _ -> new ArrayList<>()).add(s);
        }

        boolean first = true;
        for (Map.Entry<Skill.SkillSource, String> group : GROUP_ORDER) {
            List<Skill> groupSkills = bySource.get(group.getKey());
            if (groupSkills == null || groupSkills.isEmpty()) continue;
            groupSkills.sort(Comparator.comparing(s -> s.name() == null ? "" : s.name()));

            if (!first) out.add(Row.plain(""));
            first = false;

            String subtitle = groupSubtitle(group.getKey(), groupSkills);
            out.add(Row.header(group.getValue() + (subtitle != null ? " (" + subtitle + ")" : "")));
            for (Skill s : groupSkills) {
                out.add(renderSkill(s));
            }
        }
        return out;
    }


    private Row renderSkill(Skill s) {
        String frontmatter = Stream.of(s.name(), s.description())
            .filter(org.apache.commons.lang3.StringUtils::isNotBlank)
            .reduce((a, b) -> a + " " + b).orElse("");

        long tokens = Math.round(frontmatter.length() / 4.0);
        String name = s.name() == null ? "" : s.name();
        SessionCapabilityPort port = this.capabilitiesPort;
        if (port == null) {
            return Row.plain("  " + name + " · ~" + FormatUtils.formatTokens(tokens) + " description tokens");
        }
        String sid = sessionIdSupplier.get();
        boolean sessionOn = port.isSkillEnabled(sid, name);
        String badge = sessionOn ? "[ON] " : "[OFF]";
        TextColor color = sessionOn ? LanternaTheme.inputText() : LanternaTheme.welcomeDim();
        return Row.skillRow(
            "  " + badge + " " + name + " · ~" + FormatUtils.formatTokens(tokens) + " description tokens",
            color, s);
    }


    private String groupSubtitle(Skill.SkillSource source, List<Skill> groupSkills) {
        if (source == Skill.SkillSource.MCP) return null;
        // Plugin skills span multiple plugin cache roots — a single path
        // subtitle would be misleading, so show none.
        if (source == Skill.SkillSource.PLUGIN) return null;
        Path file = groupSkills.getFirst().sourceFile();
        if (file == null || file.getParent() == null) return null;
        Path root = Strings.CS.equals("SKILL.md", file.getFileName().toString()) && file.getParent().getParent() != null
            ? file.getParent().getParent()   // <root>/<name>/SKILL.md
            : file.getParent();              // <root>/<name>.md
        return displayPath(root);
    }


    private String displayPath(Path p) {
        String s = p.toString();
        if (homeDir != null) {
            String home = homeDir.toString();
            if (s.equals(home)) return "~";
            if (Strings.CS.startsWith(s, home + File.separator)) return "~" + s.substring(home.length());
        }
        return s;
    }

    @Override
    public synchronized void handleKey(KeyStroke key, AtomicBoolean deliver) {
        if (state == State.HIDDEN) return;
        ContextKeybindingDispatcher.Result resolved = keybindings.resolve("Confirmation", key);
        if (resolved instanceof ContextKeybindingDispatcher.Result.Consumed) {
            deliver.set(false);
            return;
        }
        if (resolved instanceof ContextKeybindingDispatcher.Result.Action action) {
            if (Strings.CS.equals("confirm:no", action.value())) {
                dismiss();
                deliver.set(false);
                return;
            }
            if (Strings.CS.equals("confirm:yes", action.value())) {
                // SkillsMenu's Dialog subscribes only to confirm:no. Consume
                // the resolved action so Enter cannot leak into chat below.
                deliver.set(false);
                return;
            }
        }
        KeyType t = key.getKeyType();
        Character ch = key.getCharacter();
        List<Integer> skillLineIndices = selectableLineIndices();
        if (capabilitiesPort != null && !skillLineIndices.isEmpty()) {
            if (t == KeyType.ARROW_UP || (ch != null && ch == 'k')) {
                selectedSkillCursor = Math.max(0, selectedSkillCursor - 1);
                ensureCursorVisible(skillLineIndices.get(selectedSkillCursor));
                invalidate();
                deliver.set(false);
                return;
            }
            if (t == KeyType.ARROW_DOWN || (ch != null && ch == 'j')) {
                selectedSkillCursor = Math.min(skillLineIndices.size() - 1, selectedSkillCursor + 1);
                ensureCursorVisible(skillLineIndices.get(selectedSkillCursor));
                invalidate();
                deliver.set(false);
                return;
            }
            if (t == KeyType.CHARACTER && ch != null && (ch == ' ' || ch == 'd' || ch == 'D')) {
                int rowIdx = skillLineIndices.get(
                    Math.min(selectedSkillCursor, skillLineIndices.size() - 1));
                Skill selected = lines.get(rowIdx).skill();
                if (selected != null && selected.name() != null) {
                    SessionCapabilityPort.Scope scope = (ch == ' ')
                        ? SessionCapabilityPort.Scope.SESSION
                        : SessionCapabilityPort.Scope.DEFAULT;
                    String sid = sessionIdSupplier.get();
                    boolean nextEnabled = !capabilitiesPort.isSkillEnabled(sid, selected.name());
                    var outcome = capabilitiesPort.toggle(new SessionCapabilityPort.ToggleRequest(
                        sid, SessionCapabilityPort.Kind.SKILL, selected.name(), null, nextEnabled, scope));
                    lines = loadRows();
                    if (feedbackSink != null) feedbackSink.accept(outcome.message());
                    invalidate();
                }
                deliver.set(false);
                return;
            }
        }
        int maxScroll = Math.max(0, lines.size() - MAX_VISIBLE_ROWS);
        switch (t) {
            case ARROW_UP   -> { scrollOffset = Math.max(0, scrollOffset - 1); invalidate(); deliver.set(false); }
            case ARROW_DOWN -> { scrollOffset = Math.min(maxScroll, scrollOffset + 1); invalidate(); deliver.set(false); }
            case PAGE_UP    -> { scrollOffset = Math.max(0, scrollOffset - MAX_VISIBLE_ROWS); invalidate(); deliver.set(false); }
            case PAGE_DOWN  -> { scrollOffset = Math.min(maxScroll, scrollOffset + MAX_VISIBLE_ROWS); invalidate(); deliver.set(false); }
            case HOME       -> { scrollOffset = 0; invalidate(); deliver.set(false); }
            case END        -> { scrollOffset = maxScroll; invalidate(); deliver.set(false); }
            default -> { /* fall through to global handler */ }
        }
    }

    private List<Integer> selectableLineIndices() {
        List<Integer> indices = new ArrayList<>();
        List<Row> current = this.lines;
        for (int i = 0; i < current.size(); i++) {
            if (current.get(i).skill() != null) indices.add(i);
        }
        return indices;
    }

    private void ensureCursorVisible(int lineIdx) {
        if (lineIdx < scrollOffset) {
            scrollOffset = lineIdx;
        } else if (lineIdx >= scrollOffset + MAX_VISIBLE_ROWS) {
            scrollOffset = lineIdx - MAX_VISIBLE_ROWS + 1;
        }
    }

    private synchronized void dismiss() {
        if (state == State.HIDDEN) return;
        Runnable cb = onDismiss;
        loadGeneration++;
        state = State.HIDDEN;
        lines = List.of();
        onDismiss = null;
        invalidate();
        if (cb != null) cb.run();
    }

    @Override
    public synchronized TerminalSize calculatePreferredSize() {
        if (state == State.HIDDEN) return new TerminalSize(0, 0);
        int extra = capabilitiesPort != null && !selectableLineIndices().isEmpty() ? 3 : 2;
        return new TerminalSize(WIDTH, Math.min(lines.size(), MAX_VISIBLE_ROWS) + extra);
    }

    @Override public Interactable nextFocus(Interactable fromThis) { return isActive() ? super.nextFocus(fromThis) : null; }
    @Override public Interactable previousFocus(Interactable fromThis) { return isActive() ? super.previousFocus(fromThis) : null; }

    // Test-facing accessors — package-private so tests can drive the state
    // machine without a real GUI thread (assert by content, not by position).
    int lineCount() { return lines.size(); }
    int scrollOffset() { return scrollOffset; }
    boolean isShown() { return state == State.SHOWN; }
    List<String> lineTexts() {
        List<String> out = new ArrayList<>();
        for (Row r : lines) out.add(r.text());
        return out;
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

            int visibleRows = Math.min(lines.size(), MAX_VISIBLE_ROWS);
            int end = Math.min(scrollOffset + visibleRows, lines.size());
            List<Integer> skillIndices = selectableLineIndices();
            int highlightedLine = (capabilitiesPort != null && !skillIndices.isEmpty())
                ? skillIndices.get(Math.min(selectedSkillCursor, skillIndices.size() - 1))
                : -1;
            for (int i = scrollOffset; i < end; i++) {
                Row line = lines.get(i);
                boolean isSelected = i == highlightedLine;
                g.setForegroundColor(isSelected ? LanternaTheme.suggestion() : line.color());
                if (line.bold() || isSelected) g.enableModifiers(SGR.BOLD);
                String text = isSelected && Strings.CS.startsWith(line.text(), "  ")
                    ? "▶ " + line.text().substring(2)
                    : line.text();

                g.putString(LEFT_PAD, 1 + (i - scrollOffset), InlineOverlay.clip(text, cols - LEFT_PAD - 2));
                if (line.bold() || isSelected) g.disableModifiers(SGR.BOLD);
            }

            drawScrollIndicators(g, cols, visibleRows);

            if (highlightedLine >= 0 && highlightedLine < lines.size()
                    && lines.get(highlightedLine).skill() != null) {
                String desc = lines.get(highlightedLine).skill().description();
                String summary = org.apache.commons.lang3.StringUtils.isNotBlank(desc)
                    ? "Desc: " + desc.replace('\n', ' ').trim()
                    : "Desc: (no description)";
                g.setForegroundColor(LanternaTheme.welcomeDim());
                g.putString(LEFT_PAD, visibleRows + 1, InlineOverlay.clip(summary, cols - LEFT_PAD - 2));
                g.putString(LEFT_PAD, visibleRows + 2,
                    "↑/↓ select · Space toggle session · d toggle default · Esc close");
            } else {
                g.setForegroundColor(LanternaTheme.welcomeDim());
                g.putString(LEFT_PAD, visibleRows + 1, "↑/↓/PgUp/PgDn to scroll · Esc/Enter to close");
            }
        }

        private void drawScrollIndicators(TextGUIGraphics g, int cols, int visibleRows) {
            if (lines.size() <= visibleRows) return;
            g.setForegroundColor(LanternaTheme.welcomeDim());
            if (scrollOffset > 0) g.putString(cols - 2, 1, "↑");
            if (scrollOffset + visibleRows < lines.size()) g.putString(cols - 2, visibleRows, "↓");
        }

    }
}
