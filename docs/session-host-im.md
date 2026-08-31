# Session Host IM Integration

The Java application can expose the same live Claude Code session to Lanterna
and an IM thread through an embedded cc-connect sidecar. The integration uses
semantic events before terminal rendering; it does not parse PTY output and it
does not add an AskHuman prompt to model context.

## Enable it

The normal path requires no hand-written configuration. Start the JAR, focus
the `Collaboration: Off` footer item, press Enter, and choose
`Set up Feishu…`. The in-terminal wizard can either create a bot through the
cc-connect QR flow or bind an existing App ID/App Secret. When binding an
existing app, the credentials are sent to the bundled helper over stdin, saved
in an owner-only `~/.claude/cc-connect.credentials.env`, and never placed in
process arguments. After authentication, send one message to the bot in the
Feishu conversation you want to use; the wizard discovers that chat, writes the
Session Host target, and starts collaboration without restarting the JAR.

For managed deployments, create `~/.claude/cc-connect.toml`, or point
`CLAUDE_CODE_IM_CONFIG` at an explicit file:

```toml
language = "zh"
env_file = "~/.cc-connect/credentials.env"

[[projects]]
name = "claude-code-java"

[projects.agent]
type = "sessionhost"

[projects.agent.options]
auth_token_env = "CC_SESSION_LINK_TOKEN"
# Backward-compatible single picker target; local sessions still default Off.
bind_session_key = "feishu:oc_chat_id:ou_owner"

# Optional multi-IM picker targets. One session activates at most one target.
[projects.agent.options.collaboration_targets]
feishu = "feishu:oc_chat_id:ou_owner"
slack = "slack:C0123456789:U0123456789"

[[projects.platforms]]
type = "feishu"

[projects.platforms.options]
app_id = "${FEISHU_APP_ID}"
app_secret = "${FEISHU_APP_SECRET}"
thread_isolation = true
allow_from = "ou_owner"
allow_chat = "oc_chat_id"
```

The JAR starts a private Unix-domain socket and injects its endpoint, a random
per-launch authentication token, and the current working directory into the
sidecar. The bundled binary is available for macOS and Linux on amd64/arm64.
It keeps cc-connect's upstream IM platform adapters in the distribution, while
the only bundled agent backend is Java `sessionhost`; enabling another IM is a
configuration change rather than a different Java packaging layout. Feishu is
currently the platform with the additional persistent thread/card lifecycle
semantics described below.
For development, `CLAUDE_CODE_CC_CONNECT_BINARY` may override the bundled
binary. Windows requires a future named-pipe transport and is rejected rather
than silently falling back to an unsupported endpoint.

`env_file` is optional. It is useful for desktop launches that do not inherit
shell exports: cc-connect reads `KEY=VALUE` entries before expanding `${VAR}`
placeholders, while already-exported environment values keep precedence. Keep
that file outside the repository with owner-only permissions.

## Session behavior

- One running JAR is one application-owned project/cwd. IM can proactively
  create any number of sessions for that project, but cc-connect's generic
  multi-workspace/project switching must not be enabled for the embedded
  `sessionhost` agent: Java's transcript recorder, file-history manager,
  plugin runtime, memory catalog, status line, and trust context are assembled
  for the launch project. Supporting arbitrary project selection therefore
  requires a separate Java application instance (or a future full project
  re-composition), not an in-place cwd mutation.
- Every local session starts with `Collaboration: Off`. With an empty prompt,
  press `Down` to focus the footer, use `Left`/`Right` when other footer items
  are visible, and press `Enter` to select one configured IM channel. This UI
  stays available while a model turn is running and never enters model input.
- Enabling a channel binds the current session to one IM thread and replays the
  bounded prefix of the current turn before continuing with live output.
- Selecting `Off` stops future IM mirroring without interrupting the local turn.
  Selecting another channel atomically replaces the current one.
- Starting from IM prepares the thread before opening the Java session, so the
  first reply is already thread-scoped.
- `/new [name]` opens a fresh Java session and rebinds the current IM thread to
  it before any model prompt is submitted. It does not create a sibling thread
  or terminate the previous session, which remains available through `/resume`.
- Replying in an old bound thread activates and resumes that Java transcript.
- `/resume` opens a paged session picker for the project owned by the current
  TUI process. `/resume <number|name|session-id-prefix>` resumes directly, and
  `/list` uses the same picker in Session Host mode. Card actions carry the full
  session ID, so reordering the history list cannot select the wrong session.
  The resume stays in the same IM thread and the same TUI process; it never
  starts a new Java process or creates a replacement thread. Selecting the
  already-active session is an idempotent success.
- Session Host mode reserves `/resume` for transcript activation and disables
  cc-connect's generic `/switch` workspace flow. `/switch` returns a short
  explanation and does not reach the model. Generic, non-Session-Host agents
  retain their existing `/switch` behavior.
- A resume initiated from Feishu is shown through Lanterna's native replay
  path, then atomically changes that thread's active attachment. A resume
  initiated from the terminal sends
  `↩ Resumed in TUI · <session title> · N messages loaded` to the bound thread
  and refreshes the most recent resume card while the sidecar still has its
  exact message handle. Remote resumes update their initiating card without
  sending the duplicate terminal-origin notice.
