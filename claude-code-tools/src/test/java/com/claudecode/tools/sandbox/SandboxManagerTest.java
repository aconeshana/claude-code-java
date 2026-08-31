package com.claudecode.tools.sandbox;

import org.apache.commons.lang3.Strings;

import com.claudecode.core.engine.SandboxConfig;
import com.claudecode.core.engine.SandboxDecision;
import com.claudecode.core.platform.Platform;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import com.claudecode.tools.FakeSandboxBackend;

/**
 * Tests the shared {@link SandboxManager#decide} logic and the platform backend
 * argv shapes. Backend {@code available} depends on host tooling, but
 * {@code wrap} does not invoke it, so argv shapes are asserted directly.
 */
class SandboxManagerTest {

    private static final Path CWD = Path.of("/work");

    private SandboxConfig cfg(boolean enabled, boolean failIfUnavailable, List<String> excluded) {
        return cfg(enabled, failIfUnavailable, true, excluded);
    }

    private SandboxConfig cfg(boolean enabled, boolean failIfUnavailable, boolean allowUnsandboxed, List<String> excluded) {
        ObjectNode n = new ObjectNode(JsonNodeFactory.instance);
        n.put("enabled", enabled);
        n.put("failIfUnavailable", failIfUnavailable);
        n.put("allowUnsandboxedCommands", allowUnsandboxed);
        if (excluded != null) {
            ArrayNode a = n.putArray("excludedCommands");
            excluded.forEach(a::add);
        }
        return SandboxConfig.fromJson(n);
    }

    private SandboxConfig cfgWithPlatforms(boolean enabled, List<String> enabledPlatforms) {
        ObjectNode n = new ObjectNode(JsonNodeFactory.instance);
        n.put("enabled", enabled);
        n.put("failIfUnavailable", true);
        if (enabledPlatforms != null) {
            ArrayNode a = n.putArray("enabledPlatforms");
            enabledPlatforms.forEach(a::add);
        }
        return SandboxConfig.fromJson(n);
    }


    private static String tsPlatformAlias() {
        return switch (Platform.CURRENT) {
            case DARWIN -> "macos";
            case WIN32  -> "windows";
            case LINUX  -> "wsl";
            default     -> "other";
        };
    }

    private SandboxConfig cfgWithDomains(List<String> domains) {
        ObjectNode net = new ObjectNode(JsonNodeFactory.instance);
        ArrayNode a = net.putArray("allowedDomains");
        domains.forEach(a::add);
        ObjectNode root = new ObjectNode(JsonNodeFactory.instance);
        root.put("enabled", true);
        root.set("network", net);
        return SandboxConfig.fromJson(root);
    }

    private SandboxConfig cfgWithFilesystem(List<String> allowWrite) {
        ObjectNode fs = new ObjectNode(JsonNodeFactory.instance);
        ArrayNode a = fs.putArray("allowWrite");
        allowWrite.forEach(a::add);
        ObjectNode root = new ObjectNode(JsonNodeFactory.instance);
        root.put("enabled", true);
        root.set("filesystem", fs);
        return SandboxConfig.fromJson(root);
    }

    private static String currentPlatformName() {
        return switch (Platform.CURRENT) {
            case DARWIN -> "darwin";
            case WIN32  -> "win32";
            case LINUX  -> "linux";
            default     -> "other";
        };
    }

    private static String otherPlatformName() {
        return switch (Platform.CURRENT) {
            case DARWIN -> "linux";
            case LINUX  -> "darwin";
            default     -> "darwin";
        };
    }

// ── decide logic (FakeSandboxBackend controls availability) ─────────────

    @Test
    void disabledConfig_isAlwaysUnsandboxed() {
        SandboxManager m = new FakeSandboxBackend(true);
        SandboxConfig c = cfg(false, true, null);
        assertEquals(SandboxDecision.DecisionKind.RUN_UNSANDBOXED,
            m.decide("rm -rf /", false, c).kind());
        assertEquals(SandboxDecision.DecisionKind.RUN_UNSANDBOXED,
            m.decide("rm -rf /", true, c).kind());
    }

    @Test
    void nullConfig_isUnsandboxed() {
        SandboxManager m = new FakeSandboxBackend(true);
        assertEquals(SandboxDecision.DecisionKind.RUN_UNSANDBOXED,
            m.decide("echo hi", false, null).kind());
    }

