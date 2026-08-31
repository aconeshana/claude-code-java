package com.claudecode.ui.lanterna.dialog;

import com.claudecode.commands.impl.terminal.BtwCommand;
import com.claudecode.core.constants.Figures;
import com.claudecode.core.engine.ApiRetryEvents;
import com.claudecode.core.engine.AbortController;
import com.claudecode.core.engine.SideQuestionContext;
import com.claudecode.ui.MarkdownRenderer;
import com.claudecode.ui.lanterna.components.AnsiToSegments;
import com.claudecode.ui.lanterna.components.OSC52Helper;
import com.claudecode.ui.lanterna.overlay.InlineOverlay;
import com.claudecode.ui.lanterna.theme.LanternaTheme;
import com.claudecode.ui.lanterna.transcript.MessagePanel;
import com.claudecode.ui.lanterna.transcript.MessagePanel.Segment;
import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.gui2.AbstractComponent;
import com.googlecode.lanterna.gui2.ComponentRenderer;
import com.googlecode.lanterna.gui2.Direction;
import com.googlecode.lanterna.gui2.EmptySpace;
import com.googlecode.lanterna.gui2.Interactable;
import com.googlecode.lanterna.gui2.LinearLayout;
import com.googlecode.lanterna.gui2.Panel;
import com.googlecode.lanterna.gui2.TextGUIGraphics;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Inline {@code /btw} side-question surface.
 */
public final class BtwSideQuestionDialog extends Panel implements InlineOverlay {

    public record RetryStatus(int status, int retryAttempt, int maxRetries,
                              long retryAtMillis) { }

    private record HistoryEntry(String question, String response) { }

    private static final int CHROME_ROWS = 5;
    private static final int OUTER_CHROME_ROWS = 6;
    private static final int SCROLL_LINES = 3;
    private static final int VISIBLE_HISTORY = 5;
    private static final int HISTORY_LIMIT = 20;
    private static final int LEFT_PAD = 2;
    private static final int BODY_LEFT_PAD = 2;
    private static final long SPINNER_INTERVAL_MS = 80L;
    private static final char[] SPINNER_FRAMES =
        {'⠋', '⠙', '⠹', '⠸', '⠼', '⠴', '⠦', '⠧', '⠇', '⠏'};

    private static final Object HISTORY_LOCK = new Object();
    private static List<HistoryEntry> processHistory = List.of();
    private static final ScheduledExecutorService TIMER =
        Executors.newSingleThreadScheduledExecutor(runnable ->
            Thread.ofPlatform().name("btw-ui-timer").daemon(true).unstarted(runnable));

    private final Header header = new Header();
    private final MessagePanel body = new MessagePanel();
    private final Footer footer = new Footer();

    private boolean active;
    private String question;
    private String response;
    private String error;
    private boolean synthetic;
    private boolean forking;
    private int frame;
    private int terminalRows = 24;
    private Integer selectedHistoryIndex;
    private List<HistoryEntry> historySnapshot = List.of();
    private Function<String, String> runner;
    private BiConsumer<String, String> forkAction;
    private Runnable onClose;
    private Thread worker;
    private ScheduledFuture<?> spinnerTask;
    private ScheduledFuture<?> copiedResetTask;
    private boolean copied;
    private RetryStatus retry;
    private Consumer<Runnable> guiInvoker = Runnable::run;
    private AbortController abortController;

    public BtwSideQuestionDialog() {
        super(new LinearLayout(Direction.VERTICAL).setSpacing(0));
        addComponent(new EmptySpace(new TerminalSize(0, 1)));
        addComponent(header);
        addComponent(new EmptySpace(new TerminalSize(0, 1)));
        body.setLayoutData(LinearLayout.createLayoutData(LinearLayout.Alignment.FILL));
        addComponent(body);
        addComponent(new EmptySpace(new TerminalSize(0, 1)));
        addComponent(footer);
    }

    public void setGuiInvoker(Consumer<Runnable> guiInvoker) {
        this.guiInvoker = guiInvoker != null ? guiInvoker : Runnable::run;
    }


