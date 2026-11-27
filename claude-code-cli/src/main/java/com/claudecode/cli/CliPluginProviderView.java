package com.claudecode.cli;

import java.util.List;
import java.util.Optional;

/**
 * Non-owning view over plugin model-provider sidecars exposed to session and gateway consumers.
 * {@link CliPluginRuntime} retains the {@link AutoCloseable} owner ({@link CliPluginProviderManager}),
 * while callers borrow this interface without triggering resource-ownership warnings, following the
 * same split as {@link CliPluginRuntimeView}.
 */
interface CliPluginProviderView {

    /** Reverse-proxy target for a plugin's embedded management UI. */
    record UiTarget(
            String pluginId,
            String providerId,
            String title,
            String description,
            String baseUrl,
            String uiPath,
            String token,
            String status,
            String error,
            boolean settingsTab) {}

    /** Clears the model list cache so the next {@link #listModels()} hits every running sidecar. */
    void invalidateModelsCache();

    /** Lists all models currently offered by running plugin sidecars. */
    List<CliCompositeModelCatalog.ProvidedModel> listModels();

    /** Returns the reverse-proxy target for {@code pluginId}'s management UI, if declared. */
    Optional<UiTarget> uiTarget(String pluginId);

    /** Returns UI panel metadata for all enabled plugins that declare an embedded UI. */
    List<UiTarget> uiPanels();
}
