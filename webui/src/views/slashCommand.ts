/**
 * The composer's `/` slash-command grammar, built-in row faces, bilingual
 * fuzzy ranking (`rankByName` from `@primitives`), argument-hint detection,
 * and `@`-directory breadcrumb helpers.
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
 * Built-in command faces & priority order (mirrors upstream `ui-commands`
 * `builtinRowFace` + `sectionRows` usage order). Unlisted commands and skills
 * follow in registry order.
 */
const BUILTIN_ORDER: readonly string[] = [
  'compact',
  'clear',
  'context',
  'model',
  'permissions',
  'plan',
  'review',
  'help',
  'export',
  'cost',
  'memory',
  'mcp',
  'init',
]

const BUILTIN_LABELS: Readonly<Record<string, string>> = {
  compact: '压缩上下文',
  clear: '清空对话',
  context: '上下文详情',
  model: '切换模型',
  permissions: '权限管理',
  plan: '规划模式',
  review: '代码审查',
  help: '帮助',
  export: '导出对话',
  cost: '用量统计',
  memory: '记忆管理',
  mcp: 'MCP 服务',
  init: '初始化项目',
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
  let start = 0
  while (start < text.length && (text[start] === ' ' || text[start] === '\t')) {
    start++
  }
  if (text[start] !== '/') return null
  if (caret <= start) return null

  let end = start + 1
  while (end < text.length && !/\s/.test(text[end]!)) {
    end++
  }
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
 * Detects when the draft holds a completed `/<name> ` command with no arguments
 * yet and returns its `argument_hint` for ghost-text guidance in the composer.
 */
export function activeCommandHint(
  draftText: string,
  catalogue: readonly ComposerCommandEntry[],
): { readonly name: string; readonly hint: string } | null {
  const match = /^[ \t]*\/([^\s]+)[ \t]+$/.exec(draftText)
  if (match == null) return null
  const name = match[1]!.toLowerCase()
  const entry = catalogue.find((c) => c.name.toLowerCase() === name)
  if (entry == null || entry.argument_hint == null || entry.argument_hint.trim() === '') {
    return null
  }
  return { name: entry.name, hint: entry.argument_hint.trim() }
}

/**
 * Extracts directory breadcrumb segments from an active `@` file query
 * (e.g. `src/views/In` → `['src', 'views']`, `src/` → `['src']`, `foo` → `[]`).
 */
export function atBreadcrumbSegments(query: string): readonly string[] {
  const lastSlash = query.lastIndexOf('/')
  if (lastSlash <= 0) return []
  return query.slice(0, lastSlash).split('/').filter((seg) => seg.length > 0)
}

/**
 * Convert `/api/commands` entries into `ComposerMenu` candidate rows, ordering
 * pinned built-ins first and attaching localized labels where available.
 */
function commandRows(catalogue: readonly ComposerCommandEntry[]): readonly MenuCandidate[] {
  const orderOf = (name: string): number => {
    const idx = BUILTIN_ORDER.indexOf(name.toLowerCase())
    return idx < 0 ? BUILTIN_ORDER.length : idx
  }
  const sorted = [...catalogue].sort((a, b) => orderOf(a.name) - orderOf(b.name))
  return sorted.map((entry) => {
    const localized = BUILTIN_LABELS[entry.name.toLowerCase()]
    return {
      name: entry.name,
      ...(localized == null ? {} : { label: localized }),
      ...(entry.description == null ? {} : { description: entry.description }),
      ...(entry.argument_hint == null ? {} : { hint: entry.argument_hint }),
      ...(entry.kind === 'skill' ? { icon: IconSkillOutline16 } : {}),
      value: 'command',
    }
  })
}

/**
 * Projects `/api/commands` onto the "+" button menu: the Add section (File
 * picker) followed by the Commands section (pinned built-ins first, then
 * remaining commands and skills in registry order).
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
 * - Empty query (`/`): all commands under the Commands section (pinned
 *   built-ins first, then registry order).
 * - Non-empty query (`/com` or `/压缩`): fuzzy-ranked via vendored `rankByName`
 *   over command names, plus localized label matches appended without
 *   duplicates, rendered flat without section headings so rank order holds.
 */
export function slashMenuCandidates(
  catalogue: readonly ComposerCommandEntry[],
  query: string,
): readonly MenuCandidate[] {
  const rows = commandRows(catalogue)
  const trimmed = query.trim()
  if (trimmed === '') {
    return sectionRows(null, rows, { add: '添加', commands: '指令' })
  }
  const byName = rankByName(rows, trimmed)
  const seen = new Set(byName.map((r) => r.name))
  const lower = trimmed.toLowerCase()
  const byLabel = rows.filter(
    (r) => !seen.has(r.name) && r.label !== undefined && r.label.toLowerCase().includes(lower),
  )
  return [...byName, ...byLabel]
}
