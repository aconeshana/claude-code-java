package com.claudecode.ui.lanterna.repl;

import com.claudecode.core.message.Message;

import java.util.List;

/**
 * Generation boundary for the focus-driven away-summary trigger. Implemented
 * by the composition root (backed by the model-facing away-summary service);
 * the UI module must not depend on model-facing services directly.
 *
 * <p>Mirrors 236 {@code JXn} as seen by the trigger: the conversation gates
 * are evaluated before the call, and the returned text is already trimmed and
 * capped. Returns {@code null} when gates fail or generation fails.
 */
public interface RecapGeneration {

    /** Generates a recap for the given conversation, or {@code null}. */
    String generate(List<Message> messages);
}