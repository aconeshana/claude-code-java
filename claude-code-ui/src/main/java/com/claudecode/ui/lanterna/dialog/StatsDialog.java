package com.claudecode.ui.lanterna.dialog;

import org.apache.commons.lang3.Strings;

import com.claudecode.keybindings.UserKeybindingsStore;
import com.claudecode.core.model.ModelNames;
import com.claudecode.ui.lanterna.repl.InteractiveSessionPort;
import com.claudecode.ui.lanterna.stats.StatsDateDisplay;
import com.claudecode.ui.lanterna.stats.AsciiChart;
import com.claudecode.ui.lanterna.stats.HeatmapRenderer;
import com.claudecode.core.text.FormatUtils;
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

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import com.claudecode.ui.lanterna.overlay.InlineOverlay;
import com.claudecode.ui.lanterna.theme.LanternaTheme;
import com.claudecode.ui.lanterna.stats.StatsScreenshot;
import com.claudecode.ui.lanterna.input.ContextKeybindingDispatcher;


public final class StatsDialog extends Panel implements InlineOverlay {

    private static final int LEFT_PAD = 2;

    private static final int COL_WIDTH = 28;
    private static final int COL_GAP = 4;

    private static final int MODEL_COL_WIDTH = 36;
    private static final int MODELS_VISIBLE = 4;

    private static final List<InteractiveSessionPort.StatsDateRange> RANGE_ORDER = List.of(
        InteractiveSessionPort.StatsDateRange.ALL, InteractiveSessionPort.StatsDateRange.SEVEN_DAYS, InteractiveSessionPort.StatsDateRange.THIRTY_DAYS);

    private static String rangeLabel(InteractiveSessionPort.StatsDateRange r) {
        return switch (r) {
            case ALL -> "All time";
            case SEVEN_DAYS -> "Last 7 days";
            case THIRTY_DAYS -> "Last 30 days";
        };
    }

    private enum State { HIDDEN, LOADING, ERROR, EMPTY, SHOWN }

    private enum Tab { OVERVIEW, MODELS }

    /** One colored span within a line. */
    private record Span(String text, TextColor color, boolean bold) {
        static Span plain(String t) { return new Span(t, LanternaTheme.inputText(), false); }
        static Span dim(String t)   { return new Span(t, LanternaTheme.welcomeDim(), false); }
        static Span claude(String t){ return new Span(t, LanternaTheme.claude(), false); }
        static Span claudeBold(String t) { return new Span(t, LanternaTheme.claude(), true); }
    }

    private final InteractiveSessionPort sessions;
    private final Consumer<Runnable> guiInvoker;
    private final IntSupplier terminalWidth;
    private final ZoneId zone;

    private State state = State.HIDDEN;
    private Runnable onDismiss;
    private String errorMessage;
    private InteractiveSessionPort.StatsSnapshot allTimeStats;
    private final Map<InteractiveSessionPort.StatsDateRange, InteractiveSessionPort.StatsSnapshot> rangeCache = new EnumMap<>(InteractiveSessionPort.StatsDateRange.class);
    private InteractiveSessionPort.StatsDateRange dateRange = InteractiveSessionPort.StatsDateRange.ALL;
    private boolean loadingFiltered = false;
    private Tab activeTab = Tab.OVERVIEW;
    private int modelScrollOffset = 0;
    private String factoid = "";

    private String copyStatus = null;
    private long showGeneration = 0;

    private List<List<Span>> lines = List.of();
    private final Body body = new Body();
    private final ContextKeybindingDispatcher keybindings =
        new ContextKeybindingDispatcher();

    public StatsDialog(InteractiveSessionPort sessions, Consumer<Runnable> guiInvoker,
                       IntSupplier terminalWidth, ZoneId zone) {
        this.sessions = sessions;
        this.guiInvoker = guiInvoker;
        this.terminalWidth = terminalWidth;
        this.zone = zone;
        setLayoutManager(new LinearLayout(Direction.VERTICAL));
        addComponent(body);
    }

    public void setKeybindingsStore(UserKeybindingsStore store) {
        keybindings.setStore(store);
    }

    // ── lifecycle ────────────────────────────────────────────────────────────

