package com.claudecode.cli;

import com.claudecode.core.platform.Platform;
import com.claudecode.runtime.sessionhost.CollaborationSetupPort;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

/**
 * Shared helpers for resolving the bundled {@code cc-connect} binary, reading/writing
 * {@code cc-connect.toml} configuration, and building setup commands.
 */
final class CcConnectSupport {

    static final String CONFIG_ENV = "CLAUDE_CODE_IM_CONFIG";
    static final String BINARY_ENV = "CLAUDE_CODE_CC_CONNECT_BINARY";

    private static final Pattern COLLAB_TARGET_ENTRY = Pattern.compile(
        "(?m)^\\s*([a-z][a-z0-9_-]{0,31})\\s*=\\s*\"([^\"]+)\"\\s*(?:#.*)?$");
    private static final Pattern PLATFORM_TYPE = Pattern.compile(
        "(?m)^\\s*type\\s*=\\s*\"([a-zA-Z0-9_-]+)\"\\s*(?:#.*)?$");

    private CcConnectSupport() {}

    static Path resolveDefaultConfig(Path claudeHome) {
        return resolveConfig(System.getenv(CONFIG_ENV), claudeHome)
            .orElse(claudeHome.resolve("cc-connect.toml"));
    }

    static Optional<Path> resolveConfig(String explicit, Path claudeHome) {
        if (StringUtils.isNotBlank(explicit)) {
            Path selected = Path.of(explicit).toAbsolutePath().normalize();
            if (Files.exists(selected) && !Files.isRegularFile(selected)) {
                throw new IllegalArgumentException(CONFIG_ENV + " is not a regular file: " + selected);
            }
            return Optional.of(selected);
        }
        Path conventional = claudeHome.resolve("cc-connect.toml");
        return Files.isRegularFile(conventional) ? Optional.of(conventional) : Optional.empty();
    }

    static boolean isPlatformSupported() {
        try {
            nativeResource(Platform.OS_NAME, Platform.OS_ARCH);
            return true;
        } catch (IllegalArgumentException _) {
            return false;
        }
    }

    static String nativeResource(String osName, String archName) {
        String os = osName.toLowerCase(Locale.ROOT);
        String platform;
        if (Strings.CS.contains(os, "mac")) {
            platform = "darwin";
        } else if (Strings.CS.contains(os, "linux")) {
            platform = "linux";
        } else {
            throw new IllegalArgumentException(
                "Session Host sidecar requires macOS or Linux Unix-domain sockets: " + osName);
        }
        String arch = archName.toLowerCase(Locale.ROOT);
        String normalizedArch;
        if (Strings.CS.equals("aarch64", arch) || Strings.CS.equals("arm64", arch)) {
            normalizedArch = "arm64";
        } else if (Strings.CS.equals("x86_64", arch) || Strings.CS.equals("amd64", arch)) {
            normalizedArch = "amd64";
        } else {
            throw new IllegalArgumentException("Unsupported Session Host architecture: " + archName);
        }
        return "/native/" + platform + "-" + normalizedArch + "/cc-connect";
    }

