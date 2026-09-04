package com.claudecode.ui.lanterna.dialog;

import com.claudecode.core.constants.AnsiStyle;
import com.claudecode.core.engine.PermissionAskCallback;
import com.claudecode.core.engine.PermissionAskContext;
import com.claudecode.core.engine.PermissionExplainerCallback;
import com.claudecode.core.engine.PermissionExplanation;
import com.claudecode.core.engine.PermissionUpdate;
import com.claudecode.core.message.ContentBlock;
import com.claudecode.core.message.ImageBlock;
import com.claudecode.core.model.PermissionModeKind;
import com.claudecode.core.paste.ImagePaste;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.core.text.FormatUtils;
import com.claudecode.keybindings.UserKeybindingsStore;
import com.claudecode.ui.DiffRenderer;
import com.claudecode.ui.lanterna.features.settings.UiSettings;
import com.claudecode.ui.lanterna.input.ContextKeybindingDispatcher;
import com.claudecode.ui.lanterna.input.ExternalEditorLauncher;
import com.claudecode.ui.lanterna.theme.LanternaTheme;
import com.claudecode.ui.lanterna.transcript.MessagePanel;
import com.claudecode.ui.syntax.ScopeColorMap;
import com.claudecode.ui.syntax.TmTokenizer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TerminalTextUtils;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.gui2.Button;
import com.googlecode.lanterna.gui2.Direction;
import com.googlecode.lanterna.gui2.EmptySpace;
import com.googlecode.lanterna.gui2.Interactable;
import com.googlecode.lanterna.gui2.Label;
import com.googlecode.lanterna.gui2.LinearLayout;
import com.googlecode.lanterna.gui2.MultiWindowTextGUI;
import com.googlecode.lanterna.gui2.Panel;
import com.googlecode.lanterna.gui2.TextBox;
import com.googlecode.lanterna.gui2.TextGUIGraphics;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.input.PasteKeyStroke;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;


public class PermissionDialog extends Panel {

    // ── Dialog state ─────────────────────────────────────────────────────────

    private Consumer<PermissionAskCallback.Result> resultConsumer;
    private boolean active;
    private Runnable onClose;


    private Consumer<List<PermissionUpdate>> onApplySuggestions;

    /** Cached context from the last {@link #show} call; used by ctrl+e. */
    private PermissionAskContext currentCtx;

    /** Explainer callback — null means feature is disabled. */
    private PermissionExplainerCallback explainer;

    /** GUI reference — needed to call invokeLater from the explainer thread. */
    private MultiWindowTextGUI guiRef;

    /** Label where the explanation text is placed once loaded. */
    private Label explainerLabel;

    /** Original tool description, hidden while the explainer replaces it. */
    private Label descriptionLabel;

    /** True while the explanation is visible (toggled by ctrl+e). */
    private boolean explainerVisible;

    /** True once the explanation has been fetched (prevents duplicate fetches). */
    private boolean explainerFetched;

    /** The amendment TextBox — non-null when the user is in "amend mode". */
    private TextBox amendBox;
    private final List<ContentBlock> amendFeedbackBlocks = new ArrayList<>();

    /** True when amending Yes; false when amending No. */
    private boolean amendingYes;


    private Label amendPrefixLabel;

    /** Risk label with color (e.g., "   Low risk: read-only"). */
    private Label explainerRiskLabel;
    private Label debugLabel;
    private boolean debugVisible;

    /** Options panel — stored to allow inline amend insertion. */
    private Panel optionsPanel;

    private AmendButton yesBtn;
    private AmendButton keepContextBtn;
    private AmendButton allowRuleBtn;  // null if no suggestion
    private AmendButton noBtn;

    /** Footer hint label — updated dynamically when focus moves between options. */
    private Label hintLabel;
    private Label titleLabel;
    private Label subtitleLabel;
    private Label questionLabel;
    private Label planContentLabel;
    private Label editorHintLabel;
    private boolean exitPlanRequest;
    private boolean enterPlanRequest;
    private TextColor currentAccent;
    private List<PermissionUpdate> primaryApprovalUpdates = List.of();
    private JsonNode approvalUpdatedInput;
    private PermissionRequestBody requestBody;
    private Consumer<String> onPlanChanged = _ -> {};
    private Consumer<PlanClearApproval> onPlanClearApproval = _ -> {};
    private final List<String> specialBodyLines = new ArrayList<>();
    private final List<String> requestedPermissionsLines = new ArrayList<>();
    private final ContextKeybindingDispatcher keybindings =
        new ContextKeybindingDispatcher();

    // ── Construction ─────────────────────────────────────────────────────────

    public PermissionDialog() {
        setLayoutManager(new LinearLayout(Direction.VERTICAL));
    }

    public void setKeybindingsStore(UserKeybindingsStore store) {
        keybindings.setStore(store);
    }

    public boolean isActive() {
        return active;
    }

    public record PlanClearApproval(String plan, PermissionModeKind mode,
                                    List<PermissionUpdate> permissionUpdates,
                                    String feedback) {
        public PlanClearApproval {
            permissionUpdates = List.copyOf(
                permissionUpdates == null ? List.of() : permissionUpdates);
        }
    }

