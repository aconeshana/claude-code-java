package com.claudecode.ui.lanterna.dialog;

import java.util.Locale;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.claudecode.keybindings.UserKeybindingsStore;
import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.RetractedMessages;
import com.claudecode.core.text.FormatUtils;
import com.claudecode.core.text.DisplayTagUtils;
import com.claudecode.core.text.TerminalSafeText;
import com.claudecode.ui.lanterna.repl.InteractiveSessionPort;
import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.bundle.LanternaThemes;
import com.googlecode.lanterna.gui2.BasicWindow;
import com.googlecode.lanterna.gui2.Direction;
import com.googlecode.lanterna.gui2.Label;
import com.googlecode.lanterna.gui2.LinearLayout;
import com.googlecode.lanterna.gui2.Panel;
import com.googlecode.lanterna.gui2.TextBox;
import com.googlecode.lanterna.gui2.Window;
import com.googlecode.lanterna.gui2.WindowBasedTextGUI;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.input.MouseAction;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.BreakIterator;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import com.claudecode.ui.lanterna.input.KillRing;
import com.claudecode.ui.lanterna.input.ContextKeybindingDispatcher;
import com.claudecode.ui.lanterna.input.MouseScrollHandler;
import com.claudecode.ui.lanterna.components.LanternaDraw;
import com.claudecode.ui.lanterna.components.LoadingStateLabel;
import com.claudecode.ui.lanterna.components.SmartLayout;
import com.claudecode.ui.lanterna.theme.LanternaTheme;
import com.claudecode.ui.lanterna.transcript.LanternaMessageDispatcher;
import com.claudecode.ui.lanterna.transcript.MessageCollapser;
import com.claudecode.ui.lanterna.transcript.MessagePanel;
import com.claudecode.ui.lanterna.transcript.TranscriptReplay;
import com.claudecode.ui.lanterna.repl.LanternaReplScreen;

/**
 * Full-screen session picker for {@code /resume}.
 */
public class SessionSelectorDialog extends BasicWindow {

    private static final Logger log = LoggerFactory.getLogger(SessionSelectorDialog.class);

    private final List<InteractiveSessionPort.SessionEntry> allSessions;
    private final InteractiveSessionPort sessionStorage;
    private final String currentBranch;
    private final String currentCwd;

    private InteractiveSessionPort.SessionListing sameRepositoryListing;
    private InteractiveSessionPort.SessionListing activeListing;
    private InteractiveSessionPort.SessionListing allProjectsListing;
    private Supplier<InteractiveSessionPort.SessionListing> allProjectsListingFactory;
    private int progressiveInitialLoad = 50;


    private volatile String pendingExitKey = null;
    private final ScheduledExecutorService exitTimer =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "session-exit-timer");
            t.setDaemon(true);
            return t;
        });
    private ScheduledFuture<?> exitTimerFuture;

    /** GUI-thread invoker for async preview callbacks — set by {@link LanternaReplScreen}. */
    private Consumer<Runnable> guiInvoker = null;

    private Supplier<List<InteractiveSessionPort.SessionEntry>> allProjectsLoader = null;
    private List<InteractiveSessionPort.SessionEntry> allProjectsSessions = null;
    private CompletableFuture<?> allProjectsLoadFuture = null;
    private long allProjectsLoadGeneration;
    private boolean allProjectsLoadInFlight;
    private CompletableFuture<?> loadMoreFuture = null;
    private long loadMoreGeneration;
    private boolean loadMoreInFlight;
    /** The cwd-scoped list to restore when all-projects mode is toggled off. */
    private List<InteractiveSessionPort.SessionEntry> currentDirSessions = null;
    /** Running async preview load — cancelled when Esc is pressed in PREVIEW mode. */
    private CompletableFuture<?> previewLoadFuture = null;
    private long previewLoadGeneration;
    private Predicate<InteractiveSessionPort.SessionEntry> deleteSessionCallback = null;
    private boolean deleteInFlight;
    private String deleteFailure = null;

    private List<DisplayEntry> filtered = new ArrayList<>();   // kept for compat
    private List<SessionGroup> groups = new ArrayList<>();
    private final Set<String> expandedGroups = new HashSet<>();
    private List<String> uniqueTags = new ArrayList<>();
    private int selectedTagIndex = 0;
    /** Flattened view: each item is either a plain entry, a group header, or a child. */
    private record FlatItem(DisplayEntry entry, boolean isHeader, boolean isChild, SessionGroup group) {}
    private List<FlatItem> flatList = new ArrayList<>();
    private final Panel listRoot;
    private final Label divider;
    private final Label titleLabel;
    private final Label searchBoxLine1;
    private final Label searchBoxLine2;
    private final Label searchBoxLine3;
    private final TextBox searchTextBox;
    private final Label projectLabel;
    private final Panel listPanel;
    private final Label footerLabel;
    private final Label[] listRows;     // title rows
    private final Label[] metaRows;     // metadata rows under each title
    private final Label[] blankRows;


    /** Content tree: transcript viewport + pinned dim-bordered footer. */
    private final Panel previewRoot;

    private final Panel previewLoadingRoot;
    private final MessagePanel previewTranscript = new MessagePanel();
    private final LanternaMessageDispatcher previewDispatcher = new LanternaMessageDispatcher();
    private final MessageCollapser previewCollapser;
    private final Label previewDivider;
    private final Label previewMetaLabel;
    private final Label previewHintLabel;
    private final LoadingStateLabel previewSpinner = new LoadingStateLabel();
    private final Label previewLoadingHint;
    private final MouseScrollHandler.WheelAccelState previewWheelAccel =
        MouseScrollHandler.newState();

    private final StringBuilder searchQuery = new StringBuilder();
/**
     * Cursor position within searchQuery.
     */
    private int searchCursorOffset = 0;
    private int selectedIndex;
    private int scrollOffset;
    private boolean showAllProjects = false;
    private boolean branchFilter = false;
    private boolean showAllWorktrees = false;
    private boolean hasMultipleWorktrees = false;

    private enum ViewMode { LIST, SEARCH, PREVIEW, RENAME, DELETE_CONFIRM }
    private ViewMode viewMode = ViewMode.LIST;
    private String previewMetadata = "";
    private InteractiveSessionPort.SessionEntry previewSession;
    private boolean previewHasTranscript = false;
    private int renameCursorOffset = 0;
    private boolean renameSaveInFlight;
    private long renameSaveGeneration;
    private InteractiveSessionPort.SessionEntry result = null;
    private final int visibleCount;
    private final ContextKeybindingDispatcher keybindings =
        new ContextKeybindingDispatcher();
    /** Prevents lazy/all-project loaders from resurrecting a deleted entry in the open dialog. */
    private final Set<String> deletedSessionIds = new HashSet<>();

    /** Augmented session info with display title + metadata. */
    private record DisplayEntry(InteractiveSessionPort.SessionEntry info, String title, String meta, String groupKey, String tag) {}

    /** A group of forked sessions sharing the same sessionId. */
    private record SessionGroup(String groupKey, DisplayEntry header, List<DisplayEntry> children, boolean expanded) {}

    public SessionSelectorDialog(List<InteractiveSessionPort.SessionEntry> sessions, InteractiveSessionPort storage,
                                 Path ignoredSessionDir, String currentBranch,
                                 String currentCwd, int termRows) {
        super("");
        this.allSessions = new ArrayList<>(sessions.stream().map(session -> {
            if (session.transcriptPath() != null || ignoredSessionDir == null) return session;
            Path transcript = ignoredSessionDir.resolve(session.id() + ".jsonl");
            long size = -1L;
            try { size = Files.size(transcript); } catch (Exception _) { }
            return new InteractiveSessionPort.SessionEntry(session.id(), session.lastModified(),
                session.createdAt(), session.messageCount(), session.summary(), session.gitBranch(),
                session.cwd(), session.tag(), transcript, session.projectPath(),
                session.customTitle(), size);
        }).toList());
        this.sessionStorage = storage;
        this.currentBranch = currentBranch;
        this.currentCwd = currentCwd;

        // headerLines min=8 (no tags, no filter line); max=10 (tags + filter).
        // Allocate for max capacity (headerLines=8) → 3 extra label slots;
// renderPreview / refreshDisplay only fills up to filtered.size.

        this.visibleCount = Math.max(1, (termRows - 10) / 3);

        setHints(Set.of(Window.Hint.FULL_SCREEN, Window.Hint.NO_DECORATIONS,
                        Window.Hint.NO_POST_RENDERING,
                        Window.Hint.FIT_TERMINAL_WINDOW));

        Panel root = new Panel(new LinearLayout(Direction.VERTICAL));
        this.listRoot = root;

        divider = new Label("");
        divider.setForegroundColor(LanternaTheme.suggestion());
        root.addComponent(divider);


        root.addComponent(new Label(" "));

        titleLabel = new Label("Resume Session");
        titleLabel.setForegroundColor(LanternaTheme.suggestion());
        titleLabel.addStyle(SGR.BOLD);
        root.addComponent(titleLabel);

        searchBoxLine1 = new Label("");
        searchBoxLine2 = new Label("");
        searchBoxLine3 = new Label("");
        root.addComponent(searchBoxLine1);
        root.addComponent(searchBoxLine2);  // middle row: LIST placeholder / SEARCH query+cursor
        // TextBox retained for RENAME mode only (real cursor + mid-text editing)
        searchTextBox = new TextBox();
        searchTextBox.setTheme(LanternaThemes.getDefaultTheme());
        searchTextBox.setVisible(false);
        root.addComponent(searchTextBox);
        root.addComponent(searchBoxLine3);

        projectLabel = new Label("");
        projectLabel.setForegroundColor(LanternaTheme.ghostText());
        root.addComponent(projectLabel);

        listPanel = new Panel(new LinearLayout(Direction.VERTICAL));
        listRows = new Label[visibleCount];
        metaRows = new Label[visibleCount];
        blankRows = new Label[visibleCount];
        for (int i = 0; i < visibleCount; i++) {
            listRows[i] = new Label("");
            metaRows[i] = new Label("");
            blankRows[i] = new Label(" ");
            listPanel.addComponent(listRows[i]);
            listPanel.addComponent(metaRows[i]);
            listPanel.addComponent(blankRows[i]);
        }
        root.addComponent(listPanel);

        footerLabel = new Label("");
        footerLabel.setForegroundColor(LanternaTheme.ghostText());
        root.addComponent(footerLabel);

        setComponent(root);

// ── Preview trees ────────────────────────────────────────────────────.

        // ternary between the loading column and the transcript + footer column.
        previewDispatcher.setVerbose(true);
        previewCollapser = new MessageCollapser(previewDispatcher, true);
        previewCollapser.setShowAll(true);

        previewDivider = new Label("");
        previewDivider.setForegroundColor(LanternaTheme.ghostText());
        previewMetaLabel = new Label("");
        previewMetaLabel.setForegroundColor(LanternaTheme.inputText());
        previewHintLabel = new Label("");
        previewHintLabel.setForegroundColor(LanternaTheme.ghostText());

        // SmartLayout: child 0 absorbs the remaining rows, children 1..N pin to
        // the bottom at their natural height. A plain LinearLayout would hand
        // MessagePanel its full content height and overflow the window.
        previewRoot = new Panel(new SmartLayout());
        previewRoot.addComponent(previewTranscript);
        previewRoot.addComponent(previewDivider);
        previewRoot.addComponent(previewMetaLabel);
        previewRoot.addComponent(previewHintLabel);

        previewLoadingHint = new Label(" Esc to cancel");
        previewLoadingHint.setForegroundColor(LanternaTheme.ghostText());
        Panel spinnerRow = new Panel(new LinearLayout(Direction.HORIZONTAL).setSpacing(0));
        spinnerRow.addComponent(new Label(" "));
        spinnerRow.addComponent(previewSpinner.setMessage("Loading session…"));
        previewLoadingRoot = new Panel(new LinearLayout(Direction.VERTICAL).setSpacing(0));
        previewLoadingRoot.addComponent(new Label(""));
        previewLoadingRoot.addComponent(spinnerRow);
        previewLoadingRoot.addComponent(previewLoadingHint);

        // TextBox text change → sync renameBuffer (rename mode only; search handled directly in handleInput).
        searchTextBox.setTextChangeListener((newText, _) -> {
            if (viewMode == ViewMode.RENAME) {
                renameBuffer.setLength(0);
                renameBuffer.append(newText);
            }
        });

        rebuildEntries();
        refreshDisplay();
    }

    public void setKeybindingsStore(UserKeybindingsStore store) {
        keybindings.setStore(store);
    }

    /**
 * Opens the dialog already searching for {@code query}.
     */
    public void setInitialSearchQuery(String query) {
        if (StringUtils.isBlank(query)) return;
        viewMode = ViewMode.SEARCH;
        searchQuery.setLength(0);
        searchQuery.append(query);
        searchCursorOffset = searchQuery.length();
        selectedIndex = 0;
        scrollOffset = 0;
        rebuildEntries();
        refreshDisplay();
    }

