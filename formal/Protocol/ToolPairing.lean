namespace Protocol.ToolPairing

/-- Abstract tool result entry keyed by `callId`. -/
structure ResultEntry where
  callId      : String
  interrupted : Bool := false
  deriving Repr, DecidableEq

/-- First matching result for `cid` in `results` (first-wins deduplication). -/
def findFirst (cid : String) : List ResultEntry → Option ResultEntry
  | [] => none
  | r :: rs => if r.callId == cid then some r else findFirst cid rs

/-- Mirrors `ToolPairingHealer.healRequestMessages` per-turn pairing step:
    for each open assistant `tool_use` ID in exact order, pick the first matching
    user `tool_result` or synthesize an interrupted placeholder. Orphan results
    (whose IDs are not in `openUses`) are excluded from the paired tool-result list. -/
def healResults (openUses : List String) (results : List ResultEntry) : List ResultEntry :=
  openUses.map fun cid =>
    match findFirst cid results with
    | some r => r
    | none   => { callId := cid, interrupted := true }

private theorem findFirst_of_map_eq (cid : String) (xs : List String)
    (f : String → ResultEntry)
    (hf : ∀ x, (f x).callId = x)
    (hMem : cid ∈ xs) :
    findFirst cid (xs.map f) = some (f cid) := by
  induction xs with
  | nil => cases hMem
  | cons x rest ih =>
      simp only [List.map_cons, findFirst, hf x]
      by_cases hEq : x = cid
      · subst hEq
        simp
      · have hBeq : (x == cid) = false := beq_false_of_ne hEq
        simp only [hBeq]
        have hInRest : cid ∈ rest := by
          cases hMem with
          | head => exact absurd rfl hEq
          | tail _ h => exact h
        exact ih hInRest

/-- Theorem 1 (Exact Order & Completeness):
    The healed tool-result IDs match `openUses` in exact 1:1 order. -/
theorem healResults_exact_ids (openUses : List String) (results : List ResultEntry) :
    (healResults openUses results).map ResultEntry.callId = openUses := by
  unfold healResults
  rw [List.map_map]
  induction openUses with
  | nil => rfl
  | cons cid rest ih =>
      simp only [List.map_cons, Function.comp_apply]
      have hHead : (match findFirst cid results with
          | some r => r
          | none => { callId := cid, interrupted := true }).callId = cid := by
        cases hF : findFirst cid results with
        | none => rfl
        | some r =>
            have hId : r.callId = cid := by
              clear ih
              induction results with
              | nil => simp [findFirst] at hF
              | cons x xs ihR =>
                  simp only [findFirst] at hF
                  split at hF
                  · rename_i hEq
                    injection hF with hR
                    subst hR
                    exact beq_iff_eq.mp hEq
                  · exact ihR hF
            exact hId
      rw [hHead, ih]

/-- Theorem 2 (No Orphan Results):
    Every `callId` in the healed output belongs to `openUses`. -/
theorem healResults_no_orphans (openUses : List String) (results : List ResultEntry)
    (r : ResultEntry) (hMem : r ∈ healResults openUses results) :
    r.callId ∈ openUses := by
  have hMap : r.callId ∈ (healResults openUses results).map ResultEntry.callId :=
    List.mem_map_of_mem ResultEntry.callId hMem
  rw [healResults_exact_ids] at hMap
  exact hMap

/-- Theorem 3 (Every Open Use Answered):
    Every `cid ∈ openUses` has a corresponding entry in `healResults openUses results`. -/
theorem healResults_answers_all_uses (openUses : List String) (results : List ResultEntry)
    (cid : String) (hMem : cid ∈ openUses) :
    ∃ r ∈ healResults openUses results, r.callId = cid := by
  rw [← healResults_exact_ids openUses results] at hMem
  rcases List.mem_map.mp hMem with ⟨r, hIn, hId⟩
  exact ⟨r, hIn, hId⟩

/-- Theorem 4 (Idempotence):
    Healing an already-healed turn is a strict fixed point:
    `healResults openUses (healResults openUses results) = healResults openUses results`. -/
theorem healResults_idempotent (openUses : List String) (results : List ResultEntry) :
    healResults openUses (healResults openUses results) = healResults openUses results := by
  let f : String → ResultEntry := fun cid =>
    match findFirst cid results with
    | some r => r
    | none   => { callId := cid, interrupted := true }
  have hf : ∀ cid, (f cid).callId = cid := by
    intro cid
    dsimp only [f]
    cases hF : findFirst cid results with
    | none => rfl
    | some r =>
        induction results with
        | nil => simp [findFirst] at hF
        | cons x xs ihR =>
            simp only [findFirst] at hF
            split at hF
            · rename_i hEq
              injection hF with hR
              subst hR
              exact beq_iff_eq.mp hEq
            · exact ihR hF
  change openUses.map (fun cid =>
      match findFirst cid (openUses.map f) with
      | some r => r
      | none   => { callId := cid, interrupted := true }) = openUses.map f
  apply List.map_congr_left
  intro cid hMem
  rw [findFirst_of_map_eq cid openUses f hf hMem]

end Protocol.ToolPairing