    public void setPlanClearApprovalConsumer(Consumer<PlanClearApproval> consumer) {
        onPlanClearApproval = consumer == null ? _ -> {} : consumer;
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Mount the inline question from a rich {@link PermissionAskContext}.
     * Replaces any prior content. Must be called on the GUI thread.
     */
    void show(PreparedPermissionPrompt prepared, PermissionExplainerCallback explainerCb,
              Consumer<List<PermissionUpdate>> applySuggestionsCallback,
              Consumer<PermissionAskCallback.Result> resultCb, Runnable onCloseCb) {
        PermissionAskContext ctx = prepared.context();
        this.resultConsumer = resultCb;
        this.onClose = onCloseCb;
        this.onApplySuggestions = applySuggestionsCallback;
        this.active = true;
        this.currentCtx = ctx;
        this.explainer = explainerCb;
        this.explainerVisible = false;
        this.explainerFetched = false;
        this.explainerLabel = null;
        this.descriptionLabel = null;
        this.explainerRiskLabel = null;
        this.debugLabel = null;
        this.debugVisible = false;
        this.amendBox = null;
        this.amendFeedbackBlocks.clear();
        this.amendPrefixLabel = null;
        this.optionsPanel = null;
        this.yesBtn = null;
        this.keepContextBtn = null;
        this.allowRuleBtn = null;
        this.noBtn = null;
        this.hintLabel = null;
        this.titleLabel = null;
        this.subtitleLabel = null;
        this.questionLabel = null;
        this.planContentLabel = null;
        this.editorHintLabel = null;
        this.exitPlanRequest = Strings.CS.equals("ExitPlanMode", ctx.toolName());
        this.enterPlanRequest = Strings.CS.equals("EnterPlanMode", ctx.toolName());
        boolean emptyExitPlan = exitPlanRequest
            && StringUtils.isBlank(exactTextField(ctx.input(), "plan"));
        boolean clearContextExit = exitPlanRequest && !emptyExitPlan
            && ctx.input() != null && ctx.input().path("_uiShowClearContext").asBoolean(false);
        this.primaryApprovalUpdates = emptyExitPlan ? exitPlanSecondaryUpdates(ctx)
            : exitPlanRequest && !clearContextExit ? exitPlanPrimaryUpdates(ctx) : List.of();
        this.approvalUpdatedInput = null;
        this.requestBody = prepared.body();
        if (requestBody instanceof PermissionRequestBody.SedEdit sed) {
            this.approvalUpdatedInput = sed.updatedInput();
        }
        this.specialBodyLines.clear();
        this.requestedPermissionsLines.clear();
        removeAllComponents();

        TextColor accent = exitPlanRequest || enterPlanRequest
            ? LanternaTheme.modePlan() : LanternaTheme.permission();
        this.currentAccent = accent;

// marginTop=1 — blank line above.
        addComponent(new EmptySpace(new TerminalSize(0, 1)));


        Label rule = new Label("─".repeat(240));
        rule.setForegroundColor(accent);
        addComponent(rule);

        // PermissionRequestTitle keeps the worker identity on the same row as
        // the bold title ("Title · @worker"), not on a separate visual row.
        Panel titleRow = new Panel(new LinearLayout(Direction.HORIZONTAL));
        titleLabel = new Label(" " + requestBody.title());
        if (emptyExitPlan) titleLabel.setText(" Exit plan mode?");
        titleLabel.setForegroundColor(accent);
        titleLabel.addStyle(SGR.BOLD);
        titleRow.addComponent(titleLabel);
        if (StringUtils.isNotBlank(ctx.workerId())) {
            Label badge = new Label(" · @" + ctx.workerId());
            badge.setForegroundColor(LanternaTheme.divider());
            titleRow.addComponent(badge);
        }
        addComponent(titleRow);
        String fileSubtitle = requestBody instanceof PermissionRequestBody.FileChange change
            ? change.subtitle() : requestBody instanceof PermissionRequestBody.NotebookEdit notebook
            ? notebook.subtitle() : requestBody instanceof PermissionRequestBody.SedEdit sed
            ? sed.subtitle() : "";
        if (!StringUtils.isBlank(fileSubtitle)) {
            subtitleLabel = new Label(" " + fileSubtitle);
            subtitleLabel.setForegroundColor(LanternaTheme.divider());
            addComponent(subtitleLabel);
        }

        boolean planPreviewInTranscript = exitPlanRequest && ctx.input() != null
            && ctx.input().path("_uiPlanPreviewInTranscript").asBoolean(false);
        if (exitPlanRequest && !emptyExitPlan && !planPreviewInTranscript) {
            addComponent(new EmptySpace(new TerminalSize(0, 1)));
            addComponent(new Label(" Here is Claude's plan:"));
            String plan = exactTextField(ctx.input(), "plan");
            planContentLabel = new Label(formatPlanContent(plan));
            addComponent(planContentLabel);
        }


        if (Strings.CS.equals("rule", ctx.decisionReasonType()) && ctx.decisionReasonDetail() != null) {
            Label ruleExpl = new Label("   Permission rule `" + ctx.decisionReasonDetail()
                + "` requires confirmation for this command.");
            ruleExpl.setForegroundColor(LanternaTheme.divider());
            addComponent(ruleExpl);
        }

        List<String> requestedPermissions = requestedPermissionLines(ctx.input());
        if (!requestedPermissions.isEmpty()) {
            requestedPermissionsLines.add("Requested permissions:");
            requestedPermissionsLines.addAll(requestedPermissions);
            Label heading = new Label(" Requested permissions:");
            heading.addStyle(SGR.BOLD);
            addComponent(heading);
            for (String line : requestedPermissions) {
                Label permission = new Label(" " + line);
                permission.setForegroundColor(LanternaTheme.divider());
                addComponent(permission);
            }
        }

        // Command / path summary
        if (requestBody instanceof PermissionRequestBody.FileChange change) {
            addFileChangeBody(change);
        } else if (requestBody instanceof PermissionRequestBody.NotebookEdit notebook) {
            addNotebookEditBody(notebook);
        } else if (requestBody instanceof PermissionRequestBody.SedEdit sed) {
            addSedEditBody(sed);
        } else if (requestBody instanceof PermissionRequestBody.Mcp mcp) {
            addMcpBody(mcp);
        } else if (requestBody instanceof PermissionRequestBody.Generic generic) {
            if (!exitPlanRequest && !StringUtils.isBlank(generic.summary())) {
                addComponent(new EmptySpace(new TerminalSize(0, 1)));
                addComponent(new Label(indentWrapped(generic.summary(), 72, "   ")));
            }
            if (!StringUtils.isBlank(generic.description())) {
                descriptionLabel = new Label("   " + generic.description());
                descriptionLabel.setForegroundColor(LanternaTheme.divider());
                addComponent(descriptionLabel);
            }
        }

// Explainer section — inserted here, initially invisible (zero-height) Main text
// (explanation + reasoning) uses default white color Risk line uses green/yellow/red based
// on riskLevel.
        explainerLabel = new Label("");
        explainerLabel.setVisible(false);
        addComponent(explainerLabel);
        explainerRiskLabel = new Label("");
        explainerRiskLabel.setVisible(false);
        addComponent(explainerRiskLabel);
        debugLabel = new Label("");
        debugLabel.setForegroundColor(LanternaTheme.welcomeDim());
        debugLabel.setVisible(false);
        addComponent(debugLabel);


        if (StringUtils.isNotBlank(ctx.destructiveWarning())) {
            addComponent(new EmptySpace(new TerminalSize(0, 1)));
            Label warn = new Label("   " + ctx.destructiveWarning());
            warn.setForegroundColor(LanternaTheme.statusCost());  // warning color
            addComponent(warn);
        }

        if (enterPlanRequest) {
            addEnterPlanModeBody();
        }

        addComponent(new EmptySpace(new TerminalSize(0, 1)));
        String questionText = enterPlanRequest
            ? "Claude wants to enter plan mode to explore and design an implementation approach."
            : emptyExitPlan ? "Claude wants to exit plan mode"
            : requestBody instanceof PermissionRequestBody.FileChange change
            ? change.question() : requestBody instanceof PermissionRequestBody.NotebookEdit notebook
            ? notebook.question() : requestBody instanceof PermissionRequestBody.SedEdit sed
            ? sed.question() : (exitPlanRequest
            ? "Claude has written up a plan and is ready to execute. Would you like to proceed?"
            : (ctx.customMessage() == null ? "Do you want to proceed?" : ctx.customMessage()));
        questionLabel = new Label(" " + questionText);
        questionLabel.setForegroundColor(LanternaTheme.divider());
        addComponent(questionLabel);

        // Options panel — 1. Yes / [2. Yes, allow …] / N. No — stored as field for inline-amend
        List<PermissionUpdate> effectiveSuggestions = emptyExitPlan ? List.of() : exitPlanRequest
            ? exitPlanSecondaryUpdates(ctx) : effectiveSuggestions(ctx);
        boolean hasSuggestion = !effectiveSuggestions.isEmpty();
        int noNumber = clearContextExit ? 4 : hasSuggestion ? 3 : 2;

        optionsPanel = new Panel(new LinearLayout(Direction.VERTICAL));


        yesBtn = new AmendButton(enterPlanRequest ? "1. Yes, enter plan mode"
                : emptyExitPlan ? "1. Yes" : primaryOptionLabel(ctx),
            clearContextExit ? () -> resolveClearContext(ctx) : this::resolvePrimaryApproval,
            !exitPlanRequest && !enterPlanRequest, true);
        yesBtn.setRenderer(new PermissionOptionRenderer());
        optionsPanel.addComponent(yesBtn);

        if (clearContextExit) {
            keepContextBtn = new AmendButton("2. " + elevatedApprovalLabel(ctx, false),
                () -> resolveWithSuggestions(exitPlanPrimaryUpdates(ctx)), false, true);
            keepContextBtn.setRenderer(new PermissionOptionRenderer());
            optionsPanel.addComponent(keepContextBtn);
        }

        if (hasSuggestion) {

            allowRuleBtn = new AmendButton(
                (clearContextExit ? "3. " : "2. ") + (exitPlanRequest
                    ? "Yes, manually approve edits" : suggestionOptionLabel(ctx)),
                () -> resolveWithSuggestions(effectiveSuggestions),
                false, true);
            allowRuleBtn.setRenderer(new PermissionOptionRenderer());
            optionsPanel.addComponent(allowRuleBtn);
        } else {
            allowRuleBtn = null;
        }




        String noLabel = enterPlanRequest ? "2. No, start implementing now"
            : noNumber + (exitPlanRequest && !emptyExitPlan
                ? ". No, keep planning" : ". No");
        noBtn = new AmendButton(noLabel,
            exitPlanRequest ? () -> toggleAmend(false) : () -> resolveFromButton(false, false),
            !enterPlanRequest, false);
        noBtn.setRenderer(new PermissionOptionRenderer());
        optionsPanel.addComponent(noBtn);
        addComponent(optionsPanel);

        // Footer hint — stored as field so AmendButton.onEnterFocus can update it dynamically

        boolean hasExplainer = explainerCb != null;
        String initialHint = " Esc to cancel"
            + (hasExplainer ? " · ctrl+e to explain" : "");
        addComponent(new EmptySpace(new TerminalSize(0, 1)));
        hintLabel = new Label(initialHint);
        hintLabel.setForegroundColor(LanternaTheme.divider());
        addComponent(hintLabel);

        if (exitPlanRequest) {
            String planPath = exactTextField(ctx.input(), "planFilePath");
            if (!StringUtils.isBlank(planPath)) {
                editorHintLabel = new Label(" ctrl-g to edit in $EDITOR · " + planPath);
                editorHintLabel.setForegroundColor(LanternaTheme.divider());
                addComponent(editorHintLabel);
            }
        }

        invalidate();
        yesBtn.takeFocus();
    }

    /**
     * Cancellation-aware mount of an already prepared immutable prompt.
     */
    public PermissionAskCallback.Result showAndWait(MultiWindowTextGUI gui,
                        PreparedPermissionPrompt prepared,
                        PermissionExplainerCallback explainerCb,
                        Consumer<List<PermissionUpdate>> onApplySuggestions,
                        Runnable onClose,
                        Consumer<String> planChangedCallback,
                        BooleanSupplier cancelled) {
        this.guiRef = gui;
        this.onPlanChanged = planChangedCallback == null ? _ -> {} : planChangedCallback;
        BlockingQueue<PermissionAskCallback.Result> queue = new ArrayBlockingQueue<>(1);
        Consumer<PermissionAskCallback.Result> complete = result -> {
            try {
                queue.put(result);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        };
        gui.getGUIThread().invokeLater(() -> {
            if (cancelled != null && cancelled.getAsBoolean()) {
                complete.accept(PermissionAskCallback.Result.deny());
                if (onClose != null) onClose.run();
                return;
            }
            show(prepared, explainerCb, onApplySuggestions, complete, onClose);
            if (cancelled != null && cancelled.getAsBoolean()) cancelPending();
        });
        try {
            return queue.take();
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return PermissionAskCallback.Result.deny();
        }
    }

    /**
     * Dismisses a pending local prompt after another endpoint answered first.
     * The synthetic deny only unblocks {@link #showAndWait}; the shared
     * InteractionCoordinator has already removed the request, so this result cannot
     * override the authoritative remote answer.
     */
    public void cancelPending() {
        resolve(false);
    }

    /** Returns {@code (0,0)} when idle so the parent layout collapses us. */
    @Override
    public synchronized TerminalSize calculatePreferredSize() {
        if (!active) return new TerminalSize(0, 0);
        return super.calculatePreferredSize();
    }

    /** Suppress focus while idle. */
    @Override
    public Interactable nextFocus(Interactable fromThis) {
        return active ? super.nextFocus(fromThis) : null;
    }

    @Override
    public Interactable previousFocus(Interactable fromThis) {
        return active ? super.previousFocus(fromThis) : null;
    }

    // ── Internal helpers ─────────────────────────────────────────────────────

    private void addMcpBody(PermissionRequestBody.Mcp mcp) {
        addComponent(new EmptySpace(new TerminalSize(0, 1)));
        Label invocation = new Label("   " + mcp.invocation());
        addComponent(invocation);
        specialBodyLines.add(mcp.invocation());
        if (!StringUtils.isBlank(mcp.description())) {
            descriptionLabel = new Label(indentWrapped(mcp.description(), 72, "   "));
            descriptionLabel.setForegroundColor(LanternaTheme.divider());
            addComponent(descriptionLabel);
        }
    }

    private void addFileChangeBody(PermissionRequestBody.FileChange change) {
        addComponent(new EmptySpace(new TerminalSize(0, 1)));
        Label topRule = new Label("   " + "─".repeat(72));
        topRule.setForegroundColor(LanternaTheme.divider());
        addComponent(topRule);

        MessagePanel diffPanel = new MessagePanel();
        String language = languageForPath(change.filePath());
        if (!change.contentPreview().isEmpty() || change.hunks().isEmpty()) {
            String preview = change.contentPreview().isEmpty()
                ? "(No content)" : change.contentPreview();
            appendCodePreview(diffPanel, preview, language);
        } else {
            for (int hunkIndex = 0; hunkIndex < change.hunks().size(); hunkIndex++) {
                if (hunkIndex > 0) appendDiffSeparator(diffPanel);
                var hunk = change.hunks().get(hunkIndex);
                for (DiffRenderer.DiffLineView line : DiffRenderer.renderHunk(hunk, language)) {
                    diffPanel.appendMixed(toDiffSegments(line));
                    specialBodyLines.add(plainDiffLine(line));
                }
            }
        }
        addComponent(diffPanel);

        Label bottomRule = new Label("   " + "─".repeat(72));
        bottomRule.setForegroundColor(LanternaTheme.divider());
        addComponent(bottomRule);
        if (!StringUtils.isBlank(change.warning())) {
            Label warning = new Label(indentWrapped(change.warning(), 72, "   "));
            warning.setForegroundColor(LanternaTheme.statusCost());
            addComponent(warning);
        }
    }

    private void addNotebookEditBody(PermissionRequestBody.NotebookEdit notebook) {
        addComponent(new EmptySpace(new TerminalSize(0, 1)));
        Label path = new Label("   " + notebook.subtitle());
        path.addStyle(SGR.BOLD);
        addComponent(path);
        Label description = new Label("   " + notebook.description());
        description.setForegroundColor(LanternaTheme.divider());
        addComponent(description);
        specialBodyLines.add(notebook.subtitle());
        specialBodyLines.add(notebook.description());

        MessagePanel preview = new MessagePanel();
        if (!notebook.hunks().isEmpty()) {
            for (int hunkIndex = 0; hunkIndex < notebook.hunks().size(); hunkIndex++) {
                if (hunkIndex > 0) appendDiffSeparator(preview);
                var hunk = notebook.hunks().get(hunkIndex);
                for (DiffRenderer.DiffLineView line : DiffRenderer.renderHunk(hunk, notebook.language())) {
                    preview.appendMixed(toDiffSegments(line));
                    specialBodyLines.add(plainDiffLine(line));
                }
            }
        } else {
            appendCodePreview(preview,
                notebook.contentPreview().isEmpty() ? "(No content)" : notebook.contentPreview(),
                notebook.language());
        }
        addComponent(preview);
    }

    private void addSedEditBody(PermissionRequestBody.SedEdit sed) {
        addComponent(new EmptySpace(new TerminalSize(0, 1)));
        if (sed.hunks().isEmpty()) {
            Label noChanges = new Label("   " + sed.noChangesMessage());
            noChanges.setForegroundColor(LanternaTheme.divider());
            addComponent(noChanges);
            specialBodyLines.add(sed.noChangesMessage());
            return;
        }
        MessagePanel diff = new MessagePanel();
        String language = languageForPath(sed.filePath());
        for (int hunkIndex = 0; hunkIndex < sed.hunks().size(); hunkIndex++) {
            if (hunkIndex > 0) appendDiffSeparator(diff);
            var hunk = sed.hunks().get(hunkIndex);
            for (DiffRenderer.DiffLineView line : DiffRenderer.renderHunk(hunk, language)) {
                diff.appendMixed(toDiffSegments(line));
                specialBodyLines.add(plainDiffLine(line));
            }
        }
        addComponent(diff);
    }

    private void appendCodePreview(MessagePanel panel, String content, String language) {
        var tokenized = UiSettings.readSyntaxHighlightingDisabled() ? null
            : TmTokenizer.tokenize(content, language);
        String[] lines = content.split("\\n", -1);
        int visible = Strings.CS.endsWith(content, "\n") ? lines.length - 1 : lines.length;
        for (int i = 0; i < visible; i++) {
            List<MessagePanel.Segment> segments = new ArrayList<>();
            segments.add(new MessagePanel.Segment("   ", LanternaTheme.welcomeDim()));
            if (tokenized != null && i < tokenized.lines().size()) {
                int cursor = 0;
                for (var token : tokenized.lines().get(i)) {
                    if (token.start() > cursor) {
                        segments.add(new MessagePanel.Segment(
                            lines[i].substring(cursor, token.start()), TextColor.ANSI.DEFAULT));
                    }
                    int end = Math.min(lines[i].length(), token.end());
                    String tokenText = lines[i].substring(token.start(), end);
                    var color = ScopeColorMap.scopeColor(
                        token.scopes(), tokenText, LanternaTheme.activeThemeName());
                    segments.add(new MessagePanel.Segment(tokenText,
                        color == null ? TextColor.ANSI.DEFAULT : LanternaTheme.toLC(color),
                        null, null, lanternaStyles(
                            ScopeColorMap.scopeStyle(token.scopes()))));
                    cursor = end;
                }
                if (cursor < lines[i].length()) {
                    segments.add(new MessagePanel.Segment(lines[i].substring(cursor),
                        TextColor.ANSI.DEFAULT));
                }
            } else {
                segments.add(new MessagePanel.Segment(lines[i], TextColor.ANSI.DEFAULT));
            }
            panel.appendMixed(segments);
            specialBodyLines.add(lines[i]);
        }
    }

    private void appendDiffSeparator(MessagePanel panel) {
        panel.appendMixed(List.of(
            new MessagePanel.Segment("   ...", LanternaTheme.welcomeDim())));
        specialBodyLines.add("...");
    }

    private static Set<SGR> lanternaStyles(
            Set<AnsiStyle> styles) {
        if (styles == null || styles.isEmpty()) return Set.of();
        Set<SGR> result = new HashSet<>();
        if (styles.contains(AnsiStyle.BOLD)) result.add(SGR.BOLD);
        if (styles.contains(AnsiStyle.ITALIC)) result.add(SGR.ITALIC);
        if (styles.contains(AnsiStyle.UNDERLINE)) result.add(SGR.UNDERLINE);
        return Set.copyOf(result);
    }

    private static List<MessagePanel.Segment> toDiffSegments(DiffRenderer.DiffLineView line) {
        String gutter = line.lineNo() == null ? "    " : String.format("%3d ", line.lineNo());
        LanternaTheme.DiffRenderPalette palette = LanternaTheme.diffRenderPalette();
        TextColor barBackground = switch (line.marker()) {
            case '+' -> palette.addedLineBackground();
            case '-' -> palette.removedLineBackground();
            default -> null;
        };
        TextColor gutterColor = switch (line.marker()) {
            case '+' -> palette.addedDecoration();
            case '-' -> palette.removedDecoration();
            case '@' -> LanternaTheme.subtle();
            default -> LanternaTheme.welcomeDim();
        };
        List<MessagePanel.Segment> result = new ArrayList<>();
        result.add(new MessagePanel.Segment("   " + gutter + markerText(line.marker()),
            gutterColor, barBackground));
        for (DiffRenderer.Segment segment : line.segments()) {
            TextColor foreground = segment.foreground() == null
                ? TextColor.ANSI.DEFAULT : LanternaTheme.toLC(segment.foreground());
            TextColor background = switch (segment.kind()) {
                case ADDED -> palette.addedWordBackground();
                case REMOVED -> palette.removedWordBackground();
                default -> barBackground;
            };
            if (segment.kind() == DiffRenderer.SegKind.HUNK) {
                foreground = LanternaTheme.subtle();
                background = null;
            }
            result.add(new MessagePanel.Segment(segment.text(), foreground, background));
        }
        return result;
    }

    private static String markerText(char marker) {
        return marker == '@' ? "" : marker + " ";
    }

    private static String plainDiffLine(DiffRenderer.DiffLineView line) {
        StringBuilder text = new StringBuilder();
        if (line.marker() != '@') text.append(line.marker());
        for (DiffRenderer.Segment segment : line.segments()) text.append(segment.text());
        return text.toString();
    }

    private static String languageForPath(String path) {
        if (StringUtils.isBlank(path)) return null;
        String normalized = path.replace('\\', '/');
        String name = normalized.substring(normalized.lastIndexOf('/') + 1)
            .toLowerCase(Locale.ROOT);
        if (Strings.CS.equals(name, "dockerfile")) return "dockerfile";
        if (Strings.CS.equals(name, "makefile")) return "makefile";
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return null;
        return switch (name.substring(dot + 1)) {
            case "mjs", "cjs" -> "javascript";
            case "mts", "cts" -> "typescript";
            case "yml" -> "yaml";
            case "sh", "bash", "zsh" -> "shell";
            default -> name.substring(dot + 1);
        };
    }

    private void hide() {
        active = false;
        resultConsumer = null;
        amendBox = null;
        amendPrefixLabel = null;
        Runnable closer = onClose;
        onClose = null;
        removeAllComponents();
        invalidate();
        if (closer != null) closer.run();
    }

    private void resolve(boolean allowed) {
        resolveWithFeedback(allowed, null);
    }

    /** Resolve with optional accept/reject feedback (Tab amend text). */
    private void resolveWithFeedback(boolean allowed, String feedback) {
        if (!active) return;
        Consumer<PermissionAskCallback.Result> cb = resultConsumer;
        PermissionAskCallback.Result r;
        if (allowed && approvalUpdatedInput != null) {
            r = PermissionAskCallback.Result.allowWithInputAndFeedback(
                approvalUpdatedInput, feedback);
        } else if (!allowed && exitPlanRequest && !amendFeedbackBlocks.isEmpty()) {
            List<ContentBlock> attached = selectedPlanFeedbackImages(feedback);
            String textFeedback = stripPlanFeedbackImageChips(feedback);
            if (attached.isEmpty()) {
                r = StringUtils.isBlank(textFeedback)
                    ? PermissionAskCallback.Result.deny()
                    : PermissionAskCallback.Result.denyWithFeedback(textFeedback);
            } else {
                String modelFeedback = StringUtils.isBlank(textFeedback)
                    ? "(See attached image)" : textFeedback;
                r = PermissionAskCallback.Result.denyWithFeedback(modelFeedback, attached);
            }
        } else if (StringUtils.isBlank(feedback)) {
            r = allowed ? PermissionAskCallback.Result.allow()
                : PermissionAskCallback.Result.deny();
        } else {
            r = allowed ? PermissionAskCallback.Result.allowWithFeedback(feedback)
                : PermissionAskCallback.Result.denyWithFeedback(feedback);
        }
        hide();
        if (cb != null) cb.accept(r);
    }

    private void resolvePrimaryApproval() {
        if (exitPlanRequest && onApplySuggestions != null
                && !primaryApprovalUpdates.isEmpty()) {
            onApplySuggestions.accept(primaryApprovalUpdates);
        }
        resolveFromButton(true, false);
    }

    private void resolveClearContext(PermissionAskContext ctx) {
        resolveClearContext(ctx, null);
    }

    private void resolveClearContext(PermissionAskContext ctx, String feedback) {
        boolean auto = ctx.input() != null
            && ctx.input().path("_uiAutoModeAvailable").asBoolean(false);
        boolean bypass = ctx.input() != null
            && ctx.input().path("_uiBypassPermissionsAvailable").asBoolean(false);
        PermissionModeKind mode = bypass ? PermissionModeKind.BYPASS_PERMISSIONS
            : auto ? PermissionModeKind.AUTO : PermissionModeKind.ACCEPT_EDITS;
        onPlanClearApproval.accept(new PlanClearApproval(
            exactTextField(ctx.input(), "plan"), mode,
            exitPlanUpdates(ctx, mode), StringUtils.trimToNull(feedback)));
        resolve(false);
    }

    /** Package-private for {@code ExitPlanModePermissionDialogTest}. */
    void approvePlanFeedback(String feedback) {
        if (currentCtx == null) return;
        if (currentCtx.input() != null
                && currentCtx.input().path("_uiShowClearContext").asBoolean(false)) {
            resolveClearContext(currentCtx, feedback);
            return;
        }
        if (onApplySuggestions != null) {
            onApplySuggestions.accept(exitPlanPrimaryUpdates(currentCtx));
        }
        resolveWithFeedback(true, StringUtils.trimToNull(feedback));
    }

    /** Called by the Yes/No buttons — closes amend mode before resolving.
     *  When the dialog is in amend mode the {@code amendBox}'s current text is
     *  read as feedback and routed via {@link #resolveWithFeedback}. */
    private void resolveFromButton(boolean allowed, boolean withAmend) {
        if (withAmend && amendBox != null) {
            String fb = amendBox.getText();
            if (!allowed && exitPlanRequest
                    && StringUtils.isBlank(stripPlanFeedbackImageChips(fb))
                    && selectedPlanFeedbackImages(fb).isEmpty()) {
                return;
            }
            resolveWithFeedback(allowed, fb);
            return;
        }
        resolve(allowed);
    }


    private void resolveWithSuggestions(List<PermissionUpdate> suggestions) {
        if (!active) return;
        Consumer<List<PermissionUpdate>> callback = onApplySuggestions;
        if (callback != null && suggestions != null && !suggestions.isEmpty()) {
            callback.accept(suggestions);
        }
        resolve(true);
    }

    private static List<PermissionUpdate> effectiveSuggestions(PermissionAskContext ctx) {
        if (!ctx.suggestions().isEmpty()) return ctx.suggestions();
        if (StringUtils.isBlank(ctx.suggestionRuleContent())) {
            return List.of();
        }
        return List.of(new PermissionUpdate.AddRules(
            List.of(new PermissionUpdate.RuleValue(
                ctx.toolName(), ctx.suggestionRuleContent())),
            PermissionUpdate.Behavior.ALLOW,
            PermissionUpdate.Destination.LOCAL_SETTINGS));
    }

    private static List<PermissionUpdate> exitPlanPrimaryUpdates(PermissionAskContext ctx) {
        boolean auto = ctx.input() != null
            && ctx.input().path("_uiAutoModeAvailable").asBoolean(false);
        boolean bypass = ctx.input() != null
            && ctx.input().path("_uiBypassPermissionsAvailable").asBoolean(false);
        return exitPlanUpdates(ctx,
            bypass ? PermissionModeKind.BYPASS_PERMISSIONS
                : auto ? PermissionModeKind.AUTO : PermissionModeKind.ACCEPT_EDITS);
    }

    private static List<PermissionUpdate> exitPlanSecondaryUpdates(PermissionAskContext ctx) {
        return exitPlanUpdates(ctx, PermissionModeKind.DEFAULT);
    }


    private static List<PermissionUpdate> exitPlanUpdates(
            PermissionAskContext ctx, PermissionModeKind mode) {
        List<PermissionUpdate> updates = new ArrayList<>();
        updates.add(new PermissionUpdate.SetMode(
            mode, PermissionUpdate.Destination.SESSION));
        JsonNode prompts = ctx.input() == null ? null : ctx.input().get("allowedPrompts");
        if (prompts == null || !prompts.isArray()) return List.copyOf(updates);
        List<PermissionUpdate.RuleValue> rules = new ArrayList<>();
        for (JsonNode prompt : prompts) {
            String tool = exactTextField(prompt, "tool");
            String description = exactTextField(prompt, "prompt").trim();
            if (StringUtils.isBlank(tool) || StringUtils.isBlank(description)) continue;
            rules.add(new PermissionUpdate.RuleValue(
                tool, "prompt: " + description));
        }
        if (!rules.isEmpty()) {
            updates.add(new PermissionUpdate.AddRules(
                rules, PermissionUpdate.Behavior.ALLOW,
                PermissionUpdate.Destination.SESSION));
        }
        return List.copyOf(updates);
    }

    private static List<String> requestedPermissionLines(JsonNode input) {
        JsonNode prompts = input == null ? null : input.get("allowedPrompts");
        if (prompts == null || !prompts.isArray()) return List.of();
        List<String> lines = new ArrayList<>();
        for (JsonNode prompt : prompts) {
            String tool = exactTextField(prompt, "tool");
            String description = exactTextField(prompt, "prompt").trim();
            if (StringUtils.isBlank(tool) || StringUtils.isBlank(description)) continue;
            lines.add("  · " + tool + "(prompt: " + description + ")");
        }
        return List.copyOf(lines);
    }

    private void addEnterPlanModeBody() {
        List<String> lines = List.of(
            "In plan mode, Claude will:",
            " · Explore the codebase thoroughly",
            " · Identify existing patterns",
            " · Design an implementation strategy",
            " · Present a plan for your approval",
            "No code changes will be made until you approve the plan.");
        specialBodyLines.addAll(lines);
        addComponent(new EmptySpace(new TerminalSize(0, 1)));
        for (int index = 0; index < lines.size(); index++) {
            if (index == lines.size() - 1) {
                addComponent(new EmptySpace(new TerminalSize(0, 1)));
            }
            Label line = new Label(" " + lines.get(index));
            line.setForegroundColor(LanternaTheme.divider());
            addComponent(line);
        }
    }

    private String primaryOptionLabel(PermissionAskContext ctx) {
        if (!exitPlanRequest) return "1. Yes";
        boolean clear = ctx.input() != null
            && ctx.input().path("_uiShowClearContext").asBoolean(false);
        if (clear) {
            JsonNode used = ctx.input().get("_uiContextUsedPercent");
            String usedLabel = used != null && used.isNumber()
                ? " (" + Math.clamp(used.asInt(), 0, 100) + "% used)" : "";
            return "1. Yes, clear context" + usedLabel + " and "
                + elevatedApprovalLabel(ctx, true);
        }
        return "1. " + elevatedApprovalLabel(ctx, false);
    }

    private static String elevatedApprovalLabel(PermissionAskContext ctx, boolean clearContext) {
        boolean auto = ctx.input() != null
            && ctx.input().path("_uiAutoModeAvailable").asBoolean(false);
        boolean bypass = ctx.input() != null
            && ctx.input().path("_uiBypassPermissionsAvailable").asBoolean(false);
        if (bypass) return clearContext ? "bypass permissions" : "Yes, and bypass permissions";
        if (auto) return clearContext ? "use auto mode" : "Yes, and use auto mode";
        return clearContext ? "auto-accept edits" : "Yes, auto-accept edits";
    }


    private static String suggestionOptionLabel(PermissionAskContext ctx) {
        List<PermissionUpdate> suggestions = effectiveSuggestions(ctx);
        if (!(Strings.CS.equals("Bash", ctx.toolName())
                || Strings.CS.equals("PowerShell", ctx.toolName()))) {
            return StringUtils.isBlank(ctx.suggestionLabel())
                ? "Yes, and apply suggested permissions"
                : "Yes, allow " + ctx.suggestionLabel();
        }

        List<PermissionUpdate.RuleValue> allRules = new ArrayList<>();
        List<String> directories = new ArrayList<>();
        for (PermissionUpdate suggestion : suggestions) {
            if (suggestion instanceof PermissionUpdate.AddRules add) {
                allRules.addAll(add.rules());
            } else if (suggestion instanceof PermissionUpdate.AddDirectories add) {
                directories.addAll(add.directories());
            }
        }
        List<String> readPaths = allRules.stream()
            .filter(rule -> Strings.CS.equals("Read", rule.toolName()))
            .map(PermissionUpdate.RuleValue::ruleContent)
            .filter(StringUtils::isNotBlank)
            .map(PermissionDialog::stripRecursiveGlob)
            .toList();
        List<String> commands = allRules.stream()
            .filter(rule -> Strings.CS.equals(ctx.toolName(), rule.toolName()))
            .map(PermissionUpdate.RuleValue::ruleContent)
            .filter(StringUtils::isNotBlank)
            .map(PermissionDialog::extractCommandPrefix)
            .distinct()
            .toList();

        boolean hasDirectories = !directories.isEmpty();
        boolean hasReads = !readPaths.isEmpty();
        boolean hasCommands = !commands.isEmpty();
        if (hasReads && !hasDirectories && !hasCommands) {
            return "Yes, allow reading from " + formatPathList(readPaths) + " from this project";
        }
        if (hasDirectories && !hasReads && !hasCommands) {
            return "Yes, and always allow access to "
                + formatPathList(directories) + " from this project";
        }
        if (hasCommands && !hasDirectories && !hasReads) {
            return "Yes, and don't ask again for " + commandList(commands)
                + " commands in " + System.getProperty("user.dir", ".");
        }
        if ((hasDirectories || hasReads) && !hasCommands) {
            List<String> paths = new ArrayList<>(directories);
            paths.addAll(readPaths);
            return "Yes, and always allow access to " + formatPathList(paths)
                + " from this project";
        }
        if (hasDirectories || hasReads) {
            List<String> paths = new ArrayList<>(directories);
            paths.addAll(readPaths);
            if (paths.size() == 1 && commands.size() == 1) {
                return "Yes, and allow access to " + formatPathList(paths)
                    + " and " + commandList(commands) + " commands";
            }
            return "Yes, and allow " + formatPathList(paths) + " access and "
                + commandList(commands) + " commands";
        }
        return StringUtils.isBlank(ctx.suggestionLabel())
            ? "Yes, and apply suggested permissions"
            : "Yes, allow " + ctx.suggestionLabel();
    }

    private static String stripRecursiveGlob(String value) {
        return Strings.CS.endsWith( value, "/**") ? value.substring(0, value.length() - 3) : value;
    }

    private static String extractCommandPrefix(String value) {
        String prefix =Strings.CS.endsWith( value, ":*") ? value.substring(0, value.length() - 2) : value;
        return prefix.replaceAll("\\s+>{1,2}\\s*\\S+", "").strip();
    }

    private static String commandList(List<String> commands) {
        String joined = String.join(", ", commands);
        if (joined.length() > 50) return "similar";
        return switch (commands.size()) {
            case 0 -> "";
            case 1 -> commands.getFirst();
            case 2 -> commands.getFirst() + " and " + commands.get(1);
            default -> String.join(", ", commands.subList(0, commands.size() - 1))
                + ", and " + commands.getLast();
        };
    }

    private static String formatPathList(List<String> paths) {
        List<String> names = paths.stream().map(PermissionDialog::baseName).toList();
        return switch (names.size()) {
            case 0 -> "";
            case 1 -> names.getFirst() + "/";
            case 2 -> names.getFirst() + "/ and " + names.get(1) + "/";
            default -> names.getFirst() + "/, " + names.get(1) + "/ and "
                + (names.size() - 2) + " more";
        };
    }

    private static String baseName(String path) {
        if (StringUtils.isBlank(path)) return "";
        String normalized = path.replace('\\', '/');
        while (normalized.length() > 1 &&Strings.CS.endsWith( normalized, "/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        int slash = normalized.lastIndexOf('/');
        return slash < 0 ? normalized : normalized.substring(slash + 1);
    }

    /**
     * Toggle Tab-to-amend mode on either the Yes (Allow) or No (Reject) option.
     */
    private void toggleAmend(boolean forYes) {
        if (optionsPanel == null || yesBtn == null || noBtn == null) return;
        if (amendBox != null) { cancelAmend(); return; }

        amendingYes = forYes;
        // Compose the prefix label exactly as the unamended option line would render:
        //   "❯ 1. Yes, "  /  "❯ 3. No, "  (number == noBtn's leading "N." if forYes=false)
        String prefix = forYes
            ? " ❯ 1. Yes, "
            : " ❯ " + noBtn.getLabel() + ", ";    // noBtn label already includes "2." or "3."
        Panel inlineRow = new Panel(new LinearLayout(Direction.HORIZONTAL));
        Label prefixLabel = new Label(prefix);
        prefixLabel.setForegroundColor(LanternaTheme.suggestion());
        inlineRow.addComponent(prefixLabel);
        // Store so afterLeaveFocus/afterEnterFocus on the TextBox can toggle "❯ " ↔ "  "
        amendPrefixLabel = prefixLabel;


        final String placeholder = exitPlanRequest && !forYes
            ? "Tell Claude what to change"
            : (forYes ? "and tell Claude what to do next"
                      : "and tell Claude what to do differently");

        TextBox box = new TextBox(new TerminalSize(40, 1)) {
            @Override
            public synchronized Result handleKeyStroke(KeyStroke ks) {
                if (exitPlanRequest && !amendingYes && ks.getKeyType() == KeyType.PASTE
                        && ks instanceof PasteKeyStroke paste) {
                    if (StringUtils.isEmpty(paste.getPastedText())) {
                        pastePlanFeedbackImage(this);
                    } else {
                        setText(getText() + paste.getPastedText().replaceAll("\\R", " "));
                    }
                    return Result.HANDLED;
                }
                if (exitPlanRequest && !amendingYes && isCtrlV(ks)) {
                    pastePlanFeedbackImage(this);
                    return Result.HANDLED;
                }
                if (exitPlanRequest && isCtrlG(ks)) {
                    editPlanInExternalEditor();
                    return Result.HANDLED;
                }
                if (ks.getKeyType() == KeyType.ESCAPE) {


                    if (guiRef != null) {
                        guiRef.getGUIThread().invokeLater(() -> resolve(false));
                    }
                    return Result.HANDLED;
                }
                if (ks.getKeyType() == KeyType.ENTER) {
                    final boolean allowed = amendingYes;
                    if (guiRef != null) {
                        guiRef.getGUIThread().invokeLater(
                            () -> resolveFromButton(allowed, true));
                    }
                    return Result.HANDLED;
                }
                if (exitPlanRequest && ks.getKeyType() == KeyType.TAB && ks.isShiftDown()) {
                    String feedback = this.getText();
                    if (guiRef != null) {
                        guiRef.getGUIThread().invokeLater(
                            () -> approvePlanFeedback(feedback));
                    }
                    return Result.HANDLED;
                }

                // `if (key.downArrow || key.upArrow) state.focusNextOption/PreviousOption`,
                // which fires handleFocus(value) → useShellPermissionFeedback.handleFocus:
// `if (value !== 'yes' && yesInputMode && !acceptFeedback.trim) setYesInputMode(false)`.
                // i.e. arrow navigates AND only exits amend mode when the TextBox is empty.
                if (ks.getKeyType() == KeyType.ARROW_UP || ks.getKeyType() == KeyType.ARROW_DOWN) {
                    final boolean isUp = ks.getKeyType() == KeyType.ARROW_UP;
                    final String currentText = this.getText();
                    final boolean isEmpty = StringUtils.isBlank(currentText);
                    if (guiRef != null) {
                        guiRef.getGUIThread().invokeLater(() -> {
                            if (isEmpty) {
                                // Exit amend mode AND navigate to prev/next option.
                                cancelAmendAndNavigate(isUp);
                            } else {
// Keep amend mode active; move focus to neighbor option.
                                Interactable target = isUp ? previousOption() : nextOption();
                                if (target != null) target.takeFocus();
                            }
                        });
                    }
                    return Result.HANDLED;
                }
                return super.handleKeyStroke(ks);
            }

// Swap "❯ " → " " when focus leaves the amendBox so that the neighboring option's own ❯
// (drawn by PermissionOptionRenderer) is the only pointer on screen.
            @Override
            protected void afterLeaveFocus(Interactable.FocusChangeDirection direction,
                                           Interactable nextInFocus) {
                super.afterLeaveFocus(direction, nextInFocus);
                if (amendPrefixLabel != null) {
                    String cur = amendPrefixLabel.getText();
                    if (cur != null && Strings.CS.startsWith(cur, " ❯ ")) {
                        amendPrefixLabel.setText("   " + cur.substring(3));
                    }
                    amendPrefixLabel.setForegroundColor(LanternaTheme.inputText());
                    invalidate();
                }
            }

            // Restore "❯ " and suggestion color when focus returns to the amendBox.
            @Override
            protected void afterEnterFocus(Interactable.FocusChangeDirection direction,
                                           Interactable previouslyInFocus) {
                super.afterEnterFocus(direction, previouslyInFocus);
                if (amendPrefixLabel != null) {
                    String cur = amendPrefixLabel.getText();
                    if (cur != null && Strings.CS.startsWith(cur, "   ") && cur.length() > 3) {
                        amendPrefixLabel.setText(" ❯ " + cur.substring(3));
                    }
                    amendPrefixLabel.setForegroundColor(LanternaTheme.suggestion());
                    invalidate();
                }
            }
        };
        // Install placeholder renderer — extends DefaultTextBoxRenderer, draws dim
        // placeholder text on top of the empty buffer when getText() is empty.
        box.setRenderer(new PlaceholderTextBoxRenderer(placeholder));
        amendBox = box;
        inlineRow.addComponent(amendBox);

        optionsPanel.removeAllComponents();
        if (forYes) {
            optionsPanel.addComponent(inlineRow);                    // amend replaces yesBtn
            if (allowRuleBtn != null) optionsPanel.addComponent(allowRuleBtn);
            optionsPanel.addComponent(noBtn);
        } else {
            optionsPanel.addComponent(yesBtn);
            if (allowRuleBtn != null) optionsPanel.addComponent(allowRuleBtn);
            optionsPanel.addComponent(inlineRow);                    // amend replaces noBtn
        }

        if (hintLabel != null) {
            hintLabel.setText(" Esc to cancel" + (explainer != null ? " · ctrl+e to explain" : ""));
        }
        invalidate();
        final TextBox amendBoxRef = amendBox;
        guiRef.getGUIThread().invokeLater(() -> {
            if (amendBoxRef == null || amendBox != amendBoxRef) return;
            if (guiRef != null && guiRef.getActiveWindow() != null) {
                guiRef.getActiveWindow().setFocusedInteractable(null);
                guiRef.getActiveWindow().setFocusedInteractable(amendBoxRef);
            } else {
                amendBoxRef.takeFocus();
            }
            invalidate();
        });
    }

    private void pastePlanFeedbackImage(TextBox target) {
        Thread.ofVirtual().name("plan-feedback-image-paste").start(() -> {
            ImagePaste.ImageWithDimensions image = ImagePaste.getImageFromClipboard();
            if (image == null) return;
            Runnable attach = () -> addPlanFeedbackImage(target, image.base64(), image.mediaType());
            if (guiRef != null) guiRef.getGUIThread().invokeLater(attach);
            else attach.run();
        });
    }

    private void addPlanFeedbackImage(TextBox target, String base64, String mediaType) {
        if (target == null || StringUtils.isBlank(base64)) return;
        amendFeedbackBlocks.add(new ImageBlock(ImagePaste.toImageSource(
            new ImagePaste.ImageWithDimensions(base64,
                StringUtils.defaultIfBlank(mediaType, "image/png"), null),
            JsonUtils.getMapper())));
        String chip = "[Image #" + amendFeedbackBlocks.size() + "]";
        String separator = StringUtils.isEmpty(target.getText()) ? "" : " ";
        target.setText(target.getText() + separator + chip);
        invalidate();
    }

    private List<ContentBlock> selectedPlanFeedbackImages(String feedback) {
        if (StringUtils.isEmpty(feedback)) return List.of();
        List<ContentBlock> selected = new ArrayList<>();
        for (int index = 0; index < amendFeedbackBlocks.size(); index++) {
            if (Strings.CS.contains(feedback, "[Image #" + (index + 1) + "]")) {
                selected.add(amendFeedbackBlocks.get(index));
            }
        }
        return List.copyOf(selected);
    }

    private static String stripPlanFeedbackImageChips(String feedback) {
        if (feedback == null) return null;
        return StringUtils.trimToNull(feedback.replaceAll("\\[Image #\\d+]", " "));
    }

    private static boolean isCtrlV(KeyStroke key) {
        return key != null && key.isCtrlDown() && key.getKeyType() == KeyType.CHARACTER
            && key.getCharacter() != null && Character.toLowerCase(key.getCharacter()) == 'v';
    }

/** Restore optionsPanel to icompatibility baseline button layout. */
    private void cancelAmend() {
        if (optionsPanel == null || yesBtn == null || noBtn == null || amendBox == null) return;
        boolean wasYes = amendingYes;
        amendBox = null;
        amendPrefixLabel = null;
        optionsPanel.removeAllComponents();
        optionsPanel.addComponent(yesBtn);
        if (allowRuleBtn != null) optionsPanel.addComponent(allowRuleBtn);
        optionsPanel.addComponent(noBtn);
        invalidate();
        // Restore focus to whichever button the user was amending
        (wasYes ? yesBtn : noBtn).takeFocus();
    }

/**
     * Exit amend mode AND move focus to the previous/next button (cycling).
     */
    private void cancelAmendAndNavigate(boolean up) {
        if (optionsPanel == null || yesBtn == null || noBtn == null || amendBox == null) return;
        // Determine the "current" button (the one being amended) before destroying state.
        AmendButton current = amendingYes ? yesBtn : noBtn;
        amendBox = null;
        amendPrefixLabel = null;
        optionsPanel.removeAllComponents();
        optionsPanel.addComponent(yesBtn);
        if (allowRuleBtn != null) optionsPanel.addComponent(allowRuleBtn);
        optionsPanel.addComponent(noBtn);
        invalidate();
        // Compute neighbor relative to `current`.
        List<AmendButton> opts = optionButtons();
        int idx = opts.indexOf(current);
        if (idx < 0) idx = 0;
        AmendButton target = opts.get(up
            ? (idx - 1 + opts.size()) % opts.size()
            : (idx + 1) % opts.size());
        target.takeFocus();
    }

/**
     * Updates the footer hint when focus moves between option buttons.
     */
    private void updateHintForButton(boolean canAmend) {
        if (hintLabel == null) return;
        boolean hasExplainer = (explainer != null);
        String base = " Esc to cancel";
        if (canAmend) base += " · Tab to amend";
        if (hasExplainer) base += " · ctrl+e to explain";
        hintLabel.setText(base);
        invalidate();
    }

/**
     * Ordered list of focusable interactables matching the on-screen option rows.
     */
    private List<Interactable> focusOrder() {
        List<Interactable> list = new ArrayList<>(3);
        if (amendBox != null && amendingYes) {
            list.add(amendBox);
        } else if (yesBtn != null) {
            list.add(yesBtn);
        }
        if (allowRuleBtn != null) list.add(allowRuleBtn);
        if (amendBox != null && !amendingYes) {
            list.add(amendBox);
        } else if (noBtn != null) {
            list.add(noBtn);
        }
        return list;
    }

    /** Ordered list of focusable option buttons (filters null allowRuleBtn).
     *  Excludes the amendBox — used only when amend mode is off. */
    private List<AmendButton> optionButtons() {
        List<AmendButton> list = new ArrayList<>(3);
        if (yesBtn != null)       list.add(yesBtn);
        if (allowRuleBtn != null) list.add(allowRuleBtn);
        if (noBtn != null)        list.add(noBtn);
        return list;
    }

    /** Returns the interactable after the focused one (wraps to first). */
    private Interactable nextOption() {
        List<Interactable> opts = focusOrder();
        if (opts.isEmpty()) return null;
        Interactable focused = guiRef != null ? guiRef.getFocusedInteractable() : null;
        int idx = opts.indexOf(focused);
        return opts.get(idx < 0 ? 0 : (idx + 1) % opts.size());
    }

    /** Returns the interactable before the focused one (wraps to last). */
    private Interactable previousOption() {
        List<Interactable> opts = focusOrder();
        if (opts.isEmpty()) return null;
        Interactable focused = guiRef != null ? guiRef.getFocusedInteractable() : null;
        int idx = opts.indexOf(focused);
        return opts.get(idx < 0 ? opts.size() - 1 : (idx - 1 + opts.size()) % opts.size());
    }

    /**
     * Toggle the Ctrl+E explainer section.
     */
    private void toggleExplainer() {
        if (explainer == null) return;
        if (explainerLabel == null) return;

        explainerVisible = !explainerVisible;

        if (!explainerVisible) {


            guiRef.getGUIThread().invokeLater(() -> {
                explainerLabel.setVisible(false);
                if (explainerRiskLabel != null) explainerRiskLabel.setVisible(false);
                if (descriptionLabel != null) descriptionLabel.setVisible(true);
                invalidate();
            });
            return;
        }

        if (explainerFetched) {
            guiRef.getGUIThread().invokeLater(() -> {
                if (descriptionLabel != null) descriptionLabel.setVisible(false);
                explainerLabel.setVisible(true);
                if (explainerRiskLabel != null && !StringUtils.isBlank(explainerRiskLabel.getText())) {
                    explainerRiskLabel.setVisible(true);
                }
                invalidate();
            });
            return;
        }

        // First time: show loading text with spinner animation, fire fetch on a Virtual Thread.
        guiRef.getGUIThread().invokeLater(() -> {
            if (descriptionLabel != null) descriptionLabel.setVisible(false);
            explainerLabel.setText("   ⠋ Loading explanation…");
            explainerLabel.setVisible(true);
            if (explainerRiskLabel != null) {
                explainerRiskLabel.setText("");
                explainerRiskLabel.setVisible(false);
            }
            invalidate();
        });

        // Spinner animation — cycles through braille dots every 200ms,

        final String[] spinnerFrames = {"⠋","⠙","⠹","⠸","⠼","⠴","⠦","⠧","⠇","⠏"};
        final int[] spinnerIdx = {0};
        Timer spinnerTimer = new Timer("explainer-spinner", true);
        spinnerTimer.scheduleAtFixedRate(new TimerTask() {
            @Override public void run() {
                if (explainerFetched) { cancel(); return; }
                spinnerIdx[0] = (spinnerIdx[0] + 1) % spinnerFrames.length;
                guiRef.getGUIThread().invokeLater(() -> {
                    if (!explainerFetched && explainerLabel != null) {
                        explainerLabel.setText("   " + spinnerFrames[spinnerIdx[0]] + " Loading explanation…");
                        invalidate();
                    }
                });
            }
        }, 200, 200);

        PermissionAskContext ctx = currentCtx;
        PermissionExplainerCallback exp = this.explainer;
        MultiWindowTextGUI gui = guiRef;

        Thread.ofVirtual().name("permission-explainer").start(() -> {
            try {
                String description = extractDescription(ctx != null ? ctx.input() : null);
                PermissionExplanation explanation = exp.explain(
                    ctx != null ? ctx.toolName() : "", ctx != null ? ctx.input() : null, description);
                String mainText = buildExplainerMainText(explanation);
                String riskText = buildExplainerRiskText(explanation);
                TextColor riskColor = riskTextColor(explanation != null ? explanation.riskLevel() : null);
                gui.getGUIThread().invokeLater(() -> {
                    explainerFetched = true;
                    spinnerTimer.cancel();
                    if (explainerLabel != null) {
                        explainerLabel.setText(mainText);
                        // Whole explainer block colored by risk level (matches reference render)
                        explainerLabel.setForegroundColor(riskColor);
                        if (explainerRiskLabel != null) {
                            explainerRiskLabel.setText(riskText);
                            explainerRiskLabel.setForegroundColor(riskColor);
                            explainerRiskLabel.setVisible(!StringUtils.isBlank(riskText));
                        }
                        explainerLabel.setVisible(true);
                        invalidate();
                    }
                });
            } catch (Exception _) {
                gui.getGUIThread().invokeLater(() -> {
                    explainerFetched = true;
                    spinnerTimer.cancel();
                    if (explainerLabel != null) {
                        explainerLabel.setText("   Explanation unavailable.");
                        explainerLabel.setForegroundColor(LanternaTheme.divider());
                        explainerLabel.setVisible(true);
                        if (explainerRiskLabel != null) {
                            explainerRiskLabel.setText("");
                            explainerRiskLabel.setVisible(false);
                        }
                        invalidate();
                    }
                });
            }
        });
    }

    private void togglePermissionDebug() {
        if (currentCtx == null || !(Strings.CS.equals("Bash", currentCtx.toolName())
                || Strings.CS.equals("PowerShell", currentCtx.toolName()))) return;
        debugVisible = !debugVisible;
        if (debugLabel != null) {
            if (!debugVisible) {
                debugLabel.setText("");
                debugLabel.setVisible(false);
            } else {
                String type = currentCtx.decisionReasonType() == null
                    ? "unknown" : currentCtx.decisionReasonType();
                String detail = currentCtx.decisionReasonDetail() == null
                    ? "" : ": " + currentCtx.decisionReasonDetail();
                String suggestion = currentCtx.suggestionRuleContent() == null
                    ? "" : "\n   Suggested rule: " + currentCtx.toolName()
                        + "(" + currentCtx.suggestionRuleContent() + ")";
                debugLabel.setText("   Permission decision: " + type + detail + suggestion);
                debugLabel.setVisible(true);
            }
        }
        invalidate();
    }

    /** Main explanation text (white): explanation + reasoning lines. */
    private static String buildExplainerMainText(PermissionExplanation e) {
        if (e == null) return "   Explanation unavailable.";
        StringBuilder sb = new StringBuilder();
        sb.append("   ").append(e.explanation());
        if (StringUtils.isNotBlank(e.reasoning())) {
            sb.append("\n   ").append(e.reasoning());
        }
        return sb.toString();
    }

/**
     * Risk line text: " [High|Medium|Low] risk: [description]" — colored separately.
     */
    private static String buildExplainerRiskText(PermissionExplanation e) {
        if (e == null || e.risk() == null || StringUtils.isBlank(e.risk())) return "";
        String label = switch (e.riskLevel() == null ? "" : e.riskLevel().toUpperCase(Locale.ROOT)) {
            case "HIGH"   -> "High risk: ";
            case "MEDIUM" -> "Medium risk: ";
            default       -> "Low risk: ";
        };
        return "   " + label + e.risk();
    }

    /**
     * Maps {@code riskLevel} to the semantic theme color.
     */
    private static TextColor riskTextColor(String riskLevel) {
        if (riskLevel == null) return LanternaTheme.toolSuccess();
        return switch (riskLevel.toUpperCase(Locale.ROOT)) {
            case "HIGH"   -> LanternaTheme.toolError();
            case "MEDIUM" -> LanternaTheme.toolWarning();
            default       -> LanternaTheme.toolSuccess();
        };
    }

    // ── PermissionOptionRenderer — no <> brackets, ❯ tracks focus ────────────


    private class PermissionOptionRenderer implements Button.ButtonRenderer {

        /** PermissionDialog contributes paddingX=1 around the Select. */
        private static final String CURSOR = " ❯ ";

        @Override
        public TerminalPosition getCursorLocation(Button component) { return null; }

        @Override
        public TerminalSize getPreferredSize(Button component) {
            int w = TerminalTextUtils.getColumnWidth(CURSOR) + TerminalTextUtils.getColumnWidth(component.getLabel());
            return new TerminalSize(w, 1);
        }

/** Source-of-truth focus check.  Lanterna's {@code button.isFocused} reads
         *  a cached {@code inFocus} field maintained by onEnter/onLeaveFocus.  After
         *  removeAllComponents + re-add reflows during {@link #toggleAmend}, that
         *  field can lag the actual window focus pointer (causes stray "❯" on
         *  yes-apply-suggestions / noBtn).  Comparing against
         *  {@code Window.getFocusedInteractable} is the authoritative check. */
        private boolean reallyFocused(Button button) {
            if (guiRef != null && guiRef.getActiveWindow() != null) {
                return guiRef.getActiveWindow().getFocusedInteractable() == button;
            }
            return button.isFocused();
        }

        @Override
        public void drawComponent(TextGUIGraphics graphics, Button button) {
            boolean focused = reallyFocused(button);
            String line = optionLine(focused, button.getLabel());
            if (focused) {
                graphics.setForegroundColor(LanternaTheme.suggestion());
                graphics.setBackgroundColor(TextColor.ANSI.DEFAULT);
            } else {
                graphics.setBackgroundColor(TextColor.ANSI.DEFAULT);
                graphics.setForegroundColor(LanternaTheme.inputText());
            }
            graphics.fill(' ');
            graphics.putString(0, 0, line);
        }
    }

    private static String optionLine(boolean focused, String label) {
        return (focused ? " ❯ " : "   ") + label;
    }

    static String optionLineForTest(boolean focused, String label) {
        return optionLine(focused, label);
    }

    // ── AmendButton — intercepts Tab / Ctrl+E before Lanterna handles them ──


    private class AmendButton extends Button {
        private final boolean canAmend;
        /** true → "Yes" semantics (allow+feedback); false → "No" semantics (reject+feedback). */
        private final boolean isAllowOption;
        private final Runnable selectionAction;

        AmendButton(String label, Runnable action, boolean canAmend, boolean isAllowOption) {
            super(label, action);
            this.selectionAction = action;
            this.canAmend = canAmend;
            this.isAllowOption = isAllowOption;
        }

        @Override
        public synchronized Result handleKeyStroke(KeyStroke ks) {
            if (exitPlanRequest && isCtrlG(ks)) {
                editPlanInExternalEditor();
                return Result.HANDLED;
            }
            ContextKeybindingDispatcher.Result resolved =
                keybindings.resolve(List.of("Select", "Confirmation"), ks);
            if (resolved instanceof ContextKeybindingDispatcher.Result.Consumed) {
                return Result.HANDLED;
            }
            if (resolved instanceof ContextKeybindingDispatcher.Result.Action(String value)
                    && dispatchKeybindingAction(value)) {
                return Result.HANDLED;
            }
// Arrow Up/Down cycle within option list.
            if (ks.getKeyType() == KeyType.ARROW_UP) {
                Interactable prev = previousOption();
                if (prev != null) prev.takeFocus();
                return Result.HANDLED;
            }
            if (ks.getKeyType() == KeyType.ARROW_DOWN) {
                Interactable next = nextOption();
                if (next != null) next.takeFocus();
                return Result.HANDLED;
            }
            if (ks.getKeyType() == KeyType.TAB) {
                if (canAmend && guiRef != null) {
                    final boolean forYes = isAllowOption;
                    guiRef.getGUIThread().invokeLater(() -> toggleAmend(forYes));
                    return Result.HANDLED;
                }
                // canAmend=false: cycle to next option (do NOT let Lanterna escape the dialog)
                Interactable next = nextOption();
                if (next != null) { next.takeFocus(); return Result.HANDLED; }
                return super.handleKeyStroke(ks);
            }
            if (ks.getKeyType() == KeyType.CHARACTER
                    && ks.getCharacter() != null
                    && ks.getCharacter() == 'e'
                    && ks.isCtrlDown()) {
                if (guiRef != null) {
                    guiRef.getGUIThread().invokeLater(PermissionDialog.this::toggleExplainer);
                }
                return Result.HANDLED;
            }
            return super.handleKeyStroke(ks);
        }

        private boolean dispatchKeybindingAction(String action) {
            return switch (action) {
                case "select:previous" -> {
                    Interactable prev = previousOption();
                    if (prev != null) prev.takeFocus();
                    yield true;
                }
                case "select:next" -> {
                    Interactable next = nextOption();
                    if (next != null) next.takeFocus();
                    yield true;
                }
                case "select:accept" -> { selectionAction.run(); yield true; }
                case "select:cancel", "app:interrupt" -> { resolve(false); yield true; }
                case "confirm:nextField" -> {
                    if (canAmend && guiRef != null) toggleAmend(isAllowOption);
                    else {
                        Interactable next = nextOption();
                        if (next != null) next.takeFocus();
                    }
                    yield true;
                }
                case "confirm:toggleExplanation" -> {
                    if (explainer != null) toggleExplainer();
                    yield true;
                }
                case "permission:toggleDebug" -> {
                    togglePermissionDebug();
                    yield true;
                }
                case "confirm:yes", "confirm:no", "confirm:toggle", "confirm:cycleMode" -> true;
                default -> false;
            };
        }

        @Override
        protected void afterEnterFocus(Interactable.FocusChangeDirection direction, Interactable previouslyInFocus) {
            super.afterEnterFocus(direction, previouslyInFocus);
            updateHintForButton(canAmend);
        }
    }

    void handleYesButtonKeyForTest(KeyStroke key) {
        if (yesBtn != null) {
            yesBtn.handleKeyStroke(key);
        }
    }

    String allowSuggestionLabelForTest() {
        return allowRuleBtn == null ? null : allowRuleBtn.getLabel();
    }

    String keepContextLabelForTest() {
        return keepContextBtn == null ? null : keepContextBtn.getLabel();
    }

    void resolveSuggestionForTest() {
        if (allowRuleBtn != null) {
            allowRuleBtn.handleKeyStroke(new KeyStroke(KeyType.ENTER));
        }
    }

    void resolvePrimaryForTest() {
        if (yesBtn != null) yesBtn.handleKeyStroke(new KeyStroke(KeyType.ENTER));
    }

    void addPlanFeedbackImageForTest(String base64, String mediaType) {
        if (amendBox == null) amendBox = new TextBox(new TerminalSize(40, 1));
        addPlanFeedbackImage(amendBox, base64, mediaType);
    }

    void rejectPlanFeedbackForTest(String feedback) {
        if (amendBox == null) amendBox = new TextBox(new TerminalSize(40, 1));
        amendBox.setText(StringUtils.defaultString(feedback));
        resolveFromButton(false, true);
    }

    boolean isActiveForTest() {
        return active;
    }

    String titleForTest() {
        return titleLabel == null ? null : titleLabel.getText().strip();
    }

    String subtitleForTest() {
        return subtitleLabel == null ? null : subtitleLabel.getText().strip();
    }

    String questionForTest() {
        return questionLabel == null ? null : questionLabel.getText().strip();
    }

    List<String> specialBodyLinesForTest() {
        return List.copyOf(specialBodyLines);
    }

    List<String> requestedPermissionsForTest() {
        return List.copyOf(requestedPermissionsLines);
    }

    TextColor accentForTest() { return currentAccent; }

    String primaryLabelForTest() { return yesBtn == null ? null : yesBtn.getLabel(); }

    String noLabelForTest() { return noBtn == null ? null : noBtn.getLabel(); }

    String planContentForTest() {
        return planContentLabel == null ? "" : planContentLabel.getText();
    }

    boolean debugVisibleForTest() { return debugVisible; }

    private static boolean isCtrlG(KeyStroke key) {
        return key != null && key.getKeyType() == KeyType.CHARACTER
            && key.getCharacter() != null && key.getCharacter() == 'g'
            && key.isCtrlDown();
    }

    private void editPlanInExternalEditor() {
        if (!exitPlanRequest || guiRef == null || currentCtx == null) return;
        String rawPath = exactTextField(currentCtx.input(), "planFilePath");
        if (StringUtils.isBlank(rawPath)) return;
        final Path planPath;
        try {
            planPath = Path.of(rawPath);
        } catch (RuntimeException _) {
            if (editorHintLabel != null) {
                editorHintLabel.setText(" Invalid plan path: " + rawPath);
            }
            return;
        }
        ExternalEditorLauncher.openInEditor(guiRef.getScreen(), guiRef, planPath);
        try {
            String edited = Files.readString(planPath);
            ObjectNode updated = currentCtx.input() != null && currentCtx.input().isObject()
                ? ((ObjectNode) currentCtx.input()).deepCopy()
                : JsonUtils.getMapper().createObjectNode();
            updated.remove("_uiBypassPermissionsAvailable");
            updated.remove("_uiPlanPreviewInTranscript");
            updated.put("plan", edited);
            approvalUpdatedInput = updated;
            if (planContentLabel != null) {
                planContentLabel.setText(formatPlanContent(edited));
            }
            onPlanChanged.accept(edited);
            if (editorHintLabel != null) {
                editorHintLabel.setText(" ctrl-g to edit in $EDITOR · " + rawPath
                    + " · ✓ Plan saved!");
            }
            invalidate();
        } catch (IOException e) {
            if (editorHintLabel != null) {
                editorHintLabel.setText(" Failed to read edited plan: " + e.getMessage());
            }
        }
    }

    /**
     * TextBox renderer that draws a dim placeholder string when the box is empty.
     */
    private static class PlaceholderTextBoxRenderer extends TextBox.DefaultTextBoxRenderer {
        private final String placeholder;

        PlaceholderTextBoxRenderer(String placeholder) {
            this.placeholder = placeholder;
        }

        @Override
        public void drawComponent(TextGUIGraphics graphics, TextBox component) {
            super.drawComponent(graphics, component);
            String text = component.getText();
            if (StringUtils.isNotEmpty(text)) return;
            if (StringUtils.isEmpty(placeholder)) return;
            // Overlay the placeholder at column 0, row 0 in dim color.
            // DefaultTextBoxRenderer already filled the cell with theme bg+space, so we
            // only need to redraw with our dim foreground.
            TerminalSize size = graphics.getSize();
            if (size.getColumns() == 0 || size.getRows() == 0) return;
            String shown = placeholder;
            if (shown.length() > size.getColumns()) shown = shown.substring(0, size.getColumns());
            graphics.setForegroundColor(LanternaTheme.welcomeDim());
            graphics.setBackgroundColor(TextColor.ANSI.DEFAULT);
            graphics.putString(0, 0, shown);
        }
    }

    // ── Static helpers ────────────────────────────────────────────────────────

    /**
     * Per-tool human-readable title.
     */
    static String toolTitle(String toolName) {
        if (toolName == null) return "Tool use";
        McpNameParts mcp = parseMcpToolName(toolName);
        if (mcp != null) {
            return mcp.server + " - " + mcp.tool + " (MCP)";
        }
        return switch (toolName) {
            case "EnterPlanMode" -> "Enter plan mode?";
            case "ExitPlanMode"  -> "Ready to code?";
            case "Bash", "REPL"  -> "Bash command";
            case "PowerShell"    -> "PowerShell command";
            case "Read"          -> "Read file";
            case "Write"         -> "Write file";
            case "Edit", "MultiEdit" -> "Edit file";
            case "NotebookEdit"  -> "Edit notebook";
            case "Glob"          -> "List files";
            case "Grep"          -> "Search files";
            case "WebFetch"      -> "Fetch URL";
            case "WebSearch"     -> "Web search";
            case "Agent"         -> "Run subagent";
            case "TodoWrite"     -> "Update tasks";
            default              -> "Tool use";
        };
    }

    /** Extracts a one-line summary of the tool input for display. */
    private static String summarizeInput(String toolName, JsonNode input) {
        if (input == null) {
            return "";
        }

// flatten the top-level
        // object into "k: v, k: v", truncating each rendered value at 80 chars.
        if (parseMcpToolName(toolName) != null) {
            return renderMcpInputSummary(input);
        }
        return switch (toolName == null ? "" : toolName) {
            case "Bash", "REPL", "PowerShell" -> textField(input, "command", "script");
            case "Read", "Write", "Edit", "MultiEdit" -> textField(input, "file_path", "path");
            case "NotebookEdit" -> textField(input, "notebook_path", "file_path", "path");
            case "Grep", "Glob" -> textField(input, "pattern");
            case "WebFetch" -> textField(input, "url");
            case "WebSearch" -> textField(input, "query");
            default -> firstStringValue(input);
        };
    }

    static String summarizeInputForBody(String toolName, JsonNode input) {
        return summarizeInput(toolName, input);
    }

    private static String formatPlanContent(String plan) {
        String value = StringUtils.isBlank(plan)
            ? "No plan found. Please write your plan to the plan file first."
            : plan.stripTrailing();
        return indentWrapped(value, 72, "   ");
    }

    private static String indentWrapped(String text, int width, String indent) {
        if (StringUtils.isEmpty(text)) return "";
        List<String> output = new ArrayList<>();
        for (String sourceLine : text.split("\\R", -1)) {
            List<String> wrapped = FormatUtils.wrapText(sourceLine, Math.max(1, width));
            if (wrapped.isEmpty()) output.add(indent);
            else wrapped.forEach(line -> output.add(indent + line));
        }
        return String.join("\n", output);
    }

    /**
     * MCP tool name parts extracted from the wire format {@code mcp__server__tool}.
     */
    record McpNameParts(String server, String tool) {}

    static McpNameParts parseMcpToolName(String toolName) {
        if (toolName == null || !Strings.CS.startsWith(toolName, "mcp__")) return null;
        String rest = toolName.substring("mcp__".length());
        int sep = rest.indexOf("__");
        if (sep <= 0 || sep >= rest.length() - 2) return null;
        String server = rest.substring(0, sep);
        String tool = rest.substring(sep + 2);
        if (StringUtils.isBlank(server) || StringUtils.isBlank(tool)) return null;
        return new McpNameParts(server, tool);
    }

    /**
     * Renders MCP tool input as {@code "k1: v1, k2: v2"} with each rendered value truncated at 80
     * chars.
     */
    private static String renderMcpInputSummary(JsonNode input) {
        if (input == null || !input.isObject() || input.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        var it = input.fields();
        while (it.hasNext()) {
            var e = it.next();
            String key = e.getKey();
            JsonNode v = e.getValue();
            String rendered;
            if (v == null || v.isNull()) rendered = "null";
            else if (v.isTextual())       rendered = v.asText();
            else                          rendered = v.toString();
            if (rendered.length() > 80) rendered = FormatUtils.truncate(rendered, 80);
            if (!sb.isEmpty()) sb.append(", ");
            sb.append(key).append(": ").append(rendered);
        }
        return sb.toString();
    }

    private static String extractDescription(JsonNode input) {
        if (input == null) return "";
        JsonNode n = input.get("description");
        return (n != null && n.isTextual()) ? n.asText().strip() : "";
    }

    private static String textField(JsonNode node, String... keys) {
        if (node == null) return "";
        for (String key : keys) {
            JsonNode f = node.get(key);
            if (f != null && f.isTextual() && !StringUtils.isBlank(f.asText())) return f.asText().strip();
        }
        return firstStringValue(node);
    }

    private static String exactTextField(JsonNode node, String key) {
        if (node == null || key == null) return "";
        JsonNode value = node.get(key);
        return value != null && value.isTextual() ? value.asText() : "";
    }

    private static String firstStringValue(JsonNode node) {
        var it = node.fields();
        while (it.hasNext()) {
            var e = it.next();
            if (e.getValue().isTextual() && !StringUtils.isBlank(e.getValue().asText()))
                return e.getValue().asText().strip();
        }
        return "";
    }
}
