package com.claudecode.ui.lanterna.plugin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;


class PluginRouteTest {

    @Test
    void nullOrBlankArgs_routeToMenu() {
        assertEquals(PluginRoute.Type.MENU, PluginRoute.parse(null).type());
        assertEquals(PluginRoute.Type.MENU, PluginRoute.parse("   ").type());
    }

    @Test
    void helpAliases_routeToHelp() {
        assertEquals(PluginRoute.Type.HELP, PluginRoute.parse("help").type());
        assertEquals(PluginRoute.Type.HELP, PluginRoute.parse("--help").type());
        assertEquals(PluginRoute.Type.HELP, PluginRoute.parse("-h").type());
    }

    @Test
    void installWithoutTarget_hasNoPluginOrMarketplace() {
        PluginRoute route = PluginRoute.parse("install");
        assertEquals(PluginRoute.Type.INSTALL, route.type());
        assertNull(route.plugin());
        assertNull(route.marketplace());
    }

    @Test
    void installPluginAtMarketplace_splitsBoth() {
        PluginRoute route = PluginRoute.parse("install formatter@my-market");
        assertEquals("formatter", route.plugin());
        assertEquals("my-market", route.marketplace());
    }

    @Test
    void installUrlOrPath_treatedAsMarketplace() {
        assertEquals("https://x.com/m.json", PluginRoute.parse("install https://x.com/m.json").marketplace());
        assertEquals("owner/repo", PluginRoute.parse("install owner/repo").marketplace());
        assertNull(PluginRoute.parse("install owner/repo").plugin());
    }

    @Test
    void installBareName_treatedAsPlugin() {
        PluginRoute route = PluginRoute.parse("i formatter");
        assertEquals(PluginRoute.Type.INSTALL, route.type());
        assertEquals("formatter", route.plugin());
        assertNull(route.marketplace());
    }

    @Test
    void manageEnableDisableUninstall_carryPluginName() {
        assertEquals(PluginRoute.Type.MANAGE, PluginRoute.parse("manage").type());
        assertEquals("x", PluginRoute.parse("uninstall x").plugin());
        assertEquals("x", PluginRoute.parse("enable x").plugin());
        assertEquals("x", PluginRoute.parse("disable x").plugin());
        assertEquals(PluginRoute.Type.DISABLE, PluginRoute.parse("disable x").type());
    }

    @Test
    void validate_joinsRestOfLineAsPath() {
        assertEquals("/tmp/my plugin", PluginRoute.parse("validate /tmp/my plugin").path());
        assertNull(PluginRoute.parse("validate").path());
    }

    @Test
    void marketplaceActions_parseTargets() {
        PluginRoute add = PluginRoute.parse("marketplace add owner/repo");
        assertEquals("add", add.action());
        assertEquals("owner/repo", add.marketplace());
        assertEquals("remove", PluginRoute.parse("market rm old").action());
        assertEquals("old", PluginRoute.parse("market rm old").marketplace());
        assertEquals("update", PluginRoute.parse("marketplace update old").action());
        assertEquals("list", PluginRoute.parse("marketplace list").action());
        assertNull(PluginRoute.parse("marketplace").action());
    }

    @Test
    void unknownCommand_fallsBackToMenu() {
        assertEquals(PluginRoute.Type.MENU, PluginRoute.parse("frobnicate").type());
    }
}
