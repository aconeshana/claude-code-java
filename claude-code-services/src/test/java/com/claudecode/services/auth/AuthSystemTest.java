package com.claudecode.services.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AuthSystemTest {

    // --- SystemKeychain ---

    @Test
    void inMemoryKeychainCrud() {
        SystemKeychain keychain = SystemKeychain.inMemory();
        assertTrue(keychain.isAvailable());

        keychain.store("claude-code", "api-key", "sk-secret");
        var retrieved = keychain.retrieve("claude-code", "api-key");
        assertTrue(retrieved.isPresent());
        assertEquals("sk-secret", retrieved.get());

        assertTrue(keychain.delete("claude-code", "api-key"));
        assertTrue(keychain.retrieve("claude-code", "api-key").isEmpty());
    }

    @Test
    void inMemoryKeychainDeleteNonexistent() {
        SystemKeychain keychain = SystemKeychain.inMemory();
        assertFalse(keychain.delete("service", "account"));
    }

    // JwtTokenManager tests removed with the class itself (2026-07-16) —

    // refresh); Java is x-api-key only. See services/coverage.yml.
}
