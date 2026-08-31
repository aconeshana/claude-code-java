package com.claudecode.services.model;

import com.claudecode.services.config.SettingsSources;
import com.claudecode.core.serialization.JsonUtils;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelAllowlistTest {

    @TempDir
    Path tempDir;

    private String originalCwd;

    @BeforeEach
    void setUp() {
        originalCwd = System.getProperty("user.dir");
        System.setProperty("user.dir", tempDir.toString());
        SettingsSources.configureAllowedSettingSources(false, false, false, tempDir.toString());
    }

    @AfterEach
    void tearDown() {
        SettingsSources.clearFlagSettings();
        SettingsSources.configureAllowedSettingSources(true, true, true,
            originalCwd == null ? tempDir.toString() : originalCwd);
        if (originalCwd != null) System.setProperty("user.dir", originalCwd);
    }

    @Test
    void absentAllowlistAllowsEveryModel() {
        assertTrue(ModelAllowlist.isAllowed("claude-custom-model"));
    }

    @Test
    void familyAliasAllowsFamilyButSpecificEntriesNarrowIt() throws Exception {
        SettingsSources.applyFlagSettings(JsonUtils.getMapper().readTree(
            "{\"availableModels\":[\"opus\"]}"));
        assertTrue(ModelAllowlist.isAllowed("claude-opus-4-6-20260101"));
        assertFalse(ModelAllowlist.isAllowed("claude-sonnet-4-6"));

        SettingsSources.applyFlagSettings(JsonUtils.getMapper().readTree(
            "{\"availableModels\":[\"opus\",\"opus-4-5\"]}"));
        assertTrue(ModelAllowlist.isAllowed("claude-opus-4-5-20251101"));
        assertFalse(ModelAllowlist.isAllowed("claude-opus-4-6"));
    }

    @Test
    void aliasesAndVersionPrefixesMatchLikeTs() throws Exception {
        SettingsSources.applyFlagSettings(JsonUtils.getMapper().readTree(
            "{\"availableModels\":[\"sonnet-4-6\",\"opusplan\"]}"));
        assertFalse(ModelAllowlist.isAllowed("sonnet"));
        assertTrue(ModelAllowlist.isAllowed("claude-sonnet-4-6-20260101"));
        assertTrue(ModelAllowlist.isAllowed("opusplan"));
        assertFalse(ModelAllowlist.isAllowed("claude-sonnet-4-5"));
    }

    @Test
    void emptyAllowlistBlocksSpecifiedModels() throws Exception {
        SettingsSources.applyFlagSettings(JsonUtils.getMapper().readTree(
            "{\"availableModels\":[]}"));
        assertFalse(ModelAllowlist.isAllowed("sonnet"));
    }

    @Test
    void providerOverrideIsComparedByCanonicalModelId() throws Exception {
        SettingsSources.applyFlagSettings(JsonUtils.getMapper().readTree(
            "{\"modelOverrides\":{\"claude-opus-4-6\":\"arn:aws:profile\"},"
                + "\"availableModels\":[\"claude-opus-4-6\"]}"));
        assertTrue(ModelAllowlist.isAllowed("arn:aws:profile"));
    }

    @Test
    void fableFamilyAliasMatchesReleasedFamilyModels() throws Exception {
        SettingsSources.applyFlagSettings(JsonUtils.getMapper().readTree(
            "{\"availableModels\":[\"fable\"]}"));

        assertTrue(ModelAllowlist.isAllowed("claude-fable-5-20260801"));
        assertFalse(ModelAllowlist.isAllowed("claude-sonnet-5-20260801"));
    }

    @Test
    void gpt56AliasesAndConcreteIdsMatchSymmetrically() throws Exception {
        SettingsSources.applyFlagSettings(JsonUtils.getMapper().readTree(
            "{\"availableModels\":[\"gpt-5.6-sol\",\"luna\"]}"));

        assertTrue(ModelAllowlist.isAllowed("sol"));
        assertTrue(ModelAllowlist.isAllowed("gpt-5.6-luna"));
        assertFalse(ModelAllowlist.isAllowed("gpt-5.6-codex"));
    }
}
