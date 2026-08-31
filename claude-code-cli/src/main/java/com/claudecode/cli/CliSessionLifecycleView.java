package com.claudecode.cli;

import com.claudecode.services.config.SettingsReloadOrchestrator;
import com.claudecode.session.TranscriptRecorder;

/** Non-owning session-lifecycle capabilities exposed to execution routes. */
interface CliSessionLifecycleView {

    CliPluginRuntimeView pluginRuntime();

    SettingsReloadOrchestrator settingsReload();

    TranscriptRecorder transcriptRecorder();

    CliSessionLifecycleBootstrap.PromptInventory promptInventory();
}
