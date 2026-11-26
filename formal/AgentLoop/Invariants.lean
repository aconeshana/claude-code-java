import AgentLoop.Step

namespace AgentLoop

/-- Invariant 1 (Abort Dominance):
    Once an abort event occurs, the loop ALWAYS terminates immediately with `.aborted`,
    regardless of configuration or prior state. -/
theorem abort_always_terminates (cfg : LoopConfig) (s : LoopState) :
    step cfg s .aborted = .done .aborted := rfl

/-- Invariant 2 (Budget Guard):
    Budget exhaustion ALWAYS terminates immediately with `.maxBudget`. -/
theorem budget_exceeded_always_terminates (cfg : LoopConfig) (s : LoopState) :
    step cfg s .budgetExceeded = .done .maxBudget := rfl

/-- Invariant 3 (Bounded Output-Token Recovery):
    `recoveryCount ≤ cfg.maxOutputTokensLimit` is an inductive safety invariant preserved
    across every possible `StepEvent`. -/
theorem recovery_count_bounded (cfg : LoopConfig) (s s' : LoopState) (ev : StepEvent)
    (r : ContinueReason)
    (hBound : s.recoveryCount ≤ cfg.maxOutputTokensLimit)
    (hStep : step cfg s ev = .next s' r) :
    s'.recoveryCount ≤ cfg.maxOutputTokensLimit := by
  cases ev with
  | aborted => simp [step] at hStep
  | budgetExceeded => simp [step] at hStep
  | blockingLimitReached => simp [step] at hStep
  | streamError => simp [step] at hStep
  | reactiveCompactAttempt succeeded =>
      simp only [step] at hStep
      split at hStep <;> try contradiction
      split at hStep <;> try contradiction
      injection hStep with hState _
      subst hState
      exact hBound
  | apiMaxOutputTokens =>
      simp only [step] at hStep
      split at hStep
      · injection hStep with hState _
        subst hState
        exact hBound
      · split at hStep
        · rename_i _ hLt
          injection hStep with hState _
          subst hState
          exact Nat.succ_le_of_lt hLt
        · contradiction
  | toolUse count hookPrevented producedSO soCalls =>
      simp only [step] at hStep
      split at hStep <;> try contradiction
      split at hStep <;> try contradiction
      split at hStep <;> try contradiction
      injection hStep with hState _
      subst hState
      exact Nat.zero_le _
  | naturalEndTurn prevent blocking =>
      simp only [step] at hStep
      split at hStep <;> try contradiction
      split at hStep <;> try contradiction
      split at hStep <;> try contradiction
      split at hStep <;> try contradiction
      injection hStep with hState _
      subst hState
      exact hBound

/-- Invariant 4 (Stop-Hook Reentry Flag):
    Whenever `step` continues via `.stopHookReentry`, `stopHookActive` MUST be `true`
    in the resulting state so Stop hooks can detect re-entry and avoid infinite loops. -/
theorem stop_hook_reentry_sets_active (cfg : LoopConfig) (s : LoopState) (ev : StepEvent)
    (s' : LoopState) (h : step cfg s ev = .next s' .stopHookReentry) :
    s'.stopHookActive = true := by
  cases ev with
  | aborted => simp [step] at h
  | budgetExceeded => simp [step] at h
  | blockingLimitReached => simp [step] at h
  | streamError => simp [step] at h
  | reactiveCompactAttempt succeeded =>
      simp only [step] at h
      split at h <;> try contradiction
      split at h <;> try contradiction
      injection h with _ hReason
      contradiction
  | apiMaxOutputTokens =>
      simp only [step] at h
      split at h
      · injection h with _ hReason; contradiction
      · split at h
        · injection h with _ hReason; contradiction
        · contradiction
  | toolUse count hookPrevented producedSO soCalls =>
      simp only [step] at h
      split at h <;> try contradiction
      split at h <;> try contradiction
      split at h <;> try contradiction
      injection h with _ hReason; contradiction
  | naturalEndTurn prevent blocking =>
      simp only [step] at h
      split at h <;> try contradiction
      split at h <;> try contradiction
      split at h <;> try contradiction
      split at h <;> try contradiction
      injection h with hState _
      subst hState
      rfl

/-- Invariant 5 (Stop-Hook Block Cap):
    When `stopHookBlockCap > 0` and consecutive stop-hook blocks exceed the cap
    (and `maxTurns` is not already exceeded), the loop MUST override and terminate
    rather than continuing indefinitely. -/
theorem stop_hook_block_cap_enforced (cfg : LoopConfig) (s : LoopState)
    (hCap : cfg.stopHookBlockCap > 0)
    (hExceeded : effectiveStopHookBlockingCount s + 1 > cfg.stopHookBlockCap)
    (hWithinTurns : ¬(cfg.maxTurns > 0 ∧ s.loopTurns + 1 > cfg.maxTurns)) :
    step cfg s (.naturalEndTurn false true) = .done .normal := by
  have hNotMax : (decide (cfg.maxTurns > 0) && decide (s.loopTurns + 1 > cfg.maxTurns)) = false := by
    cases h1 : decide (cfg.maxTurns > 0) <;> cases h2 : decide (s.loopTurns + 1 > cfg.maxTurns)
    · rfl
    · rfl
    · rfl
    · exact absurd ⟨of_decide_eq_true h1, of_decide_eq_true h2⟩ hWithinTurns
  have hCapCheck : (decide (cfg.stopHookBlockCap > 0) &&
      decide (effectiveStopHookBlockingCount s + 1 > cfg.stopHookBlockCap)) = true := by
    simp [hCap, hExceeded]
  simp only [step, Bool.false_eq_true, ↓reduceIte, Bool.true_or, hNotMax, hCapCheck]

/-- Invariant 6 (Max-Turns Guard on Tool Use):
    When `maxTurns > 0` and the next turn exceeds `maxTurns`, a non-stopped tool-use
    turn (that has not failed structured-output retries) MUST terminate with `.maxTurns`. -/
theorem max_turns_enforced_on_tool_use (cfg : LoopConfig) (s : LoopState)
    (count soCalls : Nat) (producedSO : Bool)
    (hMax : cfg.maxTurns > 0)
    (hExceed : s.loopTurns + 1 > cfg.maxTurns)
    (hNoSOFail : (cfg.requireStructuredOutput && !(s.hasStructuredOutput || producedSO) &&
        decide (soCalls >= cfg.maxStructuredOutputRetries)) = false) :
    step cfg s (.toolUse count false producedSO soCalls) = .done (.maxTurns (s.loopTurns + 1)) := by
  have hTurns : (decide (cfg.maxTurns > 0) && decide (s.loopTurns + 1 > cfg.maxTurns)) = true := by
    simp [hMax, hExceed]
  simp only [step, Bool.false_eq_true, ↓reduceIte, hNoSOFail, hTurns]

/-- Invariant 7 (Single-Shot Reactive Compact):
    Once `hasAttemptedReactiveCompact = true`, any subsequent reactive compact attempt
    MUST terminate with `.streamError` (never retries reactive compact twice). -/
theorem reactive_compact_at_most_once (cfg : LoopConfig) (s : LoopState) (succeeded : Bool)
    (hAttempted : s.hasAttemptedReactiveCompact = true) :
    step cfg s (.reactiveCompactAttempt succeeded) = .done .streamError := by
  simp [step, hAttempted]

/-- Invariant 8 (Auto-Compact Circuit Breaker):
    Once `consecutiveFailures ≥ 3`, `AutoCompactState.shouldRetry` is strictly `false`. -/
theorem autocompact_circuit_breaker_trips (ac : AutoCompactState)
    (hTripped : ac.consecutiveFailures ≥ AutoCompactState.maxFailures) :
    ac.shouldRetry = false := by
  simp [AutoCompactState.shouldRetry]
  omega

end AgentLoop
