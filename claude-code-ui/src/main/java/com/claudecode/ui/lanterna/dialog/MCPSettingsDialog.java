package com.claudecode.ui.lanterna.dialog;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.keybindings.UserKeybindingsStore;
import com.claudecode.core.text.FormatUtils;
import com.claudecode.runtime.mcp.McpManagementPort;
import com.claudecode.runtime.mcp.McpManagementPort.Server;
import com.claudecode.runtime.mcp.McpManagementPort.Snapshot;
import com.claudecode.runtime.mcp.McpManagementPort.Tool;
import com.claudecode.tools.agent.AgentMcpServerIndex;
import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Function;
import com.claudecode.ui.lanterna.overlay.InlineOverlay;
import com.claudecode.ui.lanterna.input.ContextKeybindingDispatcher;
import com.claudecode.ui.lanterna.components.LanternaDraw;
import com.claudecode.ui.lanterna.theme.LanternaTheme;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.claudecode.ui.lanterna.features.settings.MCPController;

/**
 * Inline read/write browser for MCP server configuration.
 */
public final class MCPSettingsDialog extends Panel implements InlineOverlay {

    private static final Logger log = LoggerFactory.getLogger(MCPSettingsDialog.class);

    // ── Layout constants ──────────────────────────────────────────────────────
    static final int LEFT_PAD    = 2;
    static final int TOTAL_ROWS  = 18;
    static final int FOOTER_ROW  = TOTAL_ROWS - 2;   // 16

    // ── State ─────────────────────────────────────────────────────────────────
    enum NavState { LIST, SERVER_MENU, TOOLS, TOOL_DETAIL, AGENT_MENU }
    private final ContextKeybindingDispatcher keybindings = new ContextKeybindingDispatcher();

    public void setKeybindingsStore(UserKeybindingsStore store) {
        keybindings.setStore(store);
    }

    /**
     * A user-selectable action inside SERVER_MENU / AGENT_MENU.
     */
    public enum MenuAction {
        VIEW_TOOLS,       // → TOOLS state (M1.5)
        RECONNECT,        // → reconnect flow (M1.3)
        ENABLE,           // remove from current project's disabledMcpServers (M1.4)
        DISABLE,          // add to current project's disabledMcpServers (M1.4)
        AUTHENTICATE,     // OAuth flow (M1.6, remote only)
        CLEAR_AUTH,       // revoke tokens  (M1.6, remote only)
        BACK              // pop to LIST
    }

    private boolean  active   = false;
    private NavState navState = NavState.LIST;

    /** Full server catalog snapshot passed in via {@link #show}. */
    private Snapshot snapshot = new Snapshot(List.of(), List.of());

    /**
     * Servers rendered in LIST order (built from {@link #config} on each show).
     */
    private final List<Server> servers = new ArrayList<>();
    /**
     * Row index of each {@link #servers} entry in the LIST rendering (used to
     * hit-test cursor selection and skip over section headers). Populated by
     * {@link #buildGroupedListView()}.
     */
    private final List<GroupRow> listRows = new ArrayList<>();
    /** Index into {@link #servers} for cursor position in LIST. */
    private int serverIdx = 0;
    /** Server the user drilled into (SERVER_MENU / TOOLS / TOOL_DETAIL). */
    private Server selectedServer;

    /**
     * Menu item cursor for SERVER_MENU / AGENT_MENU. Concrete items are drawn
     * by the state's {@code draw*} method — M1.0 skeleton just tracks the
     * cursor.
     */
    private int menuIdx = 0;
    /**
     * Menu items available for the current selected server, rebuilt on every
     * transition into SERVER_MENU. Ordered top-to-bottom as drawn. Used both
     * by handleServerMenu (for cursor clamping + Enter dispatch) and the
     * draw method (for cursor highlight).
     */
    private List<MenuAction> serverMenuItems = List.of();
    /**
     * Optional callback fired when a menu item is activated with Enter.
     * Wired by {@code LanternaReplScreen.openMcpDialog(...)} in M1.8; the
     * skeleton just records the activation on {@link #lastActionForTest}.
     */
    private BiConsumer<MenuAction, Server> onAction;

    /** Test-only sink for the last Enter-activated menu item + server. */
    private MenuAction lastActionForTest;

    /**
     * Reconnect UI sub-state layered on top of SERVER_MENU.
     */
    enum ReconnectSubstate { NONE, IN_PROGRESS }
    private volatile ReconnectSubstate reconnectSubstate = ReconnectSubstate.NONE;
    /** Human-readable message drawn during RESULT — success or failure detail. */
    private volatile String reconnectMessage = "";
    /** Which server the current reconnect run is for; used in the spinner label. */
    private volatile String reconnectServerName = "";
    /** Whether the current RESULT is a success (green) or failure (red/warning). */
    private volatile boolean reconnectSuccess = false;
    /** Cursor for TOOLS state. Tool list populated by M1.5. */
    private int toolIdx = 0;
    /**
     * Tool snapshot for the currently-selected server. Populated when
     * transitioning into TOOLS via {@link #onEnterTools} — no lazy fetch
     * happens during render. Cleared when leaving TOOLS.
     */
    private List<Tool> currentTools = List.of();
    /**
     * Optional supplier that returns the already-discovered tool snapshot for
     * a server. It must not issue {@code tools/list}; discovery is owned by the
     * MCP connection lifecycle. Null → the TOOLS state shows an empty list.
     */
    private Function<String, List<Tool>> toolsProvider;
    private final AtomicLong toolsLoadGeneration = new AtomicLong();
    private volatile boolean toolsLoading;

    private Runnable onClose;

    private final ContentArea contentArea;

