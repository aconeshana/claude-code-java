import type { LocaleDict, LocaleId } from '../types'

// Copy lifted from upstream ui-deliverables' own dictionary
// (packages/client/ui-deliverables/src/client/locales.ts at the pinned dsh
// commit), which the vendored `ChangedFiles`, `ReviewTab` and `FileDiff` all
// consume. The pre-vendor row's own keys (`produced.*`) and the modal's
// (`openFile.*`, `diff.title`, `diff.close`) are gone with those components.
//
// The `preview.*` copy is this app's own: the file preview renders the whole
// file in the sidebar without upstream's `CodeBlock` line-number gutter.
export const DELIVERABLES_NS = 'deliverables'

export const deliverablesZh = {
  // Upstream's own comparison copy, which the vendored `FileDiff` consumes: the
  // five states, the three notes, and the line budget its `{count}` names (this
  // project's earlier `{lines}` was a hunk count from the pre-hunks reader).
  'diff.loading': '正在读取改动…',
  'diff.missing': '这轮改动的内容已不可用',
  'diff.error': '无法读取改动',
  'diff.binary': '二进制文件，无法显示改动',
  'diff.oversized': '文件过大，无法显示改动',
  'diff.created': '本轮新建的文件',
  'diff.deleted': '本轮删除的文件',
  'diff.unchanged': '两侧内容相同',
  'diff.coarse': '逐行对比超时，按整个文件替换显示',
  'diff.truncated': '只显示前 {count} 行',
  'presented.retry': '重试',
  'review.split': '切换为左右对比',
  'review.unified': '切换为单栏对比',
  'review.splitAria': '左右对比',
  'review.wrap': '开启自动换行',
  'review.nowrap': '关闭自动换行',
  'review.wrapAria': '自动换行',
  'review.title': '第 {turn} 轮改动',
  'review.selectFile': '选择要查看的文件',
  'review.openFile': '在侧边栏打开整个文件',
  'review.openFileAria': '在侧边栏打开 {name}',
  'preview.loading': '正在读取文件…',
  'preview.error': '无法读取文件：{message}',
  'preview.binary': '二进制文件，无法预览',
  'preview.oversized': '文件过大，无法预览',
  'preview.close': '关闭',
  'preview.closeAria': '关闭文件预览',
  'changes.title': '已编辑 {count} 个文件',
  'changes.singleTitle': '已编辑 {name}',
  'changes.added': '+{count}',
  'changes.deleted': '-{count}',
  // The rest of upstream's `changes.*` group, as the vendored `ChangedFiles`
  // consumes it. `changes.binary`/`oversized` are the row's count-cell
  // replacements, deliberately shorter than the viewer's own `diff.binary`/
  // `diff.oversized` sentences. `presented.preview` is the header's hover hint.
  'changes.binary': '二进制',
  'changes.openReview': '在侧边栏查看本轮改动',
  'changes.all': '全部 {count} 个文件',
  'changes.expandAria': '展开全部 {count} 个改动文件',
  'changes.collapse': '收起',
  'changes.collapseAria': '收起改动文件列表',
  'changes.oversized': '过大',
  'changes.viewDiff': '查看 {name} 的改动',
  'presented.preview': '在侧边栏预览',
} as const

export const deliverablesEn: Record<keyof typeof deliverablesZh, string> = {
  'diff.loading': 'Reading changes…',
  'diff.missing': 'The contents of this turn’s changes are no longer available',
  'diff.error': 'Could not read the changes',
  'diff.binary': 'Binary file; changes cannot be shown',
  'diff.oversized': 'File too large; changes cannot be shown',
  'diff.created': 'Created in this turn',
  'diff.deleted': 'Deleted in this turn',
  'diff.unchanged': 'Both sides hold the same lines',
  'diff.coarse': 'Line comparison timed out; shown as a whole-file replacement',
  'diff.truncated': 'Showing the first {count} lines',
  'presented.retry': 'Retry',
  'review.split': 'Switch to split view',
  'review.unified': 'Switch to unified view',
  'review.splitAria': 'Split view',
  'review.wrap': 'Enable line wrap',
  'review.nowrap': 'Disable line wrap',
  'review.wrapAria': 'Line wrap',
  'review.title': 'Turn {turn} changes',
  'review.selectFile': 'Choose a file to view',
  'review.openFile': 'Open the whole file in the sidebar',
  'review.openFileAria': 'Open {name} in the sidebar',
  'preview.loading': 'Reading file…',
  'preview.error': 'Could not read the file: {message}',
  'preview.binary': 'Binary file; it cannot be previewed',
  'preview.oversized': 'File too large to preview',
  'preview.close': 'Close',
  'preview.closeAria': 'Close file preview',
  'changes.title': 'Edited {count} files',
  'changes.singleTitle': 'Edited {name}',
  'changes.added': '+{count}',
  'changes.deleted': '-{count}',
  'changes.binary': 'binary',
  'changes.openReview': 'Review this turn’s changes in the sidebar',
  'changes.all': 'All {count} files',
  'changes.expandAria': 'Show all {count} changed files',
  'changes.collapse': 'Collapse',
  'changes.collapseAria': 'Collapse changed files',
  'changes.oversized': 'too large',
  'changes.viewDiff': 'View changes to {name}',
  'presented.preview': 'Preview in sidebar',
}

export const deliverablesDicts: Readonly<Record<LocaleId, LocaleDict>> = {
  zh: deliverablesZh,
  en: deliverablesEn,
}