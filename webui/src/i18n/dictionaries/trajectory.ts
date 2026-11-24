import type { LocaleDict, LocaleId } from '../types'
import { en, NS, zh } from '@vendor/ui-trajectory/locales.ts'

export const TRAJECTORY_NS = NS

const extZh: LocaleDict = {
  'parallel.badge': '并行 ×{count}',
  'parallel.batch': '并行批次',
  'parallel.batchPosition': '{index} / {count}',
  'parallel.saved': '并发节省',
  'parallel.waterfall': '并发时序瀑布图',
  'details.subagentTrajectory': '子代理轨迹',
  'subagent.inspect': '查看子代理轨迹',
  'subagent.inspectCounts': '子代理轨迹 ({steps} 步 · {tools} 工具)',
}

const extEn: LocaleDict = {
  'parallel.badge': 'Parallel ×{count}',
  'parallel.batch': 'Parallel batch',
  'parallel.batchPosition': '{index} of {count}',
  'parallel.saved': 'Concurrency saved',
  'parallel.waterfall': 'Concurrency waterfall',
  'details.subagentTrajectory': 'Subagent trajectory',
  'subagent.inspect': 'Inspect subagent trajectory',
  'subagent.inspectCounts': 'Subagent ({steps} steps · {tools} tools)',
}

export const trajectoryDicts: Readonly<Record<LocaleId, LocaleDict>> = {
  zh: { ...zh, ...extZh },
  en: { ...en, ...extEn },
}
