namespace Protocol.StreamGrammar

/-- Canonical `StreamEvent` kinds shared across all 4 protocol adapters
    (Anthropic, OpenAI Chat, OpenAI Responses, Google Gemini).
    Note: `index` is `Int` because `OpenAiResponsesClient` uses negative synthetic
    indices (`-1, -2, ...`) for reasoning blocks alongside non-negative `output_index` values. -/
inductive Event where
  | messageStart
  | blockStart (index : Int)
  | blockDelta (index : Int)
  | blockStop (index : Int)
  | messageDelta
  | messageStop
  | ping
  | requestTiming
  | error
  deriving Repr, DecidableEq

/-- Finite-state grammar for a well-formed canonical `StreamEvent` stream.
    Enforces that every block opened is strictly closed before the next block or
    `messageStop`, no block index is ever reused, and `messageStop` is terminal. -/
inductive GrammarState where
  | idle
  | inMessage (usedIndices : List Int)
  | inBlock (openIndex : Int) (usedIndices : List Int)
  | afterMessageDelta
  | completed
  | failed
  deriving Repr, DecidableEq

/-- Single-event transition function returning `some nextState` iff the event is
    grammatically legal in the current state, or `none` on protocol violation. -/
def step (st : GrammarState) (ev : Event) : Option GrammarState :=
  match ev with
  | .ping => some st
  | .requestTiming => some st
  | .error =>
      match st with
      | .completed => none
      | _ => some .failed
  | .messageStart =>
      match st with
      | .idle => some (.inMessage [])
      | _ => none
  | .blockStart idx =>
      match st with
      | .inMessage used =>
          if used.contains idx then none else some (.inBlock idx (idx :: used))
      | _ => none
  | .blockDelta idx =>
      match st with
      | .inBlock openIdx used =>
          if idx == openIdx then some (.inBlock openIdx used) else none
      | _ => none
  | .blockStop idx =>
      match st with
      | .inBlock openIdx used =>
          if idx == openIdx then some (.inMessage used) else none
      | _ => none
  | .messageDelta =>
      match st with
      | .inMessage _ => some .afterMessageDelta
      | .afterMessageDelta => some .afterMessageDelta
      | _ => none
  | .messageStop =>
      match st with
      | .inMessage _ => some .completed
      | .afterMessageDelta => some .completed
      | _ => none

/-- Runs a full event sequence from `GrammarState.idle`. -/
def run (events : List Event) : Option GrammarState :=
  events.foldlM step .idle

/-- A complete stream is well-formed iff it finishes in `completed` or `failed`. -/
def isAcceptedTerminal (st : GrammarState) : Bool :=
  match st with
  | .completed => true
  | .failed => true
  | _ => false

/-- Theorem 1: No `blockDelta` can occur before `messageStart`. -/
theorem no_delta_before_start (idx : Int) :
    step .idle (.blockDelta idx) = none := rfl

/-- Theorem 2: No `blockDelta` can occur outside an open block. -/
theorem no_delta_outside_block (used : List Int) (idx : Int) :
    step (.inMessage used) (.blockDelta idx) = none := rfl

/-- Theorem 3: A `blockDelta` inside `inBlock` is accepted ONLY when its index matches `openIdx`. -/
theorem delta_requires_matching_index (openIdx : Int) (used : List Int) (idx : Int) (st' : GrammarState)
    (h : step (.inBlock openIdx used) (.blockDelta idx) = some st') :
    idx = openIdx ∧ st' = .inBlock openIdx used := by
  simp only [step] at h
  split at h
  · rename_i hEq
    injection h with hSt
    exact ⟨beq_iff_eq.mp hEq, hSt.symm⟩
  · contradiction

/-- Theorem 4: `messageStop` is forbidden while a content block is still open. -/
theorem message_stop_requires_closed_blocks (openIdx : Int) (used : List Int) :
    step (.inBlock openIdx used) .messageStop = none := rfl

/-- Theorem 5: `completed` is strictly absorbing for all content/lifecycle events
    (prevents duplicate `messageStop` or trailing deltas after completion). -/
theorem completed_rejects_lifecycle_events (ev : Event)
    (hNotMeta : ev ≠ .ping ∧ ev ≠ .requestTiming) :
    step .completed ev = none := by
  cases ev with
  | ping => exact absurd rfl hNotMeta.1
  | requestTiming => exact absurd rfl hNotMeta.2
  | messageStart => rfl
  | blockStart _ => rfl
  | blockDelta _ => rfl
  | blockStop _ => rfl
  | messageDelta => rfl
  | messageStop => rfl
  | error => rfl

/-- Theorem 6: `blockStart` forbids reusing any previously opened block index. -/
theorem block_start_forbids_duplicate_index (used : List Int) (idx : Int)
    (hUsed : used.contains idx = true) :
    step (.inMessage used) (.blockStart idx) = none := by
  simp only [step, hUsed, ↓reduceIte]

end Protocol.StreamGrammar