    /** Opens the dialog: shows the loading row, aggregates on a virtual thread. */
    public synchronized void show(Runnable onDismiss) {
        this.onDismiss = onDismiss;
        this.state = State.LOADING;
        this.dateRange = InteractiveSessionPort.StatsDateRange.ALL;
        this.activeTab = Tab.OVERVIEW;
        this.modelScrollOffset = 0;
        this.rangeCache.clear();
        this.allTimeStats = null;
        long gen = ++showGeneration;
        rebuild();
        Thread.ofVirtual().name("stats-aggregate").start(() -> {
            try {
                InteractiveSessionPort.StatsSnapshot stats = sessions.aggregateStats(InteractiveSessionPort.StatsDateRange.ALL);
                guiInvoker.accept(() -> onAllTimeLoaded(gen, stats, null));
            } catch (Exception e) {
                String message = e.getMessage() != null ? e.getMessage() : "Failed to load stats";
                guiInvoker.accept(() -> onAllTimeLoaded(gen, null, message));
            }
        });
    }

    private synchronized void onAllTimeLoaded(long gen, InteractiveSessionPort.StatsSnapshot stats, String error) {
        if (gen != showGeneration || state != State.LOADING) return;
        if (error != null) {
            state = State.ERROR;
            errorMessage = error;
        } else if (stats == null || stats.totalSessions() == 0) {
            state = State.EMPTY;
        } else {
            state = State.SHOWN;
            allTimeStats = stats;
            rangeCache.put(InteractiveSessionPort.StatsDateRange.ALL, stats);
            long totalTokens = stats.modelUsage().values().stream()
                .mapToLong(u -> u.inputTokens() + u.outputTokens()).sum();
            factoid = generateFunFactoid(stats, totalTokens);
        }
        rebuild();
    }

    private synchronized void close() {
        state = State.HIDDEN;
        lines = List.of();
        body.invalidate();
        invalidate();
        Runnable cb = onDismiss;
        onDismiss = null;
        if (cb != null) cb.run();
    }

    @Override public boolean isActive() { return state != State.HIDDEN; }



    @Override
    public synchronized void handleKey(KeyStroke key, AtomicBoolean deliver) {
        deliver.set(false);
        ContextKeybindingDispatcher.Result resolved = keybindings.resolve("Confirmation", key);
        if (resolved instanceof ContextKeybindingDispatcher.Result.Consumed) return;
        if (resolved instanceof ContextKeybindingDispatcher.Result.Action action
                && Strings.CS.equals("confirm:no", action.value())) {
            close();
            return;
        }
        KeyType t = key.getKeyType();
        Character ch = key.getCharacter();

        if (t == KeyType.ESCAPE
            || (t == KeyType.CHARACTER && key.isCtrlDown() && ch != null && (ch == 'c' || ch == 'd'))) {
            close();
            return;
        }
        if (t == KeyType.TAB) {
            activeTab = activeTab == Tab.OVERVIEW ? Tab.MODELS : Tab.OVERVIEW;
            rebuild();
            return;
        }
        if (state != State.SHOWN) {
            if (t == KeyType.PASTE) return;   // consume — no leak to the input box
            return;
        }
        if (t == KeyType.CHARACTER && ch != null && ch == 'r' && !key.isCtrlDown() && !key.isAltDown()) {
            cycleDateRange();
            return;
        }

        if (t == KeyType.CHARACTER && ch != null && ch == 's' && key.isCtrlDown()) {
            handleScreenshot();
            return;
        }
        if (activeTab == Tab.MODELS) {
            int modelCount = displayStats().modelUsage().size();
            if (t == KeyType.ARROW_DOWN && modelScrollOffset < modelCount - MODELS_VISIBLE) {
                modelScrollOffset = Math.min(modelScrollOffset + 2, modelCount - MODELS_VISIBLE);
                rebuild();
            } else if (t == KeyType.ARROW_UP && modelScrollOffset > 0) {
                modelScrollOffset = Math.max(modelScrollOffset - 2, 0);
                rebuild();
            }
        }
    }


