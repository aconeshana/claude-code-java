package com.claudecode.ui.lanterna.repl;

import com.claudecode.commands.impl.info.VersionCommand;
import com.claudecode.core.effort.EffortHelpers;
import com.claudecode.core.model.CustomModelCatalog;
import com.claudecode.core.model.ModelNames;
import com.claudecode.core.model.PermissionModeKind;
import com.claudecode.permissions.PermissionGate;
import com.claudecode.runtime.query.QuerySession;
import com.claudecode.ui.lanterna.features.settings.UiSettings;
import com.claudecode.ui.lanterna.statusline.StatusLineInputBuilder;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import org.apache.commons.lang3.StringUtils;

/**
 * Assembles the live {@code StatusLineCommandInput} ingredients (and the built-in HUD effort
 * badge) from the interactive REPL's current state. Pure read-side adapter: no Lanterna types,
 * no mutation.
 *
 * <p>TS coverage (paths relative to the claude-code repo root):
 * <ul>
 *   <li>{@code components/StatusLine.tsx} — the {@code useMemo} that builds the status-line
 *       command JSON payload from session id, transcript path, cwd, model, output style, vim
 *       mode, version, context window, and cost metrics.</li>
 *   <li>{@code utils/model/model.ts} — {@code getMainLoopModel}-style runtime model
 *       resolution (an {@code opusplan} setting shows Opus while plan mode is active).</li>
 * </ul>
 */
final class ReplStatusLineIngredients {

    private final QuerySession queryEngine;
    private final InteractiveSessionPort interactiveSessions;
    private final Supplier<PermissionGate> permissionGate;
    private final CustomModelCatalog customModels;
    private final Supplier<String> vimMode;

    ReplStatusLineIngredients(QuerySession queryEngine,
                              InteractiveSessionPort interactiveSessions,
                              Supplier<PermissionGate> permissionGate,
                              CustomModelCatalog customModels,
                              Supplier<String> vimMode) {
        this.queryEngine = Objects.requireNonNull(queryEngine, "queryEngine");
        this.interactiveSessions = Objects.requireNonNull(interactiveSessions, "interactiveSessions");
        this.permissionGate = Objects.requireNonNull(permissionGate, "permissionGate");
        this.customModels = customModels;
        this.vimMode = Objects.requireNonNull(vimMode, "vimMode");
    }

    StatusLineInputBuilder.Ingredients ingredients() {
        String sid = queryEngine.conversation().getSessionId();
        String cwd = System.getProperty("user.dir");
        String sessionName = (StringUtils.isNotBlank(sid))
            ? interactiveSessions.readCustomTitle(cwd, sid) : null;
        String transcript = (StringUtils.isNotBlank(sid))
            ? interactiveSessions.sessionFile(cwd, sid).toString() : "";
        PermissionGate gate = permissionGate.get();
        List<String> addedDirs = gate != null
            ? gate.currentContext().additionalDirs().keySet().stream().map(Path::toString).toList()
            : List.of();
        String outputStyle = UiSettings.readStringFromSettings("outputStyle");

        // an opusplan setting shows Opus while plan mode is active.
        String runtimeModel = runtimeModel();
        Long contextWindow = customModels != null ? customModels.contextWindow(runtimeModel) : null;
        return new StatusLineInputBuilder.Ingredients(
            sid, sessionName, transcript, cwd, cwd, addedDirs,
            runtimeModel, outputStyle,
            vimMode.get(),
            VersionCommand.readVersion(), contextWindow,
            queryEngine.execution().getSessionMetrics());
    }

    String runtimeModel() {
        PermissionGate gate = permissionGate.get();
        PermissionModeKind permMode = gate != null ? gate.currentMode().kind() : null;
        return ModelNames.runtimeMainLoopModel(
            queryEngine.configuration().getConfig().model(), permMode, false);
    }

    /** Effective effort sent by the session, or {@code auto} for an unknown custom endpoint. */
    String effort() {
        String runtimeModel = runtimeModel();
        if (!EffortHelpers.modelSupportsEffort(runtimeModel)) return null;
        String configured = queryEngine.configuration().getEffortOverride() != null
            ? queryEngine.configuration().getEffortOverride() : queryEngine.configuration().getConfig().effortValue();
        String applied = EffortHelpers.resolveAppliedEffort(
            runtimeModel, configured, queryEngine.configuration().getConfig().isCustomModel(runtimeModel));
        return applied != null ? applied : "auto";
    }
}
