package com.claudecode.services.config;

import com.claudecode.core.config.ClaudePaths;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;


class TrustConfigStoreTest {

    @TempDir Path tempDir;

    @AfterEach
    void reset() {
        TrustConfigStore.resetSessionTrustForTesting();
        TrustConfigStore.resetOriginalCwdForTesting();
        TrustConfigStore.setConfigPathForTesting(ClaudePaths.GLOBAL_JSON);
    }

    private Path configFile() {
        return tempDir.resolve("claude.json");
    }

    private void redirect() {
        TrustConfigStore.setConfigPathForTesting(configFile());
    }

/** Redirects config + anchors the trust key to {@code cwd} (matches compatibility baselineCwd). */
    private void setup(Path cwd) {
        redirect();
        TrustConfigStore.setOriginalCwd(cwd);
    }

    private Path projectCwd() {
        return tempDir.resolve("project").toAbsolutePath().normalize();
    }

    // ── Trust acceptance ─────────────────────────────────────────────────────

    @Test
    void initiallyNotTrusted() {
        setup(projectCwd());
        assertFalse(TrustConfigStore.isTrustAccepted(projectCwd()),
            "a fresh install has no accepted trust");
    }

    @Test
    void acceptThenTrusted_andFileBacked() throws Exception {
        setup(projectCwd());
        Path cwd = projectCwd();
        TrustConfigStore.acceptTrust(cwd);
        assertTrue(TrustConfigStore.isTrustAccepted(cwd), "accepted trust is readable");

        JsonNode root = JsonUtils.readJson(configFile());
        String key = TrustConfigStore.getProjectPathForConfig(cwd);
        JsonNode entry = root.get("projects").get(key);
        assertNotNull(entry, "project entry must be written under the git-root/cwd key");
        assertTrue(entry.get("hasTrustDialogAccepted").asBoolean(),
            "TS field hasTrustDialogAccepted must be set under projects[<key>]");
    }

    @Test
    void parentTrusted_impliesChildTrusted() {
        setup(tempDir.resolve("parent").toAbsolutePath().normalize());
        Path parent = tempDir.resolve("parent").toAbsolutePath().normalize();
        Path child = parent.resolve("sub/project").toAbsolutePath().normalize();
        TrustConfigStore.acceptTrust(parent);
        assertTrue(TrustConfigStore.isTrustAccepted(child),
            "trusting a parent must implicitly trust all descendants (TS ancestor walk)");
    }

    @Test
    void launchProjectTrusted_makesSessionTrustedEverywhere() {

        // (originalCwd), so once the launch project is trusted the whole session is
        // trusted — even a sibling directory the live cwd later moves to. This is the
        // originalCwd-vs-live-cwd split being exercised.
        Path a = tempDir.resolve("a").toAbsolutePath().normalize();
        Path b = tempDir.resolve("b").toAbsolutePath().normalize();
        setup(a);
        TrustConfigStore.acceptTrust(a);
        assertTrue(TrustConfigStore.isTrustAccepted(b),
            "TS trusts the launch project everywhere via the originalCwd-anchored primary key");
    }

    @Test
    void trustKeyMemoizedToFirstLaunchProject_likeTs() {

        // The trust entry remains anchored to the first-seen original cwd so
        // worktree transitions cannot relocate it.
        Path a = tempDir.resolve("a").toAbsolutePath().normalize();
        Path b = tempDir.resolve("b").toAbsolutePath().normalize();
        setup(a);
        TrustConfigStore.acceptTrust(a); // First call anchors the key to `a`.
        setup(b); // Re-pointing original cwd to `b` must not move the trust key.
        assertTrue(TrustConfigStore.isTrustAccepted(b),
            "trust key is memoized to the first launch project; re-pointing does not move it (TS lodash memoize)");
        assertTrue(TrustConfigStore.isTrustAccepted(a),
            "the first launch project remains trusted");
    }

    @Test
    void homeDir_acceptIsSessionOnly_notPersisted() throws Exception {
        // Redirect user.home to tempDir so tempDir is the "home" directory.
        String orig = System.setProperty("user.home", tempDir.toString());
        try {
            setup(tempDir);
            TrustConfigStore.acceptTrust(tempDir);
            assertTrue(TrustConfigStore.isTrustAccepted(tempDir),
                "home trust is active via the in-memory session flag");
            boolean persisted = Files.exists(configFile())
                && JsonUtils.readJson(configFile()) != null
                && JsonUtils.readJson(configFile()).has("projects")
                && !JsonUtils.readJson(configFile()).get("projects").isEmpty();
            assertFalse(persisted,
                "home-dir trust must NOT be persisted to disk (mirrors TS TrustDialog.tsx:174)");
        } finally {
            System.setProperty("user.home", orig);
        }
    }

    @Test
    void recognizesTsShapedTrustEntry() throws Exception {
        setup(projectCwd());
        Path cwd = projectCwd();
        String key = TrustConfigStore.getProjectPathForConfig(cwd);

        String json = "{\"projects\":{\"" + key + "\":{\"hasTrustDialogAccepted\":true}}}";
        Files.writeString(configFile(), json);
        assertTrue(TrustConfigStore.isTrustAccepted(cwd),
            "Java must recognize a TS-written trust entry under the same project key");
    }

    @Test
    void acceptedTrustRemainsLatchedIfMarkerIsRemovedMidSession() throws Exception {
        setup(projectCwd());
        Path cwd = projectCwd();
        TrustConfigStore.acceptTrust(cwd);
        assertTrue(TrustConfigStore.isTrustAccepted(cwd));

        Files.writeString(configFile(), "{}\n");

        assertTrue(TrustConfigStore.isTrustAccepted(cwd),
            "TS checkHasTrustDialogAccepted latches positive trust for the session");
    }

