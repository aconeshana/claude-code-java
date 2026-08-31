package com.claudecode.mcp.oauth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * Composes a primary + fallback {@link SecureStorage}.
 */
public final class FallbackSecureStorage implements SecureStorage {

    private static final Logger LOG = LoggerFactory.getLogger(FallbackSecureStorage.class);

    private final SecureStorage primary;
    private final SecureStorage fallback;

    public FallbackSecureStorage(SecureStorage primary, SecureStorage fallback) {
        this.primary = primary;
        this.fallback = fallback;
    }

    @Override
    public String name() {
        return primary.name() + "+" + fallback.name();
    }

    @Override
    public Optional<SecureStorageData> read() {
        try {
            Optional<SecureStorageData> primaryData = primary.read();
            if (primaryData.isPresent()) return primaryData;
        } catch (RuntimeException e) {
            LOG.debug("Primary storage read failed: {}", e.getMessage());
        }
        return fallback.read();
    }

    @Override
    public Optional<String> update(SecureStorageData data) {
        try {
            return primary.update(data);
        } catch (RuntimeException e) {
            LOG.warn("Primary storage update failed ({}): falling back to {}",
                e.getMessage(), fallback.name());
            Optional<String> fbWarning = fallback.update(data);
            String primaryWarning = "Primary storage (" + primary.name()
                + ") unavailable — wrote to " + fallback.name() + " instead.";
            return Optional.of(fbWarning.map(w -> primaryWarning + " " + w).orElse(primaryWarning));
        }
    }

    @Override
    public boolean delete() {
        boolean p, f;
        try { p = primary.delete(); }
        catch (RuntimeException e) { LOG.debug("primary delete failed: {}", e.getMessage()); p = false; }
        try { f = fallback.delete(); }
        catch (RuntimeException e) { LOG.debug("fallback delete failed: {}", e.getMessage()); f = false; }
        return p || f;
    }
}
