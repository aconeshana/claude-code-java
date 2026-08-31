package com.claudecode.ui.lanterna.dialog;

import com.claudecode.keybindings.UserKeybindingsStore;
import com.claudecode.core.text.FormatUtils;
import com.claudecode.runtime.hooks.HookConfigurationSnapshot;
import com.claudecode.runtime.hooks.HookConfigurationSnapshot.HookEntry;
import com.claudecode.runtime.hooks.HookConfigurationSnapshot.HookEvent;
import com.claudecode.runtime.hooks.HookConfigurationSnapshot.HookEventMetadata;
import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.gui2.AbstractComponent;
import com.googlecode.lanterna.gui2.ComponentRenderer;
import com.googlecode.lanterna.gui2.Direction;
import com.googlecode.lanterna.gui2.LinearLayout;
import com.googlecode.lanterna.gui2.Panel;
import com.googlecode.lanterna.gui2.TextGUIGraphics;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import com.claudecode.ui.lanterna.overlay.InlineOverlay;
import org.apache.commons.lang3.StringUtils;
import com.claudecode.ui.lanterna.input.ContextKeybindingDispatcher;
import com.claudecode.ui.lanterna.components.LanternaDraw;
import com.claudecode.ui.lanterna.theme.LanternaTheme;
import com.claudecode.ui.lanterna.input.InputPanel;

/**
 * Inline read-only hook configuration browser.
 */
public final class HooksConfigMenuDialog extends Panel implements InlineOverlay {

    // ── Layout constants ──────────────────────────────────────────────────────
    private static final int LEFT_PAD         = 2;
    private static final int MAX_LIST         = 8;   // visible rows for matcher/hook/detail (rows 3–10)
    private static final int LIST_START       = 3;
    // SELECT_EVENT has subtitle + info bar, so its list starts lower
    private static final int MAX_LIST_EVENT   = 5;   // visible event rows (rows 6–10)
    private static final int LIST_START_EVENT = 6;
    private static final int TOTAL_ROWS       = 14;  // rows 0–13
    private static final int FOOTER_ROW       = TOTAL_ROWS - 1;  // 12

    // ── State ─────────────────────────────────────────────────────────────────
    enum NavState { SELECT_EVENT, SELECT_MATCHER, SELECT_HOOK, VIEW_HOOK }

    private boolean  active           = false;
    private boolean  disabled         = false;
    private boolean  disabledByPolicy = false; // true when disabled by a managed settings file
    /**
     * True when {@code allowManagedHooksOnly=true} in the policy managed settings file — user-level
     * hooks are ignored at runtime, and the SELECT_EVENT header shows a warning explaining this.
     */
    private boolean  restrictedByPolicy = false;
    private NavState navState  = NavState.SELECT_EVENT;
    private final ContextKeybindingDispatcher keybindings = new ContextKeybindingDispatcher();

    public void setKeybindingsStore(UserKeybindingsStore store) {
        keybindings.setStore(store);
    }

    private HookConfigurationSnapshot snapshot =
        new HookConfigurationSnapshot(List.of(), Map.of());
    private List<HookEntry>                   allHooks = List.of();
    private Map<HookEvent, HookEventMetadata> metadata = Map.of();

    private List<HookEvent>                   events   = new ArrayList<>(List.of(HookEvent.values()));

    private int       eventIdx = 0;
    private HookEvent selectedEvent;

    private List<String> matchers   = List.of();
    private int          matcherIdx = 0;
    private String       selectedMatcher;

    private List<HookEntry>            hookList = List.of();
    private int                        hookIdx  = 0;
    private HookEntry                  selectedHook;

    private Runnable onClose;

    private final ContentArea contentArea;

    public HooksConfigMenuDialog() {
        super(new LinearLayout(Direction.VERTICAL));
        setLayoutData(LinearLayout.createLayoutData(LinearLayout.Alignment.FILL));
        contentArea = new ContentArea();
        contentArea.setLayoutData(LinearLayout.createLayoutData(LinearLayout.Alignment.FILL));
        addComponent(contentArea);
    }

    // ── Public API ────────────────────────────────────────────────────────────













    public void show(HookConfigurationSnapshot snapshot,
                     boolean disabled,
                     boolean disabledByPolicy,
                     boolean restrictedByPolicy,
                     Runnable onClose) {
        this.snapshot           = snapshot;
        this.allHooks           = snapshot.hooks();
        this.metadata           = snapshot.metadata();
        this.disabled           = disabled;
        this.disabledByPolicy   = disabledByPolicy;
        this.restrictedByPolicy = restrictedByPolicy;

        this.events    = new ArrayList<>(this.metadata.keySet());
        this.onClose   = onClose;
        this.navState  = NavState.SELECT_EVENT;
        this.eventIdx  = 0;
        this.active    = true;
        contentArea.invalidate();
    }

