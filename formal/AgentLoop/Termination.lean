import AgentLoop.Step

namespace AgentLoop

/-- Potential (ranking) function for consecutive `apiMaxOutputTokens` retries.
    Even though `maxTokensOverrideSet` can toggle from `false -> true -> false`
    between escalation and recovery, this lexicographic-style potential strictly
    decreases on EVERY continuing `.apiMaxOutputTokens` transition:
    - Escalate (`false -> true`, same `recoveryCount`): drops by 1
    - Recovery (`recoveryCount -> recoveryCount + 1`, `override -> false`): drops by at least 1
    Therefore `apiMaxOutputTokens` can NEVER loop infinitely. -/
def maxTokensRank (cfg : LoopConfig) (s : LoopState) : Nat :=
  2 * (cfg.maxOutputTokensLimit - s.recoveryCount) + (if s.maxTokensOverrideSet then 0 else 1)

theorem max_output_tokens_rank_decreases (cfg : LoopConfig) (s s' : LoopState) (r : ContinueReason)
    (h : step cfg s .apiMaxOutputTokens = .next s' r) :
    maxTokensRank cfg s' < maxTokensRank cfg s := by
  simp only [step] at h
  split at h
  · rename_i hEsc
    injection h with hState _
    subst hState
    have hOverrideFalse : s.maxTokensOverrideSet = false := by
      cases hO : s.maxTokensOverrideSet <;> simp_all
    simp [maxTokensRank, hOverrideFalse]
  · split at h
    · rename_i _ hLt
      injection h with hState _
      subst hState
      simp only [maxTokensRank]
      cases s.maxTokensOverrideSet <;> simp <;> omega
    · contradiction

/-- Potential function for turn-advancing loops (`toolUse` and `naturalEndTurn` re-entry).
    Whenever `cfg.maxTurns > 0`, every continuing `.toolUse` or `.naturalEndTurn`
    transition strictly decreases `turnRank`, proving bounded turn termination. -/
def turnRank (cfg : LoopConfig) (s : LoopState) : Nat :=
  cfg.maxTurns - s.loopTurns

theorem tool_use_turn_rank_decreases (cfg : LoopConfig) (s s' : LoopState)
    (count soCalls : Nat) (hookPrevented producedSO : Bool) (r : ContinueReason)
    (hMax : cfg.maxTurns > 0)
    (h : step cfg s (.toolUse count hookPrevented producedSO soCalls) = .next s' r) :
    turnRank cfg s' < turnRank cfg s := by
  simp only [step] at h
  split at h <;> try contradiction
  split at h <;> try contradiction
  split at h
  · contradiction
  · rename_i _ _ hNotExceed
    injection h with hState _
    subst hState
    simp only [turnRank]
    simp only [Bool.and_eq_true, decide_eq_true_eq] at hNotExceed
    omega

theorem stop_hook_turn_rank_decreases (cfg : LoopConfig) (s s' : LoopState)
    (prevent blocking : Bool) (r : ContinueReason)
    (hMax : cfg.maxTurns > 0)
    (h : step cfg s (.naturalEndTurn prevent blocking) = .next s' r) :
    turnRank cfg s' < turnRank cfg s := by
  simp only [step] at h
  split at h <;> try contradiction
  split at h <;> try contradiction
  split at h
  · contradiction
  · split at h
    · contradiction
    · rename_i _ _ hNotExceed _
      injection h with hState _
      subst hState
      simp only [turnRank]
      simp only [Bool.and_eq_true, decide_eq_true_eq] at hNotExceed
      omega

/-- Potential function for reactive compaction: strictly drops from 1 to 0,
    proving reactive compact can NEVER loop more than once per query. -/
def reactiveCompactRank (s : LoopState) : Nat :=
  if s.hasAttemptedReactiveCompact then 0 else 1

theorem reactive_compact_rank_decreases (cfg : LoopConfig) (s s' : LoopState)
    (succeeded : Bool) (r : ContinueReason)
    (h : step cfg s (.reactiveCompactAttempt succeeded) = .next s' r) :
    reactiveCompactRank s' < reactiveCompactRank s := by
  simp only [step] at h
  split at h
  · contradiction
  · split at h
    · rename_i hNotAttempted _
      injection h with hState _
      subst hState
      have hFalse : s.hasAttemptedReactiveCompact = false := by
        cases hA : s.hasAttemptedReactiveCompact <;> simp_all
      simp [reactiveCompactRank, hFalse]
    · contradiction

end AgentLoop