/**
     * Build grouped entry list from raw InteractiveSessionPort.SessionEntry.
     */
    private void rebuildEntries() {
        String q = searchQuery.toString().toLowerCase(Locale.ROOT);
        // Build entries with groupKey (sessionId from first message)
        List<DisplayEntry> all = new ArrayList<>();
        for (InteractiveSessionPort.SessionEntry s : allSessions) {
            String title = buildTitle(s);
            String meta = buildMeta(s);
            String gk = buildGroupKey(s);
            String tag = s.tag();
            if (!q.isEmpty()) {


                // has no {type:"pr-link"} JSONL entry writer yet (add matching
// to the OR chain when PR-tracking commands are implemented).
                String branch = s.gitBranch() != null ? s.gitBranch().toLowerCase(Locale.ROOT) : "";
                String tagStr = tag != null ? tag.toLowerCase(Locale.ROOT) : "";
                if (!Strings.CI.contains(title, q) && !Strings.CS.contains(branch, q)
                        && !Strings.CS.contains(tagStr, q)) {
                    continue;
                }
            }
            all.add(new DisplayEntry(s, title, meta, gk, tag));
        }

        Set<String> tagSet = new TreeSet<>();
        for (DisplayEntry e : all) {
            if (StringUtils.isNotBlank(e.tag())) tagSet.add(e.tag());
        }
        uniqueTags = new ArrayList<>(tagSet);
        if (selectedTagIndex > uniqueTags.size()) selectedTagIndex = 0;

        // Tag filter — "All" (index 0) shows everything; otherwise filter by tag.
// The `> uniqueTags.size` clamp above guarantees the upper bound.
        if (selectedTagIndex > 0) {
            String filterTag = uniqueTags.get(selectedTagIndex - 1);
            all = all.stream().filter(e -> filterTag.equals(e.tag())).toList();
        }


        if (branchFilter && currentBranch != null) {
            all = all.stream().filter(e -> currentBranch.equals(e.info().gitBranch())).toList();
        }



        // "any session with a different cwd" heuristic misfired catastrophically
        // in all-projects mode, filtering every cross-project session right back
// out of the Ctrl+A list (found via tmux while implementation cross-project
        // resume, 2026-07-12).
        if (!showAllWorktrees && currentCwd != null && hasMultipleWorktrees) {
            all = all.stream().filter(e -> e.info().alias()
                || currentCwd.equals(e.info().cwd())).toList();
        }


        Map<String, List<DisplayEntry>> byGroup = new LinkedHashMap<>();
        for (DisplayEntry e : all) {
            String key = e.groupKey() != null ? e.groupKey() : e.info().id();
            byGroup.computeIfAbsent(key, _ -> new ArrayList<>()).add(e);
        }
        groups = new ArrayList<>();
        for (var entry : byGroup.entrySet()) {
            List<DisplayEntry> list = entry.getValue();
// Sort by lastModified descending (newest first).
            list.sort((a, b) -> Long.compare(b.info().lastModified(), a.info().lastModified()));
            if (list.size() == 1) {
                groups.add(new SessionGroup(entry.getKey(), list.getFirst(), List.of(), false));
            } else {
                // Preserve previous expanded state
                boolean prevExpanded = expandedGroups.contains(entry.getKey());
                groups.add(new SessionGroup(entry.getKey(), list.getFirst(),
                    new ArrayList<>(list.subList(1, list.size())), prevExpanded));
            }
        }
        if (selectedIndex >= flatSize()) selectedIndex = Math.max(0, flatSize() - 1);
        rebuildFlatList();
    }

    /** Build flat display list from groups — header + expanded children. */
    private void rebuildFlatList() {
        flatList = new ArrayList<>();
        filtered = new ArrayList<>();

        boolean autoExpand = viewMode == ViewMode.SEARCH || branchFilter;
        for (SessionGroup g : groups) {
            flatList.add(new FlatItem(g.header(), true, false, g));
            filtered.add(g.header());
            if (autoExpand || g.expanded()) {
                for (DisplayEntry child : g.children()) {
                    flatList.add(new FlatItem(child, false, true, g));
                    filtered.add(child);
                }
            }
        }
    }


    private String buildGroupKey(InteractiveSessionPort.SessionEntry s) {
        return s.id();
    }

    /** Flattened display size (groups expanded → header + children; collapsed → header only). */
    private int flatSize() {
        int n = 0;
        for (SessionGroup g : groups) {
            n++;  // header
            if (g.expanded()) n += g.children().size();
        }
        return n;
    }

    private String buildTitle(InteractiveSessionPort.SessionEntry s) {

        // the lite-log summary. This matters when the title has fallen outside
        // the 4 KiB lite head/tail window but is still present in the wider
        // metadata tail scan.


// SessionManager.buildSessionInfo filters sidechain sessions using the
// first-line "isSidechain":true check, so the picker never
        // sees them — the suffix is unreachable and intentionally omitted.
        String title = StringUtils.isNotBlank(s.customTitle()) ? s.customTitle() : s.summary();
        if (StringUtils.isNotBlank(title)) {
            String stripped = DisplayTagUtils.stripDisplayTags(title);
            if (!StringUtils.isBlank(stripped)) {
                return normalizeAndTruncateToWidth(stripped, 120);
            }
        }
        return s.id().length() > 8 ? s.id().substring(0, 8) : s.id();
    }

    private String buildMeta(InteractiveSessionPort.SessionEntry s) {
        List<String> parts = new ArrayList<>();
        Instant modified = s.lastModified() > 0
            ? Instant.ofEpochMilli(s.lastModified()) : s.createdAt();
        parts.add(FormatUtils.formatRelativeTimeAgo(modified, FormatUtils.RelativeTimeStyle.SHORT));
        if (StringUtils.isNotBlank(s.gitBranch())) parts.add(s.gitBranch());
        parts.add(s.fileSize() >= 0 ? FormatUtils.formatFileSize(s.fileSize())
            : s.messageCount() + " messages");
        if (StringUtils.isNotBlank(s.tag())) parts.add("#" + s.tag());
        if (showAllProjects && StringUtils.isNotBlank(s.cwd())) parts.add(s.cwd());
        return TerminalSafeText.sanitizeLine(String.join(" · ", parts));
    }

    private void refreshDisplay() {
        int termWidth = 200;
        try {
            termWidth = getTextGUI().getScreen().getTerminalSize().getColumns();
        } catch (Exception _) {}
        termWidth = Math.max(40, termWidth);

        if (viewMode == ViewMode.PREVIEW) {
            renderPreview(termWidth);
            return;
        }
// Every other mode lives in the list tree.
        if (getComponent() != listRoot) setComponent(listRoot);
        if (viewMode == ViewMode.DELETE_CONFIRM) {
            renderDeleteConfirmation(termWidth);
            return;
        }
        listPanel.setVisible(true);

        // Divider line — full width ─
        divider.setText(repeat('─', termWidth));

// Title — TagTabs when tags exist.
        if (!uniqueTags.isEmpty()) {
            List<String> tabs = new ArrayList<>();
            tabs.add("All");
            tabs.addAll(uniqueTags);
            int sel = Math.min(selectedTagIndex, tabs.size() - 1);
            // Compute tab widths: " All " or " #tag "
            int[] widths = tabs.stream().mapToInt(t ->
                (Strings.CS.equals(t, "All") ? 5 : 2 + t.length() + 1) + 1).toArray(); // +1 gap
            int resumeW = "Resume ".length() + 1;
            int rightHintW = "→ (tab to cycle)".length();
            int maxTabsW = termWidth - resumeW - rightHintW - 2;
            int totalW = Arrays.stream(widths).sum();
            int startIdx = 0, endIdx = tabs.size();
            if (totalW > maxTabsW) {

                int leftArrowW = 4; // "← NN "
                int effMax = maxTabsW - leftArrowW;
                int winW = widths[sel];
                startIdx = sel; endIdx = sel + 1;
                while (startIdx > 0 || endIdx < tabs.size()) {
                    if (startIdx > 0) {
                        int lw = widths[startIdx - 1] + 1;
                        if (winW + lw <= effMax) { startIdx--; winW += lw; continue; }
                    }
                    if (endIdx < tabs.size()) {
                        int rw = widths[endIdx] + 1;
                        if (winW + rw <= effMax) { endIdx++; winW += rw; continue; }
                    }
                    break;
                }
            }
            int hiddenLeft = startIdx;
            int hiddenRight = tabs.size() - endIdx;
            StringBuilder tb = new StringBuilder("Resume ");
            if (hiddenLeft > 0) tb.append("← ").append(hiddenLeft).append(" ");
            for (int i = startIdx; i < endIdx; i++) {
                String t = tabs.get(i);
                String disp = Strings.CS.equals(t, "All") ? "All" : "#" + t;
                if (i == sel) tb.append("[").append(disp).append("]");
                else tb.append(" ").append(disp).append(" ");
                tb.append(" ");
            }

            if (hiddenRight > 0) tb.append("→").append(hiddenRight).append(" (tab to cycle)");
            else tb.append("(tab to cycle)");
            titleLabel.setText(FormatUtils.truncate(tb.toString(), termWidth));
        } else {
            titleLabel.setText("Resume Session" + (viewMode == ViewMode.LIST && flatList.size() > visibleCount
                ? " (" + (selectedIndex + 1) + " of " + flatList.size() + ")" : ""));
        }

        // Search box — ╭───╮ / Label middle row / ╰───╯
        // Border: suggestion color when SEARCH (focused), ghostText when LIST (unfocused)

        int boxWidth = termWidth;
        TextColor borderColor = (viewMode == ViewMode.SEARCH)
            ? LanternaTheme.suggestion() : LanternaTheme.ghostText();
        searchBoxLine1.setForegroundColor(borderColor);
        searchBoxLine3.setForegroundColor(borderColor);
        searchBoxLine1.setText(LanternaDraw.borderedSearchBoxTop(boxWidth));
        searchBoxLine3.setText(LanternaDraw.borderedSearchBoxBottom(boxWidth));
        searchBoxLine1.setVisible(true);
        searchBoxLine3.setVisible(true);
        searchTextBox.setVisible(false);
        if (viewMode == ViewMode.SEARCH) {
// Query text + block cursor at searchCursorOffset.
            searchBoxLine2.setText(LanternaDraw.borderedSearchBoxContent(true, searchQuery.toString(), searchCursorOffset, boxWidth));
            searchBoxLine2.setForegroundColor(LanternaTheme.suggestion());
            searchBoxLine2.setVisible(true);
        } else {
            // LIST mode — dim placeholder: "│ ⌕ Search…" + spaces + "│" = boxWidth total
            searchBoxLine2.setText(LanternaDraw.borderedSearchBoxContent(false, "", 0, boxWidth));
            searchBoxLine2.setForegroundColor(LanternaTheme.ghostText());
            searchBoxLine2.setVisible(true);
        }

// filterIndicators — hidden in SEARCH mode.
        if (viewMode == ViewMode.SEARCH) {
            projectLabel.setText("");
        } else {
            List<String> indicators = new ArrayList<>();
            if (branchFilter && currentBranch != null) {
                indicators.add(currentBranch);
            }
            if (hasMultipleWorktrees && !showAllWorktrees) {
                indicators.add("current worktree");
            }
            projectLabel.setText(indicators.isEmpty() ? "" : "  " + String.join(" · ", indicators));
        }

        // Session list
        if (filtered.isEmpty()) {
            for (int i = 0; i < visibleCount; i++) {
                listRows[i].setText("");
                metaRows[i].setText("");
                blankRows[i].setText("");
            }
        } else {
            if (selectedIndex < scrollOffset) scrollOffset = selectedIndex;
            if (selectedIndex >= scrollOffset + visibleCount)
                scrollOffset = selectedIndex - visibleCount + 1;
            scrollOffset = Math.max(0, Math.min(scrollOffset, Math.max(0, flatList.size() - visibleCount)));

            for (int i = 0; i < visibleCount; i++) {
                int ei = scrollOffset + i;
                if (ei >= flatList.size()) {
                    listRows[i].setText("");
                    metaRows[i].setText("");
                    blankRows[i].setText("");
                    continue;
                }
                FlatItem fi = flatList.get(ei);
                DisplayEntry e = fi.entry();
                SessionGroup g = fi.group();
                boolean selected = (ei == selectedIndex) && viewMode != ViewMode.SEARCH;
                String title = FormatUtils.truncate(e.title, termWidth - 10);


                String prefix;
                if (fi.isHeader() && g != null && !g.children().isEmpty()) {
                    String treePrefix = g.expanded() ? "▼ " : "▶ ";
                    prefix = (selected ? "❯ " : "  ") + treePrefix;
                    title = FormatUtils.truncate(e.title, termWidth - prefix.length() - 4);
                    title += " (+" + g.children().size() + " other session" +
                        (g.children().size() == 1 ? "" : "s") + ")";
                } else if (fi.isChild()) {
                    prefix = (selected ? "❯ " : "  ") + "  ▸ ";
                } else {
                    prefix = selected ? "❯ " : "  ";
                }
                String titleLine = prefix + title;


                String metaIndent = fi.isChild() ? "      " : "  ";
                String metaLine = metaIndent + FormatUtils.truncate(e.meta, termWidth - metaIndent.length() - 1);

              listRows[i].setText(titleLine);
              listRows[i].setBackgroundColor(TextColor.ANSI.DEFAULT);
              if (selected) {

                listRows[i].setForegroundColor(LanternaTheme.suggestion());
                    listRows[i].removeStyle(SGR.BOLD);
                    metaRows[i].setText(metaLine);
                    metaRows[i].setBackgroundColor(TextColor.ANSI.DEFAULT);
                    metaRows[i].setForegroundColor(LanternaTheme.suggestion());
              } else {

                listRows[i].setForegroundColor(TextColor.ANSI.DEFAULT);
                    listRows[i].removeStyle(SGR.BOLD);
                    metaRows[i].setText(metaLine);
                    metaRows[i].setBackgroundColor(TextColor.ANSI.DEFAULT);

                    metaRows[i].setForegroundColor(LanternaTheme.ghostText());
              }
              metaRows[i].removeStyle(SGR.BOLD);
// Blank spacer row after each item.
                blankRows[i].setText(" ");
            }
        }


        if (viewMode == ViewMode.SEARCH) {
            setFooter("  Type to Search · Enter to select · Esc to clear");
            return;
        }
// RENAME mode: "Enter to save · Esc to cancel".
        if (viewMode == ViewMode.RENAME) {
            setFooter("  Enter to save · Esc to cancel");
            return;
        }
        StringBuilder footer = new StringBuilder("  ");
        List<String> hints = new ArrayList<>();
        hints.add("Ctrl+A to " + (showAllProjects ? "show current dir" : "show all projects"));
        if (currentBranch != null) {
            hints.add("Ctrl+B to toggle branch");
        }
        if (hasMultipleWorktrees) {
            hints.add("Ctrl+W to show " + (showAllWorktrees ? "current worktree" : "all worktrees"));
        }
        // NOTE: 最新版 Claude Code 已将预览快捷键从 Ctrl+V 改为 Space，不要照抄旧版原版
        hints.add("Space to preview");
        hints.add("Ctrl+R to rename");
        hints.add("x to delete");
        hints.add("Type to search");
        hints.add("Esc to cancel");

        if (!flatList.isEmpty() && selectedIndex >= 0 && selectedIndex < flatList.size()) {
            FlatItem fi = flatList.get(selectedIndex);
            if (fi.isHeader() && fi.group() != null && !fi.group().children().isEmpty()) {
                hints.add(fi.group().expanded() ? "← to collapse" : "→ to expand");
            }
        }
        footer.append(String.join(" · ", hints));
        setFooter(FormatUtils.truncate(footer.toString(), termWidth));
    }


    private void renderPreview(int termWidth) {
        if (!previewHasTranscript) {
            if (getComponent() != previewLoadingRoot) setComponent(previewLoadingRoot);
            previewLoadingHint.setText(previewLoadingHintText());
            return;
        }
        if (getComponent() != previewRoot) setComponent(previewRoot);
        previewDivider.setText(repeat('─', termWidth));
        previewMetaLabel.setText("  " + previewMetadata);
        previewHintLabel.setText(pendingExitKey != null
            ? "  Press " + pendingExitKey + " again to exit"
            : "  Enter to resume · Esc to cancel");
    }

    /** Loading-branch hint, honouring the pending double-press exit override. */
    private String previewLoadingHintText() {
        return pendingExitKey != null
            ? " Press " + pendingExitKey + " again to exit"
            : " Esc to cancel";
    }


    private void renameSelected() {
        if (flatList.isEmpty() || selectedIndex >= flatList.size()) return;

        renameBuffer = new StringBuilder();
        renameCursorOffset = 0;
        viewMode = ViewMode.RENAME;
        refreshRename();
    }

    @Explanation("Adds confirmed permanent deletion to the session selector")
    private void confirmDeleteSelected() {
        if (flatList.isEmpty() || selectedIndex < 0 || selectedIndex >= flatList.size()
                || deleteSessionCallback == null) return;
        deleteFailure = null;
        viewMode = ViewMode.DELETE_CONFIRM;
        refreshDisplay();
    }

    private void renderDeleteConfirmation(int termWidth) {
        divider.setText(repeat('─', termWidth));
        DisplayEntry entry = flatList.isEmpty() || selectedIndex < 0 || selectedIndex >= flatList.size()
            ? null : flatList.get(selectedIndex).entry();
        titleLabel.setText("Delete conversation permanently?");
        searchBoxLine1.setVisible(false);
        searchBoxLine2.setVisible(false);
        searchBoxLine3.setVisible(false);
        searchTextBox.setVisible(false);
        listPanel.setVisible(false);
        projectLabel.setText(entry == null ? "" : "  " + FormatUtils.truncate(entry.title(), termWidth - 4));
        if (deleteInFlight) {
            setFooter("  Deleting permanently…");
        } else if (deleteFailure != null) {
            setFooter("  Delete failed: " + FormatUtils.truncate(deleteFailure, Math.max(1, termWidth - 20))
                + " · Enter to retry · Esc to cancel");
        } else {
            setFooter("  This cannot be undone · Enter to delete · Esc to cancel");
        }
    }

    private void deleteConfirmed() {
        if (deleteInFlight || flatList.isEmpty() || selectedIndex < 0
                || selectedIndex >= flatList.size() || deleteSessionCallback == null) return;
        InteractiveSessionPort.SessionEntry target = flatList.get(selectedIndex).entry().info();
        if (guiInvoker == null) {
            finishDeleteAttempt(target, runDelete(target), null);
            return;
        }
        deleteInFlight = true;
        deleteFailure = null;
        refreshDisplay();
        CompletableFuture.supplyAsync(() -> runDelete(target))
            .whenComplete((deleted, failure) -> guiInvoker.accept(
                () -> finishDeleteAttempt(target, Boolean.TRUE.equals(deleted), failure)));
    }

    private boolean runDelete(InteractiveSessionPort.SessionEntry target) {
        try {
            return deleteSessionCallback.test(target);
        } catch (RuntimeException failure) {
            log.warn("Failed to permanently delete session '{}'", target.id(), failure);
            deleteFailure = failure.getMessage();
            return false;
        }
    }

    private void finishDeleteAttempt(InteractiveSessionPort.SessionEntry target, boolean deleted, Throwable failure) {
        deleteInFlight = false;
        if (!deleted || failure != null) {
            if (failure != null) {
                log.warn("Failed to permanently delete session '{}'", target.id(), failure);
                deleteFailure = failure.getMessage();
            }
            if (StringUtils.isBlank(deleteFailure)) deleteFailure = "session was not deleted";
            refreshDisplay();
            return;
        }
        allSessions.remove(target);
        deletedSessionIds.add(target.id());
        if (currentDirSessions != null) {
            currentDirSessions.removeIf(session -> session.id().equals(target.id()));
        }
        deleteFailure = null;
        viewMode = ViewMode.LIST;
        rebuildEntries();
        selectedIndex = Math.min(selectedIndex, Math.max(0, flatList.size() - 1));
        scrollOffset = Math.min(scrollOffset, selectedIndex);
        refreshDisplay();
    }

    private StringBuilder renameBuffer;

    private void refreshRename() {
        int termWidth = 200;
        try { termWidth = getTextGUI().getScreen().getTerminalSize().getColumns(); }
        catch (Exception _) {}
        termWidth = Math.max(40, termWidth);
        divider.setText(repeat('─', termWidth));
        titleLabel.setText("Rename session:");

        searchBoxLine1.setText("");
        searchBoxLine1.setForegroundColor(TextColor.ANSI.DEFAULT);
        searchBoxLine1.setVisible(true);

        String text = renameBuffer.toString();
        String before = text.substring(0, Math.min(renameCursorOffset, text.length()));
        String after  = text.substring(Math.min(renameCursorOffset, text.length()));
        String inputLine;
        if (text.isEmpty()) {
// Show current title as greyed placeholder.
            String placeholder = !flatList.isEmpty() && selectedIndex < flatList.size()
                ? flatList.get(selectedIndex).entry().title() : "Enter new session name";
            inputLine = "  " + placeholder + "█";
            searchBoxLine2.setForegroundColor(LanternaTheme.ghostText());
        } else {
            inputLine = "  " + before + "█" + after;
            searchBoxLine2.setForegroundColor(LanternaTheme.suggestion());
        }
        if (inputLine.length() < termWidth) {
            inputLine = inputLine + repeat(' ', termWidth - inputLine.length());
        } else {
            inputLine = inputLine.substring(0, termWidth);
        }
        searchBoxLine2.setText(inputLine);
        searchBoxLine2.setVisible(true);
        searchBoxLine3.setVisible(false);
        searchTextBox.setVisible(false);
        projectLabel.setText(renameSaveInFlight
            ? "  Saving session name…"
            : "  Enter to save · Esc to cancel");
        projectLabel.setForegroundColor(LanternaTheme.ghostText());
        for (int i = 0; i < visibleCount; i++) {
            listRows[i].setText("");
            metaRows[i].setText("");
            blankRows[i].setText("");
        }
        footerLabel.setText("");
    }

    private void saveRename() {
        if (renameSaveInFlight || flatList.isEmpty() || selectedIndex >= flatList.size()) return;
        String newName = renameBuffer.toString().trim();
        if (newName.isEmpty() || sessionStorage == null) { viewMode = ViewMode.LIST; refreshDisplay(); return; }
        DisplayEntry entry = flatList.get(selectedIndex).entry();
// Append a {type:"custom-title"} JSONL entry.
        if (guiInvoker == null) {
            if (persistRename(entry, newName)) finishRename(entry, newName);
            return;
        }
        renameSaveInFlight = true;
        long generation = ++renameSaveGeneration;
        refreshRename();
        CompletableFuture.supplyAsync(() -> persistRename(entry, newName))
            .whenComplete((saved, failure) -> guiInvoker.accept(() -> {
                if (generation != renameSaveGeneration || viewMode != ViewMode.RENAME) return;
                renameSaveInFlight = false;
                if (failure == null && Boolean.TRUE.equals(saved)) finishRename(entry, newName);
                else refreshRename();
            }));
    }

    private boolean persistRename(DisplayEntry entry, String newName) {
        try {
            sessionStorage.saveCustomTitle(entry.info(), newName);
            return true;
        } catch (Exception renameFailure) {

            // editor open when persistence fails so the input is not lost.
            log.warn("Failed to rename session '{}'", entry.info().id(), renameFailure);
            return false;
        }
    }

    private void finishRename(DisplayEntry entry, String newName) {
        int index = allSessions.indexOf(entry.info());
        if (index >= 0) {
            InteractiveSessionPort.SessionEntry old = allSessions.get(index);
            allSessions.set(index, new InteractiveSessionPort.SessionEntry(old.id(), old.lastModified(), old.createdAt(),
                old.messageCount(), newName, old.gitBranch(), old.cwd(), old.tag(),
                old.transcriptPath(), old.projectPath(), newName, old.fileSize()));
        }
        viewMode = ViewMode.LIST;
        rebuildEntries();
        refreshDisplay();
    }

    /** Reads the stored transcript off the GUI thread. Returns a new list. */
    private List<Message> readPreviewMessages(InteractiveSessionPort.SessionEntry s) {
        if (sessionStorage == null || s.transcriptPath() == null) return List.of();
        // The preview bypasses the recovery pipeline, so it has to drop

        // directly on the session's message list.
        return RetractedMessages.filter(sessionStorage.readMessages(s.transcriptPath()));
    }

    /**
     * Commit an async preview load, on the GUI thread when one is wired.
     */
    private void applyPreviewResult(long generation, List<Message> msgs, Throwable failure) {
        if (failure != null && !(failure instanceof CancellationException)) {
            log.warn("Failed to load session preview", failure);
        }
        Runnable commit = () -> {
            if (generation != previewLoadGeneration || viewMode != ViewMode.PREVIEW) return;
            previewLoadFuture = null;
            previewSpinner.stop();
            previewTranscript.clear();
            previewHasTranscript = true;
            if (failure == null && msgs != null) {
                try {
                    TranscriptReplay.replay(msgs, previewCollapser, previewTranscript, null);
                } catch (RuntimeException e) {
                    log.warn("Failed to render session preview transcript", e);
                }
            }

            previewTranscript.scrollToBottom();
            if (failure == null && msgs != null && previewSession != null) {
// Preserve the catalog's complete transcript count.
                int count = previewSession.messageCount() >= 0
                    ? previewSession.messageCount() : msgs.size();
                previewMetadata = previewMetadata(previewSession, count);
            }
            refreshDisplay();
        };
        if (guiInvoker != null) guiInvoker.accept(commit);
        else commit.run();  // headless/tests: no thread hop to marshal onto
    }

    @Override
    public boolean handleInput(KeyStroke key) {
        if (viewMode == ViewMode.DELETE_CONFIRM) {
            if (deleteInFlight) return true;
            ContextKeybindingDispatcher.Result resolved = keybindings.resolve("Confirmation", key);
            if (resolved instanceof ContextKeybindingDispatcher.Result.Consumed) return true;
            if (resolved instanceof ContextKeybindingDispatcher.Result.Action(String value)) {
                if (Strings.CS.equals("confirm:no", value)) {
                    viewMode = ViewMode.LIST;
                    deleteFailure = null;
                    refreshDisplay();
                    return true;
                }
                if (Strings.CS.equals("confirm:yes", value)) {
                    deleteConfirmed();
                    return true;
                }
            }
            if (key.getKeyType() == KeyType.ESCAPE) {
                viewMode = ViewMode.LIST;
                deleteFailure = null;
                refreshDisplay();
                return true;
            }
            if (key.getKeyType() == KeyType.ENTER) {
                deleteConfirmed();
                return true;
            }
            return true;
        }
        // ── RENAME mode: Label-only input, no TextBox — full key control, ESC always works ──
        if (viewMode == ViewMode.RENAME) {
            if (renameSaveInFlight) return true;
            ContextKeybindingDispatcher.Result resolved = keybindings.resolve("Settings", key);
            if (resolved instanceof ContextKeybindingDispatcher.Result.Consumed) return true;
            if (resolved instanceof ContextKeybindingDispatcher.Result.Action(String value)
                    && Strings.CS.equals("confirm:no", value)) {
                cancelRename();
                return true;
            }
            if (key.getKeyType() == KeyType.ENTER) { saveRename(); return true; }
            if (key.getKeyType() == KeyType.BACKSPACE) {
                if (renameCursorOffset > 0) {
                    renameBuffer.deleteCharAt(renameCursorOffset - 1);
                    renameCursorOffset--;
                    refreshRename();
                }
                return true;
            }
            if (key.getKeyType() == KeyType.DELETE) {
                if (renameCursorOffset < renameBuffer.length()) {
                    renameBuffer.deleteCharAt(renameCursorOffset);
                    refreshRename();
                }
                return true;
            }
            if (key.getKeyType() == KeyType.ARROW_LEFT) {
                if (renameCursorOffset > 0) { renameCursorOffset--; refreshRename(); }
                return true;
            }
            if (key.getKeyType() == KeyType.ARROW_RIGHT) {
                if (renameCursorOffset < renameBuffer.length()) { renameCursorOffset++; refreshRename(); }
                return true;
            }
            if (key.getKeyType() == KeyType.HOME) {
                renameCursorOffset = 0; refreshRename(); return true;
            }
            if (key.getKeyType() == KeyType.END) {
                renameCursorOffset = renameBuffer.length(); refreshRename(); return true;
            }
            if (key.getKeyType() == KeyType.CHARACTER && key.isCtrlDown()) {
                char ch = Character.toLowerCase(key.getCharacter());
                if (ch == 'a') {
                    renameCursorOffset = 0; refreshRename();
                } else if (ch == 'e') {
                    renameCursorOffset = renameBuffer.length(); refreshRename();
                } else if (ch == 'k') {
                    // Ctrl+K = kill to end
                    if (renameCursorOffset < renameBuffer.length()) {
                        KillRing.INSTANCE.push(renameBuffer.substring(renameCursorOffset), KillRing.Direction.APPEND);
                        renameBuffer.delete(renameCursorOffset, renameBuffer.length());
                        refreshRename();
                    }
                } else if (ch == 'u') {
                    if (renameCursorOffset > 0) {
                        KillRing.INSTANCE.push(renameBuffer.substring(0, renameCursorOffset), KillRing.Direction.PREPEND);
                    }
                    renameBuffer.setLength(0); renameCursorOffset = 0; refreshRename();
                } else if (ch == 'w') {
                    int i = renameCursorOffset;
                    while (i > 0 && renameBuffer.charAt(i - 1) == ' ') i--;
                    while (i > 0 && renameBuffer.charAt(i - 1) != ' ') i--;
                    if (i < renameCursorOffset) {
                        KillRing.INSTANCE.push(renameBuffer.substring(i, renameCursorOffset), KillRing.Direction.PREPEND);
                    }
                    renameBuffer.delete(i, renameCursorOffset);
                    renameCursorOffset = i;
                    refreshRename();
                } else if (ch == 'y') {
                    // Ctrl+Y = yank
                    String kill = KillRing.INSTANCE.getLast();
                    if (!kill.isEmpty()) {
                        renameBuffer.insert(renameCursorOffset, kill);
                        renameCursorOffset += kill.length();
                        KillRing.INSTANCE.recordYank();
                        refreshRename();
                    }
                }
                return true;
            }
            if (key.getKeyType() == KeyType.CHARACTER && key.getCharacter() != null
                    && key.getCharacter() >= 0x20 && !key.isCtrlDown() && !key.isAltDown()) {
                renameBuffer.insert(renameCursorOffset, key.getCharacter());
                renameCursorOffset++;
                refreshRename();
                return true;
            }
            return true; // swallow all other keys in rename mode
        }
// ── Double-press Ctrl+C / Ctrl+D to exit.
        if (key.getKeyType() == KeyType.CHARACTER && key.isCtrlDown()
                && key.getCharacter() != null && Character.toLowerCase(key.getCharacter()) == 'c') {
            handleExitKeyPress("Ctrl-C");
            return true;
        }
        if (key.getKeyType() == KeyType.EOF
                || (key.getKeyType() == KeyType.CHARACTER && key.isCtrlDown()
                    && key.getCharacter() != null && Character.toLowerCase(key.getCharacter()) == 'd')) {

            if (viewMode == ViewMode.SEARCH && key.getKeyType() != KeyType.EOF) {
                if (searchQuery.isEmpty()) {
                    // Empty query → exit search (onCancel ?? onExit) = setViewMode("list")
                    viewMode = ViewMode.LIST;
                    refreshDisplay();
                } else if (searchCursorOffset < searchQuery.length()) {
// Non-empty query, cursor not at end → forward-delete char (cursor.del)
                    searchQuery.deleteCharAt(searchCursorOffset);
                    selectedIndex = 0; scrollOffset = 0;
                    rebuildEntries(); refreshDisplay();
                }
// Non-empty query, cursor at end → no-op (cursor.del at end returns unchanged)
                return true;
            }
            // Otherwise: trigger double-press exit flow
            handleExitKeyPress("Ctrl-D");
            return true;
        }

        if (viewMode == ViewMode.PREVIEW) {
            ContextKeybindingDispatcher.Result resolved = keybindings.resolve("Confirmation", key);
            if (resolved instanceof ContextKeybindingDispatcher.Result.Consumed) return true;
            if (resolved instanceof ContextKeybindingDispatcher.Result.Action(String value)) {
                if (Strings.CS.equals("confirm:no", value)) {
                    cancelPreview();
                    return true;
                }
                if (Strings.CS.equals("confirm:yes", value)) {
                    selectCurrent();
                    return true;
                }
            }
            if (scrollPreview(key)) return true;
            return true;  // swallow other keys in preview mode
        }

        if (viewMode == ViewMode.LIST) {
            ContextKeybindingDispatcher.Result resolved = keybindings.resolve("Select", key);
            if (resolved instanceof ContextKeybindingDispatcher.Result.Consumed) return true;
            if (resolved instanceof ContextKeybindingDispatcher.Result.Action(String value)) {
                switch (value) {
                    case "select:previous" -> { moveListPrevious(); return true; }
                    case "select:next" -> { moveListNext(); return true; }
                    case "select:accept" -> { selectCurrent(); return true; }
                    case "select:cancel" -> { cancelDialog(); return true; }
                    default -> { }
                }
            }
        }

        if (key.getKeyType() == KeyType.ESCAPE) {
// SEARCH mode: Esc clears non-empty query first; exits search on empty.
            if (viewMode == ViewMode.SEARCH) {
                if (!searchQuery.isEmpty()) {
                    searchQuery.setLength(0);
                    searchCursorOffset = 0;
                    selectedIndex = 0;
                    scrollOffset = 0;
                    rebuildEntries();
                } else {
                    viewMode = ViewMode.LIST;
                }
                refreshDisplay();
                return true;
            }
            result = null;
            close();
            return true;
        }
// SEARCH mode: Down/Up/Enter exit search → return to list.

        if (viewMode == ViewMode.SEARCH
                && (key.getKeyType() == KeyType.ARROW_DOWN
                    || key.getKeyType() == KeyType.ARROW_UP
                    || key.getKeyType() == KeyType.ENTER)) {
            viewMode = ViewMode.LIST;
            refreshDisplay();
            return true;
        }
        if (key.getKeyType() == KeyType.ARROW_UP) {
            moveListPrevious();
            return true;
        }
        if (key.getKeyType() == KeyType.ARROW_DOWN && !flatList.isEmpty()) {
            moveListNext();
            return true;
        }
// ← collapse / → expand group header.
        if ((key.getKeyType() == KeyType.ARROW_LEFT || key.getKeyType() == KeyType.ARROW_RIGHT)
                && !flatList.isEmpty() && selectedIndex >= 0 && selectedIndex < flatList.size()) {
            FlatItem fi = flatList.get(selectedIndex);
            boolean right = key.getKeyType() == KeyType.ARROW_RIGHT;
            if (fi.isHeader() && fi.group() != null && !fi.group().children().isEmpty()) {
                String gk = fi.group().groupKey();
                if (right) {

                    expandedGroups.add(gk);
                } else {
// ← collapse only if currently expanded.
                    expandedGroups.remove(gk);
                }
                rebuildEntries();
                refreshDisplay();
                return true;
            }
            if (!right && fi.isChild() && fi.group() != null) {
// ← on child → collapse parent group + focus parent header.
                String gk = fi.group().groupKey();
                expandedGroups.remove(gk);
                rebuildEntries();
                // Move focus to the parent header
                for (int idx = 0; idx < flatList.size(); idx++) {
                    FlatItem item = flatList.get(idx);
                    if (item.isHeader() && item.group() != null
                            && item.group().groupKey().equals(gk)) {
                        selectedIndex = idx;
                        break;
                    }
                }
                refreshDisplay();
                return true;
            }
        }
        if (key.getKeyType() == KeyType.ENTER && !flatList.isEmpty()) {

            FlatItem fi = flatList.get(selectedIndex);
            result = fi.entry().info();
            close();
            return true;
        }

        // intentionally handled after configurable Select bindings, so a user
        // who explicitly binds x keeps that binding. SEARCH mode consumed x above.
        if (viewMode == ViewMode.LIST && key.getKeyType() == KeyType.CHARACTER
                && key.getCharacter() != null
                && Character.toLowerCase(key.getCharacter()) == 'x'
                && !key.isCtrlDown() && !key.isAltDown()
                && deleteSessionCallback != null && !flatList.isEmpty()) {
            confirmDeleteSelected();
            return true;
        }
        // Space or Ctrl+V — preview selected session (async loading with spinner).
        if (isPreviewTriggerKey(key) && !flatList.isEmpty() && viewMode != ViewMode.SEARCH) {
            previewHasTranscript = false;
            previewTranscript.clear();
            previewMetadata = previewMetadata(flatList.get(selectedIndex).entry().info());
            previewSession = flatList.get(selectedIndex).entry().info();
            viewMode = ViewMode.PREVIEW;
            refreshDisplay();  // show spinner immediately
            previewSpinner.start(guiInvoker);
            if (previewLoadFuture != null) previewLoadFuture.cancel(true);
            long generation = ++previewLoadGeneration;
            InteractiveSessionPort.SessionEntry infoToLoad = flatList.get(selectedIndex).entry().info();
            previewLoadFuture = CompletableFuture.supplyAsync(() -> readPreviewMessages(infoToLoad))
                .whenComplete((msgs, failure) -> applyPreviewResult(generation, msgs, failure));
            return true;
        }
// ── SEARCH mode: direct searchQuery manipulation (Label-only, no TextBox) ──.
        if (viewMode == ViewMode.SEARCH) {

            if (!searchIsKillKey(key)) KillRing.INSTANCE.resetAccumulation();
            if (!searchIsYankKey(key)) KillRing.INSTANCE.resetYankState();
            if (key.getKeyType() == KeyType.CHARACTER && key.isCtrlDown()
                    && key.getCharacter() != null) {
                char ch = Character.toLowerCase(key.getCharacter());
                if (ch == 'n') {

                    viewMode = ViewMode.LIST; refreshDisplay();
                } else if (ch == 'a') {
                    searchCursorOffset = 0; refreshDisplay();                    // Ctrl+A = go to start (useSearchInput)
                } else if (ch == 'e') {
                    searchCursorOffset = searchQuery.length(); refreshDisplay(); // Ctrl+E = go to end
                } else if (ch == 'h') {

                    if (searchQuery.isEmpty()) {
                        viewMode = ViewMode.LIST; refreshDisplay();
                    } else if (searchCursorOffset > 0) {
                        searchQuery.deleteCharAt(searchCursorOffset - 1);
                        searchCursorOffset--;
                        selectedIndex = 0; scrollOffset = 0;
                        rebuildEntries(); refreshDisplay();
                    }
                } else if (ch == 'k') {

                    if (searchCursorOffset < searchQuery.length()) {
                        KillRing.INSTANCE.push(searchQuery.substring(searchCursorOffset), KillRing.Direction.APPEND);
                        searchQuery.delete(searchCursorOffset, searchQuery.length());
                        selectedIndex = 0; scrollOffset = 0;
                        rebuildEntries(); refreshDisplay();
                    }
                } else if (ch == 'b') {
                    if (searchCursorOffset > 0) { searchCursorOffset--; refreshDisplay(); } // Ctrl+B = move left
                } else if (ch == 'f') {
                    if (searchCursorOffset < searchQuery.length()) { searchCursorOffset++; refreshDisplay(); } // Ctrl+F = move right
                } else if (ch == 'u') {

                    if (searchCursorOffset > 0) {
                        KillRing.INSTANCE.push(searchQuery.substring(0, searchCursorOffset), KillRing.Direction.PREPEND);
                        searchQuery.delete(0, searchCursorOffset);
                        searchCursorOffset = 0;
                        selectedIndex = 0; scrollOffset = 0;
                        rebuildEntries(); refreshDisplay();
                    }
                } else if (ch == 'w') {
                    int i = searchCursorOffset;
                    while (i > 0 && searchQuery.charAt(i - 1) == ' ') i--;
                    while (i > 0 && searchQuery.charAt(i - 1) != ' ') i--;
                    KillRing.INSTANCE.push(searchQuery.substring(i, searchCursorOffset), KillRing.Direction.PREPEND);
                    searchQuery.delete(i, searchCursorOffset);
                    searchCursorOffset = i;
                    selectedIndex = 0; scrollOffset = 0;
                    rebuildEntries(); refreshDisplay();
                } else if (ch == 'y') {
// Ctrl+Y = yank (paste last killed text).
                    String kill = KillRing.INSTANCE.getLast();
                    if (!kill.isEmpty()) {
                        searchQuery.insert(searchCursorOffset, kill);
                        searchCursorOffset += kill.length();
                        KillRing.INSTANCE.recordYank();
                        selectedIndex = 0; scrollOffset = 0;
                        rebuildEntries(); refreshDisplay();
                    }
                }
                // All other Ctrl keys swallowed — do NOT pass to LIST handlers
                return true;
            }
            if (key.getKeyType() == KeyType.ARROW_LEFT) {
                if (key.isCtrlDown() || key.isAltDown()) {
// Ctrl+Left / Meta+Left = jump to previous word start.
                    searchCursorOffset = prevWordOffset(searchQuery, searchCursorOffset);
                } else if (searchCursorOffset > 0) {
                    searchCursorOffset--;
                }
                refreshDisplay();
                return true;
            }
            if (key.getKeyType() == KeyType.ARROW_RIGHT) {
                if (key.isCtrlDown() || key.isAltDown()) {
// Ctrl+Right / Meta+Right = jump to next word start.
                    searchCursorOffset = nextWordOffset(searchQuery, searchCursorOffset);
                } else if (searchCursorOffset < searchQuery.length()) {
                    searchCursorOffset++;
                }
                refreshDisplay();
                return true;
            }
            if (key.getKeyType() == KeyType.HOME) {
                searchCursorOffset = 0; refreshDisplay(); return true;
            }
            if (key.getKeyType() == KeyType.END) {
                searchCursorOffset = searchQuery.length(); refreshDisplay(); return true;
            }
            if (key.getKeyType() == KeyType.BACKSPACE) {
                if (key.isAltDown()) {
// Meta+Backspace = kill word before.
                    int i = prevWordOffset(searchQuery, searchCursorOffset);
                    if (i < searchCursorOffset) {
                        KillRing.INSTANCE.push(searchQuery.substring(i, searchCursorOffset), KillRing.Direction.PREPEND);
                        searchQuery.delete(i, searchCursorOffset);
                        searchCursorOffset = i;
                        selectedIndex = 0; scrollOffset = 0;
                        rebuildEntries(); refreshDisplay();
                    }
                } else if (searchQuery.isEmpty()) {

                    viewMode = ViewMode.LIST;
                    refreshDisplay();
                } else if (searchCursorOffset > 0) {
                    // Non-empty, cursor not at start → delete char before cursor (cursor.backspace())
                    searchQuery.deleteCharAt(searchCursorOffset - 1);
                    searchCursorOffset--;
                    selectedIndex = 0; scrollOffset = 0;
                    rebuildEntries(); refreshDisplay();
                }
                // Non-empty query, cursor at position 0 → no-op (cursor.backspace() at start = unchanged)
                return true;
            }
            if (key.getKeyType() == KeyType.DELETE) {
                if (searchCursorOffset < searchQuery.length()) {
                    searchQuery.deleteCharAt(searchCursorOffset);
                    selectedIndex = 0; scrollOffset = 0;
                    rebuildEntries(); refreshDisplay();
                }
                return true;
            }

            if (key.getKeyType() == KeyType.CHARACTER && key.getCharacter() != null && key.isAltDown()) {
                char ch = Character.toLowerCase(key.getCharacter());
                if (ch == 'b') {
                    searchCursorOffset = prevWordOffset(searchQuery, searchCursorOffset); refreshDisplay();
                } else if (ch == 'f') {
                    searchCursorOffset = nextWordOffset(searchQuery, searchCursorOffset); refreshDisplay();
                } else if (ch == 'd') {

                    int end = nextWordOffset(searchQuery, searchCursorOffset);
                    if (end > searchCursorOffset) {
                        searchQuery.delete(searchCursorOffset, end);
                        selectedIndex = 0; scrollOffset = 0;
                        rebuildEntries(); refreshDisplay();
                    }
                }
                return true;
            }
            if (key.getKeyType() == KeyType.CHARACTER
                    && key.getCharacter() != null
                    && key.getCharacter() >= 0x20
                    && !key.isCtrlDown() && !key.isAltDown()) {
                searchQuery.insert(searchCursorOffset, key.getCharacter());
                searchCursorOffset++;
                selectedIndex = 0;
                scrollOffset = 0;
                rebuildEntries();
                refreshDisplay();
                return true;
            }
            // Arrow up/down and Enter fall through to exit-search handlers below
        }

// ── LIST mode: any printable char enters search; "/" → empty query, other char → initial
// query ──.
        if (viewMode == ViewMode.LIST
                && key.getKeyType() == KeyType.CHARACTER && key.getCharacter() != null
                && key.getCharacter() > 0x20
                && !key.isCtrlDown() && !key.isAltDown()
                && !flatList.isEmpty()) {
            viewMode = ViewMode.SEARCH;
            searchQuery.setLength(0);
            searchCursorOffset = 0;
            if (key.getCharacter() != '/') {

                searchQuery.append(key.getCharacter());
                searchCursorOffset = 1;
            }
            selectedIndex = 0;
            scrollOffset = 0;
            rebuildEntries();
            refreshDisplay();
            return true;
        }
        if (key.getKeyType() == KeyType.CHARACTER && key.getCharacter() == 'a' && key.isCtrlDown()) {
            if (allProjectsLoadInFlight) return true;
            showAllProjects = !showAllProjects;

            // the DATA SOURCE, not just the metadata display.
            if (allProjectsLoader != null) {
                if (showAllProjects) {
                    if (currentDirSessions == null) currentDirSessions = new ArrayList<>(allSessions);
                    if (guiInvoker != null) {
                        loadAllProjectsAsync();
                        refreshDisplay();
                        return true;
                    }
                    replaceSessions(allProjectsLoader.get());
                } else if (currentDirSessions != null) {
                    replaceSessions(currentDirSessions);
                }
            } else if (allProjectsListingFactory != null) {
                if (showAllProjects) {
                    if (currentDirSessions == null) currentDirSessions = new ArrayList<>(allSessions);
                    if (allProjectsListing == null) {
                        allProjectsListing = allProjectsListingFactory.get();
                    }
                    activeListing = allProjectsListing;
                    if (allProjectsSessions != null) {
                        replaceSessions(allProjectsSessions);
                    } else if (guiInvoker != null) {
                        loadAllProjectsAsync();
                        refreshDisplay();
                        return true;
                    } else {
                        replaceSessions(activeListing.loadMore(progressiveInitialLoad));
                        allProjectsSessions = new ArrayList<>(allSessions);
                    }
                } else {
                    activeListing = sameRepositoryListing;
                    if (currentDirSessions != null) replaceSessions(currentDirSessions);
                }
            }
            rebuildEntries();
            refreshDisplay();
            return true;
        }
        // Ctrl+B — toggle branch filter
        if (key.getKeyType() == KeyType.CHARACTER && key.getCharacter() == 'b' && key.isCtrlDown()) {
            branchFilter = !branchFilter;
            rebuildEntries();
            refreshDisplay();
            return true;
        }
// Ctrl+R — rename selected session.
        if (key.getKeyType() == KeyType.CHARACTER && key.getCharacter() == 'r' && key.isCtrlDown()
                && !flatList.isEmpty()) {
            renameSelected();
            return true;
        }
        // Ctrl+W — toggle worktree filter; only has effect when hasMultipleWorktrees


        if (key.getKeyType() == KeyType.CHARACTER && key.getCharacter() == 'w' && key.isCtrlDown()) {
            if (hasMultipleWorktrees) {
                showAllWorktrees = !showAllWorktrees;
                rebuildEntries();
                refreshDisplay();
            }
            return true;
        }
// Tab / Shift+Tab — cycle tag filter.
        if (key.getKeyType() == KeyType.TAB && !uniqueTags.isEmpty()) {
            int offset = key.isShiftDown() ? -1 : 1;
            int len = uniqueTags.size() + 1;
            selectedTagIndex = ((selectedTagIndex + len + offset) % len + len) % len;
            selectedIndex = 0;
            scrollOffset = 0;
            rebuildEntries();
            refreshDisplay();
            return true;
        }
        return super.handleInput(key);
    }

    private void cancelRename() {
        viewMode = ViewMode.LIST;
        renameBuffer.setLength(0);
        renameCursorOffset = 0;
        refreshDisplay();
    }

    private void cancelPreview() {
        if (previewLoadFuture != null) {
            previewLoadFuture.cancel(true);
            previewLoadFuture = null;
        }
        previewLoadGeneration++;  // retires an in-flight load that already read its rows
        previewSpinner.stop();
        viewMode = ViewMode.LIST;
        previewHasTranscript = false;
        previewTranscript.clear();
        refreshDisplay();
    }


    private static boolean isPreviewTriggerKey(KeyStroke key) {
        if (key.getKeyType() != KeyType.CHARACTER || key.getCharacter() == null) return false;
        char c = key.getCharacter();
        if (c == ' ') return !key.isCtrlDown() && !key.isAltDown();
        return key.isCtrlDown() && Character.toLowerCase(c) == 'v';
    }

    /**
     * Scrolls the preview transcript. Returns true when the key was consumed.
     */
    @Explanation("197's preview has no scroll keys or wheel handler: it renders an "
        + "unbounded free-flow tree, so overflow lands in the terminal's own scrollback "
        + "and the native mouse/trackpad scroll reaches it. Lanterna paints into a fixed "
        + "full-screen surface with no scrollback, so the earlier transcript is only "
        + "reachable if the dialog scrolls its own viewport.")
    private boolean scrollPreview(KeyStroke key) {
        if (!previewHasTranscript) return false;
        if (key instanceof MouseAction) {
            int delta = MouseScrollHandler.getScrollDelta(key, previewWheelAccel);
            if (delta == 0) return false;
            previewTranscript.scrollUp(delta);
            return true;
        }
        switch (key.getKeyType()) {
            case ARROW_UP -> previewTranscript.scrollUp(1);
            case ARROW_DOWN -> previewTranscript.scrollDown(1);
            case PAGE_UP -> previewTranscript.pageUp();
            case PAGE_DOWN -> previewTranscript.pageDown();
            case HOME -> {
                if (!key.isCtrlDown()) return false;
                previewTranscript.scrollToTop();
            }
            case END -> {
                if (!key.isCtrlDown()) return false;
                previewTranscript.scrollToBottom();
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    private void moveListPrevious() {
        if (!flatList.isEmpty() && selectedIndex > 0) {
            selectedIndex--;
            refreshDisplay();
        } else if (selectedIndex == 0) {
            viewMode = ViewMode.SEARCH;
            scrollOffset = 0;
            refreshDisplay();
        }
    }

    private void moveListNext() {
        if (flatList.isEmpty()) return;
        selectedIndex = Math.min(flatList.size() - 1, selectedIndex + 1);
        maybeLoadMore();
        refreshDisplay();
    }

    private void selectCurrent() {
        if (flatList.isEmpty()) return;
        result = flatList.get(selectedIndex).entry().info();
        close();
    }

    private void cancelDialog() {
        result = null;
        close();
    }

    private String previewMetadata(InteractiveSessionPort.SessionEntry session) {
        return previewMetadata(session, session.messageCount());
    }

    private String previewMetadata(InteractiveSessionPort.SessionEntry session, int messageCount) {
        String relative = FormatUtils.formatRelativeTimeAgo(
            session.lastModified() > 0 ? Instant.ofEpochMilli(session.lastModified()) : session.createdAt(),
            FormatUtils.RelativeTimeStyle.NARROW);
        String branch = StringUtils.isBlank(session.gitBranch())
            ? "" : " · " + session.gitBranch();
        return relative + " · " + Math.max(0, messageCount) + " messages" + branch;
    }


    public void setHasMultipleWorktrees(boolean v) {
        this.hasMultipleWorktrees = v;
    }

    /** Wires the Ctrl+A all-projects data source (see {@code allProjectsLoader}). */
    public void setAllProjectsLoader(Supplier<List<InteractiveSessionPort.SessionEntry>> loader) {
        this.allProjectsLoader = loader;
    }

    /** Installs independent cursors for current-repository and all-project modes. */
    public void setProgressiveListings(
            InteractiveSessionPort.SessionListing sameRepository,
            Supplier<InteractiveSessionPort.SessionListing> allProjectsFactory,
            int initialLoad) {
        this.sameRepositoryListing = sameRepository;
        this.activeListing = sameRepository;
        this.allProjectsListingFactory = allProjectsFactory;
        this.progressiveInitialLoad = Math.max(1, initialLoad);
        this.currentDirSessions = new ArrayList<>(allSessions);
    }

    /** Whether all-projects mode was active (drives the cross-project resume check). */
    public boolean isShowAllProjects() {
        return showAllProjects;
    }

    int selectedIndexForTest() { return selectedIndex; }
    String viewModeForTest() { return viewMode.name(); }
    InteractiveSessionPort.SessionEntry resultForTest() { return result; }
    int sessionCountForTest() { return allSessions.size(); }
    List<String> sessionIdsForTest() {
        return allSessions.stream().map(InteractiveSessionPort.SessionEntry::id).toList();
    }
    void loadMoreForTest(int count) {
        if (activeListing != null) appendMoreSessions(activeListing.loadMore(count));
    }
    /** Guards the "a failed load must stop the animation, not just hide it" contract. */
    public boolean previewSpinnerRunningForTest() { return previewSpinner.isRunning(); }
    String previewMetadataForTest() { return previewMetadata; }

    /** Wires physical deletion after the dialog's explicit confirmation step. */
    @Explanation("Adds confirmed permanent deletion to the session selector")
    public void setDeleteSessionCallback(Predicate<InteractiveSessionPort.SessionEntry> callback) {
        this.deleteSessionCallback = callback;
    }

    /** Set the GUI-thread invoker for async preview callbacks (required for loading spinner). */
    public void setGuiInvoker(Consumer<Runnable> inv) {
        this.guiInvoker = inv;
    }

    @Override
    public void close() {
        allProjectsLoadGeneration++;
        if (allProjectsLoadFuture != null) allProjectsLoadFuture.cancel(true);
        loadMoreGeneration++;
        if (loadMoreFuture != null) loadMoreFuture.cancel(true);
        renameSaveGeneration++;
        exitTimer.shutdownNow();
        super.close();
    }

    public InteractiveSessionPort.SessionEntry showAndGet(WindowBasedTextGUI gui) {
        gui.addWindowAndWait(this);
        return result;
    }



    /**
     * Handle a Ctrl+C / Ctrl+D press — first press shows "Press X again to exit", second press within
     * 800 ms closes the dialog.
     */
    private void handleExitKeyPress(String keyName) {
        if (keyName.equals(pendingExitKey)) {
            // Second press within timeout → cancel
            result = null;
            close();
            return;
        }
        pendingExitKey = keyName;
        if (exitTimerFuture != null) exitTimerFuture.cancel(false);
        exitTimerFuture = exitTimer.schedule(() -> {
            pendingExitKey = null;
            try {
                WindowBasedTextGUI g = getTextGUI();
                if (g != null) g.getGUIThread().invokeLater(() -> {
                    if (getTextGUI() != null) refreshDisplay();
                });
            } catch (Exception _) {}
        }, 800, TimeUnit.MILLISECONDS);
        refreshDisplay();
    }



/**
     * Called after each navigation step — loads more sessions when near the bottom.
     */
    private void maybeLoadMore() {
        if (activeListing == null || loadMoreInFlight || !activeListing.hasMore()) return;
        int buffer = visibleCount * 2;

        if ((selectedIndex + 1) + buffer >= flatList.size()) {
            int count = visibleCount * 3;
            if (guiInvoker == null) {
                appendMoreSessions(activeListing.loadMore(count));
                return;
            }
            loadMoreInFlight = true;
            long generation = ++loadMoreGeneration;
            loadMoreFuture = CompletableFuture.supplyAsync(() -> {
                List<InteractiveSessionPort.SessionEntry> loaded = activeListing.loadMore(count);
                return loaded != null ? List.copyOf(loaded)
                    : List.<InteractiveSessionPort.SessionEntry>of();
            }).whenComplete((loaded, failure) -> guiInvoker.accept(() -> {
                if (generation != loadMoreGeneration) return;
                loadMoreInFlight = false;
                loadMoreFuture = null;
                if (failure == null) appendMoreSessions(loaded);
                refreshDisplay();
            }));
        }
    }

    private void appendMoreSessions(List<InteractiveSessionPort.SessionEntry> more) {
        if (more == null || more.isEmpty()) return;
        Set<String> existingIds = allSessions.stream()
            .map(InteractiveSessionPort.SessionEntry::id)
            .collect(Collectors.toSet());
        more.stream()
            .filter(session -> !deletedSessionIds.contains(session.id()))
            .filter(session -> existingIds.add(session.id()))
            .forEach(allSessions::add);
        if (showAllProjects) allProjectsSessions = new ArrayList<>(allSessions);
        else currentDirSessions = new ArrayList<>(allSessions);
        rebuildEntries();
    }

    private void loadAllProjectsAsync() {
        allProjectsLoadInFlight = true;
        long generation = ++allProjectsLoadGeneration;
        allProjectsLoadFuture = CompletableFuture.supplyAsync(() -> {
            List<InteractiveSessionPort.SessionEntry> loaded = activeListing != null
                ? activeListing.loadMore(progressiveInitialLoad) : allProjectsLoader.get();
            List<InteractiveSessionPort.SessionEntry> snapshot =
                loaded != null ? List.copyOf(loaded) : List.of();
            return snapshot;
        }).whenComplete((loaded, failure) -> guiInvoker.accept(() -> {
            if (generation != allProjectsLoadGeneration) return;
            allProjectsLoadInFlight = false;
            allProjectsLoadFuture = null;
            if (failure == null && showAllProjects) {
                replaceSessions(loaded);
                allProjectsSessions = new ArrayList<>(allSessions);
                rebuildEntries();
            }
            refreshDisplay();
        }));
    }

    private void replaceSessions(List<InteractiveSessionPort.SessionEntry> sessions) {
        allSessions.clear();
        if (sessions != null) {
            sessions.stream()
                .filter(session -> !deletedSessionIds.contains(session.id()))
                .forEach(allSessions::add);
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Set footer text, applying the double-press exit override when pending.
     */
    private void setFooter(String normal) {
        footerLabel.setText(pendingExitKey != null
            ? "  Press " + pendingExitKey + " again to exit"
            : normal);
    }

    private static String repeat(char c, int n) {
        if (n <= 0) return "";
        char[] arr = new char[n];
        Arrays.fill(arr, c);
        return new String(arr);
    }

    /**
     * Normalizes whitespace (collapses runs to single space, trims) then truncates.
     */
    static String normalizeAndTruncateToWidth(String text, int maxWidth) {
        if (text == null) return "";
        String normalized = TerminalSafeText.sanitize(text).replaceAll("\\s+", " ").trim();
        return FormatUtils.truncate(normalized, maxWidth);
    }


    /**
     * Return the offset of the previous word start before {@code offset}.
     */
    private static int prevWordOffset(StringBuilder text, int offset) {
        if (offset <= 0) return 0;
        BreakIterator wb = BreakIterator.getWordInstance();
        wb.setText(text.toString());
        int boundary = wb.preceding(offset);
        // BreakIterator.preceding() may land on a non-word (space/punct) segment; skip back
        while (boundary > 0 && !isWordChar(text.charAt(boundary))) {
            boundary = wb.preceding(boundary);
        }
        // If we landed mid-word, go to its start
        int start = wb.preceding(boundary + 1);
        return start == BreakIterator.DONE ? 0 : Math.max(0, start);
    }

    /**
     * Return the offset of the next word start after {@code offset}.
     */
    private static int nextWordOffset(StringBuilder text, int offset) {
        if (offset >= text.length()) return text.length();
        BreakIterator wb = BreakIterator.getWordInstance();
        wb.setText(text.toString());
        int boundary = wb.following(offset);
        if (boundary == BreakIterator.DONE) return text.length();
        // Skip non-word segments (spaces, punctuation) to find the next actual word start
        while (boundary < text.length() && !isWordChar(text.charAt(boundary))) {
            int next = wb.following(boundary);
            if (next == BreakIterator.DONE) return text.length();
            boundary = next;
        }
        return boundary;
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }


    private static boolean searchIsKillKey(KeyStroke key) {
        if (key.getKeyType() == KeyType.CHARACTER && key.getCharacter() != null && key.isCtrlDown()) {
            char lc = Character.toLowerCase(key.getCharacter());
            if (lc == 'k' || lc == 'u' || lc == 'w') return true;
        }
        return key.getKeyType() == KeyType.BACKSPACE && key.isAltDown();
    }


    private static boolean searchIsYankKey(KeyStroke key) {
        if (key.getKeyType() != KeyType.CHARACTER || key.getCharacter() == null) return false;
        char lc = Character.toLowerCase(key.getCharacter());
        return (key.isCtrlDown() || key.isAltDown()) && lc == 'y';
    }
}
