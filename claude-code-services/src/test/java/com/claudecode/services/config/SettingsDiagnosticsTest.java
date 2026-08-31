package com.claudecode.services.config;

import com.claudecode.permissions.RuleSource;
import com.claudecode.core.serialization.JsonUtils;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.apache.commons.lang3.Strings;

/**
 * Contracts for the diagnostic aggregate distinct from the effective snapshot path.
 */
class SettingsDiagnosticsTest {

    @TempDir
    Path tempDir;

    private final String originalHome = System.getProperty("user.home");
    private final String originalDir = System.getProperty("user.dir");
    private final String originalOs = System.getProperty("os.name");

    @AfterEach
    void restoreState() {
        if (originalHome != null) System.setProperty("user.home", originalHome);
        if (originalDir != null) System.setProperty("user.dir", originalDir);
        if (originalOs != null) System.setProperty("os.name", originalOs);
        MdmSettingsStore.clearCache();
        SettingsSources.clearFlagSettings();
        SettingsSources.clearPluginSettingsBase();
        SettingsSources.configureAllowedSettingSources(true, true, true,
            originalDir == null ? tempDir.toString() : originalDir);
        SettingsDiagnostics.invalidateForReload();
    }

    @Test
    void keepsValidationErrorsSeparateFromTheAcceptedEffectiveSettings() throws Exception {
        Path home = tempDir.resolve("home");
        Path cwd = tempDir.resolve("project");
        Files.createDirectories(home.resolve(".claude"));
        Files.createDirectories(cwd);
        Files.writeString(home.resolve(".claude/settings.json"),
            "{\"model\":\"valid-model\",\"language\":42}");
        System.setProperty("user.home", home.toString());
        System.setProperty("user.dir", cwd.toString());
        SettingsSources.configureAllowedSettingSources(List.of(RuleSource.USER_SETTINGS), cwd.toString());

        SettingsWithErrors result = SettingsDiagnostics.loadSettingsWithErrors(cwd.toString());

        assertFalse(result.errors().isEmpty());
        assertFalse(result.settings().has("model"),
            "a schema-invalid source must not contribute a partial effective snapshot");
    }

    @Test
    void retainsAdminMdmErrorsWhenPolicyFallsThroughToLowerPrioritySources() throws Exception {
        System.setProperty("os.name", "Linux");
        SettingsValidationError mdmError = new SettingsValidationError(
            "managed preferences", "model", "invalid managed model");
        installCachedAdminResult(new MdmSettingsStore.ReadResult(
            JsonUtils.getMapper().createObjectNode(), List.of(mdmError)));
        SettingsSources.configureAllowedSettingSources(List.of(), tempDir.toString(), false);

        SettingsWithErrors result = SettingsDiagnostics.loadSettingsWithErrors(tempDir.toString());

        assertTrue(result.errors().contains(mdmError),
            "MDM diagnostics must survive empty-settings fallback to file/HKCU sources");
    }

    @Test
    void cleanupGuardSeesRawRetentionAlongsideSettingsValidationFailure() throws Exception {
        Path home = tempDir.resolve("cleanup-home");
        Files.createDirectories(home.resolve(".claude"));
        Files.writeString(home.resolve(".claude/settings.json"),
            "{\"cleanupPeriodDays\":7,\"permissions\":{\"allow\":[123]}}");
        System.setProperty("user.home", home.toString());
        System.setProperty("user.dir", home.toString());
        SettingsSources.configureAllowedSettingSources(List.of(RuleSource.USER_SETTINGS), home.toString());

        assertTrue(SettingsDiagnostics.rawSettingsContainsKey("cleanupPeriodDays"));
        assertTrue(SettingsDiagnostics.shouldSkipFileHistoryCleanup());
    }

    @Test
    void allErrorsRespectDisabledMcpSettingSources() throws Exception {
        Path home = tempDir.resolve("mcp-home");
        Path project = tempDir.resolve("mcp-project");
        Files.createDirectories(home.resolve(".claude"));
        Files.createDirectories(project);
        Files.writeString(project.resolve(".mcp.json"), "{not valid json");
        System.setProperty("user.home", home.toString());
        System.setProperty("user.dir", project.toString());
        SettingsSources.configureAllowedSettingSources(
            List.of(RuleSource.USER_SETTINGS), project.toString());

        SettingsWithErrors result = SettingsDiagnostics.getSettingsWithAllErrors();

        assertTrue(result.errors().stream().noneMatch(error ->
            error.file().equals(project.resolve(".mcp.json").toString())),
            "disabled project MCP diagnostics must not leak through getSettingsWithAllErrors");
    }

    @Test
    void allErrorsDoNotExposeMergedMcpOverrideBookkeeping() throws Exception {
        Path project = tempDir.resolve("mcp-override-project");
        Path child = project.resolve("child");
        Files.createDirectories(child);
        String server = "{\"command\":\"node\",\"args\":[\"server.js\"]}";
        Files.writeString(project.resolve(".mcp.json"),
            "{\"mcpServers\":{\"shared\":" + server + "}}");
        Files.writeString(child.resolve(".mcp.json"),
            "{\"mcpServers\":{\"shared\":" + server + "}}");
        System.setProperty("user.dir", child.toString());
        SettingsSources.configureAllowedSettingSources(
            List.of(RuleSource.PROJECT_SETTINGS), child.toString());

        SettingsWithErrors result = SettingsDiagnostics.getSettingsWithAllErrors();

        assertTrue(result.errors().stream().noneMatch(error ->Strings.CS.contains(
            error.message(), "overrides an earlier entry")),
            "cross-file merge bookkeeping is not a TS validation error");
    }

    @Test
    void loadingScopeShortCircuitsRecursionAndClearsAfterFailure() {
        assertFalse(SettingsDiagnostics.isLoadingSettings());

        SettingsWithErrors recursive = SettingsDiagnostics.withLoadingSettings(() -> {
            assertTrue(SettingsDiagnostics.isLoadingSettings());
            return SettingsDiagnostics.loadSettingsWithErrors(tempDir.toString());
        });

        assertEquals(0, recursive.settings().size());
        assertTrue(recursive.errors().isEmpty());
        assertFalse(SettingsDiagnostics.isLoadingSettings());

        assertThrows(IllegalStateException.class, () ->
            SettingsDiagnostics.withLoadingSettings(() -> {
                assertTrue(SettingsDiagnostics.isLoadingSettings());
                throw new IllegalStateException("boom");
            }));
        assertFalse(SettingsDiagnostics.isLoadingSettings());
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
}
