# Terminal UI Components

The terminal UI uses a Lanterna full-screen, double-buffered TUI. Its code is under
claude-code-ui/src/main/java/com/claudecode/ui/.

## Core shell

| Component | Path | Responsibility |
|---|---|---|
| LanternaReplScreen | lanterna/repl/LanternaReplScreen.java | Terminal/GUI lifecycle, SlashHost port, runtime setters |
| ReplComposer | lanterna/repl/ReplComposer.java | Composition root: builds every widget, feature, controller and the turn engine from a ReplContext |
| ReplContext / ReplGraph | lanterna/repl/ReplContext.java, ReplGraph.java | Composer input (pre-scene collaborators) and immutable output (grouped object graph) |
| ReplSceneLayout | lanterna/repl/ReplSceneLayout.java | The single overlay-registration and z-order mount declaration |
| ReplScene | lanterna/repl/ReplScene.java | Component stack, full-screen shell, and overlays |
| ReplCommandUiBridge | lanterna/repl/ReplCommandUiBridge.java | Typed capability bridge that slash commands use to reach feature UIs |
| WindowInputRouter | lanterna/input/WindowInputRouter.java | Window-level key, scroll, and selection routing |
| Ansi | Ansi.java | ANSI capabilities, OSC support, and style helpers |
| LanternaTheme | lanterna/theme/LanternaTheme.java | Active theme and color-level resolution |
| Themes | lanterna/theme/Themes.java | Dark, light, ANSI, and daltonized palettes |

LanternaReplScreen owns the terminal and GUI-thread lifecycle and the SlashHost port; it hands
scene construction to ReplComposer and adopts the resulting ReplGraph. The composed graph reaches
back into the screen only through the narrow `ReplComposer.Host` port. Features implement
`ReplFeature` to contribute their inline overlays and a `ReplCommandUiBridge` capability to expose
their launchers; the CLI binds slash commands to the bridge, never to the screen.
`ReplFeatureArchitectureTest` and `ReplSceneOrderSnapshotTest` pin these boundaries and the mount
order.

## Input

| Component | Path | Responsibility |
|---|---|---|
| InputPanel | lanterna/input/InputPanel.java | Prompt text box, mode detection, key pipeline, and composition of the collaborators below |
| PromptFooter | lanterna/input/PromptFooter.java | Single footer selection and its keyboard/mouse protocol over ProjectsButton, TasksPill, WorkflowFooter, CoordinatorFooter, and CollaborationPill |
| PromptHintBar | lanterna/input/PromptHintBar.java | Hint row priority, temporary notifications, and the status line |
| PromptDividers | lanterna/input/PromptDividers.java | Top/bottom rules, session badge, history label, and border color |
| PromptChipEditor | lanterna/input/PromptChipEditor.java | Atomic `[Image #N]` / pasted-text chip insertion, hopping, deletion, and pruning |
| PromptPasteHandler | lanterna/input/PromptPasteHandler.java | Bracketed and Ctrl+V paste classification, pending-paste state, deferred submit |
| PromptSuggestionBridge | lanterna/input/PromptSuggestionBridge.java | Suggestion dropdown context, accept keys, and `@token` / shell-token splice rules |
| PromptVimAdapter | lanterna/input/PromptVimAdapter.java | Vim key translation, buffer mirroring, INSERT label, and cursor shape |
| ReadlineEngine | lanterna/input/ReadlineEngine.java | Cursor movement and readline editing |
| InputHistoryController | lanterna/input/InputHistoryController.java | History navigation and draft restoration |
| PromptPastedContentController | lanterna/input/PromptPastedContentController.java | Paste identity, lazy spaces, and pasted-image handling |
| PromptTaskNavigationController | lanterna/input/PromptTaskNavigationController.java | Task footer and teammate navigation |
| SuggestionPanel | lanterna/suggest/SuggestionPanel.java | Slash-command and file suggestion dropdown |
| FileSuggestionService | lanterna/suggest/FileSuggestionService.java | @-file completion |
| DirectorySuggestionService | lanterna/suggest/DirectorySuggestionService.java | Directory completion |
| VimStateMachine | vim/VimStateMachine.java | Vim editing state machine, wired into InputPanel when vim mode is enabled |

