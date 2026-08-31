package com.claudecode.mcp.oauth;

import java.util.Optional;

/**
 * Platform-agnostic storage abstraction for MCP OAuth tokens + DCR client credentials.
 */
public interface SecureStorage {

    /** Human-readable name of this backend, e.g. {@code "keychain"} or {@code "plaintext"}. */
    String name();

    /** Reads current storage; empty when the backend has nothing stored yet. */
    Optional<SecureStorageData> read();

    /**
     * Writes {@code data}, replacing whatever was there. Returns a warning
     * message when the backend fell through to a less-secure storage
     * (e.g. plaintext fallback), or empty on clean success. Throws on
     * unrecoverable IO failure.
     */
    Optional<String> update(SecureStorageData data);

    /** Deletes the store. Returns true if the backing file/keychain entry is gone (or was already gone). */
    boolean delete();
}
