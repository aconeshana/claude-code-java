package com.claudecode.ui.lanterna.components;

/**
 * Shared, mutable reference to the welcome banner's current {@link LogoPanel.WelcomeBlock}.
 *
 * <p>The welcome block is rewritten by three independent writers (model-line refresh, web-gateway
 * line refresh, and Pokémon replacement) that must all observe each other's latest line-range
 * bookkeeping. A holder avoids per-writer copies drifting out of sync.
 */
public final class WelcomeBlockHolder {

    private volatile LogoPanel.WelcomeBlock block;

    public LogoPanel.WelcomeBlock get() {
        return block;
    }

    public void set(LogoPanel.WelcomeBlock block) {
        this.block = block;
    }
}