    @Test
    void enabled_excludedCommand_isUnsandboxed() {
        SandboxManager m = new FakeSandboxBackend(true);
        SandboxConfig c = cfg(true, true, List.of("git status"));
        assertEquals(SandboxDecision.DecisionKind.RUN_UNSANDBOXED,
            m.decide("git status --short", false, c).kind());
        // Non-matching command still sandboxes.
        assertEquals(SandboxDecision.DecisionKind.RUN_SANDBOXED,
            m.decide("rm -rf /", false, c).kind());
    }

    @Test
    void enabled_dangerouslyDisableSandbox_isUnsandboxed() {
        SandboxManager m = new FakeSandboxBackend(true);
        SandboxConfig c = cfg(true, true, null);
        assertEquals(SandboxDecision.DecisionKind.RUN_UNSANDBOXED,
            m.decide("rm -rf /", true, c).kind());
    }

    @Test
    void enabled_dangerouslyDisableSandbox_blockedWhenDisallowed_staysSandboxed() {
        // allowUnsandboxedCommands=false → the model cannot opt out of the sandbox.
        SandboxManager m = new FakeSandboxBackend(true);
        SandboxConfig c = cfg(true, true, false, null);
        assertEquals(SandboxDecision.DecisionKind.RUN_SANDBOXED,
            m.decide("rm -rf /", true, c).kind());
    }



    @Test
    void excludedCommand_compoundSubcommand_isUnsandboxed() {
        SandboxManager m = new FakeSandboxBackend(true);
        SandboxConfig c = cfg(true, true, true, List.of("rm"));
        assertEquals(SandboxDecision.DecisionKind.RUN_UNSANDBOXED,
            m.decide("npm run build && rm -rf /", false, c).kind());
    }

    @Test
    void excludedCommand_safeWrapperStripped_isUnsandboxed() {
        SandboxManager m = new FakeSandboxBackend(true);
        SandboxConfig c = cfg(true, true, true, List.of("bazel"));
        // wrapper + its flags/duration are peeled: `timeout 30` → `bazel run`.
        assertEquals(SandboxDecision.DecisionKind.RUN_UNSANDBOXED,
            m.decide("timeout 30 bazel run", false, c).kind());
        // only HIJACKABLE leading env vars are peeled (LD_/DYLD_/PATH); after
        // `LD_LIBRARY_PATH=/x ` is stripped the command is `bazel run` → excluded.
        assertEquals(SandboxDecision.DecisionKind.RUN_UNSANDBOXED,
            m.decide("LD_LIBRARY_PATH=/x bazel run", false, c).kind());
        // arbitrary FOO=bar is NOT peeled (potential binary-hijack) → not excluded.
        assertEquals(SandboxDecision.DecisionKind.RUN_SANDBOXED,
            m.decide("FOO=bar bazel run", false, c).kind());
    }

    @Test
    void excludedCommand_prefixDoesNotOverMatch() {

        SandboxManager m = new FakeSandboxBackend(true);
        SandboxConfig c = cfg(true, true, true, List.of("rm"));
        assertEquals(SandboxDecision.DecisionKind.RUN_SANDBOXED,
            m.decide("rmSafe --version", false, c).kind());
    }

    @Test
    void excludedCommand_wildcard_isUnsandboxed() {
        SandboxManager m = new FakeSandboxBackend(true);
        SandboxConfig c = cfg(true, true, true, List.of("git *"));
        assertEquals(SandboxDecision.DecisionKind.RUN_UNSANDBOXED,
            m.decide("git commit -m x", false, c).kind());
    }

    @Test
    void enabled_available_isSandboxed() {
        SandboxManager m = new FakeSandboxBackend(true);
        SandboxConfig c = cfg(true, true, null);
        assertEquals(SandboxDecision.DecisionKind.RUN_SANDBOXED,
            m.decide("echo hi", false, c).kind());
    }

    @Test
    void enabled_unavailable_failIfUnavailable_isReject() {
        SandboxManager m = new FakeSandboxBackend(false);
        SandboxConfig c = cfg(true, true, null);
        SandboxDecision d = m.decide("echo hi", false, c);
        assertTrue(d.isReject());
        assertNotNull(d.rejectReason());
    }

    @Test
    void enabled_unavailable_noFailIfUnavailable_isUnsandboxed() {
        SandboxManager m = new FakeSandboxBackend(false);
        SandboxConfig c = cfg(true, false, null);
        assertEquals(SandboxDecision.DecisionKind.RUN_UNSANDBOXED,
            m.decide("echo hi", false, c).kind());
    }