    /**
     * Overload preserved for callers that don't know about policy-restriction
     * yet (matches the pre-{@code restrictedByPolicy} signature). Defaults to
     * unrestricted — safe for tests and headless callers.
     */
    public void show(HookConfigurationSnapshot snapshot,
                     boolean disabled,
                     boolean disabledByPolicy,
                     Runnable onClose) {
        show(snapshot, disabled, disabledByPolicy, /* restrictedByPolicy */ false, onClose);
    }

    public void hide() {
        active = false;
        contentArea.invalidate();
    }

    /**
     * Swaps in a fresh snapshot of hooks + metadata while the dialog is still open.
     */
    public void refresh(HookConfigurationSnapshot snapshot,
                        boolean disabled,
                        boolean disabledByPolicy,
                        boolean restrictedByPolicy) {
        if (!active) return;

        this.snapshot           = snapshot;
        this.allHooks           = snapshot.hooks();
        this.metadata           = snapshot.metadata();
        this.disabled           = disabled;
        this.disabledByPolicy   = disabledByPolicy;
        this.restrictedByPolicy = restrictedByPolicy;
        this.events             = new ArrayList<>(this.metadata.keySet());

        if (disabled) {
            // Everything is off — the disabled screen owns the frame, deeper
            // navigation state is meaningless.
            navState = NavState.SELECT_EVENT;
            eventIdx = 0;
            contentArea.invalidate();
            return;
        }

        // Reconcile navigation stack against the new snapshot, unwinding one
        // level at a time until we land on something that still exists.
        reconcileNavStack();

        // Clamp the event cursor after events list may have shrunk.
        if (eventIdx >= events.size()) eventIdx = Math.max(0, events.size() - 1);

        contentArea.invalidate();
    }

    /**
     * Overload preserved for callers pre-dating {@code restrictedByPolicy}
     * (mostly tests). Assumes unrestricted.
     */
    public void refresh(HookConfigurationSnapshot snapshot,
                        boolean disabled,
                        boolean disabledByPolicy) {
        refresh(snapshot, disabled, disabledByPolicy, /* restrictedByPolicy */ false);
    }

    private void reconcileNavStack() {
        if (navState == NavState.SELECT_EVENT) return;

        if (selectedEvent == null || !events.contains(selectedEvent)) {
            navState = NavState.SELECT_EVENT;
            return;
        }

        if (navState == NavState.SELECT_MATCHER
                || navState == NavState.SELECT_HOOK
                || navState == NavState.VIEW_HOOK) {
            HookEventMetadata evMeta = metadata.get(selectedEvent);
            boolean hasMatcher = evMeta != null && evMeta.matcherMetadata() != null;
            if (hasMatcher) {
                matchers = snapshot.sortedMatchers(selectedEvent);
                if (matcherIdx >= matchers.size()) matcherIdx = Math.max(0, matchers.size() - 1);
                if (matchers.isEmpty()) {
                    navState = NavState.SELECT_EVENT;
                    return;
                }
            }
        }

        if (navState == NavState.SELECT_HOOK || navState == NavState.VIEW_HOOK) {
            hookList = filterHooksForCurrentSelection();
            if (hookList.isEmpty()) {
                // Fall back to matcher list (if any) or event list.
                navState = matchers.isEmpty() ? NavState.SELECT_EVENT : NavState.SELECT_MATCHER;
                return;
            }
            if (hookIdx >= hookList.size()) hookIdx = Math.max(0, hookList.size() - 1);
        }

        if (navState == NavState.VIEW_HOOK) {
            // selectedHook may have been removed even though the parent list
            // still has entries — pop back to SELECT_HOOK in that case.
            if (selectedHook == null || !hookList.contains(selectedHook)) {
                navState = NavState.SELECT_HOOK;
            }
        }
    }

    private List<HookEntry> filterHooksForCurrentSelection() {
        if (selectedMatcher != null) {
            return snapshot.hooksFor(selectedEvent, selectedMatcher);
        }
        return snapshot.hooksFor(selectedEvent);
    }

    @Override public boolean isActive() { return active; }

    // ── Test-only accessors (package-private) ────────────────────────────────
    // Consumed by HooksConfigMenuDialogRefreshTest to inspect the state
    // machine's response to refresh() without a full Lanterna GUI harness.
    NavState navStateForTest()     { return navState; }
    int eventIdxForTest()          { return eventIdx; }
    int matcherIdxForTest()        { return matcherIdx; }
    int hookIdxForTest()           { return hookIdx; }
    HookEvent selectedEventForTest() { return selectedEvent; }
    HookEntry selectedHookForTest() { return selectedHook; }
    List<HookEvent>
    eventsForTest() { return events; }
    boolean isDisabledForTest()    { return disabled; }
    boolean isRestrictedByPolicyForTest() { return restrictedByPolicy; }

