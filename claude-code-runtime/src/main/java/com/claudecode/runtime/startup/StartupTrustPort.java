package com.claudecode.runtime.startup;

import java.nio.file.Path;

/**
 * Persistence boundary for interactive startup trust decisions.
 */
public interface StartupTrustPort {

    default void migrateLegacyTrust() {}

    boolean isTrustAccepted(Path cwd);

    void acceptTrust(Path cwd);

    boolean hasExternalIncludesApproved();

    boolean hasExternalIncludesWarningShown();

    void saveExternalIncludesDecision(boolean approved);

    static StartupTrustPort trustAll() {
        return new StartupTrustPort() {
            @Override public boolean isTrustAccepted(Path cwd) { return true; }
            @Override public void acceptTrust(Path cwd) {}
            @Override public boolean hasExternalIncludesApproved() { return true; }
            @Override public boolean hasExternalIncludesWarningShown() { return true; }
            @Override public void saveExternalIncludesDecision(boolean approved) {}
        };
    }
}