- Resume activation is generation-fenced and serialized per thread. An active
  model turn rejects the resume without changing the current mapping; failed,
  stale, or superseded activations leave the previous session attached. Once a
  resume commits, queued messages and subsequent output belong only to the
  highest-generation winning session.
- `/model` lists and changes the model for the Java session bound to the current
  IM thread. It supports card selection, numeric rows, short aliases, and custom
  model names; switching an old thread first resumes that transcript and keeps
  the same Session Link attachment alive.
- `/effort` (also `/reasoning`) reads or changes the reasoning effort for the
  Java session bound to the current IM thread. Choices are model-specific and
  may include `auto`, `none`, `minimal`, `low`, `medium`, `high`, `xhigh`, and
  `max`; the host reports the effective value when `auto` or model-specific
  clamping applies. The change is live and does not restart the session, clear
  transcript history, or create a new one.
- `/compact [optional summarization instructions]` runs Java's native manual
  compact command for the session bound to the current IM thread. It uses a
  dedicated semantic request rather than submitting `/compact` as model input;
  terminal progress/result rendering remains active, and new terminal or IM
  input queues until compaction finishes.
- The bound IM thread mirrors TUI-originated user input, effective model,
  thinking/progress text, tool calls/results, and final output from the same
  semantic event layer used by Lanterna. IM-originated input carries explicit
  endpoint provenance and is not echoed back to the thread that already rendered it.
- Permission cards include the current user request, effective model and
  reasoning effort, workspace, permission mode, model output produced before
  the request, recent tools, and the native decision reason/warning supplied by
  Java. These fields are display-only and never become model-visible prompt
  content. Card/callback values are bounded before crossing the IM boundary.
- The latest turn has a bounded semantic replay window, so thread creation that
  completes after a fast local turn has started still receives the input/model/
  output prefix before a permission card.
- `AskUserQuestion` is rendered through the platform's native options when
  available and falls back to numbered/natural-language replies elsewhere.
  Multi-select cards always advertise a natural-language `Other` path. When a
  multi-question interaction is answered locally, the original card is updated
  in place with a summary of every question and answer.
- Permission/question answers are first-responder-wins. A decision in either
  endpoint resolves the native Java interaction and updates/dismisses the same
  card on the other endpoint; it is never converted into model-visible user
  text. Accepted card updates are drained during shutdown, including the case
  where the user approves in the terminal and immediately exits.
- Secret interactions may be explicitly local-only. When a bound IM endpoint
  observes Java's sudo-password request, Session Link emits the secret-free
  `interaction.unsupported` notice and cc-connect asks the user to complete it
  in the local TUI. The command and password never cross the Session Link wire,
  and the notice does not resolve, deny, or otherwise alter the Bash tool call.
  The v1 event shape is:

  ```json
  {
    "name": "interaction.unsupported",
    "session_id": "...",
    "payload": {
      "request_id": "...",
      "interaction_kind": "sudo_password",
      "action": "complete_in_tui"
    }
  }
  ```
- Every permission and single-choice question action is bound to the native
  interaction request ID. A callback without that ID, with an old ID, or after
  the interaction has already resolved is consumed as stale; it cannot answer
  a newer prompt and cannot fall through as a normal model-visible message.
- Feishu currently uses native buttons for permissions and single-choice
  questions. For multi-select plus free-text `Other`, it renders a numbered
  selection card and explicitly accepts a natural-language thread reply,
  because Feishu's callback shape does not provide that combined interaction
  portably across the other bundled IM adapters.
- Session Link submissions use the IM message ID as a bounded idempotency key,
  so a response/socket retry cannot execute the same prompt twice. Outbound
  semantic events use a per-connection bounded single-writer lane; a slow or
  disconnected sidecar cannot backpressure the model or local terminal.
- Feishu host-thread bindings survive cc-connect restarts in a mode-`0600`
  state file under the cc-connect data directory.
- Multiple local TUI processes may share one Feishu application. Each bundled
  sidecar uses a private Unix API socket, while a shared mode-`0600` route maps
  every bound thread to its owning process. Messages, `/compact`, permission
  decisions, and AskUserQuestion callbacks received by a sibling Feishu long
  connection are forwarded to that owner. Permission cards are updated only
  after the owning Java interaction broker confirms the response.
- In Session Host mode, the Feishu bot's main conversation is intentionally not
  a fallback input surface. Main-chat text, slash commands, and attachments are
  deduplicated across sidecars and silently consumed whether zero, one, or many
  TUI processes are online. Only a bound thread can route input to its owning
  TUI process.
- Feishu card actions are authorized against both `allow_chat` and the
  clicking operator's `allow_from`; another group member cannot approve a tool
  request merely because the card is visible in the shared thread.

The wire protocol and platform extension contract are documented in
[`docs/session-host.md`](https://github.com/aconeshana/cc-connect/blob/main/docs/session-host.md)
in the cc-connect fork.
