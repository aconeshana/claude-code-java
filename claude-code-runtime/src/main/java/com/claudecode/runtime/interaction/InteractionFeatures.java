package com.claudecode.runtime.interaction;

import com.claudecode.core.engine.PermissionAskCallback;
import com.claudecode.core.engine.PermissionAskContext;

/** Canonical strongly typed human-interaction definitions. */
public final class InteractionFeatures {
    public static final InteractionFeature<PermissionAskContext, PermissionAskCallback.Result>
        PERMISSION = permissionFeature(InteractionKind.PERMISSION);
    public static final InteractionFeature<PermissionAskContext, PermissionAskCallback.Result>
        USER_QUESTION = permissionFeature(InteractionKind.USER_QUESTION);
    public static final InteractionFeature<SudoPasswordInteraction.Request,
        SudoPasswordInteraction.Result> SUDO_PASSWORD = new InteractionFeature<>(
            InteractionKind.SUDO_PASSWORD,
            InteractionResponsePolicy.LOCAL_ONLY,
            InteractionSensitivity.SECRET,
            SudoPasswordInteraction.Request.class,
            SudoPasswordInteraction.Result.class,
            SudoPasswordInteraction.Result::cancelled,
            SudoPasswordInteraction.Result::unavailable);

    private InteractionFeatures() {}

    static InteractionFeature<?, ?> canonical(InteractionKind kind) {
        return switch (kind) {
            case PERMISSION -> PERMISSION;
            case USER_QUESTION -> USER_QUESTION;
            case SUDO_PASSWORD -> SUDO_PASSWORD;
        };
    }

    private static InteractionFeature<PermissionAskContext, PermissionAskCallback.Result>
            permissionFeature(InteractionKind kind) {
        return new InteractionFeature<>(kind,
            InteractionResponsePolicy.FIRST_RESPONDER,
            InteractionSensitivity.NORMAL,
            PermissionAskContext.class,
            PermissionAskCallback.Result.class,
            PermissionAskCallback.Result::deny,
            PermissionAskCallback.Result::deny);
    }
}
