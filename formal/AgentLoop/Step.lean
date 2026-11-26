import AgentLoop.Types

namespace AgentLoop

/-- Normalizes per-iteration counters at the start of `prepareIteration` in `QueryLoop.java`:
    `stopHookBlockingCount` resets to 0 unless the previous transition was `StopHookReentry`. -/
def effectiveStopHookBlockingCount (s : LoopState) : Nat :=
  match s.lastContinue with
  | some .stopHookReentry => s.stopHookBlockingCount
  | _ => 0

/-- Pure state-transition specification of `QueryLoop.java`.
    Every branch corresponds directly to a control-flow decision in `QueryLoop`:
    - `prepareIteration` (`budgetExceeded`, `blockingLimitReached`)
    - `executeModelCall` / `tryReactiveCompact` (`aborted`, `streamError`, `reactiveCompactAttempt`)
    - `handleToolUse` (`toolUse`)
    - `handleApiErrorMessage` (`apiMaxOutputTokens`)
    - `handleNaturalTurnEnd` / `handleStopHookBlocking` (`naturalEndTurn`) -/
def step (cfg : LoopConfig) (s : LoopState) (ev : StepEvent) : StepOutcome :=
  let blockingCount := effectiveStopHookBlockingCount s
  match ev with
  | .aborted =>
      .done .aborted

  | .budgetExceeded =>
      .done .maxBudget

  | .blockingLimitReached =>
      .done .promptTooLong

  | .streamError =>
      .done .streamError

  | .reactiveCompactAttempt succeeded =>
      if s.hasAttemptedReactiveCompact then
        .done .streamError
      else if succeeded then
        .next { s with
          stopHookBlockingCount := blockingCount
          hasAttemptedReactiveCompact := true
          maxTokensOverrideSet := false
          lastContinue := some .promptTooLongRecovery
        } .promptTooLongRecovery
      else
        .done .streamError

  | .apiMaxOutputTokens =>
      if cfg.allowMaxTokensEscalation && !s.maxTokensOverrideSet then
        .next { s with
          stopHookBlockingCount := blockingCount
          maxTokensOverrideSet := true
          stopHookActive := false
          lastContinue := some .maxOutputTokensEscalate
        } .maxOutputTokensEscalate
      else if s.recoveryCount < cfg.maxOutputTokensLimit then
        let attempt := s.recoveryCount + 1
        .next { s with
          stopHookBlockingCount := blockingCount
          recoveryCount := attempt
          maxTokensOverrideSet := false
          stopHookActive := false
          lastContinue := some (.maxOutputTokensRecovery attempt)
        } (.maxOutputTokensRecovery attempt)
      else
        .done .normal

  | .toolUse count hookPrevented producedSO soCallsThisQuery =>
      if hookPrevented then
        .done .hookStopped
      else
        let hasSO := s.hasStructuredOutput || producedSO
        if cfg.requireStructuredOutput && !hasSO && soCallsThisQuery >= cfg.maxStructuredOutputRetries then
          .done (.maxStructuredOutputRetries soCallsThisQuery)
        else
          let nextTurns := s.loopTurns + 1
          if cfg.maxTurns > 0 && nextTurns > cfg.maxTurns then
            .done (.maxTurns nextTurns)
          else
            .next { s with
              loopTurns := nextTurns
              recoveryCount := 0
              maxTokensOverrideSet := false
              stopHookBlockingCount := blockingCount
              hasStructuredOutput := hasSO
              structuredOutputCalls := soCallsThisQuery
              lastContinue := some (.toolUse count)
            } (.toolUse count)

  | .naturalEndTurn stopHookPrevent stopHookBlocking =>
      if stopHookPrevent then
        .done .stopHookPrevented
      else
        let needsSO := cfg.requireStructuredOutput && !s.hasStructuredOutput
        if stopHookBlocking || needsSO then
          let nextTurns := s.loopTurns + 1
          if cfg.maxTurns > 0 && nextTurns > cfg.maxTurns then
            .done (.maxTurns nextTurns)
          else
            let nextBlock := blockingCount + 1
            if cfg.stopHookBlockCap > 0 && nextBlock > cfg.stopHookBlockCap then
              .done .normal
            else
              .next { s with
                loopTurns := nextTurns
                maxTokensOverrideSet := false
                stopHookActive := true
                stopHookBlockingCount := nextBlock
                lastContinue := some .stopHookReentry
              } .stopHookReentry
        else
          .done .normal

end AgentLoop
