package com.claudecode.tools.hints;

import org.apache.commons.lang3.Strings;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;


public final class ClaudeCodeHintStore {

    private static final ClaudeCodeHintStore INSTANCE = new ClaudeCodeHintStore();

    public static ClaudeCodeHintStore getInstance() {
        return INSTANCE;
    }

    private final Set<String> seenValues = new HashSet<>();
    private final AtomicBoolean shownThisSession = new AtomicBoolean(false);
    private volatile Consumer<ClaudeCodeHint> listener;

    private ClaudeCodeHintStore() {}

    /** UI registers this to be notified when a gated plugin hint is recorded. */
    public void setListener(Consumer<ClaudeCodeHint> listener) {
        this.listener = listener;
    }

    /**
     * Gate + record a plugin hint.
     */
    public boolean recordPluginHint(ClaudeCodeHint hint) {
        if (hint == null || !Strings.CS.equals("plugin", hint.type())) {
            return false;
        }
        if (shownThisSession.get()) {
            return false;
        }
        if (!seenValues.add(hint.value())) {
            return false;
        }
        Consumer<ClaudeCodeHint> l = listener;
        if (l != null) {
            l.accept(hint);
        }
        return true;
    }

    /** Flip the once-per-session flag. Call only when a dialog is actually shown. */
    public void markShownThisSession() {
        shownThisSession.set(true);
    }

    public boolean hasShownHintThisSession() {
        return shownThisSession.get();
    }

    /** Test-only reset. */
    public void resetForTest() {
        shownThisSession.set(false);
        seenValues.clear();
        listener = null;
    }
}
