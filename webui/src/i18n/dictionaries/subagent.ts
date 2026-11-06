import type { LocaleDict, LocaleId } from '../types'

// Copy lifted from upstream `ui-subagent`'s own dictionary
// (packages/client/ui-subagent/src/client/locales.ts at the pinned dsh
// commit). Upstream ships eight locales; this project's shell is zh/en only
// (see `stats.ts`), so the other six are cut — recorded as a deviation in
// `webui/UPSTREAM.md`.
//
// Two groups of keys live here, from the two halves of upstream's package:
//
//   - The **catalog** keys (`section.*`, `progress.*`, `count.*`, `tree.aria`,
//     `mode.*`, `activity.*`, `branch.*`, `loading.*`, `load.error`, `retry`,
//     `open.sidebar*`, `catalog.migrationRequired`, `duration.months*`,
//     `duration.years*`, `duration.exactDays`) belong to the header
//     descendant-count catalog — `vendor/ui-subagent/SubagentCatalogMenu.tsx`.
//   - The **row** keys (`title.*`, `progress.label/empty`, `status.*`,
//     `metrics.*`, `prompt.label`, `transcript.*`, `duration.seconds…daysHours`,
//     `duration.exactTitle`, `tokens.*`) belong to the in-transcript
//     `SubagentRow`, which has no upstream counterpart (upstream's catalog row
//     is a child-session row, not a tool-call row). Its duration and token
//     formatters are upstream's own pure functions; the surrounding copy is
//     this project's, written to match upstream's register.
//
// Upstream keys deliberately **not** carried over: the read-only composer copy
// (`readonly.*`) — this product has no child conversation to host a composer
// in (see the `@`/continuation deviations in `webui/UPSTREAM.md`).
export const SUBAGENT_NS = 'subagent'

export const subagentZh = {
  // --- header catalog (upstream ui-subagent) ---
  'section.title': '子智能体',
  'section.empty': '暂无子智能体',
  'progress.completed': '{count} 已完成',
  'progress.active': '{count} 进行中',
  'progress.inactive': '{count} 未运行',
  'progress.external': '{count} 外部任务',
  'duration.months': '约{months}个月',
  'duration.monthsDays': '约{months}个月{days}天',
  'duration.years': '约{years}年',
  'duration.yearsMonths': '约{years}年{months}个月',
  'duration.exactDays': '{days}天{hours}小时{minutes}分{seconds}秒',
  'loading.label': '正在加载子智能体…',
  'load.error': '无法加载子智能体',
  'catalog.migrationRequired': '需要迁移',
  'retry': '重试',
  'mode.external': '外部任务',
  'mode.oneShot': '一次性',
  'mode.continuable': '可继续',
  'mode.unknown': '模式未知',
  'activity.running': '正在运行',
  'activity.completed': '已完成',
  'activity.inactive': '当前未运行',
  'branch.collapse': '收起 {label} 的下级子智能体',
  'branch.expand': '展开 {label} 的下级子智能体',
  'count.total.one': '{count} 个子智能体',
  'count.total.other': '{count} 个子智能体',
  'count.running.one': '{count} 个子智能体，正在运行',
  'count.running.other': '{count} 个子智能体，正在运行',
  'switcher.aria': '切换子智能体：{title}',
  'tree.aria': '子智能体会话',
  'open.sidebar': '在侧边栏打开',
  'open.sidebar.aria': '在侧边栏打开 {label}',
  'open.floatWindow': '在浮窗中打开',

  // --- in-transcript subagent row (this project) ---
  'title.subagent': '子智能体',
  'title.task': '任务',
  'progress.label': '进度',
  'progress.empty': '等待子智能体输出…',
  'progress.settled': '没有可显示的实时进度。',
  'result.label': '结果',
  'status.running': '正在运行',
  'status.completed': '已完成',
  'status.failed': '已失败',
  'metrics.toolUses': '{count} 次工具调用',
  'metrics.tokens': '{value} tok',
  'prompt.label': '任务提示',
  'transcript.open': '查看完整对话',
  'transcript.loading': '正在加载对话…',
  'transcript.error': '无法加载子智能体对话',
  'transcript.retry': '重试',
  'transcript.empty': '（子智能体没有留下对话记录）',
  'duration.seconds': '{seconds}秒',
  'duration.minutes': '{minutes}分{seconds}秒',
  'duration.hours': '{hours}小时{minutes}分{seconds}秒',
  'duration.days': '{days}天',
  'duration.daysHours': '{days}天{hours}小时',
  'duration.exactTitle': '总活跃耗时：{duration}',
  'tokens.thousand': '{value}K',
  'tokens.million': '{value}M',
  'tokens.total': '{value} tok',
}