    // ── backend argv shapes ──────────────────────────────────────────────────

    @Test
    void seatbelt_wrap_shape() {
        SeatbeltSandboxBackend b = new SeatbeltSandboxBackend();
        List<String> argv = b.wrap("echo hi", CWD, SandboxConfig.disabled());
        assertEquals("sandbox-exec", argv.getFirst());
        assertEquals("-p", argv.get(1));
        String profile = argv.get(2);
        assertTrue(Strings.CS.contains(profile, CWD.toString()), "profile should allow write to cwd");
        assertTrue(Strings.CS.contains(profile, "deny network*"), "network denied by default");
        assertEquals("bash", argv.get(3));
        assertEquals("-c", argv.get(4));
        assertEquals("echo hi", argv.get(5));
    }

    @Test
    void bwrap_wrap_shape_defaults() {
        BwrapSandboxBackend b = new BwrapSandboxBackend();
        List<String> argv = b.wrap("echo hi", CWD, SandboxConfig.disabled());
        assertTrue(argv.contains("--unshare-net"), "network isolated by default");
        assertTrue(argv.contains("--ro-bind"));
        assertTrue(argv.contains("/"));
        assertTrue(argv.contains("--bind"));
        assertTrue(argv.contains(CWD.toString()));
        assertEquals("bash", argv.get(argv.size() - 3));
        assertEquals("-c", argv.get(argv.size() - 2));
        assertEquals("echo hi", argv.getLast());
    }

    @Test
    void bwrap_wrap_shape_networkAllowed_noUnshareNet() {
        ObjectNode net = new ObjectNode(JsonNodeFactory.instance);
        net.put("allowLocalBinding", true);
        ObjectNode root = new ObjectNode(JsonNodeFactory.instance);
        root.set("network", net);
        SandboxConfig c = SandboxConfig.fromJson(root);
        BwrapSandboxBackend b = new BwrapSandboxBackend();
        List<String> argv = b.wrap("echo hi", CWD, c);
        assertFalse(argv.contains("--unshare-net"), "network allowed → no net namespace");
    }



    @Test
    void enabledPlatforms_currentPlatform_isSandboxed() {
        SandboxManager m = new FakeSandboxBackend(true);
        SandboxConfig c = cfgWithPlatforms(true, List.of(currentPlatformName()));
        assertEquals(SandboxDecision.DecisionKind.RUN_SANDBOXED,
            m.decide("echo hi", false, c).kind());
    }

    @Test
    void enabledPlatforms_tsAlias_isSandboxed() {

        assumeTrue(Platform.CURRENT == Platform.DARWIN || Platform.CURRENT == Platform.WIN32);
        SandboxManager m = new FakeSandboxBackend(true);
        SandboxConfig c = cfgWithPlatforms(true, List.of(tsPlatformAlias()));
        assertEquals(SandboxDecision.DecisionKind.RUN_SANDBOXED,
            m.decide("echo hi", false, c).kind());
    }

    @Test
    void enabledPlatforms_otherPlatform_isUnsandboxed() {
        SandboxManager m = new FakeSandboxBackend(true);
        SandboxConfig c = cfgWithPlatforms(true, List.of(otherPlatformName()));
        assertEquals(SandboxDecision.DecisionKind.RUN_UNSANDBOXED,
            m.decide("echo hi", false, c).kind());
    }

    @Test
    void enabledPlatforms_emptyList_isUnsandboxed() {

        SandboxManager m = new FakeSandboxBackend(true);
        SandboxConfig c = cfgWithPlatforms(true, List.of());
        assertEquals(SandboxDecision.DecisionKind.RUN_UNSANDBOXED,
            m.decide("echo hi", false, c).kind());
    }

    @Test
    void globPatternWarnings_surfacesGlobsOnLinux() {
        // bubblewrap cannot match glob chars (a trailing /** is allowed).
        assumeTrue(Platform.CURRENT == Platform.LINUX);
        SandboxManager m = new FakeSandboxBackend(true);
        SandboxConfig c = cfgWithFilesystem(List.of("/foo/*", "/bar/**"));
        List<String> warnings = m.globPatternWarnings(c);
        assertTrue(warnings.contains("/foo/*"), "expected glob warning for /foo/*");
        assertFalse(warnings.contains("/bar/**"), "trailing /** must not warn");
    }

    // ── filesystem hardening (#7) ───────────────────────────────────────────

