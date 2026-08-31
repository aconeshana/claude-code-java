# Slash Command Status Index

This is the current human-readable index for slash-command support. It is a
status snapshot, not a historical audit log. Archived review documents are
intentionally outside this index.

Changes must be validated against the documented command contract, including
metadata, availability gates, execution paths, cancellation behavior, output,
and focused regression tests.

## Status vocabulary

| Status | Meaning |
|---|---|
| DONE | Implemented and covered by current tests, with only documented platform differences |
| PARTIAL | The main path exists, but a concrete behavior gap remains |
| STUB | The default registry retains the name for compatibility and returns `Not yet implemented` |
| NOT_APPLICABLE | The reference command is disabled, internal-only, or has no meaningful Java equivalent |
| DYNAMIC | Registered at runtime from MCP, plugin, workflow, or skill state |

`StubCommand` entries are hidden from `/help` and slash suggestions, but remain
dispatchable by exact name. Runtime catalogs may replace a placeholder when a
plugin or skill with the same command name is available.

## Current command surface

### Implemented built-ins

The default command factory currently provides the following implemented
command families:

~~~text
help, exit, clear, compact, config, output-style, sandbox, model, cost,
reload-plugins, rename, advisor, btw, insights, diff, resume, export, memory,
doctor, permissions, status, branch, skills, stats, init, dream, goal, hooks,
theme, copy, plugin, effort, add-dir, agents, usage, color, pokemon, rewind,
tasks, workflows, keybindings, statusline, plan, context, version, tag
~~~

Additional built-ins:

- `/mcp` is installed by the CLI after a live `McpClientManager` exists;
- MCP prompts become dynamic slash commands after prompt discovery;
- plugin, workflow, and visible skill commands are synchronized from their
  runtime catalogs;
- `/loop` is projected from the bundled skill catalog unless cron support is
  disabled with `CLAUDE_CODE_DISABLE_CRON`; it is not a default
  `CommandFactory` built-in;
- `/version` and `/tag` are available only when `USER_TYPE=ant`;
- `/usage` remains registered for metadata compatibility but is unavailable in
  the current Java product profile;
- `/pokemon` is an optional Java-specific command for displaying or hatching the
  configured terminal companion.

The inventory is intentionally not documented as a fixed count: the model and
command surfaces can grow through MCP, plugins, workflows, skills, and feature
gates.

### Hidden compatibility stubs

The following names are retained as hidden exact-name stubs:

~~~text
security-review, init-verifiers, terminal-setup, heapdump, ide,
remote-control, brief, install, review, ultrareview, ultraplan, pr-comments,
stickers, release-notes, commit, commit-push-pr, files, feedback, vim,
chrome, desktop, extra-usage, install-github-app, install-slack-app, passes,
rate-limit-options, remote-env, web-setup, session, think-back,
thinkback-play, voice, env, share, login, logout, mobile, fast, upgrade,
privacy-settings
~~~

The stubs fall into these deliberate categories:

- subscriber or claude.ai account features that require OAuth identity;
- environment-specific commands unavailable to this distribution;
- remote-control and remote-session features that require subscriber OAuth;
- commands moved to a marketplace plugin or a product-specific service;
- native installer, IDE, V8 heap-dump, or terminal-setup features with no
  equivalent need in the JVM distribution;
- disabled or experimental commands;
- `/vim`, which is a hidden compatibility name and does not control the Java
  input editor.

## Current implementation snapshots

### MCP

The MCP path is implemented across stdio, Streamable HTTP, and legacy SSE
transports. The current implementation covers:

- initialize and capability negotiation, including roots and elicitation;
- tools, prompts, and resources discovery;
- `notifications/tools/list_changed`, `notifications/prompts/list_changed`, and
  `notifications/resources/list_changed`;
- dynamic MCP prompt slash commands;
- form and URL elicitation request handling through the SDK control channel;
- URL elicitation retry and terminal-result behavior;
- a 60-second Streamable HTTP response-header deadline;
- `MCP_TIMEOUT` for connection and ordinary JSON-RPC operation deadlines, with
  `MCP_TOOL_TIMEOUT` overriding `tools/call` operations.

The CLI bridge owns interactive consent presentation, while SDK sessions can
forward elicitation requests over the SDK control channel.

### Exit and shutdown

The main exit path follows the documented shutdown contract:

- `/exit` and prompt-input exit use the `prompt_input_exit` reason;
- Ctrl+C and Ctrl+D double-press handling uses an 800 ms window;
- SIGTERM and SIGHUP use exit codes 143 and 129;
- `SessionEnd` hooks have a 1.5-second default timeout;
- the failsafe budget is `max(5 seconds, hook timeout + 3.5 seconds)`;
- resume hints and session-file existence checks are preserved;
- `WorktreeExitDialog` has the five-state UI flow.

Worktree support is exposed through the registered `EnterWorktree` and
`ExitWorktree` model tools rather than a `/worktree` slash command. The exit
dialog is active when the current session was created in a managed worktree.
Java resources use explicit `close` methods and JVM shutdown handling. The
shutdown sequence persists session cost, drains pending memory extraction,
cleans registered team sessions, prints the resume hint when a transcript
exists, and dispatches `SessionEnd` hooks.

### Memory

The `/memory` command, User/Project/Local scanning, recursive includes,
frontmatter paths, excludes, additional-directory gates, agent memory,
Auto Memory, Auto Dream, external-include approval, and the selector editor
handoff are implemented. The selector also persists Auto Memory and Auto Dream
toggles.

Team memory is an optional local subdirectory of the project's auto-memory
location. When enabled, the selector exposes it and file write/edit tools apply
the team-memory secret guard. Hosted synchronization is not implemented. The
memory scanner loads User, Project, and Local scopes; managed settings do not
add a managed `CLAUDE.md` scope. See `docs/memory-system.md` for the current
memory contract and implementation map.

## Command change validation procedure

For every changed command:

1. Read the Java command and its ports, feature gates, and UI adapters.
2. Review the documented command contract and existing regression tests.
3. Validate name, description, aliases, immediacy, argument hints, command type,
   and non-interactive availability.
4. Validate normal, empty-argument, error, cancellation, and permission paths.
5. Validate user-visible text and structured command results.
6. Add or update a focused Java regression test.
7. Record only a concrete remaining gap; do not preserve a stale historical
   checklist entry after the code is fixed.

The main registration and dispatch sources are:

- `claude-code-commands/src/main/java/com/claudecode/commands/bootstrap/CommandFactory.java`;
- `claude-code-commands/src/main/java/com/claudecode/commands/CommandRegistry.java`;
- CLI runtime registration for `/mcp`, discovered MCP prompts, and commands
  projected from user-invocable skills;
- `PluginCommandSync`, `WorkflowCommandSync`, and `CliSkillCommandSync` for
  dynamic catalogs;
- the Lanterna slash dispatcher for interactive routing.

## Intentional product boundaries

The following are product-profile decisions rather than unfinished public
command implementations:

- claude.ai subscriber OAuth commands and remote-control sessions;
- environment-specific commands;
- platform-specific native installer and IDE-management workflows;
- V8 heap-dump support in the JVM distribution.

When one of these boundaries changes, update this index and the relevant
module documentation together.