    // Package-private hooks for driving the state machine in tests without
// going through key events. `enter*` match the behaviour that the
    // handle* methods perform on ENTER; kept intentionally minimal.
    void enterMatcherStateForTest(HookEvent event) {
        this.selectedEvent = event;
        this.eventIdx = events.indexOf(event);
        HookEventMetadata evMeta = metadata.get(event);
        if (evMeta != null && evMeta.matcherMetadata() != null) {
            this.matchers   = snapshot.sortedMatchers(event);
            this.matcherIdx = 0;
            this.navState   = NavState.SELECT_MATCHER;
        }
    }
    void enterHookStateForTest(HookEvent event, String matcher) {
        this.selectedEvent   = event;
        this.eventIdx        = events.indexOf(event);
        this.selectedMatcher = matcher;
        if (matcher == null) {
            this.hookList = snapshot.hooksFor(event);
        } else {
            this.hookList = snapshot.hooksFor(event, matcher);
            this.matchers = snapshot.sortedMatchers(event);
            this.matcherIdx = matchers.indexOf(matcher);
        }
        this.hookIdx = 0;
        this.navState = NavState.SELECT_HOOK;
    }
    void enterViewStateForTest(HookEvent event, String matcher, HookEntry hook) {
        enterHookStateForTest(event, matcher);
        this.selectedHook = hook;
        this.hookIdx = Math.max(0, hookList.indexOf(hook));
        this.navState = NavState.VIEW_HOOK;
    }

    @Override
    public TerminalSize calculatePreferredSize() {
        return active ? super.calculatePreferredSize() : TerminalSize.of(0, 0);
    }

    /**
     * Routes a key stroke to the current state handler.
     * The host {@code WindowListener.onInput} must call this while {@link #isActive}.
     *
     * <p>Contract: {@code deliver.set(false)} means "I ate the key, do not
     * forward downstream". This dialog consumes every keystroke while active,
     * so we unconditionally clear the flag. Leaving it {@code true} lets arrow
     * keys / Enter fall through to the InputPanel and submit a stray message
     * (same bug pattern as {@link MCPSettingsDialog}).
     */
    @Override public void handleKey(KeyStroke key, AtomicBoolean deliver) {
        if (!active) return;
        // Ctrl+C must reach the outer handler (double-press-to-exit /
        // interrupt). See MCPSettingsDialog.isCtrlC for detection details.
        if (isCtrlC(key)) return;
        deliver.set(false);
        List<String> contexts = navState == NavState.VIEW_HOOK
            ? List.of("Confirmation") : List.of("Select", "Confirmation");
        ContextKeybindingDispatcher.Result resolved = keybindings.resolve(contexts, key);
        if (resolved instanceof ContextKeybindingDispatcher.Result.Consumed) return;
        if (resolved instanceof ContextKeybindingDispatcher.Result.Action(String value)
                && dispatchKeybindingAction(value)) {
            contentArea.invalidate();
            return;
        }
        KeyType t   = key.getKeyType();
        Character c = key.getCharacter();
        if (disabled) {
            // Disabled screen: only Esc is handled — close the dialog
            if (t == KeyType.ESCAPE) close();
            return;
        }
        switch (navState) {
            case SELECT_EVENT   -> handleSelectEvent(t, c);
            case SELECT_MATCHER -> handleSelectMatcher(t, c);
            case SELECT_HOOK    -> handleSelectHook(t, c);
            case VIEW_HOOK      -> { if (t == KeyType.ESCAPE) navState = NavState.SELECT_HOOK; }
        }
        contentArea.invalidate();
    }

    private boolean dispatchKeybindingAction(String action) {
        KeyType synthetic = switch (action) {
            case "select:previous" -> KeyType.ARROW_UP;
            case "select:next" -> KeyType.ARROW_DOWN;
            case "select:accept" -> KeyType.ENTER;
            case "select:cancel", "confirm:no" -> KeyType.ESCAPE;
            default -> null;
        };
        if (synthetic == null) return false;
        switch (navState) {
            case SELECT_EVENT -> handleSelectEvent(synthetic, null);
            case SELECT_MATCHER -> handleSelectMatcher(synthetic, null);
            case SELECT_HOOK -> handleSelectHook(synthetic, null);
            case VIEW_HOOK -> {
                if (synthetic == KeyType.ESCAPE) navState = NavState.SELECT_HOOK;
                else return false;
            }
        }
        return true;
    }

    // ── Key handlers ─────────────────────────────────────────────────────────

    private void handleSelectEvent(KeyType t, Character c) {
        boolean down = t == KeyType.ARROW_DOWN || (c != null && c == 'j');
        boolean up   = t == KeyType.ARROW_UP   || (c != null && c == 'k');
        if (down) {
            eventIdx = Math.min(eventIdx + 1, events.size() - 1);
        } else if (up) {
            eventIdx = Math.max(eventIdx - 1, 0);
        } else if (t == KeyType.ENTER) {
            selectedEvent = events.get(eventIdx);
            HookEventMetadata evMeta = metadata.get(selectedEvent);
            boolean hasMatcher = evMeta != null && evMeta.matcherMetadata() != null;
            if (hasMatcher) {
// Event supports matchers → show matcher selection.
                matchers   = snapshot.sortedMatchers(selectedEvent);
                matcherIdx = 0;
                navState   = NavState.SELECT_MATCHER;
            } else {
                // No matcher concept for this event → skip SELECT_MATCHER, show all hooks
                selectedMatcher = null;
                hookList = snapshot.hooksFor(selectedEvent);
                hookIdx  = 0;
                navState = NavState.SELECT_HOOK;
            }
        } else if (t == KeyType.ESCAPE) {
            close();
        }
    }