    private void cycleDateRange() {
        int idx = RANGE_ORDER.indexOf(dateRange);
        dateRange = RANGE_ORDER.get((idx + 1) % RANGE_ORDER.size());
        modelScrollOffset = 0;
        if (dateRange == InteractiveSessionPort.StatsDateRange.ALL || rangeCache.containsKey(dateRange)) {
            rebuild();
            return;
        }
        loadingFiltered = true;
        long gen = showGeneration;
        InteractiveSessionPort.StatsDateRange target = dateRange;
        rebuild();
        Thread.ofVirtual().name("stats-range").start(() -> {
            InteractiveSessionPort.StatsSnapshot stats;
            try {
                stats = sessions.aggregateStats(target);
            } catch (Exception _) {
                stats = null;
            }
            InteractiveSessionPort.StatsSnapshot result = stats;
            guiInvoker.accept(() -> {
                synchronized (this) {
                    if (gen != showGeneration) return;
                    loadingFiltered = false;
                    if (result != null) rangeCache.put(target, result);
                    rebuild();
                }
            });
        });
    }


    private void handleScreenshot() {
        InteractiveSessionPort.StatsSnapshot stats = displayStats();
        if (stats == null) return;
        copyStatus = "copying…";
        rebuild();
        boolean overview = activeTab == Tab.OVERVIEW;
        long gen = showGeneration;
        Thread.ofVirtual().name("stats-screenshot").start(() -> {
            StatsScreenshot.Result result = StatsScreenshot.copy(stats, overview, zone);
            guiInvoker.accept(() -> {
                synchronized (this) {
                    if (gen != showGeneration) return;
                    copyStatus = result.success() ? "copied!" : "copy failed";
                    rebuild();
                }
            });
            try {
                Thread.sleep(2000);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                return;
            }
            guiInvoker.accept(() -> {
                synchronized (this) {
                    if (gen != showGeneration) return;
                    copyStatus = null;
                    rebuild();
                }
            });
        });
    }


    private InteractiveSessionPort.StatsSnapshot displayStats() {
        InteractiveSessionPort.StatsSnapshot stats = rangeCache.get(dateRange);
        return stats != null ? stats : allTimeStats;
    }

    // ── rendering ────────────────────────────────────────────────────────────

    private void rebuild() {
        List<List<Span>> out = new ArrayList<>();
        switch (state) {
            case HIDDEN -> { /* nothing */ }
            case LOADING -> out.add(List.of(Span.plain("Loading your Claude Code stats…")));
            case ERROR -> out.add(List.of(
                new Span("Failed to load stats: " + errorMessage, LanternaTheme.toolError(), false)));
            case EMPTY -> out.add(List.of(
                new Span("No stats available yet. Start using Claude Code!", LanternaTheme.toolWarning(), false)));
            case SHOWN -> buildShownLines(out);
        }
        lines = out;
        body.invalidate();
        invalidate();
    }

    private void buildShownLines(List<List<Span>> out) {
        InteractiveSessionPort.StatsSnapshot stats = displayStats();


        out.add(List.of(
            activeTab == Tab.OVERVIEW ? Span.claudeBold("Overview") : Span.dim("Overview"),
            Span.dim("  |  "),
            activeTab == Tab.MODELS ? Span.claudeBold("Models") : Span.dim("Models")));
        out.add(List.of());

        if (activeTab == Tab.OVERVIEW) {
            buildOverview(out, stats);
        } else {
            buildModels(out, stats);
        }

        out.add(List.of());
        out.add(List.of(Span.dim("Esc to cancel · Tab to switch tabs · r to cycle dates · ctrl+s to copy"
            + (copyStatus != null ? " · " + copyStatus : ""))));
    }