    public synchronized void show(String question,
                                  int terminalRows,
                                  Function<String, String> runner,
                                  BiConsumer<String, String> forkAction,
                                  Runnable onClose) {
        Objects.requireNonNull(runner, "runner");
        hide(false);
        this.question = normalizeQuestion(question);
        this.terminalRows = Math.max(CHROME_ROWS + OUTER_CHROME_ROWS, terminalRows);
        this.runner = runner;
        this.forkAction = forkAction;
        this.onClose = onClose;
        this.historySnapshot = history();
        this.selectedHistoryIndex = null;
        this.response = null;
        this.error = null;
        this.synthetic = false;
        this.forking = false;
        this.copied = false;
        this.retry = null;
        this.abortController = new AbortController();
        this.frame = 0;
        this.active = true;
        renderBody();
        startSpinner();
        invalidate();

        worker = Thread.ofVirtual().name("btw-worker").start(() -> {
            String answer;
            String failure = null;
            try {
                List<SideQuestionContext.Exchange> history = historySnapshot.stream()
                    .map(entry -> new SideQuestionContext.Exchange(entry.question(), entry.response()))
                    .toList();
                answer = SideQuestionContext.withHistory(history, abortController, () ->
                    ApiRetryEvents.observe(event -> showRetry(
                            event.status(), event.retryAttempt(), event.maxRetries(), event.retryInMs()),
                        () -> runner.apply(BtwCommand.wrapQuestion(this.question))));
            } catch (Exception exception) {
                if (Thread.currentThread().isInterrupted()) return;
                answer = null;
                failure = StringUtils.defaultIfBlank(exception.getMessage(), "Failed to get response");
            }
            String completedAnswer = answer;
            String completedFailure = failure;
            guiInvoker.accept(() -> {
                synchronized (BtwSideQuestionDialog.this) {
                    if (!active) return;
                    response = StringUtils.isBlank(completedAnswer) ? null : completedAnswer;
                    synthetic = isSyntheticResponse(response);
                    error = completedFailure != null ? completedFailure
                        : response == null ? "No response received" : null;
                    retry = null;
                    if (response != null && !synthetic) appendHistory(this.question, response);
                    stopSpinner();
                    renderBody();
                    invalidate();
                }
            });
        });
    }


    public void showRetry(int status, int retryAttempt,
                          int maxRetries, long retryInMillis) {
        guiInvoker.accept(() -> updateRetry(status, retryAttempt, maxRetries, retryInMillis));
    }

    private synchronized void updateRetry(int status, int retryAttempt,
                                          int maxRetries, long retryInMillis) {
        if (!active || response != null || error != null) return;
        retry = new RetryStatus(status, retryAttempt, maxRetries,
            System.currentTimeMillis() + Math.max(0L, retryInMillis));
        renderBody();
        invalidate();
    }

    @Override public synchronized boolean isActive() { return active; }

    @Override public boolean overlaysTranscript() { return false; }

    public synchronized void hide() { hide(true); }

    private void hide(boolean notify) {
        if (!active && !notify) return;
        Runnable callback = notify ? onClose : null;
        active = false;
        stopSpinner();
        if (copiedResetTask != null) copiedResetTask.cancel(false);
        copiedResetTask = null;
        if (worker != null && worker.isAlive()) worker.interrupt();
        if (abortController != null) abortController.abort("user-cancel");
        abortController = null;
        worker = null;
        question = null;
        response = null;
        error = null;
        retry = null;
        historySnapshot = List.of();
        selectedHistoryIndex = null;
        runner = null;
        forkAction = null;
        onClose = null;
        body.clear();
        invalidate();
        if (callback != null) callback.run();
    }

