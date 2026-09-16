package com.claudecode.ui.lanterna.repl;

import com.claudecode.ui.lanterna.features.ReplFeature;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.gui2.EmptySpace;

/**
 * The one place that declares the REPL scene: which inline overlays receive routed input and, in
 * render order, which components the root layout stacks beneath the message stream.
 *
 * <p>Mount order is z-order and layout position. Every component below {@code messagePanel}
 * collapses to (0,0) while idle so the layout hands those rows back to the transcript; when one
 * activates it appears exactly where it sits in this list relative to the spinner, the startup
 * gates and the prompt. {@code ReplSceneOrderSnapshotTest} pins the list.
 *
 * <p>Only one inline overlay is active at a time (see {@code OverlayHost}), so overlay
 * registration is grouped per feature rather than interleaved.
 */
final class ReplSceneLayout {

    private ReplSceneLayout() {}

    static void install(ReplScene scene, ReplGraph.Widgets widgets, ReplGraph.Features features) {
        registerOverlays(scene, widgets, features);
        mount(scene, widgets, features);
    }

    private static void registerOverlays(ReplScene scene, ReplGraph.Widgets widgets,
                                         ReplGraph.Features features) {
        ReplFeature[] ordered = {
            features.preferences(), features.permissions(), features.agents(), features.sandbox(),
            features.hooks(), features.goal(), features.btw(), features.conversationTools(),
            features.plugins(), features.mcp(), features.exit(), features.pokemon(),
            features.memory(), features.session(), features.diagnostics(), features.tasks(),
            features.startupGates(), features.bypassPermissionsGate(),
        };
        for (ReplFeature feature : ordered) scene.registerAll(feature.overlays());
        scene.register(features.toolApproval().questionView());
        scene.register(features.toolApproval().refusalView());
        scene.register(widgets.lspRecommendationDialog());
        scene.register(widgets.pluginHintMenu());
        scene.register(widgets.thinkingToggleDialog());
        scene.register(widgets.collaborationPickerDialog());
        scene.register(widgets.feishuSetupDialog());
        scene.register(widgets.projectPanel());
    }

    private static void mount(ReplScene scene, ReplGraph.Widgets widgets, ReplGraph.Features features) {
        var messagePanel = widgets.messagePanel();
        var spinnerComponent = widgets.spinnerComponent();
        var inputPanel = widgets.inputPanel();
        var projectPanel = widgets.projectPanel();
        var lspRecommendationDialog = widgets.lspRecommendationDialog();
        var thinkingToggleDialog = widgets.thinkingToggleDialog();
        var collaborationPickerDialog = widgets.collaborationPickerDialog();
        var feishuSetupDialog = widgets.feishuSetupDialog();
        var toolApproval = features.toolApproval();
        var preferences = features.preferences();
        var startupGates = features.startupGates();
        var bypassPermissionsGate = features.bypassPermissionsGate();
        var sandbox = features.sandbox();
        var taskBoard = features.taskBoard();
        var conversationTools = features.conversationTools();
        var hooks = features.hooks();
        var goal = features.goal();
        var btw = features.btw();
        var plugins = features.plugins();
        var permissions = features.permissions();
        var agents = features.agents();
        var mcp = features.mcp();
        var exit = features.exit();
        var pokemon = features.pokemon();
        var memory = features.memory();
        var session = features.session();
        var diagnostics = features.diagnostics();
        var tasks = features.tasks();

        // Root: SmartLayout — messagePanel sized by content, everything else pinned beneath it.
        scene.mount(
            messagePanel,
            spinnerComponent,
            toolApproval.leaderView(),
            preferences.effortView(),
            toolApproval.questionView(),
            toolApproval.refusalView(),
            startupGates.trustView(),
            startupGates.managedSettingsView(),
            bypassPermissionsGate.view(),
            sandbox.view(),
            startupGates.externalIncludesView(),
            lspRecommendationDialog,
            preferences.modelView(),
            preferences.customModelView(),
            taskBoard.view(),
            thinkingToggleDialog,
            collaborationPickerDialog,
            feishuSetupDialog,
            conversationTools.exportView(),
            hooks.view(),
            goal.view(),
            btw.view(),
            preferences.themeView(),
            conversationTools.copyView(),
            conversationTools.diffView(),
            conversationTools.helpView(),
            projectPanel,
            plugins.view(),
            permissions.addDirectoryView(),
            preferences.settingsView(),
            permissions.rulesView(),
            agents.view(),
            mcp.view(),
            exit.view(),
            conversationTools.tagRemovalView(),
            pokemon.view(),
            memory.view(),
            session.view(),
            diagnostics.doctorView(),
            diagnostics.skillsView(),
            tasks.tasksView(),
            tasks.workflowsView(),
            diagnostics.statsView(),
            // one blank row between the live spinner/tool zone and the prompt divider
            new EmptySpace(new TerminalSize(0, 1)),
            inputPanel);
    }
}