    private void buildOverview(List<List<Span>> out, InteractiveSessionPort.StatsSnapshot stats) {

        if (!allTimeStats.dailyActivity().isEmpty()) {
            HeatmapRenderer.Heatmap heatmap = HeatmapRenderer.render(
                allTimeStats.dailyActivity(), terminalWidth.getAsInt(), zone);
            out.add(List.of(Span.plain(heatmap.monthLabelRow())));
            for (int day = 0; day < 7; day++) {
                List<Span> row = new ArrayList<>();
                row.add(Span.plain(heatmap.dayLabels().get(day)));
                for (HeatmapRenderer.Cell cell : heatmap.grid().get(day)) {
                    row.add(new Span(String.valueOf(cell.ch()), heatmapColor(cell.intensity()), false));
                }
                out.add(row);
            }
            out.add(List.of());
            out.add(List.of(Span.plain("    Less "),
                new Span("░ ▒ ▓ █", LanternaTheme.claude(), false),
                Span.plain(" More")));
            out.add(List.of());
        }

        out.add(dateRangeSelector());
        out.add(List.of());

        // Section 1: Usage — favorite model | total tokens.
        List<Map.Entry<String, InteractiveSessionPort.ModelUsage>> models = sortedModels(stats);
        long totalTokens = models.stream()
            .mapToLong(e -> e.getValue().inputTokens() + e.getValue().outputTokens()).sum();
        List<Span> left = models.isEmpty() ? List.of() : List.of(
            Span.plain("Favorite model: "),
            Span.claudeBold(ModelNames.displayName(models.getFirst().getKey())));
        List<Span> right = List.of(Span.plain("Total tokens: "),
            Span.claude(FormatUtils.formatNumber(totalTokens)));
        out.add(twoColumns(left, right));
        out.add(List.of());

        // Section 2: Activity rows.
        long rangeDays = switch (dateRange) {
            case SEVEN_DAYS -> 7;
            case THIRTY_DAYS -> 30;
            case ALL -> stats.totalDays();
        };
        out.add(twoColumns(
            List.of(Span.plain("Sessions: "), Span.claude(FormatUtils.formatNumber(stats.totalSessions()))),
            stats.longestSession() != null
                ? List.of(Span.plain("Longest session: "),
                    Span.claude(FormatUtils.formatDuration(stats.longestSession().duration())))
                : List.of()));
        out.add(twoColumns(
            List.of(Span.plain("Active days: "), Span.claude(String.valueOf(stats.activeDays())),
                Span.dim("/" + rangeDays)),
            List.of(Span.plain("Longest streak: "),
                Span.claudeBold(String.valueOf(stats.streaks().longestStreak())),
                Span.plain(stats.streaks().longestStreak() == 1 ? " day" : " days"))));
        out.add(twoColumns(
            stats.peakActivityDay() != null
                ? List.of(Span.plain("Most active day: "), Span.claude(formatPeakDay(stats.peakActivityDay())))
                : List.of(),
            List.of(Span.plain("Current streak: "),
                Span.claudeBold(String.valueOf(allTimeStats.streaks().currentStreak())),
                Span.plain(allTimeStats.streaks().currentStreak() == 1 ? " day" : " days"))));

        if (!factoid.isEmpty()) {
            out.add(List.of());
            out.add(List.of(new Span(factoid, LanternaTheme.suggestion(), false)));
        }
    }

    private void buildModels(List<List<Span>> out, InteractiveSessionPort.StatsSnapshot stats) {
        List<Map.Entry<String, InteractiveSessionPort.ModelUsage>> models = sortedModels(stats);
        if (models.isEmpty()) {
            out.add(List.of(Span.dim("No model usage data available")));
            return;
        }
        long totalTokens = models.stream()
            .mapToLong(e -> e.getValue().inputTokens() + e.getValue().outputTokens()).sum();

        // Tokens per Day chart.
        TokenChart chart = buildTokenChart(stats.dailyModelTokens(),
            models.stream().map(Map.Entry::getKey).toList(), terminalWidth.getAsInt());
        if (chart != null) {
            out.add(List.of(new Span("Tokens per Day", LanternaTheme.inputText(), true)));
            TextColor[] seriesColors = chartSeriesColors();
            for (List<AsciiChart.Cell> row : chart.grid()) {
                List<Span> line = new ArrayList<>();
                for (AsciiChart.Cell cell : row) {
                    TextColor color = cell.seriesIndex() == null
                        ? LanternaTheme.welcomeDim()
                        : seriesColors[cell.seriesIndex() % seriesColors.length];
                    line.add(new Span(cell.text(), color, false));
                }
                out.add(line);
            }
            out.add(List.of(Span.dim(chart.xAxisLabels())));
            List<Span> legend = new ArrayList<>();
            for (int i = 0; i < chart.legendModels().size(); i++) {
                if (i > 0) legend.add(Span.plain(" · "));
                legend.add(new Span("●", seriesColors[i % seriesColors.length], false));
                legend.add(Span.plain(" " + chart.legendModels().get(i)));
            }
            out.add(legend);
            out.add(List.of());
        }

        out.add(dateRangeSelector());
        out.add(List.of());


        List<Map.Entry<String, InteractiveSessionPort.ModelUsage>> visible = models.subList(
            Math.min(modelScrollOffset, models.size()),
            Math.min(modelScrollOffset + MODELS_VISIBLE, models.size()));
        int midpoint = (int) Math.ceil(visible.size() / 2.0);
        for (int i = 0; i < midpoint; i++) {
            var leftEntry = visible.get(i);
            var rightEntry = (i + midpoint) < visible.size() ? visible.get(i + midpoint) : null;
            out.add(modelColumns(modelTitleLine(leftEntry, totalTokens),
                rightEntry != null ? modelTitleLine(rightEntry, totalTokens) : List.of()));
            out.add(modelColumns(modelDetailLine(leftEntry),
                rightEntry != null ? modelDetailLine(rightEntry) : List.of()));
        }

        if (models.size() > MODELS_VISIBLE) {
            boolean canUp = modelScrollOffset > 0;
            boolean canDown = modelScrollOffset < models.size() - MODELS_VISIBLE;
            out.add(List.of());
            out.add(List.of(Span.dim((canUp ? "↑" : " ") + " " + (canDown ? "↓" : " ") + " "
                + (modelScrollOffset + 1) + "-" + Math.min(modelScrollOffset + MODELS_VISIBLE, models.size())
                + " of " + models.size() + " models (↑↓ to scroll)")));
        }
    }

