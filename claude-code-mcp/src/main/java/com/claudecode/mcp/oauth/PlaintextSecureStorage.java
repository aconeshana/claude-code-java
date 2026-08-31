package com.claudecode.mcp.oauth;

import com.claudecode.core.config.ClaudePaths;
import com.claudecode.core.io.FileUtils;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * writes with {@code 0600} POSIX permissions.
 */
public final class PlaintextSecureStorage implements SecureStorage {

    private static final Logger LOG = LoggerFactory.getLogger(PlaintextSecureStorage.class);
    private static final String FILE_NAME = ".credentials.json";

    private final Path filePath;

    public PlaintextSecureStorage() {
        this(defaultConfigDir());
    }

    PlaintextSecureStorage(Path configDir) {
        this.filePath = configDir.resolve(FILE_NAME);
    }

    static Path defaultConfigDir() {
        return ClaudePaths.CLAUDE_HOME;
    }

    @Override
    public String name() { return "plaintext"; }

    @Override
    public synchronized Optional<SecureStorageData> read() {
        if (!Files.isRegularFile(filePath)) return Optional.empty();
        try {
            byte[] bytes = Files.readAllBytes(filePath);
            if (bytes.length == 0) return Optional.of(SecureStorageData.empty());
            JsonNode root = JsonUtils.getMapper().readTree(bytes);
            return Optional.of(SecureStorageCodec.decode(root));
        } catch (IOException e) {
            LOG.warn("Failed to read {}: {}", filePath, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public synchronized Optional<String> update(SecureStorageData data) {
        try {
            Files.createDirectories(filePath.getParent());
            JsonNode root = readRawTolerant();
            ObjectNode encoded = SecureStorageCodec.encode(data, root);
            FileUtils.atomicReplace(filePath, temp -> JsonUtils.writeJson(temp, encoded, true));
            FileUtils.trySetOwnerOnlyPermissions(filePath);
            return Optional.of("Warning: Storing MCP OAuth credentials in plaintext at "
                + filePath + ". Set up macOS Keychain if available for stronger protection.");
        } catch (IOException e) {
            throw new RuntimeException("Failed to write " + filePath + ": " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized boolean delete() {
        try {
            return Files.deleteIfExists(filePath) || !Files.exists(filePath);
        } catch (IOException e) {
            LOG.warn("Failed to delete {}: {}", filePath, e.getMessage());
            return false;
        }
    }

    /**
     * Reads the raw JSON without decoding, or an empty object if missing/
     * unreadable. Used by {@link #update} so unknown top-level keys survive.
     */
    private JsonNode readRawTolerant() {
        try {
            if (!Files.isRegularFile(filePath)) return JsonUtils.getMapper().createObjectNode();
            byte[] bytes = Files.readAllBytes(filePath);
            if (bytes.length == 0) return JsonUtils.getMapper().createObjectNode();
            JsonNode n = JsonUtils.getMapper().readTree(bytes);
            return n.isObject() ? n : JsonUtils.getMapper().createObjectNode();
        } catch (IOException e) {
            LOG.debug("Falling back to empty JSON for {}: {}", filePath, e.getMessage());
            return JsonUtils.getMapper().createObjectNode();
        }
    }

    /**
     * Force-close pattern for tests that need a temporary storage rooted at
     * a temp dir. Package-private to keep the surface tiny.
     */
    Path filePathForTest() { return filePath; }
}
