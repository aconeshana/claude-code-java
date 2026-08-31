package com.claudecode.runtime.startup;

import java.nio.file.Path;

/**
 * Persistence boundary for interactive startup trust decisions.
 */
public interface StartupTrustPort {

    default void migrateLegacyTrust() {}

    boolean isTrustAccepted(Path cwd);

    void acceptTrust(Path cwd);

    boolean hasExternalIncludesApproved(Path cwd);

    boolean hasExternalIncludesWarningShown(Path cwd);

    void saveExternalIncludesDecision(Path cwd, boolean approved);

    static StartupTrustPort trustAll() {
        return new StartupTrustPort() {
            @Override public boolean isTrustAccepted(Path cwd) { return true; }
            @Override public void acceptTrust(Path cwd) {}
            @Override public boolean hasExternalIncludesApproved(Path cwd) { return true; }
            @Override public boolean hasExternalIncludesWarningShown(Path cwd) { return true; }
            @Override public void saveExternalIncludesDecision(Path cwd, boolean approved) {}
        };
    }
}
