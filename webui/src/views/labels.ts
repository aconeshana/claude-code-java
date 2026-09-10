import type { DiffBlockLabels, ReadBlockLabels, TerminalBlockLabels } from '@primitives'
import type { MarkdownLabels } from '@vendor/ui-primitives/markdown/MarkdownText'

/**
 * Chinese copy for the vendored cordis-free blocks. Every vendored block
 * takes its localized strings through props by design ("this package is
 * cordis-free, so copy arrives via props"), so the app owns one shared
 * labels object per block family.
 */

export const markdownLabels: MarkdownLabels = {
  code: {
    copyLabel: '复制',
    copiedLabel: '已复制',
  },
  footnotes: '脚注',
}

export const terminalLabels: TerminalBlockLabels = {
  signal: (signal) => `被信号 ${signal} 终止`,
  exitCode: (code) => `退出码 ${code}`,
  running: '运行中…',
  failed: '已结束',
  done: '已完成',
  copy: '复制',
  copied: '已复制',
  noOutput: '（无输出）',
  collapseAria: '折叠',
  collapse: '收起',
  expandAria: (hidden) => `展开 ${hidden} 行`,
  expand: (hidden) => `展开 ${hidden} 行`,
}

export const readLabels: ReadBlockLabels = {
  window: (shown, total) => `显示 ${shown}/${total} 行`,
  copy: '复制',
  copied: '已复制',
  collapseAria: '折叠',
  expandAria: (hidden) => `展开 ${hidden} 行`,
  collapse: '收起',
  expand: (hidden) => `展开 ${hidden} 行`,
}

export const diffLabels: DiffBlockLabels = {
  copy: '复制',
  copied: '已复制',
  collapseAria: '折叠',
  expandAria: (hidden) => `展开 ${hidden} 行`,
  collapse: '收起',
  expand: (hidden) => `展开 ${hidden} 行`,
  files: (count) => `${count} 个文件`,
}
