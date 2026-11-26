namespace AgentLoop

/-- Mirrors `AutoCompactTrackingState.java`. -/
structure AutoCompactState where
  compacted           : Bool := false
  consecutiveFailures : Nat  := 0
  deriving Repr, DecidableEq

def AutoCompactState.maxFailures : Nat := 3

def AutoCompactState.shouldRetry (s : AutoCompactState) : Bool :=
  s.consecutiveFailures < AutoCompactState.maxFailures

/-- Mirrors `Continue.java` (sealed interface). -/
inductive ContinueReason where
  | toolUse (toolCount : Nat)
  | compact
  | maxOutputTokensEscalate
  | maxOutputTokensRecovery (attempt : Nat)
  | promptTooLongRecovery
  | stopHookReentry
  deriving Repr, DecidableEq

/-- Mirrors `Terminal.java` (sealed interface). -/
inductive TerminalReason where
  | normal
  | maxTurns (turns : Nat)
  | maxStructuredOutputRetries (retries : Nat)
  | maxBudget
  | promptTooLong
  | streamError
  | stopHookPrevented
  | hookStopped
  | aborted
  deriving Repr, DecidableEq

/-- Static per-query configuration bounds extracted from `LoopContext` & `QuerySessionSpec`. -/
structure LoopConfig where
  maxTurns                  : Nat
  stopHookBlockCap          : Nat
  maxOutputTokensLimit      : Nat := 3
  allowMaxTokensEscalation  : Bool := false
  requireStructuredOutput   : Bool := false
  maxStructuredOutputRetries : Nat := 5
  deriving Repr, DecidableEq

/-- Abstract control-flow state mirroring `QueryState.java` + `LoopContext` counters. -/
structure LoopState where
  loopTurns                   : Nat := 1
  recoveryCount               : Nat := 0
  maxTokensOverrideSet        : Bool := false
  stopHookActive              : Bool := false
  stopHookBlockingCount       : Nat := 0
  hasStructuredOutput         : Bool := false
  structuredOutputCalls       : Nat := 0
  hasAttemptedReactiveCompact : Bool := false
  autoCompact                 : AutoCompactState := {}
  lastContinue                : Option ContinueReason := none
  deriving Repr, DecidableEq

/-- Abstract observable events fed into the `QueryLoop` state machine.
    All I/O, HTTP, JSON, prompt rendering, and tool internals are abstracted away. -/
inductive StepEvent where
  | aborted
  | budgetExceeded
  | blockingLimitReached
  | streamError
  | reactiveCompactAttempt (succeeded : Bool)
  | apiMaxOutputTokens
  | toolUse (count : Nat) (hookPrevented : Bool) (producedStructuredOutput : Bool) (structuredOutputCallsThisQuery : Nat)
  | naturalEndTurn (stopHookPrevent : Bool) (stopHookBlocking : Bool)
  deriving Repr, DecidableEq

/-- Single-step transition decision: either continue into a new state or terminate once. -/
inductive StepOutcome where
  | next (state : LoopState) (reason : ContinueReason)
  | done (terminal : TerminalReason)
  deriving Repr, DecidableEq

end AgentLoop