    @Test
    void seatbelt_profile_deniesSettingsAndSkills() throws Exception {
        SeatbeltSandboxBackend b = new SeatbeltSandboxBackend();
        Path cwd = Files.createTempDirectory("sbtest");
        SandboxConfig c = cfgWithFilesystem(List.of());
        String profile = String.join("\n", b.wrap("echo hi", cwd, c));
        assertTrue(Strings.CS.contains(profile, "settings.json"), "profile must deny writes to settings.json");
        assertTrue(Strings.CS.contains(profile, ".claude/skills"), "profile must deny writes to .claude/skills");
        assertTrue(Strings.CS.contains(profile, "claude-") || Strings.CS.contains(profile, "claude"),
            "profile must allow the Claude temp dir");
    }

    @Test
    void builtInDenyWrite_includesManagedDropInsAndFlagSettings() throws Exception {
        Path cwd = Files.createTempDirectory("sbtest");
        Path flag = cwd.resolve("flag-settings.json").toAbsolutePath().normalize();
        SandboxManager.setFlagSettingsPath(flag);
        try {
            List<String> deny = SandboxManager.builtInDenyWrite(cwd);
            assertTrue(deny.contains(flag.toString()),
                "--settings file must be denied by the native sandbox: " + deny);
            assertTrue(deny.stream().anyMatch(p ->Strings.CS.endsWith( p, "managed-settings.d")),
                "managed settings drop-in directory must be denied: " + deny);
        } finally {
            SandboxManager.setFlagSettingsPath(null);
        }
    }

    @Test
    void bwrap_argv_roBindsExistingSettings() throws Exception {
        BwrapSandboxBackend b = new BwrapSandboxBackend();
        Path cwd = Files.createTempDirectory("sbtest");
        Files.createDirectories(cwd.resolve(".claude"));
        Files.writeString(cwd.resolve(".claude/settings.json"), "{}");
        SandboxConfig c = cfgWithFilesystem(List.of());
        List<String> argv = b.wrap("echo hi", cwd, c);
        String settingsPath = cwd.resolve(".claude/settings.json").toString();
        assertTrue(argv.contains("--ro-bind") && argv.contains(settingsPath),
            "bwrap must ro-bind the existing settings.json: " + argv);
    }

    @Test
    void scrubBareGitRepoFiles_removesPlantedOnly() throws Exception {
        Path dir = Files.createTempDirectory("sbtest");
        Path readme = dir.resolve("README.md");
        Files.writeString(readme, "x");
        Set<Path> before = SandboxManager.bareGitFilesSnapshot(dir);
        assertTrue(before.isEmpty(), "no bare-repo files before command");
        Path head = dir.resolve("HEAD");
        Files.writeString(head, "planted");
        SandboxManager.scrubBareGitRepoFiles(dir, before);
        assertFalse(Files.exists(head), "planted HEAD must be scrubbed");
        assertTrue(Files.exists(readme), "unrelated file must survive");
    }

    // ── allowedDomains network proxy wiring ──────────────────────────────────

    @Test
    void seatbelt_withAllowedDomains_loopsBackOnly() {
        SeatbeltSandboxBackend b = new SeatbeltSandboxBackend();
        SandboxConfig c = cfgWithDomains(List.of("example.com"));
        List<String> argv = b.wrap("echo hi", CWD, c);
        String profile = argv.get(2);
        assertTrue(Strings.CS.contains(profile, "(deny network*)"), profile);
        assertTrue(Strings.CS.contains(profile, "(allow network* (to \"lo0\"))"), profile);
        assertFalse(Strings.CS.contains(profile, "(allow network*)"), "plain allow-all network must not be emitted");
    }

    @Test
    void bwrap_withAllowedDomains_keepsNetworkForProxy() {
        BwrapSandboxBackend b = new BwrapSandboxBackend();
        SandboxConfig c = cfgWithDomains(List.of("example.com"));
        List<String> argv = b.wrap("echo hi", CWD, c);
        assertFalse(argv.contains("--unshare-net"), "domain proxy needs loopback → no net namespace");
    }

    @Test
    void sandboxEnvironment_withAllowedDomains_setsProxyVars() {
        SandboxManager m = new FakeSandboxBackend(true);
        SandboxConfig c = cfgWithDomains(List.of("example.com"));
        Map<String, String> env = m.sandboxEnvironment(c);
        assertFalse(env.isEmpty());
        assertTrue(env.containsKey("HTTP_PROXY"));
        assertTrue(Strings.CS.startsWith(env.get("HTTP_PROXY"), "http://127.0.0.1:"));
        assertTrue(env.containsKey("HTTPS_PROXY"));
        assertTrue(env.containsKey("ALL_PROXY"));
    }

