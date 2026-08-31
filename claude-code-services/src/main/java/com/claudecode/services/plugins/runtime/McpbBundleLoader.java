package com.claudecode.services.plugins.runtime;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.services.http.ServiceHttpClient;
import com.claudecode.services.plugins.marketplace.UserConfigOption;
import com.claudecode.services.plugins.marketplace.UserConfigValidator;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.core.platform.SystemDirectories;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.EnumSet;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.nio.file.attribute.PosixFilePermission;

/**
 * Loads a DXT/MCPB archive, validates and safely extracts it, then generates the stdio MCP
 * configuration declared by its.
 */
public final class McpbBundleLoader {

    private static final Logger LOG = LoggerFactory.getLogger(McpbBundleLoader.class);

    private static final long MAX_FILE_SIZE = 512L * 1024 * 1024;
    private static final long MAX_TOTAL_SIZE = 1024L * 1024 * 1024;
    private static final int MAX_FILE_COUNT = 100_000;
    private static final double MAX_COMPRESSION_RATIO = 50.0;

    // Unix st_mode permission bits. Hex/shift notation avoids Java's legacy
    // leading-zero octal warning while retaining the conventional octal values

    private static final int MODE_OWNER_READ = 1 << 8;       // 0400
    private static final int MODE_OWNER_WRITE = 1 << 7;      // 0200
    private static final int MODE_OWNER_EXECUTE = 1 << 6;    // 0100
    private static final int MODE_GROUP_READ = 1 << 5;       // 0040
    private static final int MODE_GROUP_WRITE = 1 << 4;      // 0020
    private static final int MODE_GROUP_EXECUTE = 1 << 3;    // 0010
    private static final int MODE_OTHER_READ = 1 << 2;       // 0004
    private static final int MODE_OTHER_WRITE = 1 << 1;      // 0002
    private static final int MODE_OTHER_EXECUTE = 1;         // 0001

    private final OkHttpClient http;

    public record Result(String serverName, ObjectNode mcpConfig, Path extractedPath,
                         String contentHash,
                         LinkedHashMap<String, UserConfigOption> configSchema,
                         Map<String, Object> existingConfig,
                         List<String> validationErrors) {
        public boolean needsConfig() { return !validationErrors.isEmpty(); }
    }

    public McpbBundleLoader() {
        this(ServiceHttpClient.marketplace());
    }

    McpbBundleLoader(OkHttpClient http) {
        this.http = http;
    }

