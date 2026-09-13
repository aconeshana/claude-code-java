import type { LocaleDict, LocaleId } from '../types'

export const COMMON_NS = 'common'

/** zh is the source of truth for this namespace's key set; en is checked complete against it. */
export const commonZh = {
  ok: '确定',
  cancel: '取消',
  close: '关闭',
  loading: '加载中…',
  'load.failed': '加载失败',
  retry: '重试',
} as const

export const commonEn: Record<keyof typeof commonZh, string> = {
  ok: 'OK',
  cancel: 'Cancel',
  close: 'Close',
  loading: 'Loading…',
  'load.failed': 'Failed to load',
  retry: 'Retry',
}

export const commonDicts: Readonly<Record<LocaleId, LocaleDict>> = { zh: commonZh, en: commonEn }
