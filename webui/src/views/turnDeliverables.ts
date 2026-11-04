/**
 * Turn-scoped produced-file collection — the pure layer the deliverable row
 * renders. Mirrors upstream `ui-deliverables/src/client/turn-deliverables.ts`:
 * the vocabulary is the successful first-party mutation tools' own paths,
 * never the closing prose, so a produced file is listed whether or not the
 * model remembered to name it. Reads, unsupported tools, malformed calls, and
 * failed results contribute nothing; a path written and then edited in the
 * same turn is one entry.
 *
 * This is the turn-wide sibling of `deliverables.ts`, which stays per-row for
 * `DeliverablesRow.tsx`. Both dedup by exact path keeping first-occurrence
 * order; this one feeds the turn tail, that one feeds a message's chip row.
 *
 * Deviation from upstream: upstream validates the mutation call's raw JSON
 * arguments itself (`mutationPath` over `write`/`edit`/`str_replace_editor`)
 * because the session surface hands it `tool/call` events. This project's
 * gateway already projects the same paths onto the successful result as
 * `locations`, so this layer reads that projection instead of re-parsing
 * arguments. The validation it must still perform is the one the projection
 * cannot express: a call whose result failed produced nothing.
 */

import type { ToolCallState } from '../store/conversations'

/** Maximum chips rendered before the remainder counter (upstream `SHOWN_LIMIT`). */
export const SHOWN_LIMIT = 6

/**
 * Files produced across one turn's assistant rows.
 *
 * @param rows - the turn's assistant rows, in transcript order. A row's tool
 *   calls are consulted in order, and a call contributes only when it settled
 *   without an error.
 * @returns the produced paths, deduped, in first-seen order.
 */
export function collectTurnDeliverables(
  rows: readonly (readonly ToolCallState[])[],
): readonly string[] {
  const seen = new Set<string>()
  const paths: string[] = []
  for (const calls of rows) {
    for (const call of calls) {
      // A failed mutation writes nothing; upstream drops the call entirely
      // (`result.isError === true` contributes nothing to `produced`). A call
      // still in flight likewise has no result yet, so it contributes nothing.
      if (call.status !== 'executed') continue
      for (const path of call.locations ?? []) {
        if (seen.has(path)) continue
        seen.add(path)
        paths.push(path)
      }
    }
  }
  return paths
}

/**
 * Trailing path segment, the part that identifies the file at a glance.
 * Upstream resolves over both separators, so a path recorded on Windows still
 * yields a name here.
 *
 * @param path - slash- or backslash-separated path.
 * @returns the final segment, or the whole string when separator-free.
 */
export function basename(path: string): string {
  const at = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'))
  return at === -1 ? path : path.slice(at + 1)
}

/**
 * File-mention vocabulary over one turn's produced paths, for the closing
 * message's prose: an inline-code token opens the file it names. A token
 * resolves by exact path, or by being exactly the basename of exactly one
 * produced path — a basename two paths share stays inert rather than
 * guessing, so a mention link can never open the wrong file.
 *
 * @param paths - the turn's produced paths (tool order, already deduped).
 * @param openFile - the opener a resolved mention calls.
 * @param label - localizes the accessible open-label for a resolved path.
 * @returns the resolver `MarkdownText` consumes; the full path rides `title`,
 *   the same disambiguator the row's chips carry.
 */
export function producedFileMentions(
  paths: readonly string[],
  openFile: (path: string) => void,
  label: (path: string) => string,
): {
  readonly resolve: (value: string) =>
    { readonly open: () => void; readonly label: string; readonly title: string } | undefined
} {
  return {
    resolve(value) {
      const path = paths.includes(value) ? value : onlyPathWithBasename(paths, value)
      if (path === undefined) return undefined
      return { open: () => { openFile(path) }, label: label(path), title: path }
    },
  }
}

/** The single produced path whose basename is exactly `value`, else undefined. */
function onlyPathWithBasename(paths: readonly string[], value: string): string | undefined {
  const matches = paths.filter((path) => basename(path) === value)
  return matches.length === 1 ? matches[0] : undefined
}