## Transcript and renderers

| Component | Path | Responsibility |
|---|---|---|
| TranscriptController | lanterna/transcript/TranscriptController.java | Transcript state and viewed-teammate state |
| MessagePanel | lanterna/transcript/MessagePanel.java | Message rows, segments, selection, and actions |
| LanternaMessageDispatcher | lanterna/transcript/LanternaMessageDispatcher.java | SDKMessage routing, tool-card lifecycle, tombstone retraction, and composition of the renderers below |
| PendingToolLedger | lanterna/transcript/PendingToolLedger.java | In-flight tool calls, their header/status rows, and per-id progress/result tables |
| ToolHeaderRenderer | lanterna/transcript/ToolHeaderRenderer.java | Tool header rows: status dot, name, external tag, argument preview, completion recolour |
| ToolResultRenderer | lanterna/transcript/ToolResultRenderer.java | Per-ResultMode and generic tool result bodies; delegates file edits to FileChangeResultRenderer |
| FileChangeResultRenderer | lanterna/transcript/FileChangeResultRenderer.java | Edit/Write/Notebook diffs, full-content previews, and rejected-change previews |
| ToolProgressRenderer | lanterna/transcript/ToolProgressRenderer.java | MCP progress bar, WebSearch updates, shell output summary, and TaskOutput waiting hint |
| SourceCodePainter | lanterna/transcript/SourceCodePainter.java | Syntax-highlighted code and diff hunk painting, display paths |
| ToolResultLines | lanterna/transcript/ToolResultLines.java | Shared result-row gutters, error text mapping, plan and denial rows |
| UserMessageRenderer | lanterna/transcript/UserMessageRenderer.java | Prompt echo, bash/local-command output, channel and compact-summary rows, image chips |
| SystemMessageRenderer | lanterna/transcript/SystemMessageRenderer.java | System notices, turn summary, errors, retries, attachments, and API status texts |
| ThinkingRenderer | lanterna/transcript/ThinkingRenderer.java | Completed thinking blocks and their verbose/transcript visibility |
| AgentProgressPresenter | lanterna/transcript/AgentProgressPresenter.java | Sub-agent progress rows, parallel-agent group cards, and verbose child transcripts |
| StreamingTextRenderer | lanterna/transcript/StreamingTextRenderer.java | Live streaming text window with snapshot rollback and stable/unstable Markdown tail |
| BackgroundTasksRenderer | lanterna/transcript/BackgroundTasksRenderer.java | Shell, agent, workflow, monitor, and dream task rows |
| BackgroundTaskPill | lanterna/transcript/BackgroundTaskPill.java | Compact task status in the transcript |
| ContextVisualizationRenderer | lanterna/transcript/ContextVisualizationRenderer.java | Context grid, legend, MCP/tools/agents/memory/skills sections, and suggestions |
| MarkdownRenderer | MarkdownRenderer.java | CommonMark rendering, CJK-aware layout, OSC 8 links, and issue references |
| SyntaxHighlighter | SyntaxHighlighter.java | Java, Python, JavaScript, and Bash highlighting |
| DiffRenderer | DiffRenderer.java | ANSI diff output and structured word-level diff views |
| ToolUseIndicatorRenderer | render/ToolUseIndicatorRenderer.java | Tool-use indicator contract |
| HighlightedThinkingRenderer | render/HighlightedThinkingRenderer.java | Thinking labels and token highlighting |
| LspDiagnosticRenderer | render/LspDiagnosticRenderer.java | Theme-aware LSP diagnostics |

The structured diff path is documented in docs/diff-renderer.md. Syntax highlighting
uses the TextMate grammar integration documented in this project.

