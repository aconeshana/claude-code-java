package com.claudecode.ui.lanterna.slash;

import com.claudecode.runtime.query.QuerySession;
import com.claudecode.permissions.PermissionGate;
import com.claudecode.ui.lanterna.input.InputPanel;
import com.claudecode.ui.lanterna.transcript.LanternaMessageDispatcher;
import com.claudecode.ui.lanterna.transcript.MessageHistory;
import com.claudecode.ui.lanterna.transcript.MessagePanel;
import com.googlecode.lanterna.gui2.WindowBasedTextGUI;

/**
 * The REPL components {@link SlashCommandDispatcher} reads and renders into, injected as plain
 * references.
 *
 * <p>Split out of {@link SlashHost} so that interface stays a pure command port — the
 * irreducible behaviors only the REPL can perform — rather than a service-locator bag. These
 * seven are already-concrete objects that need no dependency inversion: a slash command reads
 * them, it does not ask the REPL to "do" them. See the design invariants on
 * {@link com.claudecode.ui.lanterna.repl.LanternaReplScreen}.
 */
public record ReplRefs(
    WindowBasedTextGUI gui,
    MessagePanel messagePanel,
    InputPanel inputPanel,
    MessageHistory messageHistory,
    LanternaMessageDispatcher dispatcher,
    QuerySession queryEngine,
    PermissionGate permissionGate
) {}