    private void handleSelectMatcher(KeyType t, Character c) {
        boolean down = t == KeyType.ARROW_DOWN || (c != null && c == 'j');
        boolean up   = t == KeyType.ARROW_UP   || (c != null && c == 'k');
        if (down && !matchers.isEmpty()) {
            matcherIdx = Math.min(matcherIdx + 1, matchers.size() - 1);
        } else if (up && !matchers.isEmpty()) {
            matcherIdx = Math.max(matcherIdx - 1, 0);
        } else if (t == KeyType.ENTER && !matchers.isEmpty()) {
            selectedMatcher = matchers.get(matcherIdx);
            hookList  = snapshot.hooksFor(selectedEvent, selectedMatcher);
            hookIdx   = 0;
            navState  = NavState.SELECT_HOOK;
        } else if (t == KeyType.ESCAPE) {
            navState   = NavState.SELECT_EVENT;
            matcherIdx = 0;
        }
    }

    private void handleSelectHook(KeyType t, Character c) {
        boolean down = t == KeyType.ARROW_DOWN || (c != null && c == 'j');
        boolean up   = t == KeyType.ARROW_UP   || (c != null && c == 'k');
        if (down && !hookList.isEmpty()) {
            hookIdx = Math.min(hookIdx + 1, hookList.size() - 1);
        } else if (up && !hookList.isEmpty()) {
            hookIdx = Math.max(hookIdx - 1, 0);
        } else if (t == KeyType.ENTER && !hookList.isEmpty()) {
            selectedHook = hookList.get(hookIdx);
            navState     = NavState.VIEW_HOOK;
        } else if (t == KeyType.ESCAPE) {

            // otherwise skip straight to SELECT_EVENT (no matcher screen exists for this event).
            HookEventMetadata evMeta = selectedEvent != null ? metadata.get(selectedEvent) : null;
            boolean hasMatcher = evMeta != null && evMeta.matcherMetadata() != null;
            navState = hasMatcher ? NavState.SELECT_MATCHER : NavState.SELECT_EVENT;
            hookIdx  = 0;
        }
    }

    private void close() {
        active = false;
        if (onClose != null) onClose.run();
    }

    // ── Rendering ─────────────────────────────────────────────────────────────

    final class ContentArea extends AbstractComponent<ContentArea> {

        @Override
        protected ComponentRenderer<ContentArea> createDefaultRenderer() {
            return new ComponentRenderer<>() {
                @Override
                public TerminalSize getPreferredSize(ContentArea c) {
                    return active ? new TerminalSize(1, TOTAL_ROWS) : TerminalSize.of(0, 0);
                }

                @Override
                public void drawComponent(TextGUIGraphics g, ContentArea c) {
                    if (!active) return;
                    g.fill(' ');
                    int cols = g.getSize().getColumns();
                    if (disabled) {
                        drawDisabled(g, cols);
                        return;
                    }
                    switch (navState) {
                        case SELECT_EVENT   -> drawSelectEvent(g, cols);
                        case SELECT_MATCHER -> drawSelectMatcher(g, cols);
                        case SELECT_HOOK    -> drawSelectHook(g, cols);
                        case VIEW_HOOK      -> drawViewHook(g, cols);
                    }
                }
            };
        }
    }

    // ── Draw: SELECT_EVENT ────────────────────────────────────────────────────