export const subagentEn: Record<keyof typeof subagentZh, string> = {
  // --- header catalog (upstream ui-subagent) ---
  'section.title': 'Subagents',
  'section.empty': 'No subagents',
  'progress.completed': '{count} completed',
  'progress.active': '{count} in progress',
  'progress.inactive': '{count} not running',
  'progress.external': '{count} external',
  'duration.months': '~{months}mo',
  'duration.monthsDays': '~{months}mo {days}d',
  'duration.years': '~{years}y',
  'duration.yearsMonths': '~{years}y {months}mo',
  'duration.exactDays': '{days}d {hours}h {minutes}m {seconds}s',
  'loading.label': 'Loading subagents…',
  'load.error': 'Unable to load subagents',
  'catalog.migrationRequired': 'Migration required',
  'retry': 'Retry',
  'mode.external': 'External task',
  'mode.oneShot': 'one-shot',
  'mode.continuable': 'continuable',
  'mode.unknown': 'unknown mode',
  'activity.running': 'running',
  'activity.completed': 'completed',
  'activity.inactive': 'not running',
  'branch.collapse': 'Collapse {label} descendants',
  'branch.expand': 'Expand {label} descendants',
  'count.total.one': '{count} subagent',
  'count.total.other': '{count} subagents',
  'count.running.one': '{count} subagent running',
  'count.running.other': '{count} subagents running',
  'switcher.aria': 'Switch subagent: {title}',
  'tree.aria': 'Subagent sessions',
  'open.sidebar': 'Open in sidebar',
  'open.sidebar.aria': 'Open {label} in sidebar',
  'open.floatWindow': 'Open in floating panel',

  // --- in-transcript subagent row (this project) ---
  'title.subagent': 'Subagent',
  'title.task': 'Task',
  'progress.label': 'Progress',
  'progress.empty': 'Waiting for the subagent…',
  'progress.settled': 'No live progress to show.',
  'result.label': 'Result',
  'status.running': 'Running',
  'status.completed': 'Completed',
  'status.failed': 'Failed',
  'metrics.toolUses': '{count} tool calls',
  'metrics.tokens': '{value} tok',
  'prompt.label': 'Prompt',
  'transcript.open': 'View full conversation',
  'transcript.loading': 'Loading conversation…',
  'transcript.error': 'Could not load the subagent conversation',
  'transcript.retry': 'Retry',
  'transcript.empty': '(the subagent left no conversation record)',
  'duration.seconds': '{seconds}s',
  'duration.minutes': '{minutes}m {seconds}s',
  'duration.hours': '{hours}h {minutes}m {seconds}s',
  'duration.days': '{days}d',
  'duration.daysHours': '{days}d {hours}h',
  'duration.exactTitle': 'Total active time: {duration}',
  'tokens.thousand': '{value}K',
  'tokens.million': '{value}M',
  'tokens.total': '{value} tok',
}

/** Key domain of the `subagent` namespace (the zh dictionary is the source of truth). */
export type SubagentKey = keyof typeof subagentZh

export const subagentDicts: Readonly<Record<LocaleId, LocaleDict>> = {
  zh: subagentZh,
  en: subagentEn,
}