package com.claudecode.ui.lanterna.repl;

import org.apache.commons.lang3.Strings;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Executable ownership rules for the Lanterna REPL feature boundary. */
class ReplFeatureArchitectureTest {

    private static final Path SCREEN = Path.of(
        "src/main/java/com/claudecode/ui/lanterna/repl/LanternaReplScreen.java");
    private static final Path UI_SETTINGS = Path.of(
        "src/main/java/com/claudecode/ui/lanterna/features/settings/UiSettings.java");

    @Test
    void screenDependsOnFeatureFacadesInsteadOfConcreteFeatureViews() throws IOException {
        String source = Files.readString(SCREEN);

        for (String forbidden : List.of(
                "EffortSliderDialog", "ModelPickerDialog", "ThemePickerDialog",
                "SettingsTabContainer", "PermissionsPanel", "AgentsPanel",
                "AddDirDialog", "SandboxSettingsDialog", "ReplSettingsController",
                "MemorySelectorDialog")) {
            assertFalse(Strings.CS.contains(source, forbidden),
                () -> "LanternaReplScreen must not own concrete feature view: " + forbidden);
        }
        assertTrue(Strings.CS.contains(source, "ReplCommandUiBridge"),
            "the screen should install typed feature capabilities into the command UI bridge");
    }

    @Test
    void screenIsNotTheCommandLauncherFacadeForExtractedFeatures() throws IOException {
        String source = Files.readString(SCREEN);

        for (String launcher : List.of(
                "openSandboxSettingsDialog", "openEffortDialog", "openModelPicker",
                "openThemeDialog", "openConfigDialog", "openStatusDialog",
                "openUsageDialog", "openPermissionsDialog", "openAgentsDialog",
                "openAddDirDialog")) {
            assertFalse(Strings.CS.contains(source, "\n    public void " + launcher + "("),
                () -> "command launchers should bind to feature capabilities, not LRS: " + launcher);
        }
    }

    @Test
    void screenDelegatesTranscriptAndMessageActionsState() throws IOException {
        String source = Files.readString(SCREEN);

        assertTrue(Strings.CS.contains(source, "TranscriptController"));
        assertTrue(Strings.CS.contains(source, "MessageActionsController"));
        for (String forbidden : List.of(
                "TranscriptWindow", "activeTranscriptTaskId", "transcriptMode",
                "messageActionsMode",
                "private void toggleTranscriptMode(", "private void toggleTranscriptShowAll(",
                "private void toggleMessageActions(", "private void messageActionsPrev(",
                "private void messageActionsNext(", "private void messageActionsCopy(",
                "private void messageActionsEdit(")) {
            assertFalse(Strings.CS.contains(source, forbidden),
                () -> "LanternaReplScreen must delegate transcript/message-action ownership: " + forbidden);
        }
    }

    @Test
    void screenIsNotTheSessionCommandFacade() throws IOException {
        String source = Files.readString(SCREEN);

        for (String method : List.of(
                "clearConversation", "switchActiveSession", "resetSessionCost",
                "restoreSessionColor")) {
            assertFalse(Strings.CS.contains(source, "\n    public void " + method + "("),
                () -> "session lifecycle commands belong to SessionController: " + method);
        }
    }

    @Test
    void screenDelegatesSceneAndWindowMechanics() throws IOException {
        String source = Files.readString(SCREEN);

        assertTrue(Strings.CS.contains(source, "ReplScene"));
        assertFalse(Strings.CS.contains(source, "new Panel(new SmartLayout())"));
        assertFalse(Strings.CS.contains(source, "mainWindow = new BasicWindow()"));
        assertFalse(Strings.CS.contains(source, "private final OverlayHost"));
    }

    @Test
    void customModelEditorIsMountedAlongsideModelPicker() throws IOException {
        String source = Files.readString(SCREEN);

        assertTrue(Strings.CS.contains(source,
            "preferencesFeature.modelView(),\n            preferencesFeature.customModelView(),"),
            "an overlay that is registered for input must also be mounted to render");
    }

    @Test
    void modelAndThinkingPreferencesUseUserSettingsTier() throws IOException {
        String source = Files.readString(SCREEN);

        assertTrue(Strings.CS.contains(source,
            "UiSettings.writeUserSettingAsync(\"model\", model)"));
        assertTrue(Strings.CS.contains(source,
            "setModel(String model)"));
        assertTrue(Strings.CS.contains(source,
            "executeStatusLineCommandImmediately();"),
            "a live /model change must immediately refresh the HUD/status line");
        assertTrue(Strings.CS.contains(source,
            "UiSettings.writeUserSettingAsync(\"alwaysThinkingEnabled\", enabled ? null : false)"),
            "enabling the default-on Thinking mode must remove the user override");
        assertFalse(Strings.CS.contains(source,
            "UiSettings.writeGlobal(\"model\", model)"));
    }

    @Test
    void pokemonPersistenceObservesAsyncWriteFailures() throws IOException {
        String source = Files.readString(SCREEN);
        int start = source.indexOf("UiSettings.writeGlobalAsync(\"welcomePokemon\"");
        int end = source.indexOf("Runnable applyExperience", start);
        assertTrue(start >= 0 && end > start);
        String persistence = source.substring(start, end);

        assertTrue(Strings.CS.contains(persistence, ".whenComplete("));
        assertTrue(Strings.CS.contains(persistence, "Failed to persist welcomePokemon"));
    }

    @Test
    void uiSettingsDoesNotBridgeClaudeMdExcludes() throws IOException {
        String source = Files.readString(UI_SETTINGS);

        assertFalse(Strings.CS.contains(source, "claudeMdExcludes"),
            "CLAUDE.md filtering belongs to the services memory scanner, not the UI port");
    }

    @Test
    void completedTurnImmediatelyChecksDueScheduledTasks() throws IOException {
        String source = Files.readString(SCREEN);
        int idle = source.indexOf("featureRuntime.loopWakeups().onTurnIdle();");
        int callbackEnd = source.indexOf("},\n            this::addPokemonExperience", idle);
        assertTrue(idle >= 0 && callbackEnd > idle);

        String idleCallback = source.substring(idle, callbackEnd);
        assertTrue(Strings.CS.contains(idleCallback, "cronScheduler.checkNow()"),
            "197 checks scheduled tasks as soon as loading returns to idle");
    }

    @Test
    void sessionHostModelChangeDoesNotPersistTheUserDefault() throws IOException {
        String source = Files.readString(SCREEN);
        int start = source.indexOf("private SessionHostModelState setSessionModel(");
        int end = source.indexOf("private SessionHostEffortState currentSessionEffortState(", start);
        assertTrue(start >= 0 && end > start);
        String method = source.substring(start, end);

        assertTrue(Strings.CS.contains(method,
            "queryEngine.configuration().setModel(preference)"));
        assertTrue(Strings.CS.contains(method,
            "setModel(queryEngine.configuration().getConfig().model())"));
        assertFalse(method.matches("(?s).*\\n\\s*applyModelSelection\\(.*"),
            "Feishu/Session Host model.set must remain session-scoped");
        assertFalse(method.matches("(?s).*\\n\\s*saveModelSetting\\(.*"),
            "remote model changes must not rewrite ~/.claude/settings.json");
    }
}
