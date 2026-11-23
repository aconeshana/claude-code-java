/**
 * The composer's `/` slash-command grammar and candidate ranking — the client
 * half of the slash commands `CommandRegistry.dispatchNonInteractive` parses
 * at submission time, paired with upstream dsh's `rankByName` fuzzy scorer
 * (`vendor/ui-primitives/rank-by-name.ts`).
 */
import { IconPaperclipOutline16, IconSkillOutline16, rankByName } from '@primitives'
import type { ComposerCommandEntry } from '../api/types'
import type { MenuCandidate } from '../../vendor/dsh-composer-menu/menuCore'
import { sectionRows } from '../../vendor/dsh-composer-menu/sectionRows'

/** The active `/` token under the caret, or null when there is none. */
export interface SlashToken {
  /** Offset of the `/` in the draft text. */
  readonly start: number
  /** Offset just past the command-name token (up to the next whitespace or end). */
  readonly end: number
  /** The typed query after `/` up to the caret (without the leading `/`). */
  readonly query: string
}

/**
 * The active `/` command token at `caret`, or null.
 *
 * Matches the backend's `CommandRegistry.dispatchNonInteractive` boundary: a
 * slash command only lives at the start of the draft (optional leading
 * horizontal spaces allowed) and ends at the first whitespace. Anything after
 * whitespace is the command's arguments (so the menu closes), and a `/` later
 * in prose or on a subsequent line is never a command trigger.
 */
export function activeSlashToken(text: string, caret: number): SlashToken | null {
  if (caret <= 0 || caret > text.length) return null
  // Skip leading spaces/tabs on the first line only (a newline before `/` is
  // not a leading slash command).
  let start = 0
  while (start < text.length && (text[start] === ' ' || text[start] === '\t')) {
    start++
  }
  if (text[start] !== '/') return null
  if (caret <= start) return null

  // The command-name token runs until the first whitespace character.
  let end = start + 1
  while (end < text.length && !/\s/.test(text[end]!)) {
    end++
  }
  // Once the caret moves past the token (into whitespace or arguments), the
  // slash trigger is no longer active.
  if (caret > end) return null

  return {
    start,
    end,
    query: text.slice(start + 1, caret),
  }
}

/** Value-equality check for two optional tokens (avoids redundant state updates). */
export function sameSlashToken(a: SlashToken | null, b: SlashToken | null): boolean {
  if (a === b) return true
  if (a == null || b == null) return false
  return a.start === b.start && a.end === b.end && a.query === b.query
}

/**
 * Replace the active `/` token in `text` with `/<name>` (adding a trailing
 * space unless whitespace already follows) and return the merged text plus the
 * caret offset parked right after the inserted command.
 */
export function applySlashPick(
  text: string,
  token: SlashToken,
  name: string,
): { readonly text: string; readonly caret: number } {
  const after = text.slice(token.end)
  const needsSpace = after.length === 0 || !/\s/.test(after[0]!)
  const inserted = needsSpace ? `/${name} ` : `/${name}`
  const merged = text.slice(0, token.start) + inserted + after
  const caret = token.start + inserted.length + (needsSpace ? 0 : 1)
  return { text: merged, caret }
}

/**
 * Convert `/api/commands` entries into `ComposerMenu` candidate rows.
 */
function commandRows(catalogue: readonly ComposerCommandEntry[]): readonly MenuCandidate[] {
  return catalogue.map((entry) => ({
    name: entry.name,
    ...(entry.description == null ? {} : { description: entry.description }),
    ...(entry.argument_hint == null ? {} : { hint: entry.argument_hint }),
    ...(entry.kind === 'skill' ? { icon: IconSkillOutline16 } : {}),
    value: 'command',
  }))
}

/**
 * Projects `/api/commands` onto the "+" button menu: the Add section (File
 * picker) followed by the Commands section in registry order.
 */
export function plusMenuCandidates(catalogue: readonly ComposerCommandEntry[]): readonly MenuCandidate[] {
  const fileRow: MenuCandidate = {
    name: 'file',
    label: '文件',
    description: '添加图片或文件到输入框',
    icon: IconPaperclipOutline16,
    value: 'file',
  }
  return sectionRows(fileRow, commandRows(catalogue), { add: '添加', commands: '指令' })
}

/**
 * Projects `/api/commands` onto the typed `/` menu:
 * - Empty query (`/`): all commands under the Commands section in registry order.
 * - Non-empty query (`/com`): fuzzy-ranked across all commands via vendored
 *   `rankByName` (prefix hits first, then strongest alignment score, then
 *   registry order), rendered flat without section headings so rank order is
 *   preserved.
 */
export function slashMenuCandidates(
  catalogue: readonly ComposerCommandEntry[],
  query: string,
): readonly MenuCandidate[] {
  const rows = commandRows(catalogue)
  if (query.trim() === '') {
    return sectionRows(null, rows, { add: '添加', commands: '指令' })
  }
  return rankByName(rows, query)
}
