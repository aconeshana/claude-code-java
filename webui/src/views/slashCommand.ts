/**
 * The composer's `/` slash-command grammar, built-in row faces, bilingual
 * fuzzy ranking (`rankByName` from `@primitives`), argument-hint detection,
 * and `@`-directory breadcrumb helpers.
 */
import {
  IconAlarmClockOutline16,
  IconBrowseOutline16,
  IconClockOutline16,
  IconCodeOutline16,
  IconContextInjectionOutline16,
  IconDatabaseOutline16,
  IconDownloadOutline16,
  IconGaugeOutline16,
  IconLinkOutline16,
  IconListPenOutline16,
  IconPaperclipOutline16,
  IconProjectAddOutline16,
  IconSendOutline16,
  IconSettingsOutline16,
  IconShieldOutline16,
  IconSkillOutline16,
  IconThinkOutline16,
  IconTrashOutline16,
  rankByName,
} from '@primitives'
import type { ComposerCommandEntry } from '../api/types'
import type { MenuCandidate, MenuIconComponent } from '../../vendor/dsh-composer-menu/menuCore'
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

interface BuiltinFace {
  readonly label: string
  readonly description: string
  readonly icon: MenuIconComponent
}

const BUILTIN_FACES: Readonly<Record<string, BuiltinFace>> = {
  compact: { label: '压缩', description: '压缩以上对话内容', icon: IconContextInjectionOutline16 },
  clear: { label: '清空', description: '清空当前对话历史', icon: IconTrashOutline16 },
  context: { label: '上下文', description: '查看当前上下文占用详情', icon: IconGaugeOutline16 },
  model: { label: '模型', description: '选择本会话使用的模型', icon: IconDatabaseOutline16 },
  permissions: { label: '权限', description: '切换权限预设（沙箱模式与审批策略）', icon: IconShieldOutline16 },
  plan: { label: '计划', description: '进入或退出计划模式', icon: IconListPenOutline16 },
  review: { label: '审查', description: '审查当前代码变更', icon: IconCodeOutline16 },
  help: { label: '帮助', description: '查看帮助与可用指令', icon: IconBrowseOutline16 },
  export: { label: '导出', description: '导出当前对话到文件或剪贴板', icon: IconDownloadOutline16 },
  cost: { label: '用量', description: '查看本次会话的 Token 与耗时统计', icon: IconClockOutline16 },
  memory: { label: '记忆', description: '管理项目与个人记忆', icon: IconThinkOutline16 },
  mcp: { label: 'MCP', description: '管理 MCP 工具服务连接', icon: IconLinkOutline16 },
  init: { label: '初始化', description: '初始化项目 CLAUDE.md 配置', icon: IconProjectAddOutline16 },
  feedback: { label: '反馈', description: '发送关于当前会话的反馈', icon: IconSendOutline16 },
  loop: { label: '定时', description: '设置循环或定时执行的任务', icon: IconAlarmClockOutline16 },
  schedule: { label: '定时', description: '管理后台定时任务', icon: IconAlarmClockOutline16 },
  config: { label: '设置', description: '查看或修改配置项', icon: IconSettingsOutline16 },
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
    const face = BUILTIN_FACES[entry.name.toLowerCase()]
    const description = face?.description ?? entry.description
    const icon = face?.icon ?? (entry.kind === 'skill' ? IconSkillOutline16 : IconCodeOutline16)
    return {
      name: entry.name,
      ...(face == null ? {} : { label: face.label }),
      ...(description == null ? {} : { description }),
      icon,
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
    const commandEntries = catalogue.filter((c) => c.kind !== 'skill')
    const skillEntries = catalogue.filter((c) => c.kind === 'skill')
    const cmdRows = commandRows(commandEntries).map((r) => ({ ...r, section: '指令' }))
    const skillRows = commandRows(skillEntries).map((r) => ({ ...r, section: '技能' }))
    return [...cmdRows, ...skillRows]
  }
  const byName = rankByName(rows, trimmed)
  const seen = new Set(byName.map((r) => r.name))
  const lower = trimmed.toLowerCase()
  const byLabel = rows.filter(
    (r) => !seen.has(r.name) && r.label !== undefined && r.label.toLowerCase().includes(lower),
  )
  return [...byName, ...byLabel]
}
