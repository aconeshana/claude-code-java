package com.claudecode.ui.lanterna.repl;

import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.model.CustomModelCatalog;
import com.claudecode.keybindings.UserKeybindingsStore;
import com.claudecode.runtime.interaction.InteractionCoordinator;
import com.claudecode.runtime.sessionhost.CollaborationSetupPort;
import com.claudecode.runtime.sessionhost.SessionCollaborationController;
import com.claudecode.runtime.sessionhost.SessionHostRegistry;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Immutable launch-time state for one interactive REPL session.
 */
public record ReplLaunchState(
    UserKeybindingsStore keybindings,
    boolean allowDangerouslySkipPermissions,
    String initialPrompt,
    String initialSessionName,
    boolean restoredSession,
    Function<String, CompletableFuture<String>> sessionTitleGenerator,
    @Explanation("Provider-aware model picker visibility for non-first-party routes")
    boolean showBuiltInModelFamilies,
    CustomModelCatalog customModels,
    Supplier<String> tipSupplier,
    SessionHostRegistry sessionHostRegistry,
    InteractionCoordinator interactionCoordinator,
    @Explanation("Per-session semantic IM collaboration selection")
    SessionCollaborationController collaborationController,
    @Explanation("Interactive collaboration onboarding")
    CollaborationSetupPort collaborationSetup
) {}
