# HUD metrics design

This implementation follows the normative
[HUD metrics specification](hud-metrics-specification.md).

## Architecture

- Core owns the versioned `SessionMetricsEvent`, immutable
  `SessionMetricsSnapshot`, and shared formatter.
- Runtime owns one `SessionMetricsTracker` per `DefaultQuerySession`. It is the
  only event fold and the only metrics snapshot consumed by HUD/status-line
  rendering.
- `TranscriptRecorder` writes events as `type:"java-session-metrics"` through
  the same ordered queue as messages. Metric rows never participate in parent
  UUID chains.
- `SessionStorage` replays metric rows and validates the schema, session ID,
  and strictly increasing sequence. Missing coverage produces an incomplete
  snapshot, which is not displayed.
- An open step found on resume is repaired with synthetic `step/end` and
  `turn/end` events. Open tools remain unmatched and contribute no tool time.
- Forking copies only metric turns whose `turnId` belongs to retained message
  UUIDs, rewrites `sessionId`, and resequences rows from zero.

## Traceability

| Contract | Measurement | Persistence | HUD/status line |
|---|---|---|---|
| Step and turn counts | Query-loop turn and request-step boundaries | `turn/*`, `step/*` | `turns`, `steps` |
| LLM duration | Step start to finalized assistant response | `assistant/message` | `llm_ms`, `LLM` |
| Time to first token | First non-empty stream delta or tool name | `assistant/first-token` | `ttft_*`, `TTFT avg` |
| Decode throughput | First token, message completion, and final usage | `assistant/usage` | `decode_*`, `tok/s` |
| Tool duration | Tool entry and return by tool-use ID | `tool/call`, `tool/result` | `tool_ms`, `Tool call` |
| Token accounting | Provider-normalized final cumulative sample | Usage fields | Cache/input/output |
| Formatting | Shared `SessionMetricsFormat` | Derived at read time | Shared by both consumers |
| Coverage | `SessionStorage` validation | Schema/session/sequence | Omitted when incomplete |

## Public interfaces

`session_metrics` is optional in custom status-line JSON. When present it has
`coverage:"complete"`, raw projection totals, disjoint usage buckets, and the
derived `billed_input_tokens`, `ttft_average_ms`, `tokens_per_second`, and
textual `cache_hit_percent`. Incomplete metrics omit the entire object.

Transcript event schema version 1 contains `type`, `schemaVersion`, `seq`,
`time`, `sessionId`, `event`, and applicable `turnId`, `turn`, `step`, `callId`,
usage buckets, and `synthetic`.

## HUD behavior

The second line is ordered as follows:

```text
Context | turns/steps | LLM/Tool | TTFT/tok/s | Cache hit | Input/Output
```

The renderer preserves the left prefix by terminal display columns, including
CJK width and zero-width ANSI sequences, and ends clipped output with `…`.

## Compatibility and rollout

- Existing transcripts without timing events remain context-only.
- A fresh transcript begins with `session/start` before metrics-bearing turns.
- Unknown metric rows are non-chain metadata and remain safe for clients that
  ignore unrecognized transcript row types.
- If transcript compatibility requires separate storage, setting
  `CLAUDE_CODE_SESSION_METRICS_SIDECAR=1` writes
  `<sessionId>.metrics.jsonl`; resume and fork operations preserve it.
