import type { LocaleDict, LocaleId } from '../types'

// Copy lifted from upstream ui-deliverables' own dictionary
// (packages/client/ui-deliverables/src/client/locales.ts at the pinned dsh
// commit). Upstream keeps the row's label, the `+ N files` remainder in a
// singular and a plural form, and the chip's accessible open-label; those are
// the keys the produced-files row consumes.
export const DELIVERABLES_NS = 'deliverables'

export const deliverablesZh = {
  'produced.label': '产出文件',
  'produced.moreOne': '还有 1 个文件',
  'produced.more': '还有 {count} 个文件',
  'produced.open': '打开 {name}',
  // The viewer's own chrome. Upstream hosts the diff in the right Sidebar's
  // text-preview tab; this project hosts it in a modal, so the modal-level
  // strings live here too rather than in a separate namespace.
  'diff.title': '改动查看',
  'diff.unified': '合并视图',
  'diff.split': '并排视图',
  'diff.wrap': '自动换行',
  'diff.binary': '二进制文件，无法显示差异',
  'diff.oversized': '文件过大，无法显示差异',
  'diff.truncated': '差异过长，仅显示前 {lines} 行',
  'diff.empty': '没有可显示的改动',
  'diff.loading': '正在读取差异…',
  'diff.failed': '读取差异失败',
  'diff.close': '关闭',
  'openFile.label': '在本地打开',
  'openFile.unavailable': '当前运行在浏览器中，无法打开本地文件',
} as const

export const deliverablesEn: Record<keyof typeof deliverablesZh, string> = {
  'produced.label': 'Files',
  'produced.moreOne': '1 more file',
  'produced.more': '{count} more files',
  'produced.open': 'Open {name}',
  'diff.title': 'Changes',
  'diff.unified': 'Unified',
  'diff.split': 'Side by side',
  'diff.wrap': 'Wrap',
  'diff.binary': 'Binary file — no diff available',
  'diff.oversized': 'File too large — no diff available',
  'diff.truncated': 'Diff truncated to the first {lines} lines',
  'diff.empty': 'No changes to show',
  'diff.loading': 'Loading diff…',
  'diff.failed': 'Failed to load the diff',
  'diff.close': 'Close',
  'openFile.label': 'Open locally',
  'openFile.unavailable': 'Running in a browser — local files cannot be opened',
}

export const deliverablesDicts: Readonly<Record<LocaleId, LocaleDict>> = {
  zh: deliverablesZh,
  en: deliverablesEn,
}