    private List<Span> modelTitleLine(Map.Entry<String, InteractiveSessionPort.ModelUsage> entry, long totalTokens) {
        InteractiveSessionPort.ModelUsage usage = entry.getValue();
        long modelTokens = usage.inputTokens() + usage.outputTokens();
        String pct = String.format(Locale.US, "%.1f", totalTokens > 0 ? modelTokens * 100.0 / totalTokens : 0);
        List<Span> line = new ArrayList<>();
        line.add(Span.plain("• "));
        line.add(new Span(ModelNames.displayName(entry.getKey()), LanternaTheme.inputText(), true));
        line.add(Span.dim(" (" + pct + "%)"));
        return line;
    }

    private List<Span> modelDetailLine(Map.Entry<String, InteractiveSessionPort.ModelUsage> entry) {
        InteractiveSessionPort.ModelUsage usage = entry.getValue();
        return List.of(Span.dim("  In: " + FormatUtils.formatNumber(usage.inputTokens())
            + " · Out: " + FormatUtils.formatNumber(usage.outputTokens())));
    }

    private List<Span> dateRangeSelector() {
        List<Span> line = new ArrayList<>();
        for (int i = 0; i < RANGE_ORDER.size(); i++) {
            InteractiveSessionPort.StatsDateRange range = RANGE_ORDER.get(i);
            if (i > 0) line.add(Span.dim(" · "));
            line.add(range == dateRange
                ? Span.claudeBold(rangeLabel(range))
                : Span.dim(rangeLabel(range)));
        }
        if (loadingFiltered) line.add(Span.dim("  …"));
        return line;
    }

    private static List<Map.Entry<String, InteractiveSessionPort.ModelUsage>> sortedModels(InteractiveSessionPort.StatsSnapshot stats) {
        return stats.modelUsage().entrySet().stream()
            .sorted(Comparator.comparingLong((Map.Entry<String, InteractiveSessionPort.ModelUsage> e) ->
                e.getValue().inputTokens() + e.getValue().outputTokens()).reversed())
            .toList();
    }


    private static List<Span> twoColumns(List<Span> left, List<Span> right) {
        return columns(left, right, COL_WIDTH, COL_GAP);
    }

    private static List<Span> modelColumns(List<Span> left, List<Span> right) {
        return columns(left, right, MODEL_COL_WIDTH, COL_GAP);
    }

    private static List<Span> columns(List<Span> left, List<Span> right, int colWidth, int gap) {
        List<Span> line = new ArrayList<>();
        int width = 0;
        for (Span s : left) {
            line.add(s);
            width += s.text().length();
        }
        if (!right.isEmpty()) {
            int pad = Math.max(1, colWidth + gap - width);
            line.add(Span.plain(" ".repeat(pad)));
            line.addAll(right);
        }
        return line;
    }

    private static TextColor heatmapColor(int intensity) {
        return intensity <= 0 ? LanternaTheme.welcomeDim() : LanternaTheme.claude();
    }

