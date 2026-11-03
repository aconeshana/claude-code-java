package com.claudecode.permissions;

/**
 * Observer for a {@link PermissionGate}'s effective permission-mode changes.
 *
 * <p>A gate is a shared mutable holder: the TUI and a gateway-hosted web
 * session read and write the same instance. Every surface that renders the
 * mode (the TUI composer, the webui permission chip) keeps its own cached
 * copy, so a mode change made on one surface must be broadcast to the
 * others or they drift. Registering a listener through {@link
 * PermissionGate#addModeListener} is that broadcast.
 *
 * <p>The callback fires synchronously on the thread that changed the mode,
 * outside the gate's state update, and only when the <em>effective</em> mode
 * actually differs from the previous one. A listener must therefore never
 * re-enter the gate's mode setters (that would recurse) and must marshal to
 * its own UI thread itself.
 */
@FunctionalInterface
public interface PermissionModeListener {

    /** Called with the gate's new effective mode after it changed. */
    void onModeChanged(PermissionMode mode);
}