    private void drawSelectEvent(TextGUIGraphics g, int cols) {
        LanternaDraw.divider(g, cols, 0);


        LanternaDraw.title(g, "Hooks");

        // Row 2: "N hooks configured" dim subtitle
        int total = allHooks.size();
        String hookWord = total == 1 ? "hook" : "hooks";
        g.setForegroundColor(LanternaTheme.welcomeDim());
        g.putString(LEFT_PAD, 2, total + " " + hookWord + " configured");

        int maxWidth = Math.max(4, cols - LEFT_PAD);

        if (restrictedByPolicy) {

            g.setForegroundColor(LanternaTheme.toolWarning());
            String heading = "⛔ Hooks Restricted by Policy";
            g.putString(LEFT_PAD, 3, truncateForCols(heading, maxWidth));
            g.setForegroundColor(LanternaTheme.welcomeDim());
            String detail = "Only hooks from managed settings can run. "
                + "User hooks (~/.claude, project, local) are blocked.";
            g.putString(LEFT_PAD, 4, truncateForCols(detail, maxWidth));
            g.setForegroundColor(TextColor.ANSI.DEFAULT);
        } else {

            String info = "ℹ This menu is read-only. To add or modify hooks, edit settings.json directly or ask Claude.";
            g.setForegroundColor(LanternaTheme.welcomeDim());
            g.putString(LEFT_PAD, 4, truncateForCols(info, maxWidth));
            g.setForegroundColor(TextColor.ANSI.DEFAULT);
        }

        // Row 5: blank gap

        // Rows 6-10: event list with numbered items, count badge, and summary description
        int scroll = computeScroll(eventIdx, events.size(), MAX_LIST_EVENT);
        for (int vi = 0; vi < MAX_LIST_EVENT; vi++) {
            int ei = scroll + vi;
            if (ei >= events.size()) break;
            int row      = LIST_START_EVENT + vi;
            HookEvent ev = events.get(ei);
            boolean sel  = ei == eventIdx;
            int count    = snapshot.hookCount(ev);

            // pointer + number
            String pointer = sel ? "❯ " : "  ";
            String number  = (ei + 1) + ". ";
            String name    = ev.displayName();

            g.setForegroundColor(sel ? LanternaTheme.suggestion() : TextColor.ANSI.DEFAULT);
            g.putString(LEFT_PAD, row, pointer + number + name);

            int x = LEFT_PAD + pointer.length() + number.length() + name.length();

// count badge in suggestion (green) when > 0.
            if (count > 0) {
                String badge = " (" + count + ")";
                g.setForegroundColor(LanternaTheme.suggestion());
                g.putString(x, row, badge);
                x += badge.length();
            }

            // description column (summary), dim text, right-side of the row
            HookEventMetadata m = metadata.get(ev);
            if (m != null && !StringUtils.isBlank(m.summary())) {
                int descCol = Math.max(36, x + 2);  // dynamic: at least x+2 gap
                if (descCol < cols - 4) {
                    int avail = cols - descCol - 1;
                    String desc = m.summary();
                    if (desc.length() > avail) desc = FormatUtils.truncate(desc, avail);
                    g.setForegroundColor(LanternaTheme.welcomeDim());
                    g.putString(descCol, row, desc);
                }
            }
            g.setForegroundColor(TextColor.ANSI.DEFAULT);
        }

        // Scroll indicators using event-specific constants
        if (events.size() > MAX_LIST_EVENT) {
            g.setForegroundColor(LanternaTheme.welcomeDim());
            if (scroll > 0)
                g.putString(cols - 2, LIST_START_EVENT, "↑");
            if (scroll + MAX_LIST_EVENT < events.size())
                g.putString(cols - 2, LIST_START_EVENT + MAX_LIST_EVENT - 1, "↓");
            g.setForegroundColor(TextColor.ANSI.DEFAULT);
        }

        drawFooter(g, "↑↓ navigate · Enter select · Esc to close");
    }

    // ── Draw: SELECT_MATCHER ──────────────────────────────────────────────────

    private void drawSelectMatcher(TextGUIGraphics g, int cols) {
        LanternaDraw.divider(g, cols, 0);
        LanternaDraw.title(g, (selectedEvent != null ? selectedEvent.displayName() : "") + " - Matchers");

// Row 2: event description subtitle.
        if (selectedEvent != null) {
            HookEventMetadata evMeta = metadata.get(selectedEvent);
            if (evMeta != null && !StringUtils.isBlank(evMeta.description())) {
                g.setForegroundColor(LanternaTheme.welcomeDim());
                String sub = evMeta.description();
                // Use only first line to stay within one row
                int nl = sub.indexOf('\n');
                if (nl > 0) sub = sub.substring(0, nl);
                int maxSub = cols - LEFT_PAD;
                g.putString(LEFT_PAD, 2, sub.length() > maxSub ? FormatUtils.truncate(sub, maxSub) : sub);
                g.setForegroundColor(TextColor.ANSI.DEFAULT);
            }
        }

        if (matchers.isEmpty()) {
            g.setForegroundColor(LanternaTheme.welcomeDim());
            g.putString(LEFT_PAD + 2, LIST_START,     "No hooks configured for this event.");
            g.putString(LEFT_PAD + 2, LIST_START + 1, "To add hooks, edit settings.json directly or ask Claude.");
            g.setForegroundColor(TextColor.ANSI.DEFAULT);
            drawFooter(g, "Esc to go back");
            return;
        }

        int scroll = computeScroll(matcherIdx, matchers.size());
        for (int vi = 0; vi < MAX_LIST; vi++) {
            int mi = scroll + vi;
            if (mi >= matchers.size()) break;
            int row     = LIST_START + vi;
            String m    = matchers.get(mi);
            boolean sel = mi == matcherIdx;

            List<HookEntry> mh = snapshot.hooksFor(selectedEvent, m);
            int count      = mh.size();
            String ptr     = sel ? "❯ " : "  ";
            // #18: empty/null matcher → "(all)"
            String matchLbl = (StringUtils.isEmpty(m)) ? "(all)" : m;

            String srcTag  = buildSourceTag(mh);
            String leftPart = ptr + (srcTag.isEmpty() ? "" : srcTag + " ") + matchLbl;
            String rightPart = count + (count == 1 ? " hook" : " hooks");

            if (sel) g.setForegroundColor(LanternaTheme.suggestion());
            else     g.setForegroundColor(TextColor.ANSI.DEFAULT);
            int leftMax = cols - LEFT_PAD - rightPart.length() - 2;
            String leftTrunc = leftPart.length() > leftMax ? FormatUtils.truncate(leftPart, leftMax) : leftPart;
            g.putString(LEFT_PAD, row, leftTrunc);


            int rightX = cols - rightPart.length() - 1;
            g.setForegroundColor(LanternaTheme.welcomeDim());
            if (rightX > LEFT_PAD + leftTrunc.length() + 1)
                g.putString(rightX, row, rightPart);
            g.setForegroundColor(TextColor.ANSI.DEFAULT);
        }

        drawScrollIndicators(g, cols, scroll, matchers.size());
        drawFooter(g, "↑↓ navigate · Enter select · Esc to go back");
    }