    private static TextColor[] chartSeriesColors() {
        return new TextColor[]{
            LanternaTheme.suggestion(), LanternaTheme.toolSuccess(), LanternaTheme.toolWarning()};
    }



    public record TokenChart(List<List<AsciiChart.Cell>> grid, List<String> legendModels, String xAxisLabels) {}

    public static TokenChart buildTokenChart(List<InteractiveSessionPort.DailyModelTokens> dailyTokens, List<String> models, int termWidth) {
        if (dailyTokens.size() < 2 || models.isEmpty()) return null;

        int yAxisWidth = 7;
        int chartWidth = Math.min(52, Math.max(20, termWidth - yAxisWidth));

        List<InteractiveSessionPort.DailyModelTokens> recentData;
        if (dailyTokens.size() >= chartWidth) {
            recentData = dailyTokens.subList(dailyTokens.size() - chartWidth, dailyTokens.size());
        } else {
            int repeat = chartWidth / dailyTokens.size();
            recentData = new ArrayList<>();
            for (InteractiveSessionPort.DailyModelTokens day : dailyTokens) {
                for (int i = 0; i < repeat; i++) recentData.add(day);
            }
        }

        List<double[]> series = new ArrayList<>();
        List<String> legend = new ArrayList<>();
        List<String> topModels = models.subList(0, Math.min(3, models.size()));
        for (String model : topModels) {
            double[] data = new double[recentData.size()];
            boolean any = false;
            for (int i = 0; i < recentData.size(); i++) {
                Long v = recentData.get(i).tokensByModel().get(model);
                data[i] = v != null ? v : 0;
                if (data[i] > 0) any = true;
            }
            if (any) {
                series.add(data);
                legend.add(ModelNames.displayName(model));
            }
        }
        if (series.isEmpty()) return null;

        List<List<AsciiChart.Cell>> grid = AsciiChart.plot(series, 8, StatsDialog::chartLabel);
        return new TokenChart(grid, legend, xAxisLabels(recentData, yAxisWidth));
    }


    private static String chartLabel(long x) {
        String label;
        if (x >= 1_000_000) label = String.format(Locale.US, "%.1fM", x / 1_000_000.0);
        else if (x >= 1_000) label = (x / 1_000) + "k";
        else label = Long.toString(x);
        return label.length() >= 6 ? label : " ".repeat(6 - label.length()) + label;
    }


    static String xAxisLabels(List<InteractiveSessionPort.DailyModelTokens> data, int yAxisOffset) {
        if (data.isEmpty()) return "";
        int numLabels = Math.min(4, Math.max(2, data.size() / 8));
        int usable = data.size() - 6;
        // numLabels ∈ [2,4] (see Math.max(2, …) above), so numLabels-1 ≥ 1 — no guard.
        int step = Math.max(1, usable / (numLabels - 1));
        StringBuilder result = new StringBuilder(" ".repeat(yAxisOffset));
        int currentPos = 0;
        for (int i = 0; i < numLabels; i++) {
            int idx = Math.min(i * step, data.size() - 1);
            String label = FormatUtils.formatMonthDay(LocalDate.parse(data.get(idx).date()));
            int spaces = Math.max(1, idx - currentPos);
            result.append(" ".repeat(spaces)).append(label);
            currentPos = idx + label.length();
        }
        return result.toString();
    }



    private record Book(String name, long tokens) {}
    private record TimeComparison(String name, long minutes) {}

    private static final List<Book> BOOK_COMPARISONS = List.of(
        new Book("The Little Prince", 22_000),
        new Book("The Old Man and the Sea", 35_000),
        new Book("A Christmas Carol", 37_000),
        new Book("Animal Farm", 39_000),
        new Book("Fahrenheit 451", 60_000),
        new Book("The Great Gatsby", 62_000),
        new Book("Slaughterhouse-Five", 64_000),
        new Book("Brave New World", 83_000),
        new Book("The Catcher in the Rye", 95_000),
        new Book("Harry Potter and the Philosopher's Stone", 103_000),
        new Book("The Hobbit", 123_000),
        new Book("1984", 123_000),
        new Book("To Kill a Mockingbird", 130_000),
        new Book("Pride and Prejudice", 156_000),
        new Book("Dune", 244_000),
        new Book("Moby-Dick", 268_000),
        new Book("Crime and Punishment", 274_000),
        new Book("A Game of Thrones", 381_000),
        new Book("Anna Karenina", 468_000),
        new Book("Don Quixote", 520_000),
        new Book("The Lord of the Rings", 576_000),
        new Book("The Count of Monte Cristo", 603_000),
        new Book("Les Misérables", 689_000),
        new Book("War and Peace", 730_000));