    @Test
    void sandboxEnvironment_noDomains_isEmpty() {
        SandboxManager m = new FakeSandboxBackend(true);
        assertEquals(Map.of(), m.sandboxEnvironment(SandboxConfig.disabled()));
    }

    // ── F3: pure denylist (no allowlist) must still route through the proxy ──

    private SandboxConfig cfgWithNetwork(boolean networkAllowed, List<String> allow, List<String> deny) {
        ObjectNode net = new ObjectNode(JsonNodeFactory.instance);
        net.put("allowed", networkAllowed);
        ArrayNode a = net.putArray("allowedDomains");
        allow.forEach(a::add);
        ArrayNode d = net.putArray("deniedDomains");
        deny.forEach(d::add);
        ObjectNode root = new ObjectNode(JsonNodeFactory.instance);
        root.put("enabled", true);
        root.set("network", net);
        return SandboxConfig.fromJson(root);
    }

    @Test
    void usesDomainProxy_pureDenylist_isTrue() {
        SandboxManager m = new FakeSandboxBackend(true);
        // Network otherwise blocked, only a denylist configured.
        SandboxConfig c = cfgWithNetwork(false, List.of(), List.of("evil.com"));
        assertTrue(m.usesDomainProxy(c), "pure denylist must use the proxy");
    }

    @Test
    void seatbelt_pureDenylist_loopsBackOnly() {
        SeatbeltSandboxBackend b = new SeatbeltSandboxBackend();
        SandboxConfig c = cfgWithNetwork(false, List.of(), List.of("evil.com"));
        List<String> argv = b.wrap("echo hi", CWD, c);
        String profile = argv.get(2);
        assertTrue(Strings.CS.contains(profile, "(deny network*)"), profile);
        assertTrue(Strings.CS.contains(profile, "(allow network* (to \"lo0\"))"),
            "proxy must carve out loopback so non-denied traffic is allowed: " + profile);
    }

    @Test
    void sandboxEnvironment_pureDenylist_setsProxyVars() {
        SandboxManager m = new FakeSandboxBackend(true);
        SandboxConfig c = cfgWithNetwork(false, List.of(), List.of("evil.com"));
        Map<String, String> env = m.sandboxEnvironment(c);
        assertFalse(env.isEmpty(), "pure denylist must start the proxy");
        assertTrue(env.containsKey("HTTP_PROXY"));
    }

    // ── F6: worktree main-repo path detection + injection ────────────────────

    @Test
    void detectWorktreeMainRepoPath_findsMainRepo() throws Exception {
        Path main = Files.createTempDirectory("wt-main");
        Path wtGit = Files.createDirectories(main.resolve(".git/worktrees/wt"));
        Files.writeString(wtGit.resolve("commondir"), main.resolve(".git").toString());
        Path wt = Files.createTempDirectory("wt-link");
        Files.writeString(wt.resolve(".git"), "gitdir: " + wtGit.toString());
        assertEquals(main, SandboxManager.detectWorktreeMainRepoPath(wt));
    }

    @Test
    void detectWorktreeMainRepoPath_plainRepo_isNull() throws Exception {
        Path repo = Files.createTempDirectory("plain");
        Files.createDirectory(repo.resolve(".git"));
        assertNull(SandboxManager.detectWorktreeMainRepoPath(repo));
    }

    @Test
    void seatbelt_worktreeMainRepo_allowedWrite() throws Exception {
        SeatbeltSandboxBackend b = new SeatbeltSandboxBackend();
        Path main = Files.createTempDirectory("wt-main");
        Path wtGit = Files.createDirectories(main.resolve(".git/worktrees/wt"));
        Files.writeString(wtGit.resolve("commondir"), main.resolve(".git").toString());
        Path wt = Files.createTempDirectory("wt-link");
        Files.writeString(wt.resolve(".git"), "gitdir: " + wtGit.toString());
        SandboxConfig c = cfgWithFilesystem(List.of());
        String profile = String.join("\n", b.wrap("echo hi", wt, c));
        assertTrue(Strings.CS.contains(profile, "(allow file-write* (subpath \"" + main + "\"))"),
            "worktree main repo must be writable: " + profile);
    }
}
