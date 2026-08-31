package com.claudecode.mcp.oauth;

import org.apache.commons.lang3.Strings;
import java.util.Map;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class FallbackSecureStorageTest {

    /** In-memory stub for wiring tests. */
    static final class MemStore implements SecureStorage {
        final String label;
        SecureStorageData data;
        boolean throwOnUpdate = false;
        boolean throwOnRead = false;
        MemStore(String label) { this.label = label; }
        @Override public String name() { return label; }
        @Override public Optional<SecureStorageData> read() {
            if (throwOnRead) throw new RuntimeException(label + " read boom");
            return Optional.ofNullable(data);
        }
        @Override public Optional<String> update(SecureStorageData d) {
            if (throwOnUpdate) throw new RuntimeException(label + " update boom");
            this.data = d;
            return Optional.empty();
        }
        @Override public boolean delete() { data = null; return true; }
    }

    @Test
    void update_writesPrimary_whenHealthy() {
        MemStore keychain = new MemStore("keychain");
        MemStore plain = new MemStore("plaintext");
        SecureStorage fs = new FallbackSecureStorage(keychain, plain);

        var d = new SecureStorageData(Map.of(), null, null);
        fs.update(d);

        assertNotNull(keychain.data);
        assertNull(plain.data, "fallback should not be touched when primary succeeds");
    }

    @Test
    void update_fallsBackToPlaintext_whenKeychainThrows() {
        MemStore keychain = new MemStore("keychain");
        keychain.throwOnUpdate = true;
        MemStore plain = new MemStore("plaintext");
        SecureStorage fs = new FallbackSecureStorage(keychain, plain);

        var d = new SecureStorageData(Map.of(), null, null);
        Optional<String> warning = fs.update(d);

        assertNotNull(plain.data, "fallback plaintext must receive the write");
        assertNull(keychain.data);
        assertTrue(warning.isPresent(), "user-facing warning expected on fallback");
        assertTrue(Strings.CI.contains(warning.get(), "plaintext"));
    }

    @Test
    void read_prefersPrimary_whenNonEmpty() {
        MemStore keychain = new MemStore("keychain");
        keychain.data = new SecureStorageData(Map.of("k",
            new SecureStorageData.McpOAuthEntry("srv", null, null, null, "primary", null, 0L, null, null)),
            null, null);
        MemStore plain = new MemStore("plaintext");
        plain.data = new SecureStorageData(Map.of("k",
            new SecureStorageData.McpOAuthEntry("srv", null, null, null, "fallback", null, 0L, null, null)),
            null, null);
        SecureStorage fs = new FallbackSecureStorage(keychain, plain);

        assertEquals("primary",
            fs.read().orElseThrow().mcpOAuth().get("k").accessToken());
    }

    @Test
    void read_walksToFallback_whenPrimaryEmpty() {
        MemStore keychain = new MemStore("keychain");
// keychain.data is null → returns Optional.empty
        MemStore plain = new MemStore("plaintext");
        plain.data = new SecureStorageData(Map.of(), null, null);
        SecureStorage fs = new FallbackSecureStorage(keychain, plain);

        assertTrue(fs.read().isPresent());
    }

    @Test
    void read_walksToFallback_whenPrimaryThrows() {
        MemStore keychain = new MemStore("keychain");
        keychain.throwOnRead = true;
        MemStore plain = new MemStore("plaintext");
        plain.data = SecureStorageData.empty();
        SecureStorage fs = new FallbackSecureStorage(keychain, plain);

        assertTrue(fs.read().isPresent());
    }

    @Test
    void name_composesBothBackends() {
        SecureStorage fs = new FallbackSecureStorage(new MemStore("kc"), new MemStore("pt"));
        assertEquals("kc+pt", fs.name());
    }
}