## Dialogs and overlays

Dialogs live in lanterna/dialog/. The current catalog includes:

~~~text
AddDirDialog, AskUserQuestionDialog, BackgroundTasksDialog,
BypassPermissionsModeDialog, BtwSideQuestionDialog,
ClaudeMdExternalIncludesDialog, CollaborationPickerDialog, CopyPickerDialog,
CustomModelDialog, DiffDialog, DoctorDialog, EffortSliderDialog, ExportDialog,
FeishuSetupDialog, GoalDialog, HistorySearchDialog, HooksConfigMenuDialog,
LspRecommendationDialog, MCPSettingsDialog, ManagedSettingsSecurityDialog,
MemorySelectorDialog, MessageSelectorDialog, ModelPickerDialog,
OutputStylePickerDialog, PermissionDialog, PokemonHatchDialog,
RefusalFallbackDialog, SandboxSettingsDialog, SessionSelectorDialog,
SkillsDialog, StatsDialog, TagRemovalDialog, ThemePickerDialog,
ThinkingToggleDialog, TrustFolderDialog, WorkflowsDialog, WorktreeExitDialog
~~~

Permission variants are handled by PermissionDialog and its request-body
helpers rather than by one class per tool. Inline overlays implement the
InlineOverlay contract and are hosted by OverlayHost.

## Feature slices

Feature state and workflows are grouped under lanterna/features/:

- agents: AgentsFeature, AgentsPanel, AgentCreateWizard, and the model,
  tools, and color pickers;
- settings: PreferencesFeature, PermissionsFeature, ConfigPanel,
  SettingsTabContainer, HooksController, MCPController, WorkspaceTab, and
  PermissionRulesTab;
- memory: MemoryFeature and the /memory selector integration;
- sandbox: SandboxFeature and sandbox settings;
- tasks: TaskListPanel and task navigation.

Plugin-specific views live under lanterna/plugin/, including installed,
marketplace, validation, error, and plugin-option flows. Help views live under
lanterna/features/help/.

## Status line, statistics, and terminal protocols

Status-line code is split between lanterna/status/ and lanterna/statusline/.
StatusLineComponent and StatusLineController handle the built-in HUD and
custom status-line input. StatsDialog uses AsciiChart, HeatmapRenderer,
StatsDateDisplay, and StatsScreenshot; screenshot capture and clipboard
copying are implemented.

TerminalController and OSC52Helper provide the current terminal protocol
surface:

| Capability | Implementation |
|---|---|
| OSC 8 hyperlinks | MarkdownRenderer and Ansi |
| OSC 9;4 progress | TerminalController |
| OSC 21337 tab status | TerminalController |
| Terminal title updates | TerminalController |
| Clipboard copy | OSC52Helper and StatsScreenshot |
| Pasted image references | InputPanel and PromptPastedContentController |

## Vim mode

The Vim state machine is wired into the prompt editor. The implemented areas
include:

| Feature | Status |
|---|---|
| INSERT, NORMAL, VISUAL, and COMMAND modes | Complete |
| Operators and h/j/k/l/w/b/e motions | Complete |
| Text objects iw, aw, i", and a" | Complete |
| Counts, dot-repeat, undo, and redo | Complete |
| Find motions f/F/t/T and ;/, | Complete |
| Named registers and macros | Complete |

The /vim slash command is a separate compatibility command and remains a
hidden stub because it is not exposed by the current reference CLI profile.

## Current implementation boundaries

These are documented product boundaries rather than roadmap entries:

- UsagePane can render usage information, but subscriber-specific OAuth
  limits and overage upsells are not available in the Java auth model.
- WorktreeExitDialog is implemented, while the /worktree command that creates
  live worktree sessions is not.
- The structured diff renderer provides line and word coloring, but not the
  reference Rust/TextMate syntax-highlighting path.

For color-resolution details, see docs/color-system.md. For memory and slash
command status, see docs/memory-system.md and docs/migration-checklist.md.