    static Path resolveExecutable(Path dir) throws IOException {
        String override = System.getenv(BINARY_ENV);
        if (StringUtils.isNotBlank(override)) {
            Path candidate = Path.of(override).toAbsolutePath().normalize();
            if (!Files.isRegularFile(candidate) || !Files.isExecutable(candidate)) {
                throw new IOException(BINARY_ENV + " is not an executable file: " + candidate);
            }
            return candidate;
        }
        String resource = nativeResource(Platform.OS_NAME, Platform.OS_ARCH);
        try (InputStream input = CcConnectSupport.class.getResourceAsStream(resource)) {
            if (input == null) throw new IOException("bundled cc-connect is unavailable for " + resource);
            Path executable = dir.resolve(
                Strings.CS.endsWith(resource, ".exe") ? "cc-connect.exe" : "cc-connect");
            Files.copy(input, executable, StandardCopyOption.REPLACE_EXISTING);
            restrict(executable, Set.of(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
            return executable;
        }
    }

    /** Creates a private short path that remains below macOS' Unix socket limit. */
    static Path createSocketDirectory() throws IOException {
        Path shortTempRoot = Path.of("/tmp");
        if (!Files.isDirectory(shortTempRoot)) {
            shortTempRoot = Path.of(System.getProperty("java.io.tmpdir"));
        }
        Path dir = Files.createTempDirectory(shortTempRoot, "ccsh-");
        restrict(dir, Set.of(PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
        return dir;
    }

    static void writeStarterConfig(Path config, String projectName,
                                   String workDir) throws IOException {
        Files.createDirectories(config.toAbsolutePath().getParent());
        String body = """
            language = "en"
            data_dir = "%s"

            [[projects]]
            name = "%s"

            [projects.agent]
            type = "sessionhost"

            [projects.agent.options]
            work_dir = "%s"
            auth_token_env = "CC_SESSION_LINK_TOKEN"
            request_timeout_seconds = 30
            max_frame_bytes = 16777216

            [[projects.platforms]]
            type = "feishu"

            [projects.platforms.options]
            thread_isolation = true
            enable_feishu_card = true
            progress_style = "card"
            """.formatted(
                toml(config.getParent().resolve("cc-connect-data").toString()),
                toml(projectName), toml(workDir));
        Files.writeString(config, body, StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        restrict(config, Set.of(PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE));
    }

    static List<String> setupCommand(Path executable, Path config, Path credentialEnv,
                                     String project, CollaborationSetupPort.Mode mode) {
        if (mode == CollaborationSetupPort.Mode.RESUME) {
            return List.of(executable.toString(), "feishu", "resume",
                "--config", config.toString(), "--project", project, "--timeout", "600");
        }
        boolean bind = mode == CollaborationSetupPort.Mode.BIND;
        var command = new ArrayList<>(List.of(executable.toString(), "feishu",
            bind ? "bind" : "new", "--config", config.toString(), "--project", project,
            "--credential-env", credentialEnv.toString(), "--discover-target", "--timeout", "600"));
        if (bind) command.add("--app-stdin");
        return List.copyOf(command);
    }

    static boolean targetConfigured(Path config) {
        String text = readConfig(config);
        return hasAssignment(text, "bind_session_key") && hasAssignment(text, "allow_chat");
    }

    static boolean setupPending(Path config) {
        return config != null && Files.isRegularFile(config) && !targetConfigured(config)
            && hasSavedFeishuCredentials(config);
    }

    static boolean hasSavedFeishuCredentials(Path config) {
        String text = readConfig(config);
        if (!Strings.CS.contains(text, "type = \"feishu\"")
                && !Strings.CS.contains(text, "type = \"lark\"")) return false;
        String appId = assignmentValue(text, "app_id");
        String appSecret = assignmentValue(text, "app_secret");
        if (StringUtils.isBlank(appId) || StringUtils.isBlank(appSecret)) return false;
        return credentialValueAvailable(config, text, appId)
            && credentialValueAvailable(config, text, appSecret);
    }

    static boolean isLegacyUninitializedStarter(Path config, Path credentialEnv) {
        String text = readConfig(config);
        return !Files.isRegularFile(credentialEnv)
            && Strings.CS.contains(text, "type = \"sessionhost\"")
            && Strings.CS.equals(credentialEnv.toString(), assignmentValue(text, "env_file"))
            && Strings.CS.equals("${FEISHU_APP_ID}", assignmentValue(text, "app_id"))
            && Strings.CS.equals("${FEISHU_APP_SECRET}", assignmentValue(text, "app_secret"));
    }

    /**
     * Extracts configured IM channel names (e.g. {@code ["feishu"]}) from {@code cc-connect.toml}
     * so both Web UI and TUI can display selectable channels before any sidecar connects.
     */
    static List<String> configuredChannels(Path config) {
        if (!targetConfigured(config)) return List.of();
        String text = readConfig(config);
        Set<String> channels = new LinkedHashSet<>();

        int targetsHeader = text.indexOf("[projects.agent.options.collaboration_targets]");
        if (targetsHeader >= 0) {
            int nextSection = text.indexOf("\n[", targetsHeader + 1);
            String section = nextSection >= 0
                ? text.substring(targetsHeader, nextSection)
                : text.substring(targetsHeader);
            Matcher matcher = COLLAB_TARGET_ENTRY.matcher(section);
            while (matcher.find()) {
                channels.add(matcher.group(1).toLowerCase(Locale.ROOT));
            }
        }

        String bindKey = assignmentValue(text, "bind_session_key");
        int colon = bindKey.indexOf(':');
        if (colon > 0) {
            String prefix = bindKey.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            if (!prefix.isEmpty()) channels.add(prefix);
        }

        if (channels.isEmpty()) {
            Matcher matcher = PLATFORM_TYPE.matcher(text);
            while (matcher.find()) {
                String type = matcher.group(1).trim().toLowerCase(Locale.ROOT);
                if (!Strings.CS.equals("sessionhost", type)) {
                    channels.add(type);
                }
            }
        }
        return channels.stream().sorted().toList();
    }

    static String projectName(String cwd) {
        Path name = Path.of(cwd).toAbsolutePath().normalize().getFileName();
        String value = name == null ? "claude-code" : name.toString();
        value = value.replaceAll("[^A-Za-z0-9._-]+", "-");
        return StringUtils.isBlank(value) ? "claude-code" : value;
    }

    static void restrict(Path path, Set<PosixFilePermission> permissions) {
        try {
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException | IOException _) {
            // Windows has no POSIX permission view.
        }
    }

    private static String toml(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r");
    }

    private static boolean credentialValueAvailable(Path config, String text, String value) {
        var placeholder = Pattern.compile("^\\$\\{([A-Za-z_][A-Za-z0-9_]*)}$")
            .matcher(value);
        if (!placeholder.matches()) return true;
        String name = placeholder.group(1);
        if (StringUtils.isNotBlank(System.getenv(name))) return true;
        String configuredEnvFile = assignmentValue(text, "env_file");
        if (StringUtils.isBlank(configuredEnvFile)) return false;
        Path envFile = Path.of(configuredEnvFile);
        if (!envFile.isAbsolute()) envFile = config.toAbsolutePath().getParent().resolve(envFile);
        String envText = readConfig(envFile.normalize());
        String savedValue = dotenvValue(envText, name);
        return StringUtils.isNotBlank(savedValue);
    }

    private static String assignmentValue(String text, String key) {
        var matcher = Pattern.compile("(?m)^\\s*" + Pattern.quote(key)
            + "\\s*=\\s*\"([^\"]*)\"\\s*(?:#.*)?$").matcher(text);
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    private static String dotenvValue(String text, String name) {
        var matcher = Pattern.compile("(?m)^\\s*(?:export\\s+)?" + Pattern.quote(name)
            + "\\s*=\\s*([^\\r\\n#]*)").matcher(text);
        if (!matcher.find()) return "";
        String value = matcher.group(1).trim();
        if (value.length() >= 2
                && ((Strings.CS.startsWith(value, "\"") && Strings.CS.endsWith(value, "\""))
                    || (Strings.CS.startsWith(value, "'") && Strings.CS.endsWith(value, "'")))) {
            value = value.substring(1, value.length() - 1);
        }
        return value.trim();
    }

    private static String readConfig(Path config) {
        if (config == null || !Files.isRegularFile(config)) return "";
        try { return Files.readString(config); }
        catch (IOException _) { return ""; }
    }

    private static boolean hasAssignment(String text, String key) {
        return Pattern.compile("(?m)^\\s*" + Pattern.quote(key)
            + "\\s*=\\s*\\\"[^\\\"]+\\\"\\s*(?:#.*)?$").matcher(text).find();
    }
}
