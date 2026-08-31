package com.claudecode.cli;

import com.claudecode.services.config.SettingsReloadOrchestrator;
import com.claudecode.session.TranscriptRecorder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;

class CliSessionRuntimeTest {

    @Test
    void carriesTheBorrowedLifecycleViewIntoExecutionRoutes() {
        CliSessionLifecycleView lifecycle = new CliSessionLifecycleView() {
            @Override
            public CliPluginRuntimeView pluginRuntime() {
                return null;
            }

            @Override
            public SettingsReloadOrchestrator settingsReload() {
                return null;
            }

            @Override
            public TranscriptRecorder transcriptRecorder() {
                return null;
            }

            @Override
            public CliSessionLifecycleBootstrap.PromptInventory promptInventory() {
                return null;
            }
        };

        CliSessionRuntime runtime = new CliSessionRuntime(
            null, null, null, null, lifecycle, null, null, null, null, null);

        assertSame(lifecycle, runtime.lifecycle());
    }
}
