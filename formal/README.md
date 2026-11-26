# Formal Verification of `claude-code-java` (Lean 4)

This standalone Lean 4 project (`formal/`) formally specifies and proves the core control-flow and protocol invariants of `claude-code-java`, and compiles native trace verifiers (`trace-verifier` and `stream-verifier`) executed by the JVM conformance test suites.

## Structure

| File | Purpose |
| :--- | :--- |
| `AgentLoop/Types.lean` | Mirrors `QueryState`, `AutoCompactTrackingState`, `Continue`, `Terminal`, and abstract `StepEvent`. |
| `AgentLoop/Step.lean` | Pure transition function `step : LoopConfig → LoopState → StepEvent → StepOutcome`. |
| `AgentLoop/Invariants.lean` | 8 machine-checked safety theorems for `QueryLoop` (abort dominance, budget guard, bounded recovery, stop-hook re-entry & block cap, max-turns guard, single-shot reactive compact, auto-compact circuit breaker). |
| `AgentLoop/Termination.lean` | 4 machine-checked bounded-termination proofs via strictly decreasing ranking functions (`maxTokensRank`, `turnRank`, `reactiveCompactRank`). |
| `AgentLoop/TraceVerifier.lean` | Native executable (`trace-verifier`) validating real JVM `QueryLoop` JSONL traces against `AgentLoop.step`. |
| `Protocol/StreamGrammar.lean` | 6 machine-checked safety theorems for the canonical `StreamEvent` grammar shared across all 4 protocols (`Anthropic`, `OpenAI Chat`, `OpenAI Responses`, `Google Gemini`). |
| `Protocol/ToolPairing.lean` | 4 machine-checked theorems for `ToolPairingHealer` (exact 1:1 pairing order, zero orphan results, completeness, strict idempotence). |
| `Protocol/StreamVerifier.lean` | Native executable (`stream-verifier`) validating canonical `StreamEvent` traces decoded from all 4 protocols and all 4×4 cross-protocol SSE translations. |

## Quick Start

```bash
# 1. Build all proofs & compile trace-verifier + stream-verifier (zero sorry allowed)
cd formal && lake build

# 2. Run Java conformance + exhaustive 4-protocol matrix suites with Lean verification enforced
FORMAL_REQUIRE_VERIFIER=true ./gradlew \
  :claude-code-runtime:test --tests "com.claudecode.runtime.query.QueryLoopConformanceTest" \
  :claude-code-api:test --tests "com.claudecode.api.ProtocolMatrixAndGrammarConformanceTest"
```
