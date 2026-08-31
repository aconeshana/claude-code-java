# Memory System

Claude Code loads persistent instructions from Markdown files named `CLAUDE.md`,
related rule files, and files reached through `@` imports. These instructions are
independent of conversation history, so restarting the REPL does not clear them.
For each model request, the current instruction set is rendered into a
`<system-reminder>` user-context block and merged with the request's user turn.

## Scopes and loading order

| Scope | Locations | Applies to | Typical use |
|---|---|---|---|
| User | `~/.claude/CLAUDE.md` and `~/.claude/rules/**/*.md` | Every project for the user | Personal preferences and general rules |
| Project | `CLAUDE.md`, `.claude/CLAUDE.md`, and `.claude/rules/**/*.md` from filesystem root through the current working directory | A project or subtree, normally checked in | Architecture, terminology, and project conventions |
| Local | `CLAUDE.local.md` from filesystem root through the current working directory | One checkout, normally ignored | Private debugging notes and local workarounds |

When `CLAUDE_CONFIG_DIR` is set, the user-scope files are resolved beneath that
configuration directory instead of `~/.claude`.

The scanner loads user memory first. It then walks the directory tree from the
filesystem root toward the current working directory. At each directory it
loads `CLAUDE.md`, then `.claude/CLAUDE.md`, recursively scans
`.claude/rules/`, and loads `CLAUDE.local.md`. Files closer to the current
working directory therefore appear later in the assembled context.

The eager instruction supplier creates a fresh scanner for each query, so file
edits and path-dependent rules take effect without restarting the session. When
a tool reads or `@`-mentions a file below the working directory, nested memory
discovery can also attach instructions found between the working directory and
the referenced file. Conditional user rules and conditional project or local
rules from the root-to-working-directory chain are matched against the referenced
path. Project and local instructions discovered below the working directory are
attached without an additional path filter.

## Recursive `@` imports

Any discovered memory or rule file can import another supported text file with
an `@path` directive:

~~~markdown
# Root

@./docs/architecture.md
@~/notes/personal.md
@/absolute/path.md

Ordinary Markdown follows.
~~~

The implementation:

- follows imports to a maximum depth of five files, including the root file;
- uses normalized and real paths to prevent cycles and duplicate content;
- ignores directives inside fenced, indented, or inline code;
- supports relative, home-relative, and absolute paths, escaped spaces, and
  optional `#fragment` suffixes;
- accepts the text extensions declared by `MemoryFileScanner` and skips
  unsupported or binary file types;
- silently skips missing, unreadable, blank, or excluded files.

Imported files retain their parent path so the memory picker can display the
file tree and the `@-imported` label.

In the interactive UI, startup warns once when a project or local memory file
imports instructions outside the current working directory. The response is
stored per project. The scanner discovers the imported files regardless of that
response; the stored state suppresses the warning on later startups rather than
filtering imported instructions.

## The `/memory` command

In the interactive REPL, `/memory` opens an inline selector. Command arguments
do not bypass this selector. It lists discovered files, marks missing user and
project defaults as new, and opens the selected file using `VISUAL`, `EDITOR`,
or `vi`:

~~~text
Memory

  1. ./CLAUDE.md                 Checked in at ./CLAUDE.md
    └ ./docs/rules.md            @-imported
  2. User memory (new)           Saved in ~/.claude/CLAUDE.md
~~~

Lanterna is stopped while the external editor runs and restarted after the
editor exits. In headless command execution, the following arguments select a
path directly:

~~~text
/memory global   -> <config-home>/CLAUDE.md
/memory project  -> <cwd>/CLAUDE.md
/memory local    -> <cwd>/.claude/CLAUDE.md
/memory <path>   -> an arbitrary path
~~~

The selector also exposes Auto Memory and Auto Dream settings. When Auto Memory
is enabled, it can show the auto-memory folder, the optional team-memory folder,
and folders for agents that declare persistent memory.

## Path-specific frontmatter

A memory file may contain YAML frontmatter that limits it to matching paths:

~~~markdown
---
paths:
  - src/**/*.java
  - config/**/*.json
---

# Path-specific rules
~~~

Without `paths` frontmatter, a file is unconditional. During eager prompt
assembly, patterns are matched against the current working directory. During
nested discovery, patterns are matched against the file that triggered the
discovery. The condition is re-evaluated for each query.

Frontmatter and standalone block-level HTML comments are removed from the text
shown to the model. Inline HTML comments within a paragraph are preserved.

## Configuration

### `claudeMdExcludes`

Settings sources may provide a list of glob patterns:

~~~json
{
  "claudeMdExcludes": [
    "**/legacy-notes.md",
    "/tmp/experimental/CLAUDE.md"
  ]
}
~~~

`WorkspaceSettings.loadClaudeMdExcludes` reads the effective settings snapshot,
removes blank and duplicate entries, and passes the patterns to
`MemoryFileScanner`. Absolute patterns are also checked against real paths, so
an alias such as `/tmp/x` can match `/private/tmp/x` on macOS.

### `--setting-sources`

The CLI can select which user-configurable settings and memory scopes to load:

~~~bash
java -jar claude-code-app-*.jar --setting-sources=user,project
java -jar claude-code-app-*.jar --setting-sources=
~~~

