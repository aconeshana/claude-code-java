package com.claudecode.commands.recap;

import org.apache.commons.lang3.StringUtils;

import com.claudecode.core.message.Message;

import java.util.List;

/**
 * Command-facing boundary for synthesizing a one-line session recap.
 *
 * <p>Mirrors the shared recap execution of the authoritative 2.1.236 bundle: the
 * same generation path backs both the {@code /recap} slash command and the
 * away-summary watcher. A single {@link #synthesize(List)} call returns a
 * structured {@link Outcome} so the command can map each state onto 236's
 * {@code text}-typed command result (ok / api-error / no-turn / aborted /
 * failed).
 */
@FunctionalInterface
public interface RecapPort {

    /** The terminal state of a recap synthesis attempt. */
    enum Kind {
        /** A recap was produced and is available on {@link Outcome#text()}. */
        OK,
        /** Nothing to recap: no robust conversation state (mirrors 236 {@code no-turn}). */
        NO_TURN,
        /** The generation could not complete (API error or internal failure). */
        FAILED
    }

    /**
     * Immutable result of a recap attempt.
     *
     * @param kind terminal state
     * @param text the recap text when {@link Kind#OK}; otherwise {@code null}
     */
    record Outcome(Kind kind, String text) {

        public Outcome {
            if (kind == Kind.OK && (StringUtils.isBlank(text))) {
                throw new IllegalArgumentException("OK recap outcome must carry non-blank text");
            }
        }

        public static Outcome ok(String text) {
            return new Outcome(Kind.OK, text);
        }

        public static Outcome noTurn() {
            return new Outcome(Kind.NO_TURN, null);
        }

        public static Outcome failed() {
            return new Outcome(Kind.FAILED, null);
        }
    }

    /** Synthesize a recap from the given conversation messages. */
    Outcome synthesize(List<Message> messages);

    static RecapPort none() {
        return _ -> Outcome.failed();
    }
}