    @Override
    public synchronized void handleKey(KeyStroke key, AtomicBoolean deliver) {
        if (!active) return;
        deliver.set(false);
        if (forking) return;

        KeyType type = key.getKeyType();
        Character character = key.getCharacter();
        if (type == KeyType.ESCAPE || type == KeyType.ENTER
                || type == KeyType.CHARACTER && character != null
                    && ((character == ' ')
                        || key.isCtrlDown() && (character == 'c' || character == 'd'))) {
            hide();
            return;
        }
        if (type == KeyType.ARROW_LEFT || type == KeyType.ARROW_RIGHT) {
            switchHistory(type == KeyType.ARROW_LEFT ? -1 : 1);
            return;
        }
        if (type == KeyType.CHARACTER && character != null && !key.isCtrlDown() && !key.isAltDown()) {
            if (character == 'x' && !historySnapshot.isEmpty()) {
                clearHistory();
                return;
            }
            if (character == 'c' && displayedResponse() != null) {
                copyDisplayedResponse();
                return;
            }
            if (character == 'f' && response != null && !synthetic
                    && selectedHistoryIndex == null && forkAction != null) {
                forkCurrentResponse();
                return;
            }
        }
        if (type == KeyType.ARROW_UP
                || type == KeyType.CHARACTER && key.isCtrlDown() && character != null
                    && character == 'p') {
            body.scrollUp(SCROLL_LINES);
        } else if (type == KeyType.ARROW_DOWN
                || type == KeyType.CHARACTER && key.isCtrlDown() && character != null
                    && character == 'n') {
            body.scrollDown(SCROLL_LINES);
        }
    }

    private void switchHistory(int delta) {
        int size = historySnapshot.size();
        if (size == 0) return;
        int firstVisible = Math.max(0, size - VISIBLE_HISTORY);
        int current = selectedHistoryIndex == null ? size : selectedHistoryIndex;
        int next = Math.max(firstVisible, Math.min(size, current + delta));
        if (next == current) return;
        selectedHistoryIndex = next == size ? null : next;
        renderBody();
        invalidate();
    }

    private void clearHistory() {
        List<HistoryEntry> replacement = response != null && !synthetic
            ? List.of(new HistoryEntry(question, response)) : List.of();
        replaceHistory(replacement);
        historySnapshot = List.of();
        selectedHistoryIndex = null;
        renderBody();
        invalidate();
    }

    private void copyDisplayedResponse() {
        String displayed = displayedResponse();
        if (displayed == null) return;
        OSC52Helper.copyToClipboard(displayed);
        copied = true;
        if (copiedResetTask != null) copiedResetTask.cancel(false);
        copiedResetTask = TIMER.schedule(() -> guiInvoker.accept(() -> {
            synchronized (BtwSideQuestionDialog.this) {
                copied = false;
                invalidate();
            }
        }), 2, TimeUnit.SECONDS);
        invalidate();
    }

    private void forkCurrentResponse() {
        forking = true;
        invalidate();
        String forkQuestion = question;
        String forkResponse = response;
        Thread.ofVirtual().name("btw-fork").start(() -> forkAction.accept(forkQuestion, forkResponse));
    }

    /** Host calls this after the asynchronous fork action has completed or failed. */
    public synchronized void finishFork() {
        if (!active) return;
        forking = false;
        invalidate();
    }

    private void startSpinner() {
        spinnerTask = TIMER.scheduleAtFixedRate(() -> guiInvoker.accept(() -> {
            synchronized (BtwSideQuestionDialog.this) {
                if (!active || response != null || error != null) return;
                frame++;
                renderBody();
                invalidate();
            }
        }), SPINNER_INTERVAL_MS, SPINNER_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private void stopSpinner() {
        if (spinnerTask != null) spinnerTask.cancel(false);
        spinnerTask = null;
    }

    private void renderBody() {
        body.clear();
        String selected = displayedResponse();
        if (selected != null) {
            appendMarkdown(selected);
        } else if (error != null) {
            body.appendMixed(List.of(
                new Segment("  ", TextColor.ANSI.DEFAULT),
                new Segment(error, LanternaTheme.toolError())));
        } else {
            String state = retry == null ? "Answering…" : retryLabel(retry);
            List<Segment> segments = new ArrayList<>();
            segments.add(new Segment("  " + SPINNER_FRAMES[Math.floorMod(frame, SPINNER_FRAMES.length)] + " ",
                LanternaTheme.statusCost()));
            segments.add(new Segment(state, LanternaTheme.statusCost()));
            if (retry != null) {
                long seconds = Math.max(0L,
                    (long) Math.ceil((retry.retryAtMillis() - System.currentTimeMillis()) / 1000.0));
                segments.add(new Segment(" · retrying in " + seconds + "s · attempt "
                    + retry.retryAttempt() + "/" + retry.maxRetries(), LanternaTheme.welcomeDim()));
            }
            body.appendMixed(segments);
        }
        body.scrollUp(Integer.MAX_VALUE / 2);
    }

    private void appendMarkdown(String text) {
        try {
            String ansi = MarkdownRenderer.shared().render(text);
            for (List<Segment> line : AnsiToSegments.ansiToLines(ansi, TextColor.ANSI.DEFAULT)) {
                List<Segment> indented = new ArrayList<>(line.size() + 1);
                indented.add(new Segment(" ".repeat(BODY_LEFT_PAD), TextColor.ANSI.DEFAULT));
                indented.addAll(line);
                body.appendMixed(indented);
            }
        } catch (Exception _) {
            for (String line : text.split("\\R", -1)) {
                body.appendLine(" ".repeat(BODY_LEFT_PAD) + line, TextColor.ANSI.DEFAULT);
            }
        }
    }

    private String displayedResponse() {
        if (selectedHistoryIndex != null
                && selectedHistoryIndex >= 0
                && selectedHistoryIndex < historySnapshot.size()) {
            return historySnapshot.get(selectedHistoryIndex).response();
        }
        return response;
    }

    private int visibleHistoryRows() {
        int visible = Math.min(VISIBLE_HISTORY, historySnapshot.size());
        return visible + (historySnapshot.size() > visible ? 1 : 0);
    }

    private int maxBodyHeight() {
        return Math.max(5,
            terminalRows - CHROME_ROWS - OUTER_CHROME_ROWS - visibleHistoryRows());
    }

    private int bodyHeight() {
        int content = Math.max(1, body.calculatePreferredSize().getRows());
        return Math.min(maxBodyHeight(), content);
    }

    @Override
    public synchronized TerminalSize calculatePreferredSize() {
        if (!active) return new TerminalSize(0, 0);
        int rows = 1 + visibleHistoryRows() + 1 + 1 + bodyHeight() + 1 + 1;
        return new TerminalSize(80, rows);
    }

    @Override public Interactable nextFocus(Interactable fromThis) {
        return active ? super.nextFocus(fromThis) : null;
    }

    @Override public Interactable previousFocus(Interactable fromThis) {
        return active ? super.previousFocus(fromThis) : null;
    }

    private final class Header extends AbstractComponent<Header> {
        @Override protected ComponentRenderer<Header> createDefaultRenderer() {
            return new HeaderRenderer();
        }
    }

    private final class HeaderRenderer implements ComponentRenderer<Header> {
        @Override public TerminalSize getPreferredSize(Header component) {
            return active ? new TerminalSize(80, visibleHistoryRows() + 1) : new TerminalSize(0, 0);
        }

        @Override public void drawComponent(TextGUIGraphics g, Header component) {
            if (!active) return;
            int width = g.getSize().getColumns();
            int questionWidth = Math.max(20, width - 7);
            int visible = Math.min(VISIBLE_HISTORY, historySnapshot.size());
            int first = historySnapshot.size() - visible;
            int row = 0;
            if (first > 0) {
                g.setForegroundColor(LanternaTheme.welcomeDim());
                g.putString(LEFT_PAD, row++, "(+" + first + " earlier /btw)");
            }
            for (int index = first; index < historySnapshot.size(); index++) {
                boolean selected = selectedHistoryIndex != null && selectedHistoryIndex == index;
                g.setForegroundColor(selected ? LanternaTheme.inputText() : LanternaTheme.welcomeDim());
                if (selected) g.enableModifiers(SGR.BOLD);
                g.putString(LEFT_PAD, row++, "/btw "
                    + InlineOverlay.clip(historySnapshot.get(index).question(), questionWidth));
                if (selected) g.disableModifiers(SGR.BOLD);
            }
            boolean browsing = selectedHistoryIndex != null;
            g.setForegroundColor(browsing ? LanternaTheme.welcomeDim() : LanternaTheme.statusCost());
            if (!browsing) g.enableModifiers(SGR.BOLD);
            g.putString(LEFT_PAD, row, "/btw ");
            if (!browsing) g.disableModifiers(SGR.BOLD);
            g.setForegroundColor(LanternaTheme.welcomeDim());
            g.putString(LEFT_PAD + 5, row, InlineOverlay.clip(question, questionWidth));
        }
    }

    private final class Footer extends AbstractComponent<Footer> {
        @Override protected ComponentRenderer<Footer> createDefaultRenderer() {
            return new FooterRenderer();
        }
    }

    private final class FooterRenderer implements ComponentRenderer<Footer> {
        @Override public TerminalSize getPreferredSize(Footer component) {
            return active ? new TerminalSize(80, 1) : new TerminalSize(0, 0);
        }

        @Override public void drawComponent(TextGUIGraphics g, Footer component) {
            if (!active) return;
            g.setForegroundColor(LanternaTheme.welcomeDim());
            if (forking) {
                g.putString(LEFT_PAD, 0, "Forking…");
                return;
            }
            List<String> guides = new ArrayList<>();
            String displayed = displayedResponse();
            if (!historySnapshot.isEmpty()) {
                guides.add("←/→ switch");
            } else if (displayed != null || error != null) {
                guides.add(Figures.UP_ARROW + "/" + Figures.DOWN_ARROW + " scroll");
            }
            if (displayed != null) guides.add(copied ? "Copied to clipboard" : "c copy");
            if (response != null && !synthetic && selectedHistoryIndex == null && forkAction != null) {
                guides.add("f fork");
            }
            if (!historySnapshot.isEmpty()) guides.add("x clear history");
            guides.add("Esc close");
            g.putString(LEFT_PAD, 0,
                InlineOverlay.clip(String.join(" · ", guides), g.getSize().getColumns() - LEFT_PAD));
        }
    }

    private static String retryLabel(RetryStatus value) {
        return switch (value.status()) {
            case 429 -> "Rate limited";
            case 529 -> "API overloaded";
            case 401, 403 -> "Authentication failed";
            default -> "API error";
        };
    }

    private static boolean isSyntheticResponse(String value) {
        return value != null && (value.startsWith("(The model tried to call ")
            || value.startsWith("(API error: "));
    }

    private static String normalizeQuestion(String value) {
        return StringUtils.defaultString(value).replaceAll("\\s+", " ").trim();
    }

    private static List<HistoryEntry> history() {
        synchronized (HISTORY_LOCK) { return List.copyOf(processHistory); }
    }

    private static void appendHistory(String question, String response) {
        synchronized (HISTORY_LOCK) {
            List<HistoryEntry> next = new ArrayList<>(processHistory);
            next.add(new HistoryEntry(question, response));
            if (next.size() > HISTORY_LIMIT) {
                next = new ArrayList<>(next.subList(next.size() - HISTORY_LIMIT, next.size()));
            }
            processHistory = List.copyOf(next);
        }
    }

    private static void replaceHistory(List<HistoryEntry> values) {
        synchronized (HISTORY_LOCK) { processHistory = List.copyOf(values); }
    }

    static void resetHistoryForTest() { replaceHistory(List.of()); }

    static void seedHistoryForTest(List<String> questions) {
        List<HistoryEntry> entries = new ArrayList<>();
        for (String question : questions) entries.add(new HistoryEntry(question, "answer: " + question));
        replaceHistory(entries);
    }

    synchronized List<String> visibleHeaderLinesForTest(int width) {
        int questionWidth = Math.max(20, width - 7);
        List<String> lines = new ArrayList<>();
        int visible = Math.min(VISIBLE_HISTORY, historySnapshot.size());
        int first = historySnapshot.size() - visible;
        if (first > 0) lines.add("(+" + first + " earlier /btw)");
        for (int index = first; index < historySnapshot.size(); index++) {
            lines.add("/btw " + InlineOverlay.clip(historySnapshot.get(index).question(), questionWidth));
        }
        lines.add("/btw " + InlineOverlay.clip(question, questionWidth));
        return lines;
    }

    synchronized String displayedResponseForTest() { return displayedResponse(); }
    synchronized boolean copiedForTest() { return copied; }
    synchronized int maxBodyHeightForTest() { return maxBodyHeight(); }
}
