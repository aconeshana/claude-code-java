package com.claudecode.ui.lanterna.dialog;

import org.apache.commons.lang3.Strings;

import com.claudecode.runtime.mcp.McpManagementPort.AuthStatus;
import com.claudecode.runtime.mcp.McpManagementPort.Server;
import com.claudecode.runtime.mcp.McpManagementPort.Snapshot;
import com.claudecode.runtime.mcp.McpManagementPort.Status;
import com.claudecode.runtime.mcp.McpManagementPort.Tool;
import com.claudecode.keybindings.UserKeybindingsStore;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies {@link MCPSettingsDialog}'s 5-state machine and stdio server menu
 * behaviour (M1.0-M1.2). Drives the dialog via {@link MCPSettingsDialog#handleKey}
 * and package-private accessors — no live Lanterna GUI required.
 */
class MCPSettingsDialogTest {

    private static Server stdioServer(String name, boolean disabled) {
        return server(name, "npx", List.of("-y", "@example/" + name),
            disabled, "stdio");
    }

    private static Server agentServer(String name) {
        return server(name, "agent://" + name, List.of(), false, "agent");
    }

    private static Server server(String name, String command, List<String> args,
                                 boolean disabled, String transport) {
        return new Server(name, name, "project", 0,
            disabled ? Status.DISABLED : Status.DISCONNECTED, AuthStatus.NOT_APPLICABLE, "",
            false, true, transport, command, args, Map.of(), null, 0, ".mcp.json");
    }

    private static Snapshot configWithScopes(Server[]... groups) {
        // groups[0] = PROJECT, [1] = USER, [2] = LOCAL, etc. — order chosen

        String[] scopes = {"project", "user", "local", "enterprise", "dynamic"};
        List<Server> servers = new ArrayList<>();
        for (int i = 0; i < groups.length && i < scopes.length; i++) {
            for (Server s : groups[i]) {
                servers.add(new Server(s.name(), s.displayName(), scopes[i], i,
                    s.status(), s.authStatus(), s.authDescription(), s.pluginChild(),
                    s.manageable(), s.transport(), s.command(), s.args(), s.environment(),
                    s.url(), s.headerCount(), s.configLocation()));
            }
        }
        return new Snapshot(servers, List.of());
    }

    private static KeyStroke ks(KeyType t) { return new KeyStroke(t, false, false); }

    // ── M1.0 skeleton ────────────────────────────────────────────────────────

    @Test
    void show_startsInListState() {
        MCPSettingsDialog d = new MCPSettingsDialog();
        d.show(configWithScopes(new Server[]{ stdioServer("s1", false) }),
            Set.of(), () -> {});
        assertTrue(d.isActive());
        assertEquals(MCPSettingsDialog.NavState.LIST, d.navStateForTest());
        assertEquals(0, d.serverIdxForTest());
    }

    @Test
    void esc_atList_closesDialog_andFiresOnClose() {
        MCPSettingsDialog d = new MCPSettingsDialog();
        AtomicBoolean closed = new AtomicBoolean();
        d.show(configWithScopes(new Server[]{ stdioServer("s1", false) }),
            Set.of(), () -> closed.set(true));
        d.handleKey(ks(KeyType.ESCAPE), new AtomicBoolean());
        assertFalse(d.isActive(), "Esc at LIST closes the dialog");
        assertTrue(closed.get(), "onClose callback must fire");
    }

    @Test
    void handleKey_consumesEveryKey_whileActive() {
        // Regression: the host onInput contract is deliver.set(false)="I ate
        // the key, don't forward". A prior version flipped this to
        // handled.set(true), which let ↑/Enter fall through to InputPanel —
        // the reproducer was pressing ↑ inside SERVER_MENU pulling history
        // and Enter submitting a stray message.
        MCPSettingsDialog d = new MCPSettingsDialog();
        d.show(configWithScopes(new Server[]{ stdioServer("s1", false) }),
            Set.of("s1"), () -> {});
        for (KeyType t : new KeyType[]{
                KeyType.ARROW_DOWN, KeyType.ARROW_UP, KeyType.ENTER,
                KeyType.ESCAPE }) {
            MCPSettingsDialog d2 = new MCPSettingsDialog();
            d2.show(configWithScopes(new Server[]{ stdioServer("s1", false) }),
                Set.of("s1"), () -> {});
            AtomicBoolean deliver = new AtomicBoolean(true);
            d2.handleKey(ks(t), deliver);
            assertFalse(deliver.get(),
                "dialog must consume " + t + " and clear deliver so it "
              + "does not fall through to InputPanel");
        }
    }

    @Test
    void handleKey_letsCtrlC_passThrough_forDoubleExit() {
        // Regression: Ctrl+C inside the dialog previously got swallowed with
        // every other key, so users couldn't double-press to exit while the
        // dialog was open. Now Ctrl+C must leave deliver unchanged (true)
// so the outer REPL onInput switch can dispatch to handleCtrlC.
        MCPSettingsDialog d = new MCPSettingsDialog();
        d.show(configWithScopes(new Server[]{ stdioServer("s1", false) }),
            Set.of("s1"), () -> {});
        AtomicBoolean deliver = new AtomicBoolean(true);
        // Simulate the raw ETX keystroke that Lanterna delivers in TRAP mode.
        KeyStroke ctrlC = new KeyStroke('c', true, false, false);
        d.handleKey(ctrlC, deliver);
        assertTrue(deliver.get(), "Ctrl+C must reach the host exit handler");
    }

    @Test
    void listAndNestedMenusUseTheirRuntimeContexts(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("keybindings.json");
        Files.writeString(file, """
            [
              {"context":"Confirmation","bindings":{
                "x":"confirm:next","z":"confirm:yes","q":"confirm:no",
                "down":null,"enter":null,"escape":null
              }},
              {"context":"Select","bindings":{
                "x":"select:next","z":"select:accept","q":"select:cancel",
                "down":null,"enter":null,"escape":null
              }}
            ]
            """);
        UserKeybindingsStore store = createStore(file);
        try {
            MCPSettingsDialog d = new MCPSettingsDialog();
            d.setKeybindingsStore(store);
            d.show(configWithScopes(new Server[]{
                    stdioServer("alpha", false), stdioServer("beta", false) }),
                Set.of("alpha", "beta"), () -> {});

            d.handleKey(ks(KeyType.ARROW_DOWN), new AtomicBoolean(true));
            assertEquals(0, d.serverIdxForTest(), "LIST uses Confirmation and Down is unbound");
            d.handleKey(new KeyStroke('x', false, false), new AtomicBoolean(true));
            assertEquals(1, d.serverIdxForTest());
            d.handleKey(new KeyStroke('z', false, false), new AtomicBoolean(true));
            assertEquals(MCPSettingsDialog.NavState.SERVER_MENU, d.navStateForTest());

            d.handleKey(ks(KeyType.ARROW_DOWN), new AtomicBoolean(true));
            assertEquals(0, d.menuIdxForTest(), "SERVER_MENU uses Select and Down is unbound");
            d.handleKey(new KeyStroke('x', false, false), new AtomicBoolean(true));
            assertEquals(1, d.menuIdxForTest());
            d.handleKey(new KeyStroke('q', false, false), new AtomicBoolean(true));
            assertEquals(MCPSettingsDialog.NavState.LIST, d.navStateForTest());
        } finally {
            store.dispose();
        }
    }

    private static UserKeybindingsStore createStore(Path file) throws Exception {
        Method create = UserKeybindingsStore.class
            .getDeclaredMethod("create", Path.class, boolean.class);
        create.setAccessible(true);
        return (UserKeybindingsStore) create.invoke(null, file, true);
    }

    // ── M1.1 List ────────────────────────────────────────────────────────────

    @Test
    void list_groupsByScope_andSortsAlphabeticallyWithinGroup() {
        MCPSettingsDialog d = new MCPSettingsDialog();
        // PROJECT bucket has two servers out of order.
        d.show(configWithScopes(
                new Server[]{ stdioServer("zeta", false), stdioServer("alpha", false) },
                new Server[]{ stdioServer("solo", false) }),
            Set.of(), () -> {});

        List<Server> ordered = d.serversForTest();
        // Project (alpha, zeta) then User (solo).
        assertEquals(List.of("alpha", "zeta", "solo"),
            ordered.stream().map(Server::name).toList());
    }

    @Test
    void arrows_moveCursorWithinListWithoutWrapping() {
        MCPSettingsDialog d = new MCPSettingsDialog();
        d.show(configWithScopes(new Server[]{
                stdioServer("a", false), stdioServer("b", false), stdioServer("c", false)}),
            Set.of(), () -> {});

        d.handleKey(ks(KeyType.ARROW_DOWN), new AtomicBoolean());
        assertEquals(1, d.serverIdxForTest());
        d.handleKey(ks(KeyType.ARROW_DOWN), new AtomicBoolean());
        d.handleKey(ks(KeyType.ARROW_DOWN), new AtomicBoolean());   // past end
        assertEquals(2, d.serverIdxForTest(), "cursor must clamp at last row");
        d.handleKey(ks(KeyType.ARROW_UP), new AtomicBoolean());
        assertEquals(1, d.serverIdxForTest());
    }

    // ── M1.2 stdio server menu ───────────────────────────────────────────────

    @Test
    void enter_onStdioServer_opensServerMenu_withEnabledMenuItems() {
        MCPSettingsDialog d = new MCPSettingsDialog();
        d.show(configWithScopes(new Server[]{ stdioServer("s1", false) }),
            /* connected */ Set.of("s1"), () -> {});
        d.handleKey(ks(KeyType.ENTER), new AtomicBoolean());

        assertEquals(MCPSettingsDialog.NavState.SERVER_MENU, d.navStateForTest());
        assertEquals("s1", d.selectedServerForTest().name());
        // Connected + enabled → View Tools, Reconnect, Disable, Back.
        assertEquals(
            List.of(MCPSettingsDialog.MenuAction.VIEW_TOOLS,
                    MCPSettingsDialog.MenuAction.RECONNECT,
                    MCPSettingsDialog.MenuAction.DISABLE,
                    MCPSettingsDialog.MenuAction.BACK),
            d.menuItemsForTest());
    }

    @Test
    void enter_onDisabledServer_offersEnable_notReconnect() {
        MCPSettingsDialog d = new MCPSettingsDialog();
        d.show(configWithScopes(new Server[]{ stdioServer("s1", /* disabled */ true) }),
            Set.of(), () -> {});
        d.handleKey(ks(KeyType.ENTER), new AtomicBoolean());

        // Disabled + not connected → Enable, Back.
        assertEquals(
            List.of(MCPSettingsDialog.MenuAction.ENABLE, MCPSettingsDialog.MenuAction.BACK),
            d.menuItemsForTest(),
            "disabled server hides Reconnect (can't connect while disabled) and "
                + "hides View Tools (not connected); shows Enable toggle");
    }

    @Test
    void enter_onDisconnectedButEnabledServer_showsReconnectNotViewTools() {
        MCPSettingsDialog d = new MCPSettingsDialog();
        d.show(configWithScopes(new Server[]{ stdioServer("s1", false) }),
            /* connected */ Set.of(), () -> {});
        d.handleKey(ks(KeyType.ENTER), new AtomicBoolean());

        assertEquals(
            List.of(MCPSettingsDialog.MenuAction.RECONNECT,
                    MCPSettingsDialog.MenuAction.DISABLE,
                    MCPSettingsDialog.MenuAction.BACK),
            d.menuItemsForTest());
    }

    @Test
    void back_popsToList_andClearsSelectedServer() {
        MCPSettingsDialog d = new MCPSettingsDialog();
        d.show(configWithScopes(new Server[]{ stdioServer("s1", false) }),
            Set.of(), () -> {});
        d.handleKey(ks(KeyType.ENTER), new AtomicBoolean());
        assertEquals(MCPSettingsDialog.NavState.SERVER_MENU, d.navStateForTest());

        // Navigate to the last menu item (BACK) and press Enter.
        for (int i = 0; i < d.menuItemsForTest().size(); i++) {
            d.handleKey(ks(KeyType.ARROW_DOWN), new AtomicBoolean());
        }
        d.handleKey(ks(KeyType.ENTER), new AtomicBoolean());

        assertEquals(MCPSettingsDialog.NavState.LIST, d.navStateForTest());
        assertNull(d.selectedServerForTest(), "BACK must clear selectedServer");
        assertEquals(MCPSettingsDialog.MenuAction.BACK, d.lastActionForTest());
    }

    @Test
    void viewTools_transitionsToToolsState() {
        MCPSettingsDialog d = new MCPSettingsDialog();
        d.show(configWithScopes(new Server[]{ stdioServer("s1", false) }),
            Set.of("s1"), () -> {});
        d.handleKey(ks(KeyType.ENTER), new AtomicBoolean());
        // cursor sits on VIEW_TOOLS (index 0).
        d.handleKey(ks(KeyType.ENTER), new AtomicBoolean());

        assertEquals(MCPSettingsDialog.NavState.TOOLS, d.navStateForTest());
    }

    @Test
    void reconnectAndToggleActions_delegateToHostCallback() {
        MCPSettingsDialog d = new MCPSettingsDialog();
        AtomicReference<MCPSettingsDialog.MenuAction> lastAction = new AtomicReference<>();
        AtomicReference<String> lastServer = new AtomicReference<>();
        d.setActionHandler((a, s) -> {
            lastAction.set(a);
            lastServer.set(s.name());
        });
        d.show(configWithScopes(new Server[]{ stdioServer("s1", false) }),
            Set.of("s1"), () -> {});
        d.handleKey(ks(KeyType.ENTER), new AtomicBoolean());
        // Move cursor to RECONNECT (index 1) then Enter.
        d.handleKey(ks(KeyType.ARROW_DOWN), new AtomicBoolean());
        d.handleKey(ks(KeyType.ENTER), new AtomicBoolean());

        assertEquals(MCPSettingsDialog.MenuAction.RECONNECT, lastAction.get(),
            "RECONNECT must fire the host callback (side-effect lives in M1.3)");
        assertEquals("s1", lastServer.get());
    }

    @Test
    void agentServer_routesToAgentMenu_notServerMenu() {
        MCPSettingsDialog d = new MCPSettingsDialog();
        d.show(configWithScopes(new Server[]{ agentServer("code-reviewer") }),
            Set.of("code-reviewer"), () -> {});
        d.handleKey(ks(KeyType.ENTER), new AtomicBoolean());

        assertEquals(MCPSettingsDialog.NavState.AGENT_MENU, d.navStateForTest(),
            "transportType==\"agent\" must route to AGENT_MENU");
        assertEquals(
            List.of(MCPSettingsDialog.MenuAction.VIEW_TOOLS, MCPSettingsDialog.MenuAction.BACK),
            d.menuItemsForTest(),
            "agent menu offers View Tools + Back (no enable/disable/reconnect)");
    }

    @Test
    void esc_inServerMenu_popsToList() {
        MCPSettingsDialog d = new MCPSettingsDialog();
        d.show(configWithScopes(new Server[]{ stdioServer("s1", false) }),
            Set.of("s1"), () -> {});
        d.handleKey(ks(KeyType.ENTER), new AtomicBoolean());
        assertEquals(MCPSettingsDialog.NavState.SERVER_MENU, d.navStateForTest());

        d.handleKey(ks(KeyType.ESCAPE), new AtomicBoolean());
        assertEquals(MCPSettingsDialog.NavState.LIST, d.navStateForTest());
        assertNull(d.selectedServerForTest());
    }

    @Test
    void refresh_preservesServerSelection_whenServerStillExists() {
        MCPSettingsDialog d = new MCPSettingsDialog();
        d.show(configWithScopes(new Server[]{
                stdioServer("a", false), stdioServer("b", false) }),
            Set.of("a"), () -> {});
        d.handleKey(ks(KeyType.ENTER), new AtomicBoolean());
        assertEquals("a", d.selectedServerForTest().name());

        // Refresh with the same snapshot — should stay on SERVER_MENU.
        d.refresh(configWithScopes(new Server[]{
                stdioServer("a", false), stdioServer("b", false) }),
            Set.of("a"));

        assertEquals(MCPSettingsDialog.NavState.SERVER_MENU, d.navStateForTest());
        assertEquals("a", d.selectedServerForTest().name());
    }

    @Test
    void refresh_dropsSelection_whenServerDisappears() {
        MCPSettingsDialog d = new MCPSettingsDialog();
        d.show(configWithScopes(new Server[]{
                stdioServer("a", false), stdioServer("b", false) }),
            Set.of("a"), () -> {});
        d.handleKey(ks(KeyType.ENTER), new AtomicBoolean());

        // Refresh dropping the selected server.
        d.refresh(configWithScopes(new Server[]{ stdioServer("b", false) }),
            Set.of());

        assertEquals(MCPSettingsDialog.NavState.LIST, d.navStateForTest(),
            "removing the selected server must pop back to LIST");
        assertNull(d.selectedServerForTest());
    }

    @Test
    void refresh_rebuildsServerMenuItems_whenDisabledFlagFlips() {
        // Regression: after runToggleDisabled updates disabledMcpServers and calls
// refresh, the SERVER_MENU must show the actions for the *new*
        // state (Enable + Back) — not the stale ones from before the toggle.
        MCPSettingsDialog d = new MCPSettingsDialog();
        d.show(configWithScopes(new Server[]{ stdioServer("a", false) }),
            Set.of("a"), () -> {});
        d.handleKey(ks(KeyType.ENTER), new AtomicBoolean());
        // Before: connected + enabled → View tools / Reconnect / Disable / Back
        assertTrue(d.menuItemsForTest().contains(MCPSettingsDialog.MenuAction.DISABLE));
        assertFalse(d.menuItemsForTest().contains(MCPSettingsDialog.MenuAction.ENABLE));

        // Host disabled the server → new snapshot + connection dropped.
        d.refresh(configWithScopes(new Server[]{ stdioServer("a", true) }),
            Set.of());

        // After: disabled → Enable + Back only (no View tools / Reconnect).
        assertTrue(d.menuItemsForTest().contains(MCPSettingsDialog.MenuAction.ENABLE),
            "disabling must flip the menu to expose Enable");
        assertFalse(d.menuItemsForTest().contains(MCPSettingsDialog.MenuAction.DISABLE));
        assertFalse(d.menuItemsForTest().contains(MCPSettingsDialog.MenuAction.RECONNECT));
        assertFalse(d.menuItemsForTest().contains(MCPSettingsDialog.MenuAction.VIEW_TOOLS));
    }

    @Test
    void emptyConfig_stillRenders_andEscClosesCleanly() {
        MCPSettingsDialog d = new MCPSettingsDialog();
        AtomicBoolean closed = new AtomicBoolean();
        d.show(new Snapshot(List.of(), List.of()), Set.of(), () -> closed.set(true));
        assertTrue(d.serversForTest().isEmpty());
        // Enter/Down should be inert when no servers.
        d.handleKey(ks(KeyType.ENTER), new AtomicBoolean());
        assertEquals(MCPSettingsDialog.NavState.LIST, d.navStateForTest());
        // Esc closes.
        d.handleKey(ks(KeyType.ESCAPE), new AtomicBoolean());
        assertTrue(closed.get());
    }

    // ── M1.3 Reconnect overlay ───────────────────────────────────────────────

    @Test
    void beginReconnect_locksMenu_untilResultArrives() {
        MCPSettingsDialog d = new MCPSettingsDialog();
        d.show(configWithScopes(new Server[]{ stdioServer("s1", false) }),
            Set.of("s1"), () -> {});
        d.handleKey(ks(KeyType.ENTER), new AtomicBoolean());
        int menuBefore = d.menuIdxForTest();

        d.beginReconnect("s1");
        assertEquals(MCPSettingsDialog.ReconnectSubstate.IN_PROGRESS,
            d.reconnectSubstateForTest());

        // Arrow keys during IN_PROGRESS must not move the menu cursor.
        d.handleKey(ks(KeyType.ARROW_DOWN), new AtomicBoolean());
        d.handleKey(ks(KeyType.ARROW_UP), new AtomicBoolean());
        assertEquals(menuBefore, d.menuIdxForTest(),
            "cursor must not move while reconnecting");
        // Esc during IN_PROGRESS must not pop back to LIST — an async
        // result is still in flight for the current selection.
        d.handleKey(ks(KeyType.ESCAPE), new AtomicBoolean());
        assertEquals(MCPSettingsDialog.NavState.SERVER_MENU, d.navStateForTest());
    }

    @Test
    void endReconnectSuccess_returnsToServerMenu_noOverlay() {
        MCPSettingsDialog d = new MCPSettingsDialog();
        d.show(configWithScopes(new Server[]{ stdioServer("s1", false) }),
            Set.of("s1"), () -> {});
        d.handleKey(ks(KeyType.ENTER), new AtomicBoolean());
        d.beginReconnect("s1");
        d.endReconnectSuccess("Successfully reconnected to s1");


        // onComplete (which the host maps to a postSystemMessage).
        assertEquals(MCPSettingsDialog.ReconnectSubstate.NONE,
            d.reconnectSubstateForTest());
        assertTrue(d.reconnectSuccessForTest(),
            "success flag stays set for host inspection");
        assertTrue(Strings.CS.contains(d.reconnectMessageForTest(), "Successfully"));
        assertEquals(MCPSettingsDialog.NavState.SERVER_MENU, d.navStateForTest(),
            "user is dropped back onto the server menu, not held in an overlay");
    }

    @Test
    void endReconnectFailure_flagsFailureAndClearsSubstate() {
        MCPSettingsDialog d = new MCPSettingsDialog();
        d.show(configWithScopes(new Server[]{ stdioServer("s1", false) }),
            Set.of(), () -> {});
        d.handleKey(ks(KeyType.ENTER), new AtomicBoolean());
        d.beginReconnect("s1");
        d.endReconnectFailure("Connection refused");

        assertEquals(MCPSettingsDialog.ReconnectSubstate.NONE,
            d.reconnectSubstateForTest());
        assertFalse(d.reconnectSuccessForTest());
        assertTrue(Strings.CS.contains(d.reconnectMessageForTest(), "refused"));
    }

    // ── M1.5 Tool list + detail ──────────────────────────────────────────────

    @Test
    void viewTools_pullsFromToolsProvider() {
        MCPSettingsDialog d = new MCPSettingsDialog();
        Tool t1 = new Tool("greet", "Say hello", jsonSchema("{\"type\":\"object\"}"));
        Tool t2 = new Tool("farewell", "Say goodbye", jsonSchema("{\"type\":\"object\"}"));
        d.setToolsProvider(name -> Strings.CS.equals("s1", name) ? List.of(t1, t2) : List.of());
        d.show(configWithScopes(new Server[]{ stdioServer("s1", false) }),
            Set.of("s1"), () -> {});
        d.handleKey(ks(KeyType.ENTER), new AtomicBoolean());
        // Cursor on VIEW_TOOLS.
        d.handleKey(ks(KeyType.ENTER), new AtomicBoolean());

        assertEquals(MCPSettingsDialog.NavState.TOOLS, d.navStateForTest());
        awaitTools(d, 2);
        assertEquals(List.of("greet", "farewell"),
            d.currentToolsForTest().stream().map(Tool::name).toList());
        assertEquals(0, d.toolIdxForTest());
    }

    @Test
    void viewTools_doesNotWaitForAColdProvider() throws Exception {
        MCPSettingsDialog d = new MCPSettingsDialog();
        CountDownLatch providerStarted = new CountDownLatch(1);
        CountDownLatch releaseProvider = new CountDownLatch(1);
        d.setToolsProvider(_ -> {
            providerStarted.countDown();
            try {
                releaseProvider.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
            return List.of(new Tool("slow", "", jsonSchema("{}")));
        });
        d.show(configWithScopes(new Server[]{ stdioServer("s1", false) }),
            Set.of("s1"), () -> {});
        d.handleKey(ks(KeyType.ENTER), new AtomicBoolean());

        long startedAt = System.nanoTime();
        d.handleKey(ks(KeyType.ENTER), new AtomicBoolean());
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

        assertTrue(elapsedMs < 100, "VIEW_TOOLS waited " + elapsedMs + "ms for MCP I/O");
        assertEquals(MCPSettingsDialog.NavState.TOOLS, d.navStateForTest());
        assertTrue(providerStarted.await(1, TimeUnit.SECONDS));
        releaseProvider.countDown();
    }

    @Test
    void toolsList_arrowsMove_enterOpensDetail_escGoesBack() {
        MCPSettingsDialog d = new MCPSettingsDialog();
        Tool t1 = new Tool("a", "", jsonSchema("{}"));
        Tool t2 = new Tool("b", "", jsonSchema("{}"));
        d.setToolsProvider(_ -> List.of(t1, t2));
        d.show(configWithScopes(new Server[]{ stdioServer("s1", false) }),
            Set.of("s1"), () -> {});
        d.handleKey(ks(KeyType.ENTER), new AtomicBoolean());   // open menu
        d.handleKey(ks(KeyType.ENTER), new AtomicBoolean());   // → TOOLS
        awaitTools(d, 2);

        d.handleKey(ks(KeyType.ARROW_DOWN), new AtomicBoolean());
        assertEquals(1, d.toolIdxForTest());
        d.handleKey(ks(KeyType.ENTER), new AtomicBoolean());
        assertEquals(MCPSettingsDialog.NavState.TOOL_DETAIL, d.navStateForTest());

        d.handleKey(ks(KeyType.ESCAPE), new AtomicBoolean());
        assertEquals(MCPSettingsDialog.NavState.TOOLS, d.navStateForTest());
        d.handleKey(ks(KeyType.ESCAPE), new AtomicBoolean());
        assertEquals(MCPSettingsDialog.NavState.SERVER_MENU, d.navStateForTest());
    }

    @Test
    void viewTools_noProvider_showsEmptyList() {
        MCPSettingsDialog d = new MCPSettingsDialog();
        d.show(configWithScopes(new Server[]{ stdioServer("s1", false) }),
            Set.of("s1"), () -> {});
        d.handleKey(ks(KeyType.ENTER), new AtomicBoolean());
        d.handleKey(ks(KeyType.ENTER), new AtomicBoolean());

        assertEquals(MCPSettingsDialog.NavState.TOOLS, d.navStateForTest());
        assertTrue(d.currentToolsForTest().isEmpty(),
            "no provider wired → empty tool list, not exception");
    }

    @Test
    void viewTools_providerThrows_isSwallowed() {
        MCPSettingsDialog d = new MCPSettingsDialog();
        d.setToolsProvider(_ -> { throw new RuntimeException("boom"); });
        d.show(configWithScopes(new Server[]{ stdioServer("s1", false) }),
            Set.of("s1"), () -> {});
        d.handleKey(ks(KeyType.ENTER), new AtomicBoolean());
        assertDoesNotThrow(() -> d.handleKey(ks(KeyType.ENTER), new AtomicBoolean()));
        assertTrue(d.currentToolsForTest().isEmpty());
    }

    private static JsonNode jsonSchema(String json) {
        try {
            return JsonUtils.getMapper().readTree(json);
        } catch (Exception _) {
          return JsonUtils.getMapper().createObjectNode();
        }
    }

    private static void awaitTools(MCPSettingsDialog dialog, int count) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (dialog.currentToolsForTest().size() != count && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertEquals(count, dialog.currentToolsForTest().size());
    }
}