    // ── Draw: SELECT_HOOK ─────────────────────────────────────────────────────

    private void drawSelectHook(TextGUIGraphics g, int cols) {
        LanternaDraw.divider(g, cols, 0);

        HookEventMetadata evMeta = selectedEvent != null ? metadata.get(selectedEvent) : null;
        boolean hasMatcherMeta   = evMeta != null && evMeta.matcherMetadata() != null;
        String evName = selectedEvent != null ? selectedEvent.displayName() : "";
        String title;
        if (hasMatcherMeta) {
            String mLbl = (StringUtils.isEmpty(selectedMatcher)) ? "(all)" : selectedMatcher;
            title = evName + " - Matcher: " + mLbl;
        } else {
            title = evName;
        }
        LanternaDraw.title(g, title);

// Row 2: event description subtitle.
        if (evMeta != null && !StringUtils.isBlank(evMeta.description())) {
            g.setForegroundColor(LanternaTheme.welcomeDim());
            String sub = evMeta.description();
            int nl = sub.indexOf('\n');
            if (nl > 0) sub = sub.substring(0, nl);
            int maxSub = cols - LEFT_PAD;
            g.putString(LEFT_PAD, 2, sub.length() > maxSub ? FormatUtils.truncate(sub, maxSub) : sub);
            g.setForegroundColor(TextColor.ANSI.DEFAULT);
        }

        if (hookList.isEmpty()) {
            g.setForegroundColor(LanternaTheme.welcomeDim());
            g.putString(LEFT_PAD + 2, LIST_START,     "No hooks configured for this event.");
            g.putString(LEFT_PAD + 2, LIST_START + 1, "To add hooks, edit settings.json directly or ask Claude.");
            g.setForegroundColor(TextColor.ANSI.DEFAULT);
            drawFooter(g, "Esc to go back");
            return;
        }

        int scroll = computeScroll(hookIdx, hookList.size());
        for (int vi = 0; vi < MAX_LIST; vi++) {
            int hi = scroll + vi;
            if (hi >= hookList.size()) break;
            int row               = LIST_START + vi;
            HookEntry c = hookList.get(hi);
            boolean sel           = hi == hookIdx;

            String tag    = "[" + c.kind().typeName() + "]";
// #5/#25: use headerDisplay() (Title Case, no brackets).
            String srcTag = c.sourceHeader();
            int maxTxt    = Math.max(4, cols - LEFT_PAD - 4 - tag.length() - 2 - srcTag.length() - 2);
            String disp   = c.displayText().length() <= maxTxt
                ? c.displayText() : FormatUtils.truncate(c.displayText(), maxTxt);
            String ptr    = sel ? "❯ " : "  ";
            String main   = ptr + tag + " " + disp;

            if (sel) g.setForegroundColor(LanternaTheme.suggestion());
            else     g.setForegroundColor(TextColor.ANSI.DEFAULT);
            g.putString(LEFT_PAD, row, main.length() > cols - LEFT_PAD - srcTag.length() - 2
                ? main.substring(0, cols - LEFT_PAD - srcTag.length() - 3) + "…" : main);

            // Source tag — dim, right-aligned
            g.setForegroundColor(LanternaTheme.welcomeDim());
            g.putString(cols - srcTag.length() - 1, row, srcTag);
            g.setForegroundColor(TextColor.ANSI.DEFAULT);
        }

        drawScrollIndicators(g, cols, scroll, hookList.size());
        drawFooter(g, "↑↓ navigate · Enter view · Esc to go back");
    }

    // ── Draw: VIEW_HOOK ───────────────────────────────────────────────────────

