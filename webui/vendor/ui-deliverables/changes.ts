/**
 * The changed-files surfaces' shared contract: the paths the Host serves, the
 * address a review tab is opened under, and the guards the stores decode with.
 *
 * Vendored from upstream `packages/client/ui-deliverables/src/changes.ts` at
 * the pinned dsh commit. Two deliberate differences, both recorded in
 * `webui/UPSTREAM.md`:
 *
 * 1. Upstream addresses a comparison by `(sessionId, seq, index)` — the
 *    sequence number of the `workspace/changes` event announcing it and the
 *    file's position in that event's list. This app emits no such event, so a
 *    comparison is addressed by the file's own `path`, which is what the
 *    changed-files chip already carries.
 * 2. The turn summary upstream reads from the announcing event is this app's
 *    own `GET /api/changes.summary`, addressed by the transcript's 1-based
 *    turn number.
 *
 * Everything else — the hunk guard, the address builder, the parsed shape —
 * is upstream's, verbatim.
 */

/** The turn-summary read. */
export const CHANGED_FILES_PATH = '/api/changes.summary'

/** The single-file comparison read. */
export const CHANGES_DIFF_PATH = '/api/changes.diff'

/** The address prefix a review tab is opened under. */
export const CHANGES_REVIEW_ADDRESS = 'dsh-resource://changes-review/session/'

/** One changed file, as the summary lists it. */
export interface ChangesSummaryFile {
  readonly path: string
  readonly display: string
  readonly added: number
  readonly deleted: number
  readonly binary?: boolean
  readonly oversized?: boolean
}

/** One turn's changed files. */
export interface ChangesSummary {
  /** The 1-based turn number, as the transcript numbers it. */
  readonly turn: number
  readonly total: number
  readonly added: number
  readonly deleted: number
  readonly files: readonly ChangesSummaryFile[]
}

/** One contiguous changed region of a file. */
export interface WorkspaceDiffHunk {
  readonly oldStart: number
  readonly oldLines: number
  readonly newStart: number
  readonly newLines: number
  readonly lines: readonly string[]
}

/**
 * One file's comparison, discriminated on `kind`. `binary` and `oversized`
 * deliberately carry no hunks: there is no line diff to show, and an empty one
 * would read as "the file did not change".
 */
export type ChangesDiff =
  | {
      readonly kind: 'text'
      readonly path: string
      readonly display: string
      /** Whether a prior version exists; false for a file the turn created. */
      readonly before: boolean
      /** Whether content remains after the change; false for a deletion. */
      readonly after: boolean
      /** True when the hunks approximate the whole file rather than a patch. */
      readonly coarse: boolean
      readonly hunks: readonly WorkspaceDiffHunk[]
    }
  | { readonly kind: 'binary'; readonly path: string; readonly display: string }
  | { readonly kind: 'oversized'; readonly path: string; readonly display: string }

/** The coordinates a review tab is addressed by. */
export interface ChangesReviewCoordinates {
  readonly sessionId: string
  readonly turn: number
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

/**
 * Whether a value is a hunk this app can draw. The line prefixes are the
 * contract: `+` added, `-` removed, a space for context.
 * @param value - a decoded field.
 * @returns whether it is a served hunk.
 */
export function isHunk(value: unknown): value is WorkspaceDiffHunk {
  if (!isRecord(value)) return false
  const { oldStart, oldLines, newStart, newLines, lines } = value
  return [oldStart, oldLines, newStart, newLines].every(
    field => Number.isSafeInteger(field) && (field as number) >= 0,
  )
    && Array.isArray(lines)
    && lines.every(line => typeof line === 'string' && /^[+ -]/.test(line))
}

/** Whether a value is a comparison of any kind. */
export function isChangesDiff(value: unknown): value is ChangesDiff {
  if (!isRecord(value)) return false
  if (typeof value.path !== 'string' || typeof value.display !== 'string') return false
  if (value.kind === 'binary' || value.kind === 'oversized') return true
  if (value.kind !== 'text') return false
  return typeof value.before === 'boolean'
    && typeof value.after === 'boolean'
    && typeof value.coarse === 'boolean'
    && Array.isArray(value.hunks)
    && value.hunks.every(isHunk)
}

/** Whether a value is a turn summary. */
export function isChangesSummary(value: unknown): value is ChangesSummary {
  if (!isRecord(value)) return false
  return Number.isSafeInteger(value.turn)
    && Number.isSafeInteger(value.total)
    && Number.isSafeInteger(value.added)
    && Number.isSafeInteger(value.deleted)
    && Array.isArray(value.files)
}

/** The address a review tab opens under. */
export function changesReviewAddress(coordinates: ChangesReviewCoordinates): string {
  const { sessionId, turn } = coordinates
  return `${CHANGES_REVIEW_ADDRESS}${encodeURIComponent(sessionId)}/${turn}`
}

/**
 * The URL one file's comparison is read from. Upstream's builder takes
 * `(sessionId, seq, index)`; this app addresses a comparison by the file's own
 * path (see the header note), so the pair is `(sessionId, path)`.
 * @param sessionId - the viewed Session.
 * @param path - the file's workspace-relative path.
 * @returns the comparison's URL.
 */
export function changesDiffUrl(sessionId: string, path: string): string {
  const query = new URLSearchParams({ session_id: sessionId, path })
  return `${CHANGES_DIFF_PATH}?${query.toString()}`
}

/**
 * The URL one turn's summary is read from. Upstream reads a summary off the
 * announcing `workspace/changes` event; this app has no such event and serves a
 * turn-addressed endpoint instead (see the header note).
 * @param sessionId - the viewed Session.
 * @param turn - the 1-based turn number.
 * @returns the summary's URL.
 */
export function changesSummaryUrl(sessionId: string, turn: number): string {
  const query = new URLSearchParams({ session_id: sessionId, turn: String(turn) })
  return `${CHANGED_FILES_PATH}?${query.toString()}`
}
