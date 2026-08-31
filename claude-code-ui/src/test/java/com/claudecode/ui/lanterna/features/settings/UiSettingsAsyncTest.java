package com.claudecode.ui.lanterna.features.settings;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.core.engine.SandboxConfig;
import com.claudecode.permissions.PermissionBehavior;
import com.claudecode.permissions.RuleSource;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class UiSettingsAsyncTest {

    @AfterEach
    void restoreFallback() {
        UiSettings.configure(null);
    }

    @Test
    void ensureGlobalBooleanNeverReadsOrWritesOnTheInputCaller() throws Exception {
        CountDownLatch readEntered = new CountDownLatch(1);
        CountDownLatch releaseRead = new CountDownLatch(1);
        AtomicBoolean written = new AtomicBoolean();
        UiSettings.configure(new UiSettings.Backend() {
            @Override public boolean globalBoolean(String key, boolean defaultValue) {
                readEntered.countDown();
                try {
                    releaseRead.await();
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                }
                return false;
            }
            @Override public String globalString(String key, String defaultValue) {
                return defaultValue;
            }
            @Override public int globalInt(String key, int defaultValue) { return defaultValue; }
            @Override public void setGlobal(String key, Object value) { written.set(true); }
            @Override public boolean spinnerTipsEnabled() { return true; }
            @Override public boolean prefersReducedMotion() { return false; }
            @Override public Boolean policyBoolean(String key) { return null; }
            @Override public SandboxConfig sandboxConfig() { return SandboxConfig.disabled(); }
            @Override public void addPermissionRule(String cwd, PermissionBehavior behavior,
                                                    String rule, RuleSource source) { }
            @Override public void removePermissionRule(String cwd, PermissionBehavior behavior,
                                                       String rule, RuleSource source) { }
        });

        var write = UiSettings.ensureGlobalBooleanAsync("hasUsedStash", true);

        assertTrue(readEntered.await(1, TimeUnit.SECONDS));
        assertFalse(write.isDone());
        assertFalse(written.get());
        releaseRead.countDown();
        write.get(1, TimeUnit.SECONDS);
        assertTrue(written.get());
    }
}
