package com.claudecode.services.config;

import org.apache.commons.lang3.StringUtils;
import com.claudecode.core.config.ClaudePaths;
import com.claudecode.core.state.CwdState;
import com.claudecode.core.git.GitUtils;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public final class TrustConfigStore {

    private static final Logger LOG = LoggerFactory.getLogger(TrustConfigStore.class);

    public static final String FIELD_HAS_TRUST_DIALOG_ACCEPTED = "hasTrustDialogAccepted";
    public static final String FIELD_HAS_CLAUDE_MD_EXTERNAL_INCLUDES_APPROVED =
        "hasClaudeMdExternalIncludesApproved";
    public static final String FIELD_HAS_CLAUDE_MD_EXTERNAL_INCLUDES_WARNING_SHOWN =
        "hasClaudeMdExternalIncludesWarningShown";

/**
     * Auth fields that the GH #3117 guard must never let a write drop.
     */
    private static final List<String> AUTH_FIELDS = List.of("primaryApiKey", "oauthAccount", "hasCompletedOnboarding");


    private static volatile boolean sessionTrustAccepted = false;


    private static volatile boolean trustAcceptedLatch = false;

    /**
     * Overridable config path for tests (defaults to {@link ClaudePaths#GLOBAL_JSON}).
     * Package-private so {@code TrustConfigStoreTest} can redirect to a TempDir.
     */
    static volatile Path configPath = ClaudePaths.GLOBAL_JSON;

    private TrustConfigStore() {}



    public static boolean isSessionTrustAccepted() {
        return sessionTrustAccepted;
    }


    public static void setSessionTrustAccepted(boolean accepted) {
        sessionTrustAccepted = accepted;
    }

    /** Clears the in-memory session flag. Package-private — test isolation only. */
    static void resetSessionTrustForTesting() {
        sessionTrustAccepted = false;
        trustAcceptedLatch = false;
    }

    /** Redirects the config file. Package-private — test isolation only. */
    static void setConfigPathForTesting(Path path) {
        configPath = path;
    }



    public static void setOriginalCwd(Path cwd) {
        CwdState.setOriginalCwd(cwd);
    }


    public static Path getOriginalCwd() {
        return CwdState.getOriginalCwd();
    }

    /** Test isolation only. */
    static void resetOriginalCwdForTesting() {
        CwdState.clearForTesting();
        originalCwdKeyCache = null;
    }

// ── Project config key (matches getProjectPathForConfig / normalizePathForConfigKey) ─


    private static final Map<String, String> PROJECT_PATH_CACHE = new ConcurrentHashMap<>();

    public static String getProjectPathForConfig(Path cwd) {
        String cacheKey = cwd.toAbsolutePath().normalize().toString();
        return PROJECT_PATH_CACHE.computeIfAbsent(cacheKey, _ -> {
            Path canonical = GitUtils.findCanonicalGitRoot(cwd);
            if (canonical != null) {
                return normalizePathForConfigKey(canonical, true);
            }
            return normalizePathForConfigKey(cwd.toAbsolutePath().normalize(), false);
        });
    }


    private static volatile String originalCwdKeyCache;

    private static String originalCwdProjectKey() {
        String cached = originalCwdKeyCache;
        if (cached != null) {
            return cached;
        }
        Path anchor = CwdState.getOriginalCwd();
        if (anchor == null) {
            anchor = Path.of(System.getProperty("user.dir"));
            return getProjectPathForConfig(anchor);
        }
        String key = getProjectPathForConfig(anchor);
        originalCwdKeyCache = key; // freeze like lodash memoize (no TTL, never invalidated)
        return key;
    }


    static String normalizePathForConfigKey(Path path, boolean nfc) {
        String s = path.normalize().toString().replace('\\', '/');
        return nfc ? Normalizer.normalize(s, Normalizer.Form.NFC) : s;
    }

    // ── Reads ────────────────────────────────────────────────────────────────


    public static boolean isTrustAccepted(Path cwd) {
        if (trustAcceptedLatch) {
            return true;
        }
        if (sessionTrustAccepted) {
            trustAcceptedLatch = true;
            return true;
        }
        ObjectNode projects = readProjects();
        if (isTrue(projects, originalCwdProjectKey(), FIELD_HAS_TRUST_DIALOG_ACCEPTED)) {
            trustAcceptedLatch = true;
            return true;
        }
        String current = normalizePathForConfigKey(cwd.toAbsolutePath().normalize(), false);
        while (true) {
            if (isTrue(projects, current, FIELD_HAS_TRUST_DIALOG_ACCEPTED)) {
                trustAcceptedLatch = true;
                return true;
            }
            String parent = normalizePathForConfigKey(Path.of(current).resolve("..").normalize(), false);
            if (parent.equals(current)) {
                break;
            }
            current = parent;
        }
        return false;
    }


    public static boolean isPathTrusted(Path dir) {
        ObjectNode projects = readProjects();
        String current = normalizePathForConfigKey(dir.toAbsolutePath().normalize(), false);
        while (true) {
            if (isTrue(projects, current, FIELD_HAS_TRUST_DIALOG_ACCEPTED)) {
                return true;
            }
            String parent = normalizePathForConfigKey(Path.of(current).resolve("..").normalize(), false);
            if (parent.equals(current)) {
                return false;
            }
            current = parent;
        }
    }

    /** Reads {@code projects[path].hasClaudeMdExternalIncludesApproved} (path anchored to original cwd). */
    public static boolean hasExternalIncludesApproved(Path cwd) {
        return isTrue(readProjects(), originalCwdProjectKey(),
            FIELD_HAS_CLAUDE_MD_EXTERNAL_INCLUDES_APPROVED);
    }

    /** Reads {@code projects[path].hasClaudeMdExternalIncludesWarningShown} (path anchored to original cwd). */
    public static boolean hasExternalIncludesWarningShown(Path cwd) {
        return isTrue(readProjects(), originalCwdProjectKey(),
            FIELD_HAS_CLAUDE_MD_EXTERNAL_INCLUDES_WARNING_SHOWN);
    }

    // ── Writes ───────────────────────────────────────────────────────────────

    /**
     * Persists trust acceptance for {@code cwd}.
     */
    public static void acceptTrust(Path cwd) {
        if (isHomeDir(cwd)) {
            setSessionTrustAccepted(true);
            return;
        }
        String key = originalCwdProjectKey();
        updateProjectEntry(key, entry -> {
            entry.put(FIELD_HAS_TRUST_DIALOG_ACCEPTED, true);
            return entry;
        });
    }

    /**
     * Persists the external CLAUDE.md includes decision.
     */
    public static void saveExternalIncludesDecision(Path cwd, boolean approved) {
        String key = originalCwdProjectKey();
        updateProjectEntry(key, entry -> {
            entry.put(FIELD_HAS_CLAUDE_MD_EXTERNAL_INCLUDES_APPROVED, approved);
            entry.put(FIELD_HAS_CLAUDE_MD_EXTERNAL_INCLUDES_WARNING_SHOWN, true);
            return entry;
        });
    }

    /**
     * Deep-merge a single {@code projects[projectPath]} entry, preserving all other top-level keys
     * (incl.
     */
    @FunctionalInterface
    private interface ProjectEntryUpdater {
        ObjectNode apply(ObjectNode entry);
    }

    private static void updateProjectEntry(String key, ProjectEntryUpdater updater) {
        Path file = configPath;

        // concurrent threads/virtual-threads can't interleave a read-modify-write. The cross
        // process file lock lives inside GlobalConfigStore.writeAtomicLocked.
        synchronized (GlobalConfigStore.WRITE_MONITOR) {
            try {
                Files.createDirectories(file.getParent());
            } catch (IOException e) {
                LOG.warn("Failed to create parent dir for project config {}: {}", key, e.getMessage());
                return;
            }
            ObjectNode root;
            try {
                root = readOrEmpty(file);
            } catch (Exception _) {
                root = JsonUtils.getMapper().createObjectNode();
            }
            // Known-good snapshot taken BEFORE we mutate — the GH #3117 reference config.
            ObjectNode preRoot = root.deepCopy();
            ObjectNode projects = readProjects(root);
            ObjectNode entry = (projects.has(key) && projects.get(key) instanceof ObjectNode on)
                ? on : JsonUtils.getMapper().createObjectNode();
            ObjectNode updated = updater.apply(entry);
            projects.set(key, updated);
            root.set("projects", projects);
            // GH #3117 pre-check: never write a config missing an auth field the pre-op snapshot had.
            if (wouldLoseAuthState(root, preRoot)) {
                LOG.warn("Refusing to write project config for {}: would drop auth state (GH #3117).", key);
                return;
            }
            try {
                GlobalConfigStore.writeAtomicLocked(file, root);
            } catch (IOException _) {

                JsonNode fresh = readGlobalConfig(file);
                ObjectNode freshRoot = (fresh instanceof ObjectNode on) ? on : JsonUtils.getMapper().createObjectNode();
                if (wouldLoseAuthState(root, freshRoot)) {
                    LOG.warn("Refusing fallback write for {}: would drop auth state (GH #3117).", key);
                    return;
                }
                try {
                    GlobalConfigStore.writeAtomicLocked(file, root);
                } catch (IOException e2) {
                    LOG.warn("Failed to save project config for {} (write retry): {}", key, e2.getMessage());
                }
            }
        }
    }



    /**
     * Removes the obsolete {@code trustedFolders} array from layered settings.
     * The cleanup is best-effort and idempotent; trust is re-established through
     * the current {@code projects} map.
     */
    public static void migrateRemoveLegacyTrustedFolders() {
        String cwd = SettingsPaths.sessionProjectRoot(System.getProperty("user.dir")).toString();
        List<Path> files = List.of(
            SettingsPaths.userSettingsPath(),
            SettingsPaths.sessionProjectSettingsPath(cwd),
            SettingsPaths.sessionLocalSettingsPath(cwd));
        for (Path p : files) {
            removeTopLevelKeyIfPresent(p, "trustedFolders");
        }
    }

    private static void removeTopLevelKeyIfPresent(Path settingsPath, String key) {
        if (!Files.isReadable(settingsPath)) {
            return;
        }
        synchronized (GlobalConfigStore.WRITE_MONITOR) {
            try {
                JsonNode existing = JsonUtils.readJson(settingsPath);
                if (!(existing instanceof ObjectNode root) || !root.has(key)) {
                    return;
                }
                root.remove(key);
                GlobalConfigStore.writeAtomicLocked(settingsPath, root);
            } catch (Exception e) {
                LOG.warn("Failed to migrate/remove legacy {} from {}: {}", key, settingsPath, e.getMessage());
            }
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static ObjectNode readOrEmpty(Path file) {
        JsonNode n = readGlobalConfig(file);
        return (n instanceof ObjectNode on) ? on : JsonUtils.getMapper().createObjectNode();
    }

    private static ObjectNode readProjects() {
        return readProjects(readOrEmpty(configPath));
    }

    private static ObjectNode readProjects(ObjectNode root) {
        JsonNode p = root.get("projects");
        return (p instanceof ObjectNode on) ? on : JsonUtils.getMapper().createObjectNode();
    }

    private static JsonNode readGlobalConfig(Path file) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            JsonNode n = JsonUtils.readJson(file);
            return (n != null && n.isObject()) ? n : null;
        } catch (Exception e) {
            LOG.warn("Failed to read {}: {}", file, e.getMessage());
            return null;
        }
    }

    private static boolean isTrue(ObjectNode projects, String key, String field) {
        JsonNode entry = projects.get(key);
        if (!(entry instanceof ObjectNode)) {
            return false;
        }
        JsonNode v = entry.get(field);
        return v != null && v.isBoolean() && v.asBoolean();
    }

    private static boolean isHomeDir(Path cwd) {
        String home = System.getProperty("user.home");
        if (StringUtils.isBlank(home)) {
            return false;
        }
        return cwd.toAbsolutePath().normalize()
            .equals(Path.of(home).toAbsolutePath().normalize());
    }

    /** GH #3117 guard: true if writing {@code candidate} would drop an auth field {@code reference} still has. */
    private static boolean wouldLoseAuthState(ObjectNode candidate, JsonNode reference) {
        for (String authField : AUTH_FIELDS) {
            if (hasAuth(reference.get(authField)) && !hasAuth(candidate.get(authField))) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasAuth(JsonNode v) {
        if (v == null || v.isNull()) {
            return false;
        }
        if (v.isTextual()) {
            return !v.asText().isEmpty();
        }
        return true; // non-null object/boolean auth presence (e.g. oauthAccount)
    }
}
