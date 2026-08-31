package com.claudecode.core.message;

/**
 * Decides whether a refused turn may switch models on its own or has to ask the user first, and
 * what it does when it cannot ask.
 */
public final class RefusalFallbackDecision {

    /** Why no dialog will be shown. A {@code null} reason means "ask the user". */
    public enum Suppression {
        /** A subagent has no user to ask; it switches models without announcing it. */
        SUBAGENT,
        /** The host offers no dialog port at all (headless, or a plain stream consumer). */
        NO_DIALOG_HOST,
        /** The user left {@code switchModelsOnFlag} enabled, which is the shipped default. */
        SETTING,
        /** The consumer supplies a dialog port but never declared this dialog kind. */
        NO_CONSUMER_CAPABILITY
    }

    /** What a refused turn does next. */
    public enum Choice {
        /** Retry the turn on the fallback model. */
        RETRY_FALLBACK,
        /** Abort so the user can rewrite the prompt for the original model. */
        EDIT_PROMPT,
        /** Give up on the turn and surface the refusal. */
        CANCELLED
    }

    /**
     * The host facts the decision reads.
     */
    public record Host(
        boolean mainThread,
        boolean dialogHostAvailable,
        boolean consumerLacksDialogCapability,
        boolean switchModelsOnFlag
    ) {

        /** Mutable assembly for call sites that only care about one or two facts. */
        public static final class Builder {
            private boolean mainThread = true;
            private boolean dialogHostAvailable;
            private boolean consumerLacksDialogCapability;
            private boolean switchModelsOnFlag = true;

            public Builder mainThread(boolean value) {
                this.mainThread = value;
                return this;
            }

            public Builder dialogHostAvailable(boolean value) {
                this.dialogHostAvailable = value;
                return this;
            }

            public Builder consumerLacksDialogCapability(boolean value) {
                this.consumerLacksDialogCapability = value;
                return this;
            }

            public Builder switchModelsOnFlag(boolean value) {
                this.switchModelsOnFlag = value;
                return this;
            }

            public Host build() {
                return new Host(mainThread, dialogHostAvailable,
                    consumerLacksDialogCapability, switchModelsOnFlag);
            }
        }
    }

    private RefusalFallbackDecision() {
    }

    /**
     * Why no dialog will be shown, or {@code null} when the user should be asked.
     */
    public static Suppression suppression(Host host) {
        if (!host.mainThread()) return Suppression.SUBAGENT;
        if (!host.dialogHostAvailable()) return Suppression.NO_DIALOG_HOST;
        if (host.switchModelsOnFlag()) return Suppression.SETTING;
        if (host.consumerLacksDialogCapability()) return Suppression.NO_CONSUMER_CAPABILITY;
        return null;
    }

    /**
     * The choice a refused turn takes when no dialog is shown. A {@code null}
     * reason is the seed value the call site holds while the dialog is still
     * open, so an unanswered dialog degrades to the retry rather than to a
     * cancelled turn.
     */
    public static Choice choiceWithoutDialog(Suppression suppression) {
        return suppression == Suppression.NO_CONSUMER_CAPABILITY
            ? Choice.CANCELLED : Choice.RETRY_FALLBACK;
    }

    /**
     * Whether the server-side fallback lane stays unarmed. A main-thread host
     * that can neither ask the user nor auto-switch would be unable to act on a
     * model swap the server performed on its own, so the lane is not offered.
     */
    public static boolean suppressesServerLane(Host host) {
        return host.mainThread()
            && (!host.dialogHostAvailable() || host.consumerLacksDialogCapability())
            && !host.switchModelsOnFlag();
    }
}
