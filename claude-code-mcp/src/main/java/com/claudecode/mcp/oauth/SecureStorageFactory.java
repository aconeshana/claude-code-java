package com.claudecode.mcp.oauth;

import java.util.Locale;

import org.apache.commons.lang3.Strings;
/**
 * Platform-appropriate {@link SecureStorage} factory.
 */
public final class SecureStorageFactory {

    private SecureStorageFactory() {}

    private static volatile SecureStorage instance;

    /**
     * Returns a process-wide {@link SecureStorage} instance. First call
     * decides the backend; subsequent calls return the same one so cache
     * state is shared.
     */
    public static SecureStorage getInstance() {
        SecureStorage local = instance;
        if (local != null) return local;
        synchronized (SecureStorageFactory.class) {
            if (instance == null) {
                instance = build();
            }
            return instance;
        }
    }

    /**
     * Constructs a fresh backend. Tests use this + a manual set to inject
     * a temp-dir plaintext storage; production always goes through
     * {@link #getInstance}.
     */
    static SecureStorage build() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (Strings.CS.contains(os, "mac") || Strings.CS.contains(os, "darwin")) {
            return new FallbackSecureStorage(
                new KeychainSecureStorage(),
                new PlaintextSecureStorage());
        }
        return new PlaintextSecureStorage();
    }

    /** Test seam: overrides the singleton. Package-private on purpose. */
    static void setForTest(SecureStorage override) {
        instance = override;
    }

    /** Test seam: resets the singleton back to lazy-init. */
    static void resetForTest() {
        instance = null;
    }
}