    public MCPSettingsDialog() {
        super(new LinearLayout(Direction.VERTICAL));
        setLayoutData(LinearLayout.createLayoutData(LinearLayout.Alignment.FILL));
        contentArea = new ContentArea();
        contentArea.setLayoutData(LinearLayout.createLayoutData(LinearLayout.Alignment.FILL));
        addComponent(contentArea);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Opens the dialog with the current MCP snapshot.
     *
     * @param snapshot presentation-neutral server and warning snapshot
     * @param onClose      called when the user closes the dialog at the LIST top level
     */
    public void show(Snapshot snapshot, Runnable onClose) {
        this.snapshot = snapshot != null ? snapshot : new Snapshot(List.of(), List.of());
        buildGroupedListView();
        this.onClose      = onClose;
        this.navState     = NavState.LIST;
        this.serverIdx    = 0;
        this.menuIdx      = 0;
        this.toolIdx      = 0;
        this.selectedServer = null;
        this.active       = true;
        contentArea.invalidate();
    }

    void show(Snapshot snapshot, Set<String> ignoredConnectedIds, Runnable onClose) {
        show(withConnectedIds(snapshot, ignoredConnectedIds), onClose);
    }

    /**
     * Refreshes the servers/scopes/connection snapshot while the dialog is
     * still open. Preserves the current NavState and cursor position when
     * the selected server still exists; unwinds one level otherwise.
     * matches the pattern used by {@code HooksConfigMenuDialog.refresh}.
     */
    public void refresh(Snapshot snapshot) {
        if (!active) return;
        this.snapshot = snapshot != null ? snapshot : new Snapshot(List.of(), List.of());
        buildGroupedListView();

        if (selectedServer != null) {
            Server fresh = this.snapshot.servers().stream()
                .filter(server -> server.name().equals(selectedServer.name()))
                .findFirst().orElse(null);
            if (fresh == null) {
                // The server was removed from disk — pop back to LIST.
                selectedServer = null;
                navState = NavState.LIST;
                serverMenuItems = List.of();
            } else {
                selectedServer = fresh;
                // Rebuild the menu — disabled/connected transitions change
                // which actions are available (e.g. Disable → Enable, drop
                // View tools when the server goes disconnected). Without
                // this rebuild the SERVER_MENU shows stale items after the
                // host applies the toggle.
                if (navState == NavState.SERVER_MENU) {
                    serverMenuItems = buildServerMenuItems(selectedServer);
                } else if (navState == NavState.AGENT_MENU) {
                    serverMenuItems = buildAgentMenuItems();
                }
                if (menuIdx >= serverMenuItems.size()) {
                    menuIdx = Math.max(0, serverMenuItems.size() - 1);
                }
            }
        }
        if (serverIdx >= servers.size()) serverIdx = Math.max(0, servers.size() - 1);
        contentArea.invalidate();
    }

    void refresh(Snapshot snapshot, Set<String> ignoredConnectedIds) {
        refresh(withConnectedIds(snapshot, ignoredConnectedIds));
    }

    private static Snapshot withConnectedIds(Snapshot snapshot, Set<String> connectedIds) {
        if (snapshot == null || connectedIds == null || connectedIds.isEmpty()) return snapshot;
        List<Server> mapped = snapshot.servers().stream().map(server -> {
            if (server.disabled() || !connectedIds.contains(server.name())) return server;
            return new Server(server.name(), server.displayName(), server.scope(),
                server.scopeOrder(), McpManagementPort.Status.CONNECTED, server.authStatus(),
                server.authDescription(), server.pluginChild(), server.manageable(),
                server.transport(), server.command(), server.args(), server.environment(),
                server.url(), server.headerCount(), server.configLocation());
        }).toList();
        return new Snapshot(mapped, snapshot.warnings());
    }

    public void hide() {
        active = false;
        toolsLoadGeneration.incrementAndGet();
        contentArea.invalidate();
    }

    @Override public boolean isActive() { return active; }

    /**
     * Installs the callback fired when a SERVER_MENU / AGENT_MENU item is
     * activated with Enter. The host wires this to concrete side-effects
     * (reconnect / enable-disable / open tool list). Null clears the sink.
     */
    public void setActionHandler(BiConsumer<MenuAction, Server> handler) {
        this.onAction = handler;
    }

    /**
     * Installs the supplier that resolves tool metadata for a given server
     * name. Host wires this to
     * {@code McpClientManager::listToolsForServer}. Null clears the supplier
     * (TOOLS state will show an empty list).
     */
    public void setToolsProvider(Function<String, List<Tool>> provider) {
        this.toolsProvider = provider;
    }

    // ── Reconnect UI driver API (called by host) ─────────────────────────────

    /**
     * Enters the "reconnecting…" sub-state on top of SERVER_MENU. Called by
     * the host <em>before</em> it kicks off the async {@code connect} call so
     * the user sees the spinner immediately.
     *
     * <p>Safe to call from any thread — the ContentArea invalidate is
     * queued through the parent Lanterna component; the host still needs
     * to run the follow-up {@link #endReconnectSuccess} /
     * {@link #endReconnectFailure} on the GUI thread.
     */
    public void beginReconnect(String serverName) {
        this.reconnectServerName = serverName != null ? serverName : "";
        this.reconnectMessage    = "";
        this.reconnectSuccess    = false;
        this.reconnectSubstate   = ReconnectSubstate.IN_PROGRESS;
        contentArea.invalidate();
    }

    /**
     * Transitions IN_PROGRESS → NONE (success).
     */
    public void endReconnectSuccess(String message) {
        this.reconnectMessage  = message != null ? message : "Successfully reconnected";
        this.reconnectSuccess  = true;
        this.reconnectSubstate = ReconnectSubstate.NONE;
        contentArea.invalidate();
    }

    /**
     * Transitions IN_PROGRESS → NONE (failure). Same as
     * {@link #endReconnectSuccess} but flags failure for tests. Host
     * surfaces the error via {@code postSystemMessage}.
     */
    public void endReconnectFailure(String message) {
        this.reconnectMessage  = message != null ? message : "Failed to reconnect";
        this.reconnectSuccess  = false;
        this.reconnectSubstate = ReconnectSubstate.NONE;
        contentArea.invalidate();
    }

    // ── Test-only accessors (package-private) ────────────────────────────────
    // Consumed by MCPSettingsDialogTest so we can drive the state machine
    // without a full Lanterna GUI harness.
    NavState navStateForTest()     { return navState; }
    int serverIdxForTest()         { return serverIdx; }
    int menuIdxForTest()           { return menuIdx; }
    int toolIdxForTest()           { return toolIdx; }
    Server selectedServerForTest() { return selectedServer; }
    List<Server> serversForTest() { return servers; }
    List<MenuAction> menuItemsForTest() { return serverMenuItems; }
    MenuAction lastActionForTest() { return lastActionForTest; }
    ReconnectSubstate reconnectSubstateForTest() { return reconnectSubstate; }
    String reconnectMessageForTest() { return reconnectMessage; }
    boolean reconnectSuccessForTest() { return reconnectSuccess; }
    List<Tool> currentToolsForTest() { return currentTools; }

    @Override
    public TerminalSize calculatePreferredSize() {
        return active ? super.calculatePreferredSize() : TerminalSize.of(0, 0);
    }

    /**
     * Routes a key stroke to the current state handler. The host
     * {@code WindowListener.onInput} must call this while {@link #isActive}.
     *
     * <p>The parameter name is legacy: the host contract is
     * <b>{@code deliver.set(false)} means "I ate the key, do not forward
     * downstream"</b>. This dialog consumes every keystroke while active
     * (menu navigation, Esc-to-close, Enter-to-select), so we unconditionally
     * clear the flag. Setting it to {@code true} would let arrow keys / Enter
     * fall through to the InputPanel — reproducer: ↑ pulled a prior message
     * from history and Enter submitted it while the dialog was open.
     */
    @Override public void handleKey(KeyStroke key, AtomicBoolean deliver) {
        if (!active) return;
        // Ctrl+C must reach the outer handler (double-press-to-exit /
        // interrupt in-flight API turn). Do NOT consume — leave deliver as
        // whatever the caller set and return early. Everything else is
        // menu/nav input and gets swallowed below.
        if (isCtrlC(key)) return;
        deliver.set(false);
        List<String> contexts = switch (navState) {
            case LIST, TOOL_DETAIL -> List.of("Confirmation");
            case SERVER_MENU, TOOLS, AGENT_MENU -> List.of("Select", "Confirmation");
        };
        ContextKeybindingDispatcher.Result resolved = keybindings.resolve(contexts, key);
        if (resolved instanceof ContextKeybindingDispatcher.Result.Consumed) return;
        if (resolved instanceof ContextKeybindingDispatcher.Result.Action(String value)
                && dispatchKeybindingAction(value)) {
            contentArea.invalidate();
            return;
        }
        KeyType t   = key.getKeyType();
        Character c = key.getCharacter();
        switch (navState) {
            case LIST         -> handleList(t, c);
            case SERVER_MENU  -> handleServerMenu(t, c);
            case TOOLS        -> handleTools(t, c);
            case TOOL_DETAIL  -> { if (t == KeyType.ESCAPE) navState = NavState.TOOLS; }
            case AGENT_MENU   -> handleMenuNavigation(t, c);
        }
        contentArea.invalidate();
    }

    private boolean dispatchKeybindingAction(String action) {
        KeyType synthetic = switch (action) {
            case "confirm:previous", "select:previous" -> KeyType.ARROW_UP;
            case "confirm:next", "select:next" -> KeyType.ARROW_DOWN;
            case "confirm:yes", "select:accept" -> KeyType.ENTER;
            case "confirm:no", "select:cancel" -> KeyType.ESCAPE;
            default -> null;
        };
        if (synthetic == null) return false;
        switch (navState) {
            case LIST -> handleList(synthetic, null);
            case SERVER_MENU -> handleServerMenu(synthetic, null);
            case TOOLS -> handleTools(synthetic, null);
            case TOOL_DETAIL -> {
                if (synthetic == KeyType.ESCAPE) navState = NavState.TOOLS;
                else return false;
            }
            case AGENT_MENU -> handleMenuNavigation(synthetic, null);
        }
        return true;
    }

    /**
     * True for a Ctrl+C keystroke — CHARACTER 'c' or the literal ETX (\003)
     * with Ctrl held and no Shift/Alt. matches the detection used by the
     * outer REPL onInput switch so the dialog and the host agree.
     */
    private static boolean isCtrlC(KeyStroke key) {
        if (key == null || key.getKeyType() != KeyType.CHARACTER) return false;
        Character ch = key.getCharacter();
        if (ch == null) return false;
        return (ch == 'c' || ch == '\003')
            && key.isCtrlDown() && !key.isShiftDown() && !key.isAltDown();
    }

    // ── Key handlers ─────────────────────────────────────────────────────────

    private void handleList(KeyType t, Character c) {
        boolean down = t == KeyType.ARROW_DOWN || (c != null && c == 'j');
        boolean up   = t == KeyType.ARROW_UP   || (c != null && c == 'k');
        if (down && !servers.isEmpty()) {
            serverIdx = Math.min(serverIdx + 1, servers.size() - 1);
        } else if (up && !servers.isEmpty()) {
            serverIdx = Math.max(serverIdx - 1, 0);
        } else if (t == KeyType.ENTER && !servers.isEmpty()) {
            selectedServer = servers.get(serverIdx);
            menuIdx = 0;
            if (isAgentTransport(selectedServer)) {
                navState = NavState.AGENT_MENU;
                serverMenuItems = buildAgentMenuItems();
            } else {
                navState = NavState.SERVER_MENU;
                serverMenuItems = buildServerMenuItems(selectedServer);
            }
        } else if (t == KeyType.ESCAPE) {
            close();
        }
    }

    private void handleServerMenu(KeyType t, Character c) {
        // If reconnect UI is layered on top, absorb keys separately so the
        // user can't wander around the menu mid-connect.
        if (reconnectSubstate == ReconnectSubstate.IN_PROGRESS) {
            // Nothing dismisses the spinner — the host will call
            // endReconnect*() when it finishes. Not even Esc (would let the
            // user leave and the async result still fire on a stale server).
            return;
        }
        handleMenuNavigation(t, c);
    }

    /**
     * Shared list navigation for SERVER_MENU / AGENT_MENU.
     */
    private void handleMenuNavigation(KeyType t, Character c) {
        boolean down = t == KeyType.ARROW_DOWN || (c != null && c == 'j');
        boolean up   = t == KeyType.ARROW_UP   || (c != null && c == 'k');
        int last = Math.max(0, serverMenuItems.size() - 1);
        if (down)      menuIdx = Math.min(menuIdx + 1, last);
        else if (up)   menuIdx = Math.max(menuIdx - 1, 0);
        else if (t == KeyType.ENTER && !serverMenuItems.isEmpty()) {
            dispatchAction(serverMenuItems.get(menuIdx));
        } else if (t == KeyType.ESCAPE) {
            navState = NavState.LIST;
            selectedServer = null;
            serverMenuItems = List.of();
        }
    }

    private void handleTools(KeyType t, Character c) {
        boolean down = t == KeyType.ARROW_DOWN || (c != null && c == 'j');
        boolean up   = t == KeyType.ARROW_UP   || (c != null && c == 'k');
        int last = Math.max(0, currentTools.size() - 1);
        if (down && !currentTools.isEmpty())      toolIdx = Math.min(toolIdx + 1, last);
        else if (up && !currentTools.isEmpty())   toolIdx = Math.max(toolIdx - 1, 0);
        else if (t == KeyType.ENTER && !currentTools.isEmpty()) navState = NavState.TOOL_DETAIL;
        else if (t == KeyType.ESCAPE) navState = NavState.SERVER_MENU;
    }

    /**
     * Pulls the cached tool snapshot from the host-supplied provider off the
     * GUI thread. Called on the VIEW_TOOLS transition; host callbacks that
     * mutate connections should invalidate by re-entering VIEW_TOOLS.
     */
    private void onEnterTools() {
        currentTools = List.of();
        if (toolsProvider == null || selectedServer == null) return;
        toolsLoading = true;
        String serverName = selectedServer.name();
        long generation = toolsLoadGeneration.incrementAndGet();
        Thread.ofVirtual().name("mcp-tools-" + serverName).start(() -> {
            List<Tool> loaded;
            try {
                List<Tool> tools = toolsProvider.apply(serverName);
                loaded = tools != null ? List.copyOf(tools) : List.of();
            } catch (Exception _) {
                loaded = List.of();
            }
            List<Tool> toolSnapshot = loaded;
            runOnGuiThreadIfAttached(() -> {
                if (!active || navState != NavState.TOOLS
                        || generation != toolsLoadGeneration.get()
                        || selectedServer == null
                        || !Strings.CS.equals(serverName, selectedServer.name())) return;
                currentTools = toolSnapshot;
                toolIdx = Math.min(toolIdx, Math.max(0, currentTools.size() - 1));
                toolsLoading = false;
                contentArea.invalidate();
            });
        });
    }

    private void runOnGuiThreadIfAttached(Runnable action) {
        var textGui = getTextGUI();
        if (textGui != null) textGui.getGUIThread().invokeLater(action);
        else action.run();
    }

    /**
     * Handles Enter on a menu item. BACK is short-circuited (pops back to
     * LIST); every other action is delegated to the host callback and
     * recorded for tests. Other side-effects (reconnect, enable/disable,
     * open tool list, OAuth) land in M1.3–M1.6.
     */
    private void dispatchAction(MenuAction action) {
        lastActionForTest = action;
        if (action == MenuAction.BACK) {
            navState = NavState.LIST;
            selectedServer = null;
            serverMenuItems = List.of();
            return;
        }
        if (action == MenuAction.VIEW_TOOLS) {
            navState = NavState.TOOLS;
            toolIdx = 0;
            onEnterTools();
            return;
        }
        // Everything else is host-side: reconnect / enable / disable /
        // authenticate / clear-auth. Fire the handler; the host may close
        // the dialog, refresh, or trigger an async operation.
        if (onAction != null && selectedServer != null) {
            try {
                onAction.accept(action, selectedServer);
            } catch (Exception _) {
                // Host callbacks must be exception-safe; the dialog stays
                // where it is on failure.
            }
        }
    }

    /**
     * Menu items for a stdio / remote (non-agent) server.
     */
    List<MenuAction> buildServerMenuItems(Server s) {
        if (s == null) return List.of();
        List<MenuAction> items = new ArrayList<>();
        if (s.connected())        items.add(MenuAction.VIEW_TOOLS);
        if (!s.disabled())        items.add(MenuAction.RECONNECT);
        // Remote transports (sse / http) — expose auth items. Java's OAuth
        // token persistence is pending (M1b), so the action callback lands
        // in a host stub for now; the menu items are still shown so users

        if (isRemoteTransport(s)) {
            items.add(MenuAction.AUTHENTICATE);
            items.add(MenuAction.CLEAR_AUTH);
        }
        items.add(s.disabled() ? MenuAction.ENABLE : MenuAction.DISABLE);
        items.add(MenuAction.BACK);
        return List.copyOf(items);
    }

    /**
     * True when the server uses a network-based transport that supports
     * OAuth (sse / http / claudeai-proxy). Java's MCP layer currently only
     * recognises "sse" — anything non-stdio is treated as remote here.
     */
    private static boolean isRemoteTransport(Server s) {
        return s != null && s.remote();
    }

    /**
     * Menu items for an agent-sourced MCP. Agents are read-only in this
     * dialog: you can inspect their tools but you can't
     * enable/disable/disconnect them (they exist because an agent
     * definition references them, not because a user configured them).
     */
    List<MenuAction> buildAgentMenuItems() {
        List<MenuAction> items = new ArrayList<>();
        if (selectedServer != null && selectedServer.connected()) {
            items.add(MenuAction.VIEW_TOOLS);
        }
        items.add(MenuAction.BACK);
        return List.copyOf(items);
    }

    private static String labelFor(MenuAction a) {
        return switch (a) {
            case VIEW_TOOLS   -> "View tools";
            case RECONNECT    -> "Reconnect";
            case ENABLE       -> "Enable";
            case DISABLE      -> "Disable";
            case AUTHENTICATE -> "Authenticate";
            case CLEAR_AUTH   -> "Clear authentication";
            case BACK         -> "Back";
        };
    }

    private void close() {
        active = false;
        contentArea.invalidate();
        if (onClose != null) onClose.run();
    }

    // ── Draw dispatch ────────────────────────────────────────────────────────

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
                    switch (navState) {
                        case LIST        -> drawList(g, cols);
                        case SERVER_MENU -> drawServerMenu(g, cols);
                        case TOOLS       -> drawTools(g, cols);
                        case TOOL_DETAIL -> drawToolDetail(g, cols);
                        case AGENT_MENU  -> drawAgentMenu(g, cols);
                    }
                }
            };
        }
    }

    // ── Draw methods ─────────────────────────────────────────────────────────
    // M1.0 skeleton: every state renders a title + placeholder body so the
    // state machine + key routing can be verified against a running app.
    // Real layouts land in M1.1 (LIST), M1.2 (SERVER_MENU/stdio), M1.5
    // (TOOLS + TOOL_DETAIL), M1.6 (SERVER_MENU/remote), M1.7 (AGENT_MENU).

    private void drawList(TextGUIGraphics g, int cols) {
        LanternaDraw.divider(g, cols, 0);

        drawTitle(g, "Manage MCP servers");

        int total = servers.size();
        g.setForegroundColor(LanternaTheme.welcomeDim());

        String suffix = total == 1 ? "server" : "servers";
        g.putString(LEFT_PAD, 2, total + " " + suffix);
        g.setForegroundColor(TextColor.ANSI.DEFAULT);

        // Row 3 reserved for parse warnings when config != null and non-empty.
        int listStartRow = 4;
        if (!snapshot.warnings().isEmpty()) {
            g.setForegroundColor(LanternaTheme.toolWarning());
            int maxWidth = Math.max(4, cols - LEFT_PAD);
            String head = "⚠ " + snapshot.warnings().size() + " parse warning"
                + (snapshot.warnings().size() == 1 ? "" : "s") + ":";
            g.putString(LEFT_PAD, 3, truncateForCols(head, maxWidth));
            // Show up to 3 warnings inline; rest are in the log. Rows 4..6 are
            // borrowed from the list area.
            int shown = Math.min(3, snapshot.warnings().size());
            for (int i = 0; i < shown; i++) {
                g.putString(LEFT_PAD, 4 + i, truncateForCols(
                    "  · " + snapshot.warnings().get(i), maxWidth));
            }
            listStartRow = 4 + shown + 1;   // one blank row separates warnings from list
            g.setForegroundColor(TextColor.ANSI.DEFAULT);
        }

        if (servers.isEmpty()) {
            g.setForegroundColor(LanternaTheme.welcomeDim());
            g.putString(LEFT_PAD, listStartRow, "No MCP servers configured.");
            g.putString(LEFT_PAD, listStartRow + 1,
                "Run `claude mcp --help` or visit code.claude.com/docs/en/mcp.");
            drawFooter(g, "Esc back");
            return;
        }

        // Render rows from listRows (headers + servers interleaved), scrolled
        // so the selected server stays in view. Available range: rows listStartRow..FOOTER_ROW-1.
        int maxVisible = FOOTER_ROW - listStartRow;
        int selectedRowIdx = findRowIndexOfServer(serverIdx);
        int scroll = computeScrollWithHeader(selectedRowIdx, listRows.size(), maxVisible);
        int maxWidth = Math.max(4, cols - LEFT_PAD);

        for (int vi = 0; vi < maxVisible; vi++) {
            int ri = scroll + vi;
            if (ri >= listRows.size()) break;
            GroupRow row = listRows.get(ri);
            int y = listStartRow + vi;

            if (row.isHeader()) {
                // Scope header — dim + bold-ish (use suggestion colour so it
                // reads as a section divider rather than a menu row).
                g.setForegroundColor(LanternaTheme.suggestion());
                g.enableModifiers(SGR.BOLD);
                g.putString(LEFT_PAD, y, truncateForCols(row.header, maxWidth));
                g.disableModifiers(SGR.BOLD);
                g.setForegroundColor(TextColor.ANSI.DEFAULT);
                continue;
            }

            Server s = row.server;
            boolean selected = row.serverIndex == serverIdx;

            String pointer = selected ? "▶ " : "  ";
            String name    = s.name();
            String icon    = statusIcon(s);
            String statusText = statusText(s);

            g.setForegroundColor(selected ? LanternaTheme.suggestion() : TextColor.ANSI.DEFAULT);
            g.putString(LEFT_PAD, y, pointer + name);
            int x = LEFT_PAD + pointer.length() + name.length();


            TextColor iconColour = statusColour(s);
            g.setForegroundColor(iconColour);
            String iconStr = " · " + icon;
            g.putString(x, y, iconStr);
            x += iconStr.length();

            g.setForegroundColor(LanternaTheme.welcomeDim());
            String tail = " " + statusText;
            g.putString(x, y, truncateForCols(tail, Math.max(1, cols - x - 1)));
            g.setForegroundColor(TextColor.ANSI.DEFAULT);
        }

        drawFooter(g, "↑↓ navigate · Enter select · Esc back");
    }

    /**
     * Rebuilds {@link #listRows} from the current {@link #snapshot}.
     */
    private void buildGroupedListView() {
        servers.clear();
        listRows.clear();
        Map<String, List<Server>> byScope = new LinkedHashMap<>();
        snapshot.servers().stream()
            .sorted(Comparator.comparingInt(Server::scopeOrder)
                .thenComparing(Server::name, String.CASE_INSENSITIVE_ORDER))
            .forEach(server -> byScope.computeIfAbsent(server.scope(), _ -> new ArrayList<>())
                .add(server));
        for (Map.Entry<String, List<Server>> entry : byScope.entrySet()) {
            List<Server> group = entry.getValue();
            if (group == null || group.isEmpty()) continue;
            listRows.add(GroupRow.header(scopeHeader(entry.getKey(), group.size())));
            for (Server s : group) {
                servers.add(s);
                listRows.add(GroupRow.server(s, servers.size() - 1));
            }
        }
    }

    private static String scopeHeader(String scope, int count) {
        String label = switch (scope) {
            case "project" -> "Project MCPs";
            case "local" -> "Local MCPs";
            case "user" -> "User MCPs";
            case "enterprise" -> "Enterprise MCPs";
            case "managed" -> "Managed plugin MCPs";
            default -> "Built-in / Dynamic MCPs";
        };
        return label + " (" + count + ")";
    }


    private static String describeConfigPath(Server s) {
        return s.configLocation();
    }

    /**
     * Status icon for a server row.
     */
    private String statusIcon(Server s) {
        if (s.disabled())        return "○";
        if (s.connected())       return "✓";
        if (s.needsAuthentication()) return "△";
        return "○";
    }

    private String statusText(Server s) {
        if (s.disabled())   return "disabled";
        if (s.connected()) return "connected";
        if (s.needsAuthentication()) return "needs authentication";
        return "disconnected";
    }

    private TextColor statusColour(Server s) {
        if (s.disabled())   return LanternaTheme.welcomeDim();
        if (s.connected()) return LanternaTheme.toolSuccess();
        if (s.needsAuthentication()) return LanternaTheme.toolWarning();
        return LanternaTheme.welcomeDim();
    }

    /**
     * True when a remote (http/sse) server has no route to authenticate:
     * {@link #authStatusProvider} reports the OAuth store is empty AND the
     * server config has no static Authorization header. Detected by the
     * {@code ✗} sigil we emit from {@link MCPController#describeAuthStatus}.
     * Stdio servers never need auth by definition.
     */
    private int findRowIndexOfServer(int serverIndex) {
        for (int i = 0; i < listRows.size(); i++) {
            GroupRow r = listRows.get(i);
            if (!r.isHeader() && r.serverIndex == serverIndex) return i;
        }
        return 0;
    }

    private static int computeScrollWithHeader(int rowIdx, int total, int maxVisible) {
        if (total <= maxVisible) return 0;
        int scroll = Math.max(0, rowIdx - maxVisible + 1);
        return Math.min(scroll, total - maxVisible);
    }

    private static String truncateForCols(String s, int maxWidth) {
        if (s == null || maxWidth <= 0) return "";
        if (s.length() <= maxWidth) return s;
        if (maxWidth == 1) return "…";
        return FormatUtils.truncate(s, maxWidth);
    }

    /**
     * A single visual row in the LIST view: either a scope header (server is null) or a server row
     * (serverIndex points into {@link #servers}).
     *
     * @param header      non-null iff this is a header
     * @param server      non-null iff this is a server
     * @param serverIndex -1 for headers
     */
    private record GroupRow(String header, Server server, int serverIndex) {

        static GroupRow header(String label) {
            return new GroupRow(label, null, -1);
        }

        static GroupRow server(Server s, int idx) {
            return new GroupRow(null, s, idx);
        }

        boolean isHeader() {
            return header != null;
        }
    }

    private void drawServerMenu(TextGUIGraphics g, int cols) {
        LanternaDraw.divider(g, cols, 0);
        String title = selectedServer != null ? selectedServer.name() : "(no server)";
        drawTitle(g, title);
        if (selectedServer == null) return;

        // Reconnect UI takes over the body when active.
        if (reconnectSubstate != ReconnectSubstate.NONE) {
            drawReconnectOverlay(g);
            return;
        }

        int maxWidth = Math.max(4, cols - LEFT_PAD);
        g.setForegroundColor(LanternaTheme.welcomeDim());
        int row = 2;


        //   1. Status (own line, no transport prefix)
        //   2. Auth (remote only)
        //   3. URL / Command (transport-specific)
        //   4. Args / Headers (transport-specific)

        //   6. Used by (agents that reference this server)
        //

        // moving auth into the second row makes it the first thing users see on a
        // just-configured remote server.
        g.putString(LEFT_PAD, row++, "status: " + statusText(selectedServer));

        if (isRemoteTransport(selectedServer)) {
            String authLine = selectedServer.authDescription();
            if (StringUtils.isNotBlank(authLine)) {
                g.putString(LEFT_PAD, row++, truncateForCols(authLine, maxWidth));
            }
            String url = selectedServer.url();
            if (StringUtils.isNotBlank(url)) {
                g.putString(LEFT_PAD, row++, truncateForCols("url: " + url, maxWidth));
            }
            if (selectedServer.headerCount() > 0) {
                // Show only the count — Authorization header values are
                // secrets, and enumerating them by name would leak PAT
                // presence to a shoulder-surfer.
                g.putString(LEFT_PAD, row++, "headers: " + selectedServer.headerCount()
                    + (selectedServer.headerCount() == 1 ? " entry" : " entries"));
            }
        } else {
            // Stdio branch — command + args (only when populated; empty rows
            // just take space).
            String cmd = selectedServer.command();
            if (StringUtils.isNotBlank(cmd)) {
                g.putString(LEFT_PAD, row++, truncateForCols("command: " + cmd, maxWidth));
            }
            if (selectedServer.args() != null && !selectedServer.args().isEmpty()) {
                g.putString(LEFT_PAD, row++, truncateForCols(
                    "args: " + String.join(" ", selectedServer.args()), maxWidth));
            }
        }


        g.putString(LEFT_PAD, row++,
            "config: " + selectedServer.scope()
                + " (" + describeConfigPath(selectedServer) + ")");

        String usedByLine = usedByLine(selectedServer);
        if (usedByLine != null) {
            g.putString(LEFT_PAD, row++, truncateForCols(usedByLine, maxWidth));
        }
        g.setForegroundColor(TextColor.ANSI.DEFAULT);

        // Blank row separates header details from the action menu.
        drawMenuItems(g, cols, /* startRow */ row + 1);
        drawFooter(g, "↑↓ navigate · Enter select · Esc back");
    }

    /**
     * Renders the reconnect spinner overlay (rows 2..FOOTER_ROW-1) while a
     * connect call is in flight. Success / failure never render here —
     * once the async call resolves the substate flips to NONE and the
     * regular SERVER_MENU takes over; the outcome message reaches the user
     * through {@code postSystemMessage}.
     */
    private void drawReconnectOverlay(TextGUIGraphics g) {
        g.setForegroundColor(LanternaTheme.claude());
        String spinnerFrame = "⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏".substring(
            (int) ((System.currentTimeMillis() / 100) % 10),
            (int) ((System.currentTimeMillis() / 100) % 10) + 1);
        g.putString(LEFT_PAD, 3, spinnerFrame + " Reconnecting to " + reconnectServerName + "…");
        g.setForegroundColor(LanternaTheme.welcomeDim());
        g.putString(LEFT_PAD, 5, "Establishing connection to MCP server.");
        drawFooter(g, "Please wait…");
    }

    private void drawAgentMenu(TextGUIGraphics g, int cols) {
        LanternaDraw.divider(g, cols, 0);
        drawTitle(g, "Agent MCP: " + (selectedServer != null ? selectedServer.name() : ""));
        if (selectedServer == null) return;

        int maxWidth = Math.max(4, cols - LEFT_PAD);
        g.setForegroundColor(LanternaTheme.welcomeDim());
        g.putString(LEFT_PAD, 2, "read-only (agent-sourced servers cannot be toggled here)");
        g.putString(LEFT_PAD, 3, truncateForCols("command: " + selectedServer.command(), maxWidth));
        g.putString(LEFT_PAD, 4, "transport: " + selectedServer.transport()
            + "  ·  status: " + statusText(selectedServer));
        // "Used by" — comma-joined list of custom-agent names (from
        // {@code ~/.claude/agents/*.md} + {@code <cwd>/.claude/agents/*.md})
        // whose frontmatter {@code mcpServers:} field references this server.

        String usedByLine = usedByLine(selectedServer);
        g.putString(LEFT_PAD, 5, usedByLine != null
            ? truncateForCols(usedByLine, maxWidth) : "used by: —");
        g.setForegroundColor(TextColor.ANSI.DEFAULT);

        drawMenuItems(g, cols, /* startRow */ 7);
        drawFooter(g, "↑↓ navigate · Enter select · Esc back");
    }

    /**
     * Returns the {@code "used by: agent-a, agent-b"} row for a server, or
     * {@code null} when no custom agent references it. Consulted from both
     * {@link #drawServerMenu} (stdio / remote detail page) and
     * {@link #drawAgentMenu} (inline agent-sourced servers). Reads live via
     * {@link AgentMcpServerIndex} so hot-reloaded agent frontmatter takes
     * effect on the next repaint.
     */
    private String usedByLine(Server server) {
        try {
            String cwd = System.getProperty("user.dir");
            List<String> agents = AgentMcpServerIndex.usedByForServer(cwd, server.name());
            if (agents == null || agents.isEmpty()) return null;
            return "used by: " + String.join(", ", agents);
        } catch (Exception _) {
            // Loader errors (missing agents dir, malformed frontmatter) must
            // never break the /mcp dialog — fall through to no row.
            return null;
        }
    }

    private void drawMenuItems(TextGUIGraphics g, int cols, int startRow) {
        int maxWidth = Math.max(4, cols - LEFT_PAD);
        for (int i = 0; i < serverMenuItems.size(); i++) {
            int y = startRow + i;
            if (y >= FOOTER_ROW) break;   // out of layout budget
            MenuAction a = serverMenuItems.get(i);
            boolean selected = i == menuIdx;
            String pointer = selected ? "❯ " : "  ";
            String label   = labelFor(a);

            g.setForegroundColor(selected ? LanternaTheme.suggestion() : TextColor.ANSI.DEFAULT);
            g.putString(LEFT_PAD, y, truncateForCols(pointer + label, maxWidth));
            g.setForegroundColor(TextColor.ANSI.DEFAULT);
        }
    }

    private void drawTools(TextGUIGraphics g, int cols) {
        LanternaDraw.divider(g, cols, 0);
        drawTitle(g, (selectedServer != null ? selectedServer.name() : "") + " · Tools");

        int total = currentTools.size();
        g.setForegroundColor(LanternaTheme.welcomeDim());
        g.putString(LEFT_PAD, 2, total + " " + (total == 1 ? "tool" : "tools") + " exposed");
        g.setForegroundColor(TextColor.ANSI.DEFAULT);

        if (currentTools.isEmpty()) {
            g.setForegroundColor(LanternaTheme.welcomeDim());
            g.putString(LEFT_PAD, 4, toolsLoading ? "Loading tools…"
                : "(No tools reported. Server may not be connected or exposes none.)");
            drawFooter(g, "Esc back");
            return;
        }

        int maxVisible = FOOTER_ROW - 4;
        int scroll = computeScrollWithHeader(toolIdx, currentTools.size(), maxVisible);
        int maxWidth = Math.max(4, cols - LEFT_PAD);

        for (int vi = 0; vi < maxVisible; vi++) {
            int ti = scroll + vi;
            if (ti >= currentTools.size()) break;
            Tool tool = currentTools.get(ti);
            int y = 4 + vi;
            boolean selected = ti == toolIdx;
            String pointer = selected ? "❯ " : "  ";
            String line = pointer + tool.name();
            g.setForegroundColor(selected ? LanternaTheme.suggestion() : TextColor.ANSI.DEFAULT);
            g.putString(LEFT_PAD, y, truncateForCols(line, maxWidth));
            int x = LEFT_PAD + line.length();
            String desc = tool.description();
            if (StringUtils.isNotBlank(desc)) {
                int descCol = Math.max(32, x + 2);
                if (descCol < cols - 4) {
                    int avail = cols - descCol - 1;
                    g.setForegroundColor(LanternaTheme.welcomeDim());
                    g.putString(descCol, y, truncateForCols(firstLine(desc), avail));
                }
            }
            g.setForegroundColor(TextColor.ANSI.DEFAULT);
        }
        drawFooter(g, "↑↓ navigate · Enter select · Esc back");
    }

    private void drawToolDetail(TextGUIGraphics g, int cols) {
        LanternaDraw.divider(g, cols, 0);
        Tool tool = (toolIdx >= 0 && toolIdx < currentTools.size())
            ? currentTools.get(toolIdx) : null;
        drawTitle(g, tool != null ? tool.name() : "Tool Detail");
        if (tool == null) {
            drawFooter(g, "Esc back");
            return;
        }

        int maxWidth = Math.max(4, cols - LEFT_PAD);
        int row = 2;

        // Description block — wrap at column width.
        String desc = tool.description();
        if (StringUtils.isNotBlank(desc)) {
            g.setForegroundColor(LanternaTheme.welcomeDim());
            for (String line : wrapLines(desc, maxWidth)) {
                if (row >= FOOTER_ROW - 2) break;   // reserve room for parameters section
                g.putString(LEFT_PAD, row++, line);
            }
        }
        // Blank gap before parameters.
        if (row < FOOTER_ROW - 1) row++;


        // walking inputSchema.properties and printing one row per parameter:
        //   `{name}{* if required}  {type}  — {description}`.
        // Way more scannable than pretty-printing the raw JSON schema.
        JsonNode schema = tool.inputSchema();
        JsonNode props = schema != null ? schema.get("properties") : null;
        if (props != null && props.isObject() && !props.isEmpty()) {
            g.setForegroundColor(LanternaTheme.suggestion());
            g.putString(LEFT_PAD, row++, "Parameters:");
            g.setForegroundColor(TextColor.ANSI.DEFAULT);

            Set<String> required = new HashSet<>();
            JsonNode reqNode = schema.get("required");
            if (reqNode != null && reqNode.isArray()) {
                for (JsonNode n : reqNode) required.add(n.asText());
            }
            Iterator<Entry<String, JsonNode>> it = props.fields();
            while (it.hasNext()) {
                if (row >= FOOTER_ROW - 1) {
                    g.setForegroundColor(LanternaTheme.welcomeDim());
                    g.putString(LEFT_PAD, row++, "…(more parameters truncated)");
                    break;
                }
                Map.Entry<String, JsonNode> e = it.next();
                String name = e.getKey();
                JsonNode p = e.getValue();
                String type = p.path("type").asText("any");
                String pDesc = p.path("description").asText("");
                String star = required.contains(name) ? "*" : "";
                String line = "  " + name + star + "  " + type
                    + (pDesc.isEmpty() ? "" : "  — " + firstLine(pDesc));
                g.putString(LEFT_PAD, row++, truncateForCols(line, maxWidth));
            }
            if (!required.isEmpty() && row < FOOTER_ROW - 1) {
                g.setForegroundColor(LanternaTheme.welcomeDim());
                g.putString(LEFT_PAD, row++, "(* = required)");
                g.setForegroundColor(TextColor.ANSI.DEFAULT);
            }
        } else {
            g.setForegroundColor(LanternaTheme.welcomeDim());
            g.putString(LEFT_PAD, row++, "(no parameters)");
            g.setForegroundColor(TextColor.ANSI.DEFAULT);
        }
        if (log.isDebugEnabled()) {
            log.debug("drawToolDetail: tool={} finalRow={}", tool.name(), row);
        }
        drawFooter(g, "Esc back");
    }

    /** Extracts the first non-empty line from a possibly multi-line description. */
    private static String firstLine(String s) {
        if (s == null) return "";
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }

    /** Simple word-boundary wrapping for description paragraphs. */
    private static List<String> wrapLines(String s, int width) {
        if (StringUtils.isBlank(s) || width <= 0) return List.of();
        List<String> out = new ArrayList<>();
        for (String paragraph : s.split("\n")) {
            String rest = paragraph;
            while (rest.length() > width) {
                int cut = rest.lastIndexOf(' ', width);
                if (cut <= 0) cut = width;
                out.add(rest.substring(0, cut));
                rest = rest.substring(cut).stripLeading();
            }
            out.add(rest);
        }
        return out;
    }

    // ── Shared draw helpers ──────────────────────────────────────────────────

    private void drawTitle(TextGUIGraphics g, String title) {
        LanternaDraw.title(g, title, LEFT_PAD);
    }

    private void drawFooter(TextGUIGraphics g, String text) {
        LanternaDraw.footer(g, text, LEFT_PAD, FOOTER_ROW);
    }

    // ── Predicates ───────────────────────────────────────────────────────────

    /**
     * True when this server should route to {@link NavState#AGENT_MENU} instead of {@link
     * NavState#SERVER_MENU}.
     */
    private static boolean isAgentTransport(Server s) {
        return s != null && s.agentProvided();
    }
}
