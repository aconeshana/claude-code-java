import Lean.Data.Json
import Protocol.StreamGrammar

open Lean (Json)
open Protocol.StreamGrammar

namespace Protocol.StreamVerifier

private def parseEvent (j : Json) : Except String Event := do
  let kind ← j.getObjValAs? String "kind"
  match kind with
  | "message_start" => pure .messageStart
  | "block_start" =>
      let idx ← j.getObjValAs? Int "index"
      pure (.blockStart idx)
  | "block_delta" =>
      let idx ← j.getObjValAs? Int "index"
      pure (.blockDelta idx)
  | "block_stop" =>
      let idx ← j.getObjValAs? Int "index"
      pure (.blockStop idx)
  | "message_delta" => pure .messageDelta
  | "message_stop" => pure .messageStop
  | "ping" => pure .ping
  | "request_timing" => pure .requestTiming
  | "error" => pure .error
  | other => throw s!"Unknown StreamEvent kind: {other}"

private def verifyStream (lineNo : Nat) (line : String) : Except String Nat := do
  let j ← Json.parse line
  let scenario ← j.getObjValAs? String "scenario"
  let evArr ← match j.getObjVal? "events" with
    | .ok (Json.arr arr) => pure arr
    | _ => throw s!"Line {lineNo} [{scenario}]: missing 'events' array"
  let mut st : GrammarState := .idle
  let mut evIdx : Nat := 0
  for evJson in evArr do
    let ev ← parseEvent evJson
    match step st ev with
    | some nextSt =>
        st := nextSt
        evIdx := evIdx + 1
    | none =>
        throw s!"Line {lineNo} [{scenario}] STREAM GRAMMAR VIOLATION at event #{evIdx}: state={repr st}, event={repr ev}"
  if !isAcceptedTerminal st then
    throw s!"Line {lineNo} [{scenario}] UNTERMINATED STREAM: ended in non-terminal state {repr st} after {evIdx} event(s)"
  pure evIdx

end Protocol.StreamVerifier

def main (args : List String) : IO UInt32 := do
  match args with
  | [tracePath] =>
      let contents ← IO.FS.readFile tracePath
      let lines := contents.splitOn "\n"
        |>.map String.trim
        |>.filter (fun s => !s.isEmpty)
      if lines.isEmpty then
        IO.eprintln s!"ERROR: Stream trace file '{tracePath}' is empty."
        return 1
      let mut streams := 0
      let mut totalEvents := 0
      let mut lineNo := 0
      for line in lines do
        lineNo := lineNo + 1
        match Protocol.StreamVerifier.verifyStream lineNo line with
        | .ok n =>
            streams := streams + 1
            totalEvents := totalEvents + n
        | .error msg =>
            IO.eprintln msg
            return 1
      IO.println s!"OK: Verified {streams} canonical stream(s) ({totalEvents} total events) in '{tracePath}' against Lean 4 StreamGrammar."
      return 0
  | _ =>
      IO.eprintln "Usage: stream-verifier <path-to-stream-events.jsonl>"
      return 2
