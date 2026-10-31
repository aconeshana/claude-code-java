import type { SpinnerVerbsListing } from '../api/types'
import type { MessageState, ToolCallState } from '../store/conversations'

/**
 * The running status's verb, chosen the way the TUI spinner chooses its own
 * (`SpinnerVerbs` + `SpinnerStateMachine` in claude-code-ui):
 *
 * - a turn opens on a random active verb;
 * - once a tool starts, its fixed verb replaces it ("Read" → "Reading"), and
 *   a tool without one takes a fresh random active verb;
 * - the latest tool's verb holds until the next tool starts.
 *
 * "Random" here is a hash of the turn start or the tool-use id rather than
 * Math.random: a re-render, a remount, or a second tab on the same turn keeps
 * the same word instead of flickering through the list.
 */
export function runningVerb(
  vocabulary: SpinnerVerbsListing,
  messages: readonly MessageState[],
  turnStartedAt: number | undefined,
): string | undefined {
  const tool = latestTurnTool(messages)
  if (tool !== undefined) {
    return vocabulary.tools[tool.name] ?? pickVerb(vocabulary.active, tool.toolUseId)
  }
  return pickVerb(vocabulary.active, String(turnStartedAt ?? ''))
}

/** The current turn's most recent tool call: the last one after the last user row. */
export function latestTurnTool(messages: readonly MessageState[]): ToolCallState | undefined {
  for (let index = messages.length - 1; index >= 0; index--) {
    const message = messages[index]
    if (message.kind === 'user') return undefined
    const calls = message.toolCalls
    if (calls.length > 0) return calls[calls.length - 1]
  }
  return undefined
}

/** A stable pick from `verbs` keyed by `seed` (FNV-1a); undefined for an empty list. */
export function pickVerb(verbs: readonly string[], seed: string): string | undefined {
  if (verbs.length === 0) return undefined
  let hash = 0x811c9dc5
  for (let index = 0; index < seed.length; index++) {
    hash = Math.imul(hash ^ seed.charCodeAt(index), 0x01000193)
  }
  return verbs[(hash >>> 0) % verbs.length]
}