    // ── External CLAUDE.md includes ──────────────────────────────────────────

    @Test
    void externalIncludes_initiallyNotDecided() {
        setup(projectCwd());
        Path cwd = projectCwd();
        assertFalse(TrustConfigStore.hasExternalIncludesApproved(cwd));
        assertFalse(TrustConfigStore.hasExternalIncludesWarningShown(cwd));
    }

    @Test
    void externalIncludes_approve_marksBothFlags() throws Exception {
        setup(projectCwd());
        Path cwd = projectCwd();
        TrustConfigStore.saveExternalIncludesDecision(cwd, true);
        assertTrue(TrustConfigStore.hasExternalIncludesApproved(cwd));
        assertTrue(TrustConfigStore.hasExternalIncludesWarningShown(cwd));
        String key = TrustConfigStore.getProjectPathForConfig(cwd);
        JsonNode entry = JsonUtils.readJson(configFile()).get("projects").get(key);
        assertTrue(entry.get("hasClaudeMdExternalIncludesApproved").asBoolean());
        assertTrue(entry.get("hasClaudeMdExternalIncludesWarningShown").asBoolean());
    }

    @Test
    void externalIncludes_decline_marksWarningShownOnly() {
        setup(projectCwd());
        Path cwd = projectCwd();
        TrustConfigStore.saveExternalIncludesDecision(cwd, false);
        assertFalse(TrustConfigStore.hasExternalIncludesApproved(cwd),
            "decline must NOT approve");
        assertTrue(TrustConfigStore.hasExternalIncludesWarningShown(cwd),
            "decline still marks the warning shown so it is not re-prompted");
    }

    @Test
    void externalIncludes_decisionIsDeepMerged() throws Exception {
        setup(projectCwd());
        Path cwd = projectCwd();
        String key = TrustConfigStore.getProjectPathForConfig(cwd);
        // Pre-existing trust entry for the same project must survive an includes decision.
        Files.writeString(configFile(),
            "{\"projects\":{\"" + key + "\":{\"hasTrustDialogAccepted\":true}}}");
        TrustConfigStore.saveExternalIncludesDecision(cwd, true);
        JsonNode entry = JsonUtils.readJson(configFile()).get("projects").get(key);
        assertTrue(entry.get("hasTrustDialogAccepted").asBoolean(),
            "prior trust entry must be preserved (deep merge, not overwrite)");
        assertTrue(entry.get("hasClaudeMdExternalIncludesApproved").asBoolean());
    }

    // ── GH #3117 auth-loss guard ─────────────────────────────────────────────

    @Test
    void trustWrite_preservesAuthFields() throws Exception {
        setup(projectCwd());
        Files.writeString(configFile(), "{\"primaryApiKey\":\"sk-ant-xyz\",\"projects\":{}}");
        Path cwd = projectCwd();
        TrustConfigStore.acceptTrust(cwd);
        JsonNode root = JsonUtils.readJson(configFile());
        assertEquals("sk-ant-xyz", root.get("primaryApiKey").asText(),
            "auth must survive a trust write (GH #3117 guard)");
        assertTrue(root.get("projects").has(TrustConfigStore.getProjectPathForConfig(cwd)));
    }



    @Test
    void migrateRemovesLegacyTrustedFolders() throws Exception {
        String orig = System.setProperty("user.home", tempDir.toString());
        try {
            Path settings = tempDir.resolve(".claude/settings.json");
            Files.createDirectories(settings.getParent());
            Files.writeString(settings, "{\"trustedFolders\":[\"/foo/bar\"],\"other\":42}");
            TrustConfigStore.migrateRemoveLegacyTrustedFolders();
            JsonNode root = JsonUtils.readJson(settings);
            assertFalse(root.has("trustedFolders"),
                "legacy trustedFolders key must be removed from settings.json");
            assertEquals(42, root.get("other").asInt(), "unrelated keys are preserved");
        } finally {
            System.setProperty("user.home", orig);
        }
    }

    @Test
    void concurrentWrites_sameEntry_noCorruption() throws Exception {

        // originalCwd-anchored project entry, so concurrent trust + external-includes

        // (the WRITE_MONITOR lock + atomic rename). The distinct-key variant of this
        // guarantee (concurrent writes to DIFFERENT entries) is covered at the shared
        // primitive in GlobalConfigStoreTest.concurrentDistinctKeyWrites.
        Path cwd = projectCwd();
        setup(cwd);
        int n = 30;
        ExecutorService ex = Executors.newFixedThreadPool(8);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            final int idx = i;
            futures.add(ex.submit(() -> {
                TrustConfigStore.acceptTrust(cwd);
                TrustConfigStore.saveExternalIncludesDecision(cwd, idx % 2 == 0);
                assertTrue(TrustConfigStore.isTrustAccepted(cwd), "trust must be readable under concurrency");
                return null;
            }));
        }
        for (Future<?> f : futures) {
            f.get();
        }
        ex.shutdown();
        JsonNode root = JsonUtils.readJson(configFile());
        assertNotNull(root, "config file must be valid JSON after concurrent writes");
        JsonNode projects = root.get("projects");
        assertEquals(1, projects.size(), "all trust writes target the single originalCwd project entry");
        JsonNode entry = projects.elements().next();
        assertTrue(entry.get("hasTrustDialogAccepted").asBoolean(), "trust flag persisted");
        assertTrue(entry.get("hasClaudeMdExternalIncludesWarningShown").asBoolean(), "warning-shown flag persisted");
    }
}
