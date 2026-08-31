package com.claudecode.services.config;

import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.core.process.SubprocessEnvironment;
import com.claudecode.core.process.ProcessResult;
import com.claudecode.core.process.ProcessRunner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;


final class MdmSettingsStore {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final Pattern REG_VALUE = Pattern.compile(
        "^\\s+Settings\\s+REG_(?:EXPAND_)?SZ\\s+(.*)$", Pattern.CASE_INSENSITIVE);

    private static volatile ReadResult adminCache;
    private static volatile ReadResult userCache;
    private static volatile String adminCacheEnvironment;
    private static volatile String userCacheEnvironment;

    record ReadResult(ObjectNode settings, List<SettingsValidationError> errors) {
        ReadResult {
            settings = settings == null ? JsonUtils.getMapper().createObjectNode() : settings.deepCopy();
            errors = errors == null ? List.of() : List.copyOf(errors);
        }

        /** Keeps the MDM cache internal even when a consumer merges the returned source. */
        public ObjectNode settings() {
            return settings.deepCopy();
        }
    }

    private MdmSettingsStore() {}

    static synchronized void clearCache() {
        adminCache = null;
        userCache = null;
        adminCacheEnvironment = null;
        userCacheEnvironment = null;
    }

    /** Highest-priority admin MDM source (macOS managed plist or Windows HKLM). */
    static ObjectNode readAdminSettings() {
        return readAdminResult().settings();
    }

    static ReadResult readAdminResult() {
        String environment = cacheEnvironmentKey();
        ReadResult cached = adminCache;
        if (cached != null && environment.equals(adminCacheEnvironment)) return cached;
        ReadResult loaded = readAdminSettingsUncached();
        adminCache = loaded;
        adminCacheEnvironment = environment;
        return loaded;
    }

    /** Lowest-priority Windows HKCU source, suppressed by file-managed settings. */
    static ObjectNode readUserSettings() {
        return readUserResult().settings();
    }

    static ReadResult readUserResult() {
        String environment = cacheEnvironmentKey();
        ReadResult cached = userCache;
        if (cached != null && environment.equals(userCacheEnvironment)) return cached;
        if (hasManagedFileContent()) {
            userCache = emptyResult();
            userCacheEnvironment = environment;
            return userCache;
        }
        ReadResult loaded = readUserSettingsUncached();
        userCache = loaded;
        userCacheEnvironment = environment;
        return loaded;
    }

    private static String cacheEnvironmentKey() {
        return String.join("\u0000",
            System.getProperty("os.name", ""),
            System.getProperty("user.name", ""),
            System.getProperty("user.home", ""),
            Objects.toString(SubprocessEnvironment.get("USER_TYPE"), ""),
            Objects.toString(SubprocessEnvironment.get("CLAUDE_CODE_MANAGED_SETTINGS_PATH"), ""));
    }

    private static ReadResult emptyResult() {
        return new ReadResult(JsonUtils.getMapper().createObjectNode(), List.of());
    }

    /** Stable pair used by the periodic poll to avoid reloads when MDM is unchanged. */
    static String snapshot() {
        ObjectNode admin = readAdminSettings();
        ObjectNode user = readUserSettings();
        return admin.toString() + "\n" + user.toString();
    }