    private void drawViewHook(TextGUIGraphics g, int cols) {
        if (selectedHook == null) { navState = NavState.SELECT_HOOK; return; }
        LanternaDraw.divider(g, cols, 0);
        LanternaDraw.title(g, "Hook details");

        int row = LIST_START;
        // #19: Event/Matcher/Type values → bold; Source value → dim
        putFieldBold(g, row++, cols, "Event", selectedHook.event().displayName());

// Matcher — only shown when event supports matchers.
        HookEventMetadata meta = metadata.get(selectedHook.event());
        boolean eventSupportsMatcher = meta != null && meta.matcherMetadata() != null;
        if (eventSupportsMatcher) {
            String matcherVal = StringUtils.isNotBlank(selectedHook.matcher())
                ? selectedHook.matcher() : "(all)";
            putFieldBold(g, row++, cols, "Matcher", matcherVal);
        }

        putFieldBold(g, row++, cols, "Type", selectedHook.kind().typeName());
        putSourceField(g, row++, cols, selectedHook.sourceDescription());
        row++; // blank separator

        // Content label (dim) + round border box for value (#9)
        String contentLabel = selectedHook.kind().contentLabel();
        g.setForegroundColor(LanternaTheme.welcomeDim());
        g.putString(LEFT_PAD, row++, contentLabel + ":");

        // Round border box: ╭──╮ / │ val │ / ╰──╯
        int boxWidth = cols - LEFT_PAD * 2; // LEFT_PAD*2 == 4, so Math.min(x,x) was dead
        int contentWidth = boxWidth - 4;  // 1 border + 1 space each side

        String rawContent = selectedHook.rawContent();
        String contentVal = rawContent.length() <= contentWidth ? rawContent : FormatUtils.truncate(rawContent, contentWidth);
        String padded = contentVal + " ".repeat(Math.max(0, contentWidth - contentVal.length()));

        // Round border box: ╭──╮ / │ val │ / ╰──╯
        // The box + guidance always fit above the footer: with the current
        // layout row tops out at 12 (LIST_START=3 + Event/Type/Source fields +
        // blank + content label + 3 box rows, +1 when the event supports a
        // Matcher), strictly below FOOTER_ROW (=13). The earlier row<FOOTER_ROW
        // guards were dead (always true) once TOTAL_ROWS was bumped to 14 — re-add
        // bounds checks only if drawViewHook gains more fields.
        g.setForegroundColor(LanternaTheme.welcomeDim());
        g.putString(LEFT_PAD, row++, "╭" + "─".repeat(boxWidth - 2) + "╮");
        g.setForegroundColor(TextColor.ANSI.DEFAULT);
        g.putString(LEFT_PAD, row, "│ " + padded + " │");
        row++;
        g.setForegroundColor(LanternaTheme.welcomeDim());
        g.putString(LEFT_PAD, row++, "╰" + "─".repeat(boxWidth - 2) + "╯");
        g.setForegroundColor(TextColor.ANSI.DEFAULT);

// "To modify or remove..." guidance.
        g.setForegroundColor(LanternaTheme.welcomeDim());
        String guide = "To modify or remove this hook, edit settings.json directly or ask Claude to help.";
        int avail = cols - LEFT_PAD;
        g.putString(LEFT_PAD, row, guide.length() > avail ? FormatUtils.truncate(guide, avail) : guide);
        g.setForegroundColor(TextColor.ANSI.DEFAULT);

        drawFooter(g, "Esc to go back");
    }

    // ── Draw: DISABLED ────────────────────────────────────────────────────────

    /**
     * Renders the "Hook Configuration - Disabled" screen.
     */
    private void drawDisabled(TextGUIGraphics g, int cols) {
        LanternaDraw.divider(g, cols, 0);
        LanternaDraw.title(g, "Hook Configuration - Disabled");

        int total    = allHooks.size();
        String plural = total == 1 ? "hook" : "hooks";
        String verb   = total == 1 ? "is"   : "are";
        int maxLine   = cols - LEFT_PAD;

        // Row 3: "All hooks are currently <bold>disabled</bold>[by a managed settings file].
        int col = LEFT_PAD;
        g.setForegroundColor(TextColor.ANSI.DEFAULT);
        String prefix = "All hooks are currently ";
        g.putString(col, 3, prefix);
        col += prefix.length();

        g.enableModifiers(SGR.BOLD);
        g.putString(col, 3, "disabled");
        col += "disabled".length();
        g.disableModifiers(SGR.BOLD);

        if (disabledByPolicy) {
            String byPolicy = " by a managed settings file";
            g.putString(col, 3, byPolicy);
            col += byPolicy.length();
        }

        String mid = ". You have ";
        g.putString(col, 3, mid);
        col += mid.length();

        g.enableModifiers(SGR.BOLD);
        String countStr = String.valueOf(total);
        g.putString(col, 3, countStr);
        col += countStr.length();
        g.disableModifiers(SGR.BOLD);

        String suffix = " configured " + plural + " that " + verb + " not running.";
        if (col + suffix.length() < cols) {
            g.putString(col, 3, suffix);
        }

        // Row 5: "When hooks are disabled:"
        g.setForegroundColor(LanternaTheme.welcomeDim());
        g.putString(LEFT_PAD, 5, "When hooks are disabled:");

        // Rows 6-8: bullet points
        g.putString(LEFT_PAD, 6, "· No hook commands will execute");
        g.putString(LEFT_PAD, 7, "· StatusLine will not be displayed");
        g.putString(LEFT_PAD, 8, "· Tool operations will proceed without hook validation");


        if (!disabledByPolicy) {
            String guide = "To re-enable hooks, remove \"disableAllHooks\" from settings.json or ask Claude.";
            g.putString(LEFT_PAD, 10, guide.length() > maxLine ? FormatUtils.truncate(guide, maxLine) : guide);
        }
        g.setForegroundColor(TextColor.ANSI.DEFAULT);

        drawFooter(g, "Esc to close");
    }