    public Result load(String source, Path pluginRoot,
                       Function<String, Map<String, Object>> configProvider) {
        if (StringUtils.isBlank(source)) {
            throw new IllegalArgumentException("MCPB source must not be blank");
        }
        try {
            Path cacheDir = pluginRoot.resolve(".mcpb-cache");
            Files.createDirectories(cacheDir);
            CacheMetadata metadata = loadUsableCache(source, pluginRoot, cacheDir);
            Path extractedPath;
            String contentHash;
            if (metadata != null) {
                extractedPath = Path.of(metadata.extractedPath());
                contentHash = metadata.contentHash();
            } else {
                byte[] archive = isUrl(source)
                    ? download(source) : Files.readAllBytes(safeLocalSource(pluginRoot, source));
                contentHash = hash("SHA-256", archive).substring(0, 16);
                extractedPath = cacheDir.resolve(contentHash);
                extract(archive, extractedPath, parseUnixModes(archive));
                saveMetadata(cacheDir, source, contentHash, extractedPath);
            }
            JsonNode manifest = readAndValidateManifest(extractedPath.resolve("manifest.json"));
            String serverName = manifest.path("name").asText();
            Map<String, Object> existing = configProvider == null
                ? Map.of() : configProvider.apply(serverName);
            if (existing == null) existing = Map.of();
            LinkedHashMap<String, UserConfigOption> schema = configSchema(manifest);
            List<String> errors = UserConfigValidator.validate(existing, schema);
            ObjectNode config = errors.isEmpty()
                ? generateConfig(manifest, extractedPath, existing, schema) : null;
            if (config != null) makeDeclaredCommandExecutable(config, extractedPath);
            return new Result(serverName, config, extractedPath, contentHash, schema,
                Map.copyOf(existing), errors);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    private CacheMetadata loadUsableCache(String source, Path pluginRoot, Path cacheDir) {
        Path metadataFile = metadataPath(cacheDir, source);
        if (!Files.isRegularFile(metadataFile)) return null;
        try {
            CacheMetadata metadata = JsonUtils.getMapper().readValue(
                metadataFile.toFile(), CacheMetadata.class);
            Path extracted = Path.of(metadata.extractedPath());
            if (!Files.isRegularFile(extracted.resolve("manifest.json"))) return null;
            if (!isUrl(source)) {
                Path local = safeLocalSource(pluginRoot, source);
                if (!Files.isRegularFile(local)) return null;
                Instant cachedAt = Instant.parse(metadata.cachedAt());
                if (Files.getLastModifiedTime(local).toInstant().isAfter(cachedAt)) return null;
            }
            return metadata;
        } catch (Exception _) {
            return null;
        }
    }

    private byte[] download(String source) throws IOException {
        Request request = new Request.Builder().url(source).get().build();
        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Failed to download MCPB file from " + source
                    + ": HTTP " + response.code());
            }
            try (ResponseBody body = response.body(); InputStream in = body.byteStream()) {
                return readBounded(in, MAX_TOTAL_SIZE, "MCPB download is too large");
            }
        }
    }

    private static Path safeLocalSource(Path pluginRoot, String source) {
        Path base = pluginRoot.toAbsolutePath().normalize();
        Path resolved = base.resolve(source).normalize();
        if (!resolved.startsWith(base)) {
            throw new IllegalArgumentException("Invalid MCPB path: must remain within plugin directory");
        }
        return resolved;
    }

    private static void extract(byte[] archive, Path target, Map<String, Integer> modes)
            throws IOException {
        Files.createDirectories(target);
        int fileCount = 0;
        long total = 0;
        try (ZipInputStream zip = new ZipInputStream(
                new ByteArrayInputStream(archive), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                fileCount++;
                if (fileCount > MAX_FILE_COUNT) {
                    throw new IllegalArgumentException("Archive contains too many files: " + fileCount);
                }
                Path output = safeArchiveEntry(target, entry.getName());
                if (entry.isDirectory() || Strings.CS.endsWith(entry.getName(), "/")) {
                    Files.createDirectories(output);
                    continue;
                }
                Files.createDirectories(output.getParent());
                long written = 0;
                try (OutputStream out = Files.newOutputStream(output,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    byte[] buffer = new byte[16 * 1024];
                    int read;
                    while ((read = zip.read(buffer)) >= 0) {
                        if (read == 0) continue;
                        written += read;
                        total += read;
                        if (written > MAX_FILE_SIZE) {
                            throw new IllegalArgumentException("File \"" + entry.getName()
                                + "\" is too large");
                        }
                        if (total > MAX_TOTAL_SIZE) {
                            throw new IllegalArgumentException("Archive total size is too large");
                        }
                        out.write(buffer, 0, read);
                    }
                }
                applyMode(output, modes.get(entry.getName()));
            }
        }
        if (archive.length > 0 && total / (double) archive.length > MAX_COMPRESSION_RATIO) {
            throw new IllegalArgumentException("Suspicious compression ratio detected: "
                + String.format(Locale.ROOT, "%.1f", total / (double) archive.length) + ":1");
        }
    }

    private static Path safeArchiveEntry(Path target, String name) {
        if (StringUtils.isBlank(name) || name.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Unsafe file path detected: \"" + name + "\"");
        }
        String portable = name.replace('\\', '/');
        if (Strings.CS.startsWith(portable, "/") || portable.matches("^[A-Za-z]:/.*")) {
            throw new IllegalArgumentException("Unsafe file path detected: \"" + name + "\"");
        }
        Path normalizedTarget = target.toAbsolutePath().normalize();
        Path output = normalizedTarget.resolve(portable).normalize();
        if (!output.startsWith(normalizedTarget)) {
            throw new IllegalArgumentException("Unsafe file path detected: \"" + name + "\"");
        }
        return output;
    }

    private static JsonNode readAndValidateManifest(Path manifestPath) throws IOException {
        if (!Files.isRegularFile(manifestPath)) {
            throw new IllegalArgumentException("No manifest.json found in MCPB file");
        }
        JsonNode manifest;
        try {
            manifest = JsonUtils.readJson(manifestPath);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid JSON in manifest.json: " + e.getMessage(), e);
        }
        List<String> missing = new ArrayList<>();
        for (String field : List.of("name", "version", "description")) {
            if (!manifest.hasNonNull(field) || StringUtils.isBlank(manifest.path(field).asText())) missing.add(field);
        }
        if (!manifest.path("author").hasNonNull("name")) missing.add("author.name");
        JsonNode server = manifest.get("server");
        if (server == null || !server.isObject()) missing.add("server");
        else {
            if (!server.hasNonNull("type")) missing.add("server.type");
            if (!server.hasNonNull("entry_point")) missing.add("server.entry_point");
            if (!server.path("mcp_config").hasNonNull("command")) {
                missing.add("server.mcp_config.command");
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("Invalid manifest: missing " + String.join(", ", missing));
        }
        return manifest;
    }

    private static LinkedHashMap<String, UserConfigOption> configSchema(JsonNode manifest) {
        LinkedHashMap<String, UserConfigOption> result = new LinkedHashMap<>();
        JsonNode schema = manifest.get("user_config");
        if (schema == null || !schema.isObject()) return result;
        schema.fields().forEachRemaining(field -> result.put(field.getKey(),
            JsonUtils.getMapper().convertValue(field.getValue(), UserConfigOption.class)));
        return result;
    }

    private static ObjectNode generateConfig(JsonNode manifest, Path extractedPath,
                                             Map<String, Object> supplied,
                                             Map<String, UserConfigOption> schema) {
        ObjectNode base = (ObjectNode) manifest.path("server").path("mcp_config").deepCopy();
        JsonNode overrides = base.get("platform_overrides");
        JsonNode platform = overrides == null ? null : overrides.get(platformKey());
        if (platform != null && platform.isObject()) {
            for (String key : List.of("command", "args", "env")) {
                if (platform.has(key)) base.set(key, platform.get(key).deepCopy());
            }
        }
        base.remove("platform_overrides");

        Map<String, Object> variables = systemVariables(extractedPath);
        schema.forEach((key, option) -> {
            Object value = supplied.containsKey(key) ? supplied.get(key)
                : jsonValue(option.defaultValue());
            if (value != null) variables.put("user_config." + key, value);
        });
        JsonNode replaced = replaceVariables(base, variables);
        ObjectNode config = (ObjectNode) replaced;
        config.put("type", "stdio");
        return config;
    }

    private static Map<String, Object> systemVariables(Path extractedPath) {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("__dirname", extractedPath.toString());
        // Claude Code passes '/' explicitly to @anthropic-ai/mcpb on every platform.
        vars.put("pathSeparator", "/");
        vars.put("/", "/");
        vars.putAll(SystemDirectories.resolve());
        return vars;
    }


    private static Map<String, Integer> parseUnixModes(byte[] data) {
        Map<String, Integer> modes = new LinkedHashMap<>();
        int minimum = Math.max(0, data.length - 22 - 0xffff);
        int eocd = -1;
        for (int offset = data.length - 22; offset >= minimum; offset--) {
            if (u32(data, offset) == 0x06054b50L) {
                eocd = offset;
                break;
            }
        }
        if (eocd < 0) return modes;
        int count = u16(data, eocd + 10);
        long central = u32(data, eocd + 16);
        if (central > Integer.MAX_VALUE) return modes;
        int offset = (int) central;
        for (int i = 0; i < count; i++) {
            if (offset + 46 > data.length || u32(data, offset) != 0x02014b50L) break;
            int versionMadeBy = u16(data, offset + 4);
            int nameLength = u16(data, offset + 28);
            int extraLength = u16(data, offset + 30);
            int commentLength = u16(data, offset + 32);
            long externalAttributes = u32(data, offset + 38);
            if (offset + 46 + nameLength > data.length) break;
            String name = new String(data, offset + 46, nameLength, StandardCharsets.UTF_8);
            if ((versionMadeBy >>> 8) == 3) {
                int mode = (int) ((externalAttributes >>> 16) & 0xffff);
                if (mode != 0) modes.put(name, mode);
            }
            offset += 46 + nameLength + extraLength + commentLength;
        }
        return modes;
    }

    private static void applyMode(Path path, Integer mode) {
        if (mode == null) return;
        try {
            EnumSet<PosixFilePermission> permissions = EnumSet.noneOf(PosixFilePermission.class);
            if ((mode & MODE_OWNER_READ) != 0) permissions.add(PosixFilePermission.OWNER_READ);
            if ((mode & MODE_OWNER_WRITE) != 0) permissions.add(PosixFilePermission.OWNER_WRITE);
            if ((mode & MODE_OWNER_EXECUTE) != 0) permissions.add(PosixFilePermission.OWNER_EXECUTE);
            if ((mode & MODE_GROUP_READ) != 0) permissions.add(PosixFilePermission.GROUP_READ);
            if ((mode & MODE_GROUP_WRITE) != 0) permissions.add(PosixFilePermission.GROUP_WRITE);
            if ((mode & MODE_GROUP_EXECUTE) != 0) permissions.add(PosixFilePermission.GROUP_EXECUTE);
            if ((mode & MODE_OTHER_READ) != 0) permissions.add(PosixFilePermission.OTHERS_READ);
            if ((mode & MODE_OTHER_WRITE) != 0) permissions.add(PosixFilePermission.OTHERS_WRITE);
            if ((mode & MODE_OTHER_EXECUTE) != 0) permissions.add(PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException | IOException _) {
            // Non-POSIX filesystems fall back to the declared command executable fixup.
        }
    }

    private static int u16(byte[] data, int offset) {
        if (offset < 0 || offset + 2 > data.length) return -1;
        return (data[offset] & 0xff) | ((data[offset + 1] & 0xff) << 8);
    }

    private static long u32(byte[] data, int offset) {
        if (offset < 0 || offset + 4 > data.length) return -1;
        return (data[offset] & 0xffL)
            | ((data[offset + 1] & 0xffL) << 8)
            | ((data[offset + 2] & 0xffL) << 16)
            | ((data[offset + 3] & 0xffL) << 24);
    }

    private static JsonNode replaceVariables(JsonNode node, Map<String, Object> variables) {
        if (node.isTextual()) {
            return JsonUtils.getMapper().getNodeFactory().textNode(
                replaceString(node.asText(), variables));
        }
        if (node.isArray()) {
            ArrayNode out = JsonUtils.getMapper().createArrayNode();
            for (JsonNode child : node) {
                if (child.isTextual()) {
                    String text = child.asText();
                    String key = exactVariable(text);
                    Object replacement = key == null ? null : variables.get(key);
                    if (replacement instanceof List<?> list) {
                        list.forEach(item -> out.add(String.valueOf(item)));
                        continue;
                    }
                }
                out.add(replaceVariables(child, variables));
            }
            return out;
        }
        if (node.isObject()) {
            ObjectNode out = JsonUtils.getMapper().createObjectNode();
            node.fields().forEachRemaining(field ->
                out.set(field.getKey(), replaceVariables(field.getValue(), variables)));
            return out;
        }
        return node.deepCopy();
    }

    private static String exactVariable(String value) {
        if (Strings.CS.startsWith(value, "${") && Strings.CS.endsWith(value, "}")
                && value.indexOf("${", 2) < 0) {
            return value.substring(2, value.length() - 1);
        }
        return null;
    }

    private static String replaceString(String value, Map<String, Object> variables) {
        String result = value;
        for (Map.Entry<String, Object> variable : variables.entrySet()) {
            String replacement = variable.getValue() instanceof List<?> list
                ? String.join(",", list.stream().map(String::valueOf).toList())
                : String.valueOf(variable.getValue());
            result = result.replace("${" + variable.getKey() + "}", replacement);
        }
        return result;
    }

    private static Object jsonValue(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isBoolean()) return node.asBoolean();
        if (node.isIntegralNumber()) return node.asLong();
        if (node.isFloatingPointNumber()) return node.asDouble();
        if (node.isArray()) {
            List<String> values = new ArrayList<>();
            node.forEach(item -> values.add(item.asText()));
            return values;
        }
        return node.asText();
    }

    private static String platformKey() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (Strings.CS.contains(os, "mac")) return "darwin";
        if (Strings.CS.contains(os, "win")) return "win32";
        return "linux";
    }

    private static void makeDeclaredCommandExecutable(ObjectNode config, Path extractedPath) {
        String command = config.path("command").asText(null);
        if (command == null) return;
        try {
            Path path = Path.of(command).toAbsolutePath().normalize();
            if (path.startsWith(extractedPath.toAbsolutePath().normalize()) && Files.isRegularFile(path)) {
                boolean madeExecutable = path.toFile().setExecutable(true, true);
                if (!madeExecutable) {

                    LOG.debug("Could not set executable bit on declared MCPB command: {}", path);
                }
            }
        } catch (Exception _) {
            // The configured command may be an executable name (node/python), not a path.
        }
    }

    private static void saveMetadata(Path cacheDir, String source, String hash,
                                     Path extractedPath) throws IOException {
        String now = Instant.now().toString();
        CacheMetadata metadata = new CacheMetadata(source, hash,
            extractedPath.toString(), now, now);
        JsonUtils.writeJson(metadataPath(cacheDir, source), metadata);
    }

    private static Path metadataPath(Path cacheDir, String source) {
        return cacheDir.resolve(hash("MD5", source.getBytes(StandardCharsets.UTF_8))
            .substring(0, 8) + ".metadata.json");
    }

    private static String hash(String algorithm, byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance(algorithm).digest(bytes);
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte value : digest) out.append(String.format(Locale.ROOT, "%02x", value));
            return out.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] readBounded(InputStream in, long max, String message) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        long total = 0;
        int read;
        while ((read = in.read(buffer)) >= 0) {
            if (read == 0) continue;
            total += read;
            if (total > max) throw new IOException(message);
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static boolean isUrl(String source) {
        return Strings.CS.startsWith(source, "http://") || Strings.CS.startsWith(source, "https://");
    }

    private record CacheMetadata(String source, String contentHash, String extractedPath,
                                 String cachedAt, String lastChecked) {}
}
