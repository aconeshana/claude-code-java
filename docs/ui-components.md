# Terminal UI Components

The terminal UI uses a Lanterna full-screen, double-buffered TUI. Its code is under
claude-code-ui/src/main/java/com/claudecode/ui/.

## Core shell

| Component | Path | Responsibility |
|---|---|---|
| LanternaReplScreen | lanterna/repl/LanternaReplScreen.java | REPL orchestration and lifecycle |
| ReplScene | lanterna/repl/ReplScene.java | Component stack, full-screen shell, and overlays |
| WindowInputRouter | lanterna/input/WindowInputRouter.java | Window-level key, scroll, and selection routing |
| Ansi | Ansi.java | ANSI capabilities, OSC support, and style helpers |
| LanternaTheme | lanterna/theme/LanternaTheme.java | Active theme and color-level resolution |
| Themes | lanterna/theme/Themes.java | Dark, light, ANSI, and daltonized palettes |

LanternaReplScreen wires the session collaborators and feature slices. It does
not own every feature's state and is not the command implementation facade.

## Input

| Component | Path | Responsibility |
|---|---|---|
| InputPanel | lanterna/input/InputPanel.java | Prompt composition, key routing, image chips, and footer |
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
| LanternaMessageDispatcher | lanterna/transcript/LanternaMessageDispatcher.java | Stream and turn-event presentation |
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