    // ── Draw helpers ──────────────────────────────────────────────────────────

    private void drawFooter(TextGUIGraphics g, String text) {
        LanternaDraw.footer(g, text, LEFT_PAD, FOOTER_ROW);
    }

    private void drawScrollIndicators(TextGUIGraphics g, int cols, int scroll, int total) {
        if (total <= MAX_LIST) return;
        g.setForegroundColor(LanternaTheme.welcomeDim());
        if (scroll > 0)
            g.putString(cols - 2, LIST_START, "↑");
        if (scroll + MAX_LIST < total)
            g.putString(cols - 2, LIST_START + MAX_LIST - 1, "↓");
        g.setForegroundColor(TextColor.ANSI.DEFAULT);
    }

    /** Label dim + value bold (Event, Matcher, Type). */
    private static void putFieldBold(TextGUIGraphics g, int row, int cols,
                                     String label, String value) {
        String prefix = "  " + label + ":  ";
        g.setForegroundColor(LanternaTheme.welcomeDim());
        g.putString(LEFT_PAD, row, prefix);
        int maxVal  = Math.max(4, cols - LEFT_PAD - prefix.length());
        String disp = value.length() > maxVal ? FormatUtils.truncate(value, maxVal) : value;
        g.setForegroundColor(TextColor.ANSI.DEFAULT);
        g.enableModifiers(SGR.BOLD);
        g.putString(LEFT_PAD + prefix.length(), row, disp);
        g.disableModifiers(SGR.BOLD);
    }

    /** "Source" field: label dim + value dim. Only used by the VIEW_HOOK detail panel. */
    private static void putSourceField(TextGUIGraphics g, int row, int cols, String value) {
        String prefix = "  Source:  ";
        g.setForegroundColor(LanternaTheme.welcomeDim());
        g.putString(LEFT_PAD, row, prefix);
        int maxVal  = Math.max(4, cols - LEFT_PAD - prefix.length());
        String disp = value.length() > maxVal ? FormatUtils.truncate(value, maxVal) : value;
        g.putString(LEFT_PAD + prefix.length(), row, disp);
        g.setForegroundColor(TextColor.ANSI.DEFAULT);
    }

    private static String buildSourceTag(List<HookEntry> hooks) {
        List<String> tags = hooks.stream()
            .map(HookEntry::sourceInline)
            .distinct()
            .limit(3)
            .toList();
        return tags.isEmpty() ? "" : "[" + String.join(", ", tags) + "]";
    }

    /** Returns scroll offset so {@code idx} is always within [scroll, scroll + MAX_LIST). */
    private static int computeScroll(int idx, int total) {
        return computeScroll(idx, total, MAX_LIST);
    }

    private static int computeScroll(int idx, int total, int maxVisible) {
        if (total <= maxVisible) return 0;
        int scroll = Math.max(0, idx - maxVisible + 1);
        return Math.min(scroll, total - maxVisible);
    }

    /**
     * Trims {@code s} so it fits in {@code maxWidth} columns, appending an
     * ellipsis when it had to be cut. Never returns null; empty input passes
     * through unchanged.
     */
    private static String truncateForCols(String s, int maxWidth) {
        if (s == null || maxWidth <= 0) return "";
        if (s.length() <= maxWidth) return s;
        if (maxWidth == 1) return "…";
        return FormatUtils.truncate(s, maxWidth);
    }

    /**
     * True for a Ctrl+C keystroke — kept in sync with the outer REPL's
     * detection so the exit chord always reaches the host handler.
     * See {@code MCPSettingsDialog.isCtrlC} for the canonical version.
     */
    private static boolean isCtrlC(KeyStroke key) {
        if (key == null || key.getKeyType() != KeyType.CHARACTER) return false;
        Character ch = key.getCharacter();
        if (ch == null) return false;
        return (ch == 'c' || ch == '\003')
            && key.isCtrlDown() && !key.isShiftDown() && !key.isAltDown();
    }
}
