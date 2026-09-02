package com.claudecode.ui.lanterna.overlay;

import com.googlecode.lanterna.input.KeyStroke;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns the inline-overlay stack and its single-active input-routing invariant.
 *
 * <p>This is presentation infrastructure rather than a feature controller: it knows only the
 * {@link InlineOverlay} protocol, never model/settings/permissions concepts. Features register
 * their views here; the window router delegates first-claim input handling to this host.</p>
 */
public final class OverlayHost {

    private final List<InlineOverlay> overlays = new ArrayList<>();
    private InlineOverlay lastActive;
    private boolean sealed;

    public synchronized void register(InlineOverlay overlay) {
        ensureMutable();
        if (overlay != null) overlays.add(overlay);
    }

    public synchronized void registerAll(List<? extends InlineOverlay> values) {
        ensureMutable();
        if (values != null) values.stream().filter(Objects::nonNull).forEach(overlays::add);
    }

    public synchronized List<InlineOverlay> snapshot() {
        return List.copyOf(overlays);
    }

    public synchronized void seal() {
        sealed = true;
    }

    public synchronized boolean isSealed() {
        return sealed;
    }

    public synchronized boolean hasActiveOverlay() {
        for (InlineOverlay overlay : overlays) {
            if (overlay.isActive()) return true;
        }
        return false;
    }

    public boolean routePlainText(String text) {
        InlineOverlay active = activeOverlay();
        if (active == null) return false;
        active.handlePlainText(text, new AtomicBoolean(true));
        return true;
    }

    public boolean routeRepeatedKey(KeyStroke key, int count) {
        InlineOverlay active = activeOverlay();
        if (active == null) return false;
        active.handleRepeatedKey(key, count, new AtomicBoolean(true));
        return true;
    }

    /**
     * Routes one key directly from the GUI host, before Lanterna walks the
     * active window and focused component. An active modal always consumes the
     * key, matching {@link #route(KeyStroke, AtomicBoolean)}.
     */
    public boolean routeDirect(KeyStroke key) {
        InlineOverlay active = activeOverlay();
        if (active == null) return false;
        active.handleKey(key, new AtomicBoolean(true));
        return true;
    }

    public boolean route(KeyStroke key, AtomicBoolean deliver) {
        InlineOverlay active = activeOverlay();
        if (active == null) return false;
        active.handleKey(key, deliver);
        // A modal surface owns the complete terminal input stream. Even a key
        // it does not understand must never fall through to the model prompt.
        deliver.set(false);
        return true;
    }

    private synchronized InlineOverlay activeOverlay() {
        InlineOverlay cached = lastActive;
        if (cached != null && cached.isActive()) return cached;
        InlineOverlay active = null;
        for (InlineOverlay overlay : overlays) {
            if (!overlay.isActive()) continue;
            assert active == null : "at most one inline overlay may be active at a time";
            active = overlay;
        }
        lastActive = active;
        return active;
    }

    private void ensureMutable() {
        if (sealed) throw new IllegalStateException("overlay registry is sealed");
    }
}
