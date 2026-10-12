package com.claudecode.ui;

/**
 * The product name shown to the user as this build's own identity: the welcome banner and the
 * terminal window and tab title.
 *
 * <p>Deliberately narrow. Most "Claude Code" occurrences in this module are verbatim ports of the
 * original's copy — permission-mode explanations, the bypass-mode warning, plugin and settings
 * prose — and they stay verbatim, because user-visible parity with the released client is the
 * point of the port. Only the places that announce which program is running belong here.
 */
public final class ProductName {

    /** Pokémon Code — "poco", the name this build ships its binary under. */
    public static final String DISPLAY = "Pokémon Code";

    private ProductName() {}
}
