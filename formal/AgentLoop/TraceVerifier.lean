import Lean.Data.Json
import AgentLoop.Step
import AgentLoop.Invariants
import AgentLoop.Termination

open Lean (Json FromJson fromJson?)
open AgentLoop

namespace AgentLoop.TraceVerifier

private def parseContinueReason (j : Json) : Except String ContinueReason := do
  let kind ← j.getObjValAs? String "kind"
  match kind with
  | "tool_use" =>
      let count ← j.getObjValAs? Nat "toolCount"
      pure (.toolUse count)
  | "compact" => pure .compact
  | "max_output_tokens_escalate" => pure .maxOutputTokensEscalate
  | "max_output_tokens_recovery" =>
      let attempt ← j.getObjValAs? Nat "attempt"
      pure (.maxOutputTokensRecovery attempt)
  | "prompt_too_long_recovery" => pure .promptTooLongRecovery
  | "stop_hook_reentry" => pure .stopHookReentry
  | other => throw s!"Unknown ContinueReason kind: {other}"

private def parseTerminalReason (j : Json) : Except String TerminalReason := do
  let kind ← j.getObjValAs? String "kind"
  match kind with
  | "normal" => pure .normal
  | "max_turns" =>
      let turns ← j.getObjValAs? Nat "turns"
      pure (.maxTurns turns)
  | "max_structured_output_retries" =>
      let retries ← j.getObjValAs? Nat "retries"
      pure (.maxStructuredOutputRetries retries)
  | "max_budget" => pure .maxBudget
  | "prompt_too_long" => pure .promptTooLong
  | "stream_error" => pure .streamError
  | "stop_hook_prevented" => pure .stopHookPrevented
  | "hook_stopped" => pure .hookStopped
  | "aborted" => pure .aborted
  | other => throw s!"Unknown TerminalReason kind: {other}"

private def parseConfig (j : Json) : Except String LoopConfig := do
  pure {
    maxTurns := ← j.getObjValAs? Nat "maxTurns"
    stopHookBlockCap := ← j.getObjValAs? Nat "stopHookBlockCap"
    maxOutputTokensLimit := ← j.getObjValAs? Nat "maxOutputTokensLimit"
    allowMaxTokensEscalation := ← j.getObjValAs? Bool "allowMaxTokensEscalation"
    requireStructuredOutput := ← j.getObjValAs? Bool "requireStructuredOutput"
    maxStructuredOutputRetries := ← j.getObjValAs? Nat "maxStructuredOutputRetries"
  }

private def parseState (j : Json) : Except String LoopState := do
  let lastContinue : Option ContinueReason ←
    match j.getObjVal? "lastContinue" with
    | .ok Json.null => pure none
    | .ok v => some <$> parseContinueReason v
    | .error _ => pure none
  pure {
    loopTurns := ← j.getObjValAs? Nat "loopTurns"
    recoveryCount := ← j.getObjValAs? Nat "recoveryCount"
    maxTokensOverrideSet := ← j.getObjValAs? Bool "maxTokensOverrideSet"
    stopHookActive := ← j.getObjValAs? Bool "stopHookActive"
    stopHookBlockingCount := ← j.getObjValAs? Nat "stopHookBlockingCount"
    hasStructuredOutput := ← j.getObjValAs? Bool "hasStructuredOutput"
    structuredOutputCalls := ← j.getObjValAs? Nat "structuredOutputCalls"
    hasAttemptedReactiveCompact := ← j.getObjValAs? Bool "hasAttemptedReactiveCompact"
    lastContinue := lastContinue
  }

private def parseEvent (j : Json) : Except String StepEvent := do
  let kind ← j.getObjValAs? String "kind"
  match kind with
  | "aborted" => pure .aborted
  | "budget_exceeded" => pure .budgetExceeded
  | "blocking_limit_reached" => pure .blockingLimitReached
  | "stream_error" => pure .streamError
  | "reactive_compact_attempt" =>
      let succeeded ← j.getObjValAs? Bool "succeeded"
      pure (.reactiveCompactAttempt succeeded)
  | "api_max_output_tokens" => pure .apiMaxOutputTokens
  | "tool_use" =>
      let count ← j.getObjValAs? Nat "count"
      let hookPrevented ← j.getObjValAs? Bool "hookPrevented"
      let producedSO ← j.getObjValAs? Bool "producedStructuredOutput"
      let soCalls ← j.getObjValAs? Nat "structuredOutputCallsThisQuery"
      pure (.toolUse count hookPrevented producedSO soCalls)
  | "natural_end_turn" =>
      let prevent ← j.getObjValAs? Bool "stopHookPrevent"
      let blocking ← j.getObjValAs? Bool "stopHookBlocking"
      pure (.naturalEndTurn prevent blocking)
  | other => throw s!"Unknown StepEvent kind: {other}"

private def parseOutcome (j : Json) : Except String StepOutcome := do
  let kind ← j.getObjValAs? String "kind"
  match kind with
  | "next" =>
      let state ← parseState (← j.getObjVal? "state")
      let reason ← parseContinueReason (← j.getObjVal? "reason")
      pure (.next state reason)
  | "done" =>
      let terminal ← parseTerminalReason (← j.getObjVal? "terminal")
      pure (.done terminal)
  | other => throw s!"Unknown StepOutcome kind: {other}"

private def verifyLine (lineNo : Nat) (line : String) : Except String String := do
  let j ← Json.parse line
  let scenario ← j.getObjValAs? String "scenario"
  let cfg ← parseConfig (← j.getObjVal? "config")
  let before ← parseState (← j.getObjVal? "before")
  let event ← parseEvent (← j.getObjVal? "event")
  let actual ← parseOutcome (← j.getObjVal? "actual")
  let expected := AgentLoop.step cfg before event
  if actual == expected then
    pure scenario
  else
    throw s!"Line {lineNo} [{scenario}] CONFORMANCE MISMATCH:\n  event:    {repr event}\n  expected: {repr expected}\n  actual:   {repr actual}"

end AgentLoop.TraceVerifier

def main (args : List String) : IO UInt32 := do
  match args with
  | [tracePath] =>
      let contents ← IO.FS.readFile tracePath
      let lines := contents.splitOn "\n"
        |>.map String.trim
        |>.filter (fun s => !s.isEmpty)
      if lines.isEmpty then
        IO.eprintln s!"ERROR: Trace file '{tracePath}' is empty."
        return 1
      let mut verified := 0
      let mut idx := 0
      for line in lines do
        idx := idx + 1
        match AgentLoop.TraceVerifier.verifyLine idx line with
        | .ok _ => verified := verified + 1
        | .error msg =>
            IO.eprintln msg
            return 1
      IO.println s!"OK: Verified {verified} QueryLoop transition(s) in '{tracePath}' against Lean 4 spec."
      return 0
  | _ =>
      IO.eprintln "Usage: trace-verifier <path-to-traces.jsonl>"
      return 2