Valid tokens are `user`, `project`, and `local`. Tokens are case-sensitive, and
an invalid token is rejected. Omitting the option enables all three scopes; an
explicit empty value disables all three. The same selection controls the
corresponding settings sources and memory scopes.

The separate `--settings` option accepts an additional settings JSON file or an
inline JSON object; it does not select memory scopes.

### `CLAUDE_CODE_ADDITIONAL_DIRECTORIES_CLAUDE_MD`

When this environment variable is truthy (`1`, `true`, `yes`, or `on`),
directories added with `--add-dir` or `/add-dir` are also scanned for
`CLAUDE.md`, `.claude/CLAUDE.md`, and `.claude/rules/**/*.md`. These files use
the project scope and are loaded only when that scope is enabled. The default
is disabled.

### `CLAUDE_CODE_DISABLE_CLAUDE_MDS`

Setting this environment variable to the literal value `1` disables injection
of the CLAUDE.md user-context block.

## Agent memory

Agent frontmatter can declare `memory: user`, `memory: project`, or
`memory: local`. The corresponding persistent directories are:

- user: `<config-home>/agent-memory/<agentType>/`
- project: `<cwd>/.claude/agent-memory/<agentType>/`
- local: `<cwd>/.claude/agent-memory-local/<agentType>/`

Colons in an agent type are replaced with hyphens for directory names. When
`CLAUDE_CODE_REMOTE_MEMORY_DIR` is set, user agent memory is rooted there.
Local agent memory is relocated beneath its project namespace at
`<remote-memory-dir>/projects/<sanitized-project>/agent-memory-local/<agentType>/`.
Project-scoped agent memory remains under the working directory.

When Auto Memory is enabled, `/memory` adds folder rows for agents that declare
a memory scope. Agent memory directories are ordinary filesystem locations;
the folder links do not depend on automatic extraction having written content.

## Auto Memory, Auto Dream, and team memory

The auto-memory directory is resolved per working directory. If its `MEMORY.md`
entry point exists and is nonblank, it is appended after the regular CLAUDE.md
instruction blocks and persists across conversations.

The `/memory` selector persists `autoMemoryEnabled` and `autoDreamEnabled` in
user settings. Auto Memory defaults to enabled, subject to its environment and
remote-session gates; Auto Dream is also feature-gated and is shown only while
Auto Memory is enabled. Automatic extraction is a separate, default-disabled
feature controlled by `extractMemoriesEnabled`. At the end of a normal
main-session turn, it asynchronously runs a restricted sub-agent when both
extraction and Auto Memory are enabled. Auto Dream is evaluated at the same
main-session boundary when its gates are enabled, and `/dream` provides the
manual consolidation entry point. Dream work is represented by the task system;
it is not driven by a cron or `setInterval` scheduler.

Team memory is an optional local subdirectory of the project's auto-memory
location. When `teamMemoryEnabled` is enabled, the selector can open that folder
and file write/edit tools reject content that matches the team-memory secret
guard. The Java implementation does not provide hosted synchronization.

## Implementation map

| Module and class | Responsibility |
|---|---|
| `claude-code-services`: `claudemd.MemoryFileScanner` | Discovery, recursive imports, frontmatter and comment processing, deduplication, exclusions, scope gates, and additional-directory gates |
| `claude-code-services`: `claudemd.MemoryPromptBuilder` | Eager filtering, prompt rendering, auto-memory index loading, and read-state seeding |
| `claude-code-services`: `claudemd.NestedMemoryAttachmentProvider` | Path-triggered nested instruction attachments |
| `claude-code-services`: `claudemd.AgentMemory` and `claudemd.AutoMemory` | Persistent agent-memory paths and auto-memory feature/path resolution |
| `claude-code-runtime`: `memory.MemoryCatalog` | Application port used by the interactive presentation layer |
| `claude-code-commands`: `impl.context.MemoryCommand` | Slash-command behavior and headless path selection |
| `claude-code-ui`: `features.memory.MemoryFeature` and `dialog.MemorySelectorDialog` | Inline selector, folder actions, settings toggles, and editor handoff |
| `claude-code-ui`: `repl.StartupGateController` and `dialog.ClaudeMdExternalIncludesDialog` | Interactive external-import approval flow |
| `claude-code-core`: `engine.FileStateCache` | Shared read-state data structure used by memory deduplication and compaction recovery |

The CLI composition root wires the services implementations to runtime,
commands, and UI ports; the UI module does not depend on services production
implementations.

## Verification

Focused coverage includes `MemoryFileScannerTest`,
`MemoryFileScannerConfigHomeTest`, `MemoryPromptBuilderTest`,
`NestedMemoryAttachmentProviderTest`, `WorkspaceSettingsTest`,
`ClaudeCodeCliTest`, `MemoryCommandInteractiveTest`, `MemoryCommandPathTest`,
`MemoryFeatureTest`, `MemorySelectorDialogTest`,
`ClaudeMdExternalIncludesDialogTest`, `StartupGateControllerTest`,
`AgentMemoryPromptTest`, `AutoMemoryTest`, `SettingsAutoMemoryTest`,
`RuntimeSettingsTest`, `ExtractMemoriesServiceTest`,
`AutoDreamEngineImplTest`, `DreamCommandTest`, and `TeamMemGuardToolTest`.
Changes to discovery, prompt injection, startup approval, agent-memory paths,
Auto Memory, automatic extraction, team memory, or Auto Dream should update the
corresponding tests and this document together.