    private static ReadResult readAdminSettingsUncached() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (Strings.CS.contains(os, "mac")) {
            String username = System.getProperty("user.name", "");
            List<PlistSource> paths = StringUtils.isBlank(username)
                ? List.of(new PlistSource(
                    Path.of("/Library/Managed Preferences/com.anthropic.claudecode.plist"),
                    "device-level managed preferences"))
                : List.of(
                    new PlistSource(
                        Path.of("/Library/Managed Preferences", username,
                            "com.anthropic.claudecode.plist"),
                        "per-user managed preferences"),
                    new PlistSource(
                        Path.of("/Library/Managed Preferences/com.anthropic.claudecode.plist"),
                        "device-level managed preferences"));
            for (PlistSource candidate : paths) {
                RawReadResult raw = readPlistRaw(candidate.path());

                // the first non-empty successful stdout by priority.  The
                // successful raw result is the candidate winner, but an invalid
                // parsed payload is discarded by consumeRawReadResult rather
                // than becoming an admin source or carrying errors into the
                // lower-priority file/HKCU fallback.
                if (raw.successful()) {
                    ReadResult parsed = parseAccepted(raw.stdout(), candidate.label());
                    return parsed.settings().isEmpty() ? emptyResult() : parsed;
                }
            }
            if (Strings.CS.equals("ant", System.getenv("USER_TYPE"))) {
                Path path = Path.of(System.getProperty("user.home", ""),
                    "Library/Preferences/com.anthropic.claudecode.plist");
                RawReadResult raw = readPlistRaw(path);
                if (raw.successful()) {
                    ReadResult parsed = parseAccepted(raw.stdout(), "user preferences (ant-only)");
                    return parsed.settings().isEmpty() ? emptyResult() : parsed;
                }
            }
        } else if (Strings.CS.contains(os, "win")) {
            ReadResult settings = readRegistry(
                "HKLM\\SOFTWARE\\Policies\\ClaudeCode");

            // non-empty settings; invalid payload errors are discarded before
            // falling through to managed files/HKCU.
            if (!settings.settings().isEmpty()) return settings;
        }
        return emptyResult();
    }

    private static ReadResult readUserSettingsUncached() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (!Strings.CS.contains(os, "win")) return emptyResult();
        return readRegistry("HKCU\\SOFTWARE\\Policies\\ClaudeCode");
    }

    private record PlistSource(Path path, String label) {}
    private record RawReadResult(boolean successful, String stdout) {}

    private static RawReadResult readPlistRaw(Path path) {
        if (path == null || !Files.isRegularFile(path)) return new RawReadResult(false, "");
        ProcessResult result = ProcessRunner.run(
            List.of("/usr/bin/plutil", "-convert", "json", "-o", "-", "--", path.toString()),
            null, TIMEOUT);
        String stdout = result.stdout() == null ? "" : result.stdout();
        return new RawReadResult(result.succeeded() && !stdout.isEmpty(), stdout);
    }

    private static ReadResult readRegistry(String key) {
        ProcessResult result = ProcessRunner.run(
            List.of("reg", "query", key, "/v", "Settings"), null, TIMEOUT);
        if (!result.succeeded()) return emptyResult();
        String json = null;
        for (String line : result.stdout().split("\\R")) {
            Matcher candidate = REG_VALUE.matcher(line);
            if (candidate.matches()) { json = candidate.group(1).stripTrailing(); break; }
        }
        return json == null ? emptyResult() : parseAccepted(json, "Registry: " + key + "\\Settings");
    }

    private static ReadResult parseAccepted(String json, String source) {
        JsonNode node = JsonUtils.safeParseJson(json);
        if (node == null) return emptyResult();
        if (node.isArray()) {

            // purpose of schema parsing, so HKCU retains the root-shape
            // diagnostic even though the array can never contribute settings.
            List<SettingsValidationError> errors = SettingsSchema.validate(node)
                .stream()
                .map(error -> new SettingsValidationError(
                    source, error.path(), error.message()))
                .toList();
            return new ReadResult(JsonUtils.getMapper().createObjectNode(), errors);
        }
        if (!node.isObject()) return emptyResult();
        ManagedValidation validated = SettingsTreeReader.validateManaged(node, source);
        return new ReadResult(validated.settings(), validated.errors());
    }

    private static boolean hasManagedFileContent() {

        // non-empty managed-settings object suppresses HKCU, even if the
        // object later proves invalid. Empty/malformed files do not.
        if (hasNonEmptyJsonObject(SettingsPaths.policySettingsPath())) return true;
        Path directory = SettingsPaths.policySettingsDropInDirectory();
        try (var entries = Files.list(directory)) {
            return entries
                .filter(path -> {
                    String name = path.getFileName().toString();
                    return !Strings.CS.startsWith(name, ".") &&Strings.CS.endsWith( name, ".json")
                    && (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                            || Files.isSymbolicLink(path));
                })
                .anyMatch(MdmSettingsStore::hasNonEmptyJsonObject);
        } catch (Exception _) {
            return false;
        }
    }

    private static boolean hasNonEmptyJsonObject(Path path) {
        if (path == null || !Files.isReadable(path)) return false;
        try {
            JsonNode node = SettingsTreeReader.readJson(path);

            // non-empty JSON array also suppresses HKCU before schema parsing.
            return node != null && (node.isObject() || node.isArray()) && !node.isEmpty();
        } catch (IOException | RuntimeException _) {
            return false;
        }
    }
}