    private static final List<TimeComparison> TIME_COMPARISONS = List.of(
        new TimeComparison("a TED talk", 18),
        new TimeComparison("an episode of The Office", 22),
        new TimeComparison("listening to Abbey Road", 47),
        new TimeComparison("a yoga class", 60),
        new TimeComparison("a World Cup soccer match", 90),
        new TimeComparison("a half marathon (average time)", 120),
        new TimeComparison("the movie Inception", 148),
        new TimeComparison("watching Titanic", 195),
        new TimeComparison("a transatlantic flight", 420),
        new TimeComparison("a full night of sleep", 480));

    public static String generateFunFactoid(InteractiveSessionPort.StatsSnapshot stats, long totalTokens) {
        List<String> factoids = new ArrayList<>();
        if (totalTokens > 0) {
            for (Book book : BOOK_COMPARISONS) {
                if (totalTokens < book.tokens()) continue;
                double times = (double) totalTokens / book.tokens();
                if (times >= 2) {
                    factoids.add("You've used ~" + (long) Math.floor(times)
                        + "x more tokens than " + book.name());
                } else {
                    factoids.add("You've used the same number of tokens as " + book.name());
                }
            }
        }
        if (stats.longestSession() != null) {
            double sessionMinutes = stats.longestSession().duration() / 60_000.0;
            for (TimeComparison comparison : TIME_COMPARISONS) {
                double ratio = sessionMinutes / comparison.minutes();
                if (ratio >= 2) {
                    factoids.add("Your longest session is ~" + (long) Math.floor(ratio)
                        + "x longer than " + comparison.name());
                }
            }
        }
        if (factoids.isEmpty()) return "";
        return factoids.get((int) Math.floor(Math.random() * factoids.size()));
    }


    static String formatPeakDay(String dateStr) {
        Instant instant = StatsDateDisplay.parseFlexible(dateStr);
        if (instant == null) return dateStr;
        return FormatUtils.formatMonthDay(instant);
    }

    // ── Lanterna plumbing (SkillsDialog pattern) ─────────────────────────────

    @Override
    public synchronized TerminalSize calculatePreferredSize() {
        if (state == State.HIDDEN) return TerminalSize.of(0, 0);
        return body.calculateSize();
    }

    @Override public Interactable nextFocus(Interactable fromThis) { return isActive() ? super.nextFocus(fromThis) : null; }
    @Override public Interactable previousFocus(Interactable fromThis) { return isActive() ? super.previousFocus(fromThis) : null; }

    private final class Body extends AbstractComponent<Body> {

        TerminalSize calculateSize() {
            List<List<Span>> snapshot = lines;
            int cols = 0;
            for (List<Span> line : snapshot) {
                int w = LEFT_PAD;
                for (Span s : line) w += s.text().length();
                cols = Math.max(cols, w);
            }
            return new TerminalSize(Math.max(20, cols), Math.max(1, snapshot.size()));
        }

        @Override
        protected ComponentRenderer<Body> createDefaultRenderer() {
            return new ComponentRenderer<>() {
                @Override public TerminalSize getPreferredSize(Body c) { return c.calculateSize(); }

                @Override
                public void drawComponent(TextGUIGraphics g, Body c) {
                    if (state == State.HIDDEN) return;
                    g.fill(' ');
                    List<List<Span>> snapshot = lines;
                    int maxCols = g.getSize().getColumns();
                    for (int row = 0; row < snapshot.size() && row < g.getSize().getRows(); row++) {
                        int col = LEFT_PAD;
                        for (Span span : snapshot.get(row)) {
                            g.setForegroundColor(span.color());
                            if (span.bold()) g.enableModifiers(SGR.BOLD);
                            String text = span.text();
                            int room = maxCols - col;
                            if (room <= 0) break;
                            if (text.length() > room) text = text.substring(0, room);
                            g.putString(col, row, text);
                            col += text.length();
                            if (span.bold()) g.disableModifiers(SGR.BOLD);
                        }
                    }
                }
            };
        }
    }
}
