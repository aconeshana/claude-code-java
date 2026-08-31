package com.claudecode.services.config;

import com.claudecode.services.plugins.marketplace.MarketplaceSource;
import com.claudecode.services.plugins.marketplace.PluginSettingsStore;
import com.claudecode.core.state.CwdState;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;


class PluginSettingsPolicySourceTest {

    @TempDir
    Path tempDir;

    private final Path originalCwd = CwdState.getOriginalCwd();

    @AfterEach
    void restoreState() throws Exception {
        clearMdmCache();
        SettingsSources.clearFlagSettings();
        SettingsSources.configureAllowedSettingSources(true, true, true,
            originalCwd == null ? tempDir.toString() : originalCwd.toString());
        if (originalCwd == null) CwdState.clearForTesting();
        else CwdState.setOriginalCwd(originalCwd);
    }

    @Test
    void policyMarketplaceAllowlistUsesAdminMdmBeforeManagedFile() throws Exception {
        Path cwd = tempDir.resolve("workspace");
        CwdState.setOriginalCwd(cwd);
        SettingsSources.configureAllowedSettingSources(List.of(), cwd.toString(), false);

        ObjectNode admin = JsonUtils.getMapper().createObjectNode();
        admin.putArray("strictKnownMarketplaces").addObject()
            .put("source", "github").put("repo", "approved/repository");
        installCachedAdminResult(new MdmSettingsStore.ReadResult(admin, List.of()));

        PluginSettingsStore store = PluginSettingsStore.standard(cwd.toString());
        List<MarketplaceSource> allowlist = store.strictKnownMarketplaces();

        assertEquals(List.of(new MarketplaceSource.Github("approved/repository")), allowlist);
    }

    private static void installCachedAdminResult(MdmSettingsStore.ReadResult result)
            throws Exception {
        Field cache = MdmSettingsStore.class.getDeclaredField("adminCache");
        cache.setAccessible(true);
        cache.set(null, result);
        Method environmentKey = MdmSettingsStore.class.getDeclaredMethod("cacheEnvironmentKey");
        environmentKey.setAccessible(true);
        Field cacheEnvironment = MdmSettingsStore.class.getDeclaredField("adminCacheEnvironment");
        cacheEnvironment.setAccessible(true);
        cacheEnvironment.set(null, environmentKey.invoke(null));
    }

    private static void clearMdmCache() throws Exception {
        Method clear = MdmSettingsStore.class.getDeclaredMethod("clearCache");
        clear.setAccessible(true);
        clear.invoke(null);
    }
}
