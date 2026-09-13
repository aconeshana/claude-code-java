package com.claudecode.gateway;

import java.util.List;

/**
 * Consumer-owned boundary for the gateway's composer command catalogue.
 *
 * <p>The gateway must not depend on {@code claude-code-commands} (where
 * {@code CommandRegistry} lives) or {@code claude-code-tools} (where
 * {@code SkillLoader} lives), so this port speaks plain strings: the CLI
 * composition root projects the interactive registry's slash commands plus
 * the user-invocable skill commands onto it, and the webui's composer "+"
 * menu renders both under one Commands section, mirroring dsh's composer
 * menu over its host command catalog.
 */
public interface GatewayCommandsPort {

    /**
     * One composer-menu row. {@code kind} is {@code "command"} for registry
     * slash commands and {@code "skill"} for skill projections; the menu
     * renders both identically (dsh merges contributions into the same list).
     */
    record CommandEntry(
        String name,
        String description,
        String argumentHint,
        String kind) {}

    /** Every menu-visible command and skill known to this process. */
    default List<CommandEntry> list() { return List.of(); }
}
