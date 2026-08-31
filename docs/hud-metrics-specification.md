# HUD metrics normative specification

This document defines the metric semantics used by product code and tests.
Transport or presentation changes must preserve these contracts unless this
specification is deliberately revised.

## 1. Counts and lifecycle

- A step is counted on `step/end`. Completed, failed, cancelled, and
  max-token steps therefore count.
- Provider retry remains inside the same entered step and does not create a
  new `step/start`.
- Turns are distinct turn numbers with at least one closed step. A rejected or
  empty turn that never enters a step does not count.
- Counts are folded from the durable event log. Paging, compaction, and the
  visible conversation window cannot change them.

## 2. Timing boundaries

- `llmMs` sums `step/start → assistant/message` for steps that assembled an
  assistant message. Retry waits inside the step are included. A cancelled
  step without an assembled message contributes zero LLM time.
- `toolMs` sums every matched `tool/call → tool/result` pair by call ID.
  Parallel durations are summed and can exceed elapsed wall time. Unresolved
  calls are discarded at `turn/end`.
- First token means the first non-empty text delta, reasoning delta, tool
  argument delta, or tool-call frame carrying a name. Heartbeats and empty
  frames do not count.
- `ttftMs` sums `step/start → first token`; `ttftSteps` counts only steps with
  that boundary. `TTFT avg = ttftMs / ttftSteps`.
- `decodeMs` sums `first token → assistant/message` only when the step also
  has an output-token usage sample. `decodeTokens` sums output tokens over the
  same sampled set. `tok/s = decodeTokens / (decodeMs / 1000)`.
- Negative elapsed values are clamped to zero.

## 3. Token accounting

Usage buckets are disjoint:

```text
billed input = uncached input + cache read + cache write
cache hit = cache read / billed input
```

When a provider includes cached tokens in its input count, normalization uses
`uncached = max(0, provider input - cached)`. The provider total-token sample
remains available for context accounting.

Usage within a step is cumulative and last-sample-wins. Streaming samples are
not added together; the finalized sample replaces earlier samples.

## 4. Display formulas

- Duration below one minute is seconds rounded to one decimal. From one minute,
  use rounded whole seconds formatted as `XmYs`.
- Tokens per second use one decimal below 10 and whole tokens from 10 upward.
- Token counts use compact notation such as `517`, `12.2K`, `517K`, `1.2M`.
- Cache percentage first uses integer half-up rounding. If a non-full hit would
  round to 100, increase decimal precision only until the result stays below
  100. Only a true full hit displays `100`.
- Groups appear in this order: counts; LLM/tool duration; TTFT/TPS; cache hit;
  input/output. Empty groups disappear as a whole.
- Overflow preserves the strict left prefix and appends one ellipsis. Groups
  are never reordered.

## 5. Persistence compatibility

Transcript loading recognizes message rows and ignores unknown row types. A
`java-session-metrics` row without `uuid`, `parentUuid`, or `message` therefore
does not enter the message chain.

Resume, compaction, and fork operations preserve metric rows explicitly and
mark missing or discontinuous coverage incomplete. If a future compatibility
constraint rejects unknown rows, storage moves to
`<sessionId>.metrics.jsonl`; event and projection contracts remain unchanged.

## 6. Forbidden approximations

- Do not substitute process-wide API duration for step timing.
- Do not use response completion as first token.
- Do not count provider retries as new steps.
- Do not add cumulative usage samples.
- Do not count cached prompt tokens in both uncached and cache-read buckets.
- Do not reconstruct whole-session metrics from the visible message list.
- Do not display incomplete coverage as session